package es.avamet.aviso

import android.content.Context
import es.avamet.aviso.core.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Un ciclo completo: planificar -> descargar -> analizar (repetido mientras `faltan`) -> peligro. Sin estado de pantalla. */
class Monitor(private val ctx: Context) {
    private val coords: Map<String, Map<String, Any?>>
    private val aeropuertos: Map<String, Map<String, Any?>>
    private var referenciaId: String? = null
    private var seguimiento: Map<String, Any?> = emptyMap()
    private val cacheMetar = HashMap<String, Pair<Long, Map<String, Any?>>>()      // icao -> (ms de descarga, datos)
    private val pool = Executors.newFixedThreadPool(8)

    init {
        fun leer(n: String) = ctx.assets.open(n).use { it.readBytes().toString(Charsets.UTF_8) }
        coords = completarMunicipios(MiniJson.parse(leer("coordenadas.json")).d())
        @Suppress("UNCHECKED_CAST")
        aeropuertos = MiniJson.parse(leer("aeropuertos.json")).d() as Map<String, Map<String, Any?>>
    }

    fun cerrar() { pool.shutdownNow() }

    private fun seg(t0: Long) = "${(System.nanoTime() - t0) / 1_000_000} ms"

    @Suppress("UNCHECKED_CAST")
    fun ciclo(lat: Double, lon: Double, parcial: (Resultado) -> Unit = {}): Resultado {
        val ahoraMs = System.currentTimeMillis()
        val ahora = ahoraMs / 1000.0
        val limiteMs = ahoraMs + 100_000L                      // un ciclo no puede pasar de ~100 s
        val errores = ArrayList<String>()
        var descargas = 0
        Estado.log("Ciclo en ${fmt(lat, 4)}, ${fmt(lon, 4)}")

        val plan = planificar(lat, lon, coords, aeropuertos, referenciaId, fijaId = Config.estacionFija(ctx))
        Estado.log("Plan: ${(plan["radio"] as List<*>).size} estaciones en radio, ${(plan["aeropuertos"] as List<*>).size} aeropuertos")
        val ref = plan["referencia"] as? Map<String, Any?>
        if (ref != null) referenciaId = ref["id"] as String

        // METAR: los de la zona; si falla la descarga, se usa el último bueno (3 h para mostrar, 30 min para calcular)
        val icaos = plan["descargar_icaos"].l().map { it as String }
        if (icaos.isNotEmpty()) {
            descargas++
            val t0 = System.nanoTime()
            Estado.log("METAR: descargando ${icaos.size} aeropuertos…")
            try {
                val m = Descargas.metars(icaos)
                for ((icao, datos) in m) cacheMetar[icao] = Pair(ahoraMs, datos.d())
                Estado.log("METAR: ${m.size}/${icaos.size} aeropuertos (${seg(t0)})")
            } catch (e: Exception) {
                errores.add("Aeropuertos (METAR): " + Descargas.textoError(e))
                Estado.log("METAR: ERROR ${Descargas.textoError(e)} (${seg(t0)})")
            }
        }
        cacheMetar.entries.removeAll { ahoraMs - it.value.first > 3 * 3600_000L }
        val metarCalculo = LinkedHashMap<String, Any?>()
        for ((icao, par) in cacheMetar) if (ahoraMs - par.first <= 30 * 60_000L) metarCalculo[icao] = par.second

        val lecturas = HashMap<String, Map<String, Any?>>()
        if (ref != null) {
            descargas++
            val t0 = System.nanoTime()
            Estado.log("Referencia ${ref["nombre"]}: descargando…")
            val d = Descargas.estacion(ref["id"] as String)
            lecturas[ref["id"] as String] = d
            val err = d["Error"] as? String
            if (err != null) errores.add("AVAMET (${ref["nombre"]}): $err")
            Estado.log("Referencia ${ref["nombre"]}: " + (err?.let { "ERROR $it" } ?: "OK") + " (${seg(t0)})")
        }

        // Estaciones del radio (≤25 km), las más cercanas primero: sin ellas no se ve la lluvia a distancia
        // (aviso a 25 km, alarma a 10 km). Antes solo se bajaban las "del camino de las nubes" y una estación vecina con lluvia
        // podía pasar inadvertida.
        val delRadio = estacionesDelRadio(plan, lecturas.keys)
        if (delRadio.isNotEmpty()) {
            descargas += delRadio.size
            val t0 = System.nanoTime()
            Estado.log("Radio: descargando ${delRadio.size} estaciones…")
            val futuros = pool.invokeAll(delRadio.map { id -> Callable { Pair(id, Descargas.estacion(id)) } }, 60, java.util.concurrent.TimeUnit.SECONDS)
            var bien = 0
            delRadio.forEachIndexed { i, id ->
                val f = futuros[i]
                if (f.isCancelled) lecturas[id] = dict("Error" to "Tiempo de espera agotado")
                else { lecturas[id] = f.get().second; if (!lecturas[id]!!.containsKey("Error")) bien++ }
            }
            Estado.log("Radio: $bien/${delRadio.size} OK (${seg(t0)})")
        }

        fun armar(res: Dict): Resultado {
            val peligro = evaluarPeligro(res)
            return Resultado(ahoraMs, lat, lon, res, peligro, HashMap(cacheMetar), ArrayList(errores), descargas)
        }

        var res = analizar(plan, lecturas, metarCalculo, seguimiento, ahora, coords)
        if (res["faltan"].l().isNotEmpty()) parcial(armar(res))          // ya se puede mostrar algo mientras se descargan las demás
        var vueltas = 0
        while (res["faltan"].l().isNotEmpty() && vueltas < 3) {
            val ids = res["faltan"].l().map { it as String }.filter { !lecturas.containsKey(it) }
            if (ids.isEmpty()) break
            if (System.currentTimeMillis() > limiteMs) {
                errores.add("Se agotó el tiempo del ciclo: faltan ${ids.size} estación(es) del camino de las nubes")
                Estado.log("Tiempo agotado: faltan ${ids.size} estaciones")
                break
            }
            descargas += ids.size
            val t0 = System.nanoTime()
            val tareas = ids.map { id -> Callable { Pair(id, Descargas.estacion(id)) } }
            val futuros = pool.invokeAll(tareas, 40, java.util.concurrent.TimeUnit.SECONDS)
            var bien = 0
            ids.forEachIndexed { i, id ->
                val f = futuros[i]
                if (f.isCancelled) lecturas[id] = dict("Error" to "Tiempo de espera agotado")
                else { lecturas[id] = f.get().second; if (!lecturas[id]!!.containsKey("Error")) bien++ }
            }
            Estado.log("Estaciones del camino: $bien/${ids.size} OK (${seg(t0)})")
            res = analizar(plan, lecturas, metarCalculo, res["seguimiento"].d(), ahora, coords)
            vueltas++
        }
        // Con ~80 estaciones en el radio siempre falla alguna: solo es un problema (y bloquea el "datos fiables") si no responde ni la mitad.
        val otras = lecturas.filter { it.key != referenciaId }
        val fallos = otras.count { it.value.containsKey("Error") }
        if (fallos > 0 && fallos * 2 > otras.size) errores.add("AVAMET: $fallos de ${otras.size} estaciones no respondieron")
        seguimiento = res["seguimiento"].d()
        val r = armar(res)
        Estado.log("Ciclo terminado: ${r.nivel}, ${descargas} peticiones")
        return r
    }
}
