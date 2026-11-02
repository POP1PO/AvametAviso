package es.avamet.aviso.core

import kotlin.math.*

val ROSA = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")

private val DIR_A_GRADOS: Map<String, Double> = run {
    val m = LinkedHashMap<String, Double>()
    ROSA.forEachIndexed { i, n -> m[n] = i * 22.5 }
    m["W"] = 270.0; m["WNW"] = 292.5; m["NW"] = 315.0; m["NNW"] = 337.5; m["WSW"] = 247.5; m["SW"] = 225.0; m["SSW"] = 202.5
    m
}
private val RE_VIENTO = Regex("^[\\s\\u00a0]*(\\d+(?:[.,]\\d+)?)[\\s\\u00a0]*km/h[\\s\\u00a0]*([A-Za-zÑñ]+)?[\\s\\u00a0]*$")

fun direccionAGrados(texto: String?): Double? = if (texto == null) null else DIR_A_GRADOS[texto.trim().uppercase()]

private class Punto(val dist: Double, val vel: Double, val dir: Double)

private fun puntoReferencia(datos: Map<String, Any?>): Punto? {
    val texto = datos["Viento"] as? String ?: return null
    if (!texto.contains("km/h")) return null
    val m = RE_VIENTO.find(texto) ?: return null
    val grados = direccionAGrados(m.groups[2]?.value) ?: return null
    return Punto(0.1, m.groupValues[1].replace(",", ".").toDouble(), grados)
}

/** -> {direccion_destino, grados_destino, velocidad_kmh, categoria}. `grados_destino` = hacia DÓNDE van las nubes. */
fun estimarMovimiento(datosRef: Map<String, Any?>?, metars: Map<String, Any?>, aeropuertosCerca: List<Map<String, Any?>>): Dict {
    val puntos = ArrayList<Punto>()
    puntoReferencia(datosRef ?: emptyMap())?.let { puntos.add(it) }
    for (aero in aeropuertosCerca) {
        val m = metars[aero["icao"] as String] as? Map<String, Any?>
        if (m != null && m["viento_nudos"] != null && m["viento_dir"] != null) {
            puntos.add(Punto(max(aero["distancia_km"].dbl(), 0.1), m["viento_nudos"].dbl() * 1.852, m["viento_dir"].dbl()))
        }
    }
    if (puntos.isEmpty()) return dict("direccion_destino" to "Desconocida", "grados_destino" to 0L, "velocidad_kmh" to 0.0, "categoria" to "No estimable")
    var sumU = 0.0
    var sumV = 0.0
    for (p in puntos) {
        val r = rad(p.dir)
        sumU += -p.vel * sin(r)
        sumV += -p.vel * cos(r)
    }
    val n = puntos.size.toDouble()
    val u = sumU / n
    val v = sumV / n
    val dirOrigen = pyMod(deg(atan2(-u, -v)) + 360, 360.0)
    val vel = sqrt(u * u + v * v)
    val dirDestino = pyMod(dirOrigen + 180, 360.0)
    val idx = (pyRoundInt(dirDestino / 22.5) % 16).toInt()
    val cat = if (vel < 10) "Lento" else if (vel < 30) "Moderado" else "Rápido"
    return dict("direccion_destino" to ROSA[idx], "grados_destino" to pyRoundInt(dirDestino), "velocidad_kmh" to pyRound(vel, 1), "categoria" to cat)
}
