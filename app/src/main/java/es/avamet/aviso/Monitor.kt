package es.avamet.aviso

import android.content.Context
import es.avamet.aviso.core.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Un ciclo completo: planificar -> descargar -> analizar (repetido mientras `faltan`) -> peligro. Sin estado de pantalla. */
class Monitor(ctx: Context) {
    private val coords: Map<String, Map<String, Any?>>
    private val aeropuertos: Map<String, Map<String, Any?>>
    private var referenciaId: String? = null
    private var seguimiento: Map<String, Any?> = emptyMap()
    private val cacheMetar = HashMap<String, Pair<Long, Map<String, Any?>>>()      // icao -> (ms de descarga, datos)
    private val pool = Executors.newFixedThreadPool(4)

    init {
        fun leer(n: String) = ctx.assets.open(n).use { it.readBytes().toString(Charsets.UTF_8) }
        coords = completarMunicipios(MiniJson.parse(leer("coordenadas.json")).d())
        @Suppress("UNCHECKED_CAST")
        aeropuertos = MiniJson.parse(leer("aeropuertos.json")).d() as Map<String, Map<String, Any?>>
    }

    fun cerrar() { pool.shutdownNow() }

    @Suppress("UNCHECKED_CAST")
    fun ciclo(lat: Double, lon: Double): Resultado {
        val ahoraMs = System.currentTimeMillis()
        val ahora = ahoraMs / 1000.0
        val errores = ArrayList<String>()
        var descargas = 0

        val plan = planificar(lat, lon, coords, aeropuertos, referenciaId)
        val ref = plan["referencia"] as? Map<String, Any?>
        if (ref != null) referenciaId = ref["id"] as String

        // METAR: los de la zona; si falla la descarga, se usa el último bueno (3 h para mostrar, 30 min para calcular)
        val icaos = plan["descargar_icaos"].l().map { it as String }
        if (icaos.isNotEmpty()) {
            descargas++
            try {
                for ((icao, datos) in Descargas.metars(icaos)) cacheMetar[icao] = Pair(ahoraMs, datos.d())
            } catch (e: Exception) {
                errores.add("Aeropuertos (METAR): " + Descargas.textoError(e))
            }
        }
        cacheMetar.entries.removeAll { ahoraMs - it.value.first > 3 * 3600_000L }
        val metarCalculo = LinkedHashMap<String, Any?>()
        for ((icao, par) in cacheMetar) if (ahoraMs - par.first <= 30 * 60_000L) metarCalculo[icao] = par.second

        val lecturas = HashMap<String, Map<String, Any?>>()
        if (ref != null) {
            descargas++
            val d = Descargas.estacion(ref["id"] as String)
            lecturas[ref["id"] as String] = d
            (d["Error"] as? String)?.let { errores.add("AVAMET (${ref["nombre"]}): $it") }
        }

        var res = analizar(plan, lecturas, metarCalculo, seguimiento, ahora, coords)
        var vueltas = 0
        while (res["faltan"].l().isNotEmpty() && vueltas < 3) {
            val ids = res["faltan"].l().map { it as String }.filter { !lecturas.containsKey(it) }
            if (ids.isEmpty()) break
            descargas += ids.size
            val tareas = ids.map { id -> Callable { Pair(id, Descargas.estacion(id)) } }
            for (f in pool.invokeAll(tareas)) {
                val (id, d) = f.get()
                lecturas[id] = d
            }
            res = analizar(plan, lecturas, metarCalculo, res["seguimiento"].d(), ahora, coords)
            vueltas++
        }
        val fallos = lecturas.filter { it.value.containsKey("Error") && it.key != referenciaId }.size
        if (fallos > 0) errores.add("AVAMET: $fallos estación(es) del camino de las nubes no respondieron")
        seguimiento = res["seguimiento"].d()
        val peligro = evaluarPeligro(res)
        return Resultado(ahoraMs, lat, lon, res, peligro, HashMap(cacheMetar), errores, descargas)
    }
}
