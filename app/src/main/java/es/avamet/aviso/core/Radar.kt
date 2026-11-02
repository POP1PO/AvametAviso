package es.avamet.aviso.core

import kotlin.math.sqrt

typealias Lecturas = Map<String, Map<String, Any?>>

private fun truncar(x: Double): Long = x.toLong()      // int() de Python: hacia cero

/** Estaciones entre el aeropuerto y mi posición ("red de arrastre") y si alguna confirma lluvia en tierra. Modifica `seg`. */
fun procesarArrastre(aero: Map<String, Any?>, lat0: Double, lon0: Double, mov: Map<String, Any?>, coords: Map<String, Map<String, Any?>>,
                     lecturas: Lecturas, seg: MutableMap<String, Any?>, ahora: Double, aj: Ajustes): Dict {
    val vel = maxOf(mov["velocidad_kmh"].dbl(), 10.0)
    val distanciaTotal = aero["distancia_km"].dbl()
    val etaBase = truncar((distanciaTotal / vel) * 60)
    val aLat = aero["lat"].dbl()
    val aLon = aero["lon"].dbl()
    val dLat = lat0 - aLat
    val dLon = lon0 - aLon
    val distCuadrada = dLat * dLat + dLon * dLon
    val candidatas = ArrayList<Dict>()
    if (distCuadrada > 0) {
        for ((eid, info) in coords) {
            val sLat = info["lat"].dbl() - aLat
            val sLon = info["lon"].dbl() - aLon
            val t = (sLat * dLat + sLon * dLon) / distCuadrada
            if (t > 0.1 && t < 0.9) {
                val projLat = aLat + t * dLat
                val projLon = aLon + t * dLon
                val a = info["lat"].dbl() - projLat
                val b = info["lon"].dbl() - projLon
                val distKm = sqrt(a * a + b * b) * 111.0
                if (distKm < 15.0) {
                    candidatas.add(dict("id" to eid, "nombre" to info["nombre"], "lat" to info["lat"], "lon" to info["lon"],
                        "dist_a_mi" to haversineKm(lat0, lon0, info["lat"].dbl(), info["lon"].dbl()), "progreso" to t, "dist_a_linea" to distKm))
                }
            }
        }
    }
    val ordenadas = candidatas.sortedBy { it["progreso"].dbl() }
    val camino = ArrayList<Dict>()
    for (c in ordenadas) {
        if (camino.isEmpty()) camino.add(c)
        else if ((c["progreso"].dbl() - camino.last()["progreso"].dbl()) * distanciaTotal > 8.0) camino.add(c)
    }
    val caminoMax = camino.take(6)

    var confirmado = false
    var eta = etaBase
    val faltan = ArrayList<Any?>()
    val resultados = ArrayList<Dict>()
    for (est in caminoMax) {
        val d = lecturas[est["id"] as String]
        if (d == null) {
            faltan.add(est["id"])
            resultados.add(LinkedHashMap(est).also { it["lluvia"] = null; it["lluvia_hoy"] = null })
            continue
        }
        if (d.containsKey("Error")) {
            resultados.add(LinkedHashMap(est).also { it["lluvia"] = null; it["lluvia_hoy"] = null })
            continue
        }
        val lluvia = num(d["Lluvia hoy"] ?: "0")
        registrarLluvia(seg, est["id"] as String, lluvia, ahora, aj.umbralLluviaMm, aj.gapMaxSeguimientoS)
        val reciente = lluviaReciente(seg, est["id"] as String, ahora, aj.ventanaLluviaRadarS)
        resultados.add(LinkedHashMap(est).also { it["lluvia"] = if (reciente) lluvia else 0.0; it["lluvia_hoy"] = lluvia })
    }
    for (res in resultados) {
        val ll = (res["lluvia"] as? Number)?.toDouble() ?: 0.0
        if (ll > 0) {
            confirmado = true
            val e2 = truncar((res["dist_a_mi"].dbl() / vel) * 60)
            if (e2 < eta) eta = e2
        }
    }
    return dict("confirmado" to confirmado, "eta" to eta, "estaciones_camino" to resultados, "faltan" to faltan)
}

/** ¿Está la estación en la dirección de la que vienen las nubes y a menos de etaMaxEstacionesMin minutos? */
fun enCamino(est: Map<String, Any?>, lat0: Double, lon0: Double, mov: Map<String, Any?>, aj: Ajustes): Boolean {
    if (mov["velocidad_kmh"].dbl() <= aj.velocidadMinKmh) return false
    val rumbo = rumboGrados(est["lat"].dbl(), est["lon"].dbl(), lat0, lon0)
    if (diferenciaAngular(rumbo, mov["grados_destino"].dbl()) >= aj.anguloEstacionesGrados) return false
    return truncar((est["distancia_km"].dbl() / mov["velocidad_kmh"].dbl()) * 60) < aj.etaMaxEstacionesMin
}

/** -> {alertas_predictivas, radares, radares_aeropuertos, faltan}. */
fun evaluarRadar(lat0: Double, lon0: Double, mov: Map<String, Any?>, radio: List<Map<String, Any?>>, lecturas: Lecturas,
                 aeropuertos: List<Map<String, Any?>>, metars: Map<String, Any?>, coords: Map<String, Map<String, Any?>>,
                 seg: MutableMap<String, Any?>, ahora: Double, aj: Ajustes): Dict {
    val alertas = ArrayList<Any?>()
    val radares = ArrayList<Any?>()
    val radaresAero = ArrayList<Any?>()
    val faltan = ArrayList<Any?>()
    if (mov["velocidad_kmh"].dbl() > aj.velocidadMinKmh) {
        for (est in radio) {
            if (!enCamino(est, lat0, lon0, mov, aj)) continue
            val d = lecturas[est["id"] as String]
            if (d == null) { faltan.add(est["id"]); continue }
            if (d.isEmpty() || d.containsKey("Error")) continue
            if (lluviaReciente(seg, est["id"] as String, ahora, aj.ventanaLluviaRadarS)) {
                val minutos = truncar((est["distancia_km"].dbl() / mov["velocidad_kmh"].dbl()) * 60)
                alertas.add("Lluvia en ${est["nombre"]} a ${numStr(est["distancia_km"])} km. Llegada en ~$minutos min.")
                radares.add(dict("nombre" to est["nombre"], "lat" to est["lat"], "lon" to est["lon"], "distancia" to est["distancia_km"], "eta" to minutos))
            }
        }
        for (aero in aeropuertos) {
            val m = metars[aero["icao"] as String] as? Map<String, Any?>
            if (m == null || m.isEmpty()) continue
            val tiempoMalo = m["fenomenos"] != "Ninguno"
            val bajas = (m["nubosidad"] as? Map<String, Any?>)?.get("baja") as? List<Any?>
            val nubesBajas = bajas != null && bajas.isNotEmpty()
            if (tiempoMalo || nubesBajas) {
                val rumbo = rumboGrados(aero["lat"].dbl(), aero["lon"].dbl(), lat0, lon0)
                if (diferenciaAngular(rumbo, mov["grados_destino"].dbl()) < aj.anguloAeropuertosGrados) {
                    val arr = procesarArrastre(aero, lat0, lon0, mov, coords, lecturas, seg, ahora, aj)
                    for (i in arr["faltan"].l()) if (!faltan.contains(i)) faltan.add(i)
                    val motivo = if (tiempoMalo) "Lluvia/Tormenta" else "Nubes bajas"
                    val confirmado = arr["confirmado"] as Boolean
                    var texto = "[Largo Alcance] $motivo en ${aero["nombre"]}."
                    texto = if (confirmado) "[CONFIRMADO EN TIERRA] $motivo en ${aero["nombre"]}." else "$texto (Sin confirmar)."
                    alertas.add("$texto Llegada en ~${arr["eta"]} min.")
                    radaresAero.add(dict("nombre" to aero["nombre"], "lat" to aero["lat"], "lon" to aero["lon"], "distancia" to aero["distancia_km"],
                        "eta" to arr["eta"], "motivo" to motivo, "confirmado" to confirmado, "estaciones_camino" to arr["estaciones_camino"]))
                }
            }
        }
    }
    return dict("alertas_predictivas" to alertas, "radares" to radares, "radares_aeropuertos" to radaresAero, "faltan" to faltan)
}
