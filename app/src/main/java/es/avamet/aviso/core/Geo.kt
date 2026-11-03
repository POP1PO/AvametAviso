package es.avamet.aviso.core

import kotlin.math.*

const val R_TIERRA_KM = 6371.0

fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = rad(lat1)
    val p2 = rad(lat2)
    val dp = rad(lat2 - lat1)
    val dl = rad(lon2 - lon1)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * R_TIERRA_KM * atan2(sqrt(a), sqrt(1 - a))
}

/** Rumbo inicial de (1) hacia (2), 0-360 (0 = norte). */
fun rumboGrados(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val f1 = rad(lat1)
    val f2 = rad(lat2)
    val dl = rad(lon2 - lon1)
    val y = sin(dl) * cos(f2)
    val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
    return pyMod(deg(atan2(y, x)) + 360, 360.0)
}

/** Módulo de Python (el resultado lleva el signo del divisor). */
fun pyMod(a: Double, b: Double): Double {
    val r = a % b
    return if (r != 0.0 && (r < 0) != (b < 0)) r + b else r
}

fun diferenciaAngular(a: Double, b: Double): Double {
    val d = abs(a - b)
    return if (d > 180) 360 - d else d
}

/** Estación más cercana: Triple(id, info, distancia_km) o null. Empate: gana la primera en el orden del mapa. */
fun estacionMasCercana(lat: Double, lon: Double, coords: Map<String, Map<String, Any?>>): Triple<String, Map<String, Any?>, Double>? {
    var mejor: Triple<String, Map<String, Any?>, Double>? = null
    var mejorD = Double.POSITIVE_INFINITY
    for ((eid, info) in coords) {
        val d = haversineKm(lat, lon, info["lat"].dbl(), info["lon"].dbl())
        if (d < mejorD) { mejorD = d; mejor = Triple(eid, info, d) }
    }
    return mejor
}

fun estacionesEnRadio(lat: Double, lon: Double, coords: Map<String, Map<String, Any?>>, radioKm: Double, excluir: String? = null): List<Dict> {
    val res = ArrayList<Dict>()
    for ((eid, inf) in coords) {
        if (eid == excluir) continue
        val d = haversineKm(lat, lon, inf["lat"].dbl(), inf["lon"].dbl())
        if (d <= radioKm) res.add(dict("id" to eid, "nombre" to inf["nombre"], "distancia_km" to pyRound(d, 1), "lat" to inf["lat"], "lon" to inf["lon"]))
    }
    return res.sortedBy { it["distancia_km"].dbl() }          // sortedBy es estable, como sort() de Python
}

fun aeropuertosEnRadio(lat: Double, lon: Double, aeropuertos: Map<String, Map<String, Any?>>, radioKm: Double = 120.0): List<Dict> {
    val res = ArrayList<Dict>()
    for ((icao, info) in aeropuertos) {
        val d = haversineKm(lat, lon, info["lat"].dbl(), info["lon"].dbl())
        if (d <= radioKm) res.add(dict("icao" to icao, "nombre" to info["nombre"], "distancia_km" to pyRound(d, 1), "lat" to info["lat"], "lon" to info["lon"]))
    }
    return res.sortedBy { it["distancia_km"].dbl() }
}

/** (id, distancia) de la estación de referencia, con histéresis. */
fun elegirReferencia(lat: Double, lon: Double, coords: Map<String, Map<String, Any?>>, actualId: String?, histeresisKm: Double,
                     fijaId: String? = null): Pair<String, Double>? {
    if (!fijaId.isNullOrEmpty() && coords.containsKey(fijaId)) {          // elección manual del usuario: manda siempre
        val f = coords[fijaId]!!
        return Pair(fijaId, haversineKm(lat, lon, f["lat"].dbl(), f["lon"].dbl()))
    }
    val cerca = estacionMasCercana(lat, lon, coords) ?: return null
    if (actualId != null && coords.containsKey(actualId) && actualId != cerca.first) {
        val a = coords[actualId]!!
        val dAct = haversineKm(lat, lon, a["lat"].dbl(), a["lon"].dbl())
        if (dAct <= cerca.third + histeresisKm) return Pair(actualId, dAct)
    }
    return Pair(cerca.first, cerca.third)
}

/** Datos fijos. `municipio` = nombre sin lo que va entre paréntesis. */
fun completarMunicipios(coords: Map<String, Any?>): Map<String, Map<String, Any?>> {
    val res = LinkedHashMap<String, Map<String, Any?>>()
    for ((k, v) in coords) {
        val m = LinkedHashMap(v.d())
        val mun = m["municipio"]
        if (mun == null || (mun is String && mun.isEmpty())) m["municipio"] = (m["nombre"]?.toString() ?: "").substringBefore("(").trim()
        res[k] = m
    }
    return res
}
