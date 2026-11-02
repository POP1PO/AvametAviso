package es.avamet.aviso.core

fun condViento(viento: Double, racha: Double, aj: Ajustes = Ajustes()): Int {
    if (viento > aj.umbralVientoPeligroKmh || racha > aj.umbralRachaPeligroKmh) return 3
    if (viento >= aj.umbralVientoKmh || racha >= aj.umbralVientoMaxKmh) return 2
    return 1
}

private val RE_ETA = Regex("Llegada en ~(\\d+) min")

/** res = salida de analizar -> {nivel: SEGURO|VIGILANCIA|PELIGRO, motivos: [str]}. */
@Suppress("UNCHECKED_CAST")
fun evaluarPeligro(res: Map<String, Any?>, aj: Ajustes = Ajustes()): Dict {
    val motivos = ArrayList<Any?>()
    var nivel = "SEGURO"
    val ref = res["referencia"] as? Map<String, Any?>
    if (ref != null && (ref["estado"] as Map<String, Any?>)["estado"] == "ACTIVA" && ref["datos"] != null) {
        val datos = ref["datos"].d()
        val viento = num(datos["Viento"] ?: "0")
        val racha = num(datos["Viento máximo"] ?: "0")
        val cv = condViento(viento, racha, aj)
        if (cv == 3) { motivos.add("Viento muy fuerte: ${fmt(viento, 0)} km/h (racha ${fmt(racha, 0)})"); nivel = "PELIGRO" }
        else if (cv == 2) { motivos.add("Viento fuerte: ${fmt(viento, 0)} km/h (máx ${fmt(racha, 0)})"); nivel = "VIGILANCIA" }
    }
    for (r0 in (res["lluvia_ahora"] as? List<Any?>) ?: emptyList()) {
        val r = r0.d()
        if (r["es_referencia"] == true) {
            motivos.add("Lluvia en tu estación de referencia (${r["nombre"]})"); nivel = "PELIGRO"
        } else if (r["distancia_km"].dbl() <= aj.radioLluviaPeligroKm) {
            motivos.add("Lluvia a ${fmt(r["distancia_km"].dbl(), 1)} km en ${r["nombre"]}"); nivel = "PELIGRO"
        }
    }
    for (a in (res["alertas_predictivas"] as? List<Any?>) ?: emptyList()) {
        val m = RE_ETA.find(a as String)
        if (m != null && m.groupValues[1].toInt() < aj.etaPeligroMin) { motivos.add(a); nivel = "PELIGRO" }
    }
    return dict("nivel" to nivel, "motivos" to motivos)
}
