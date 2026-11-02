package es.avamet.aviso.core

import kotlin.math.floor

fun hayLluviaNueva(anterior: Double?, actual: Double, umbral: Double): Boolean = anterior != null && pyRound(actual - anterior, 2) >= umbral

/** seg[eid] = {valor, ts_subida, ts_lectura}. La primera lectura (o tras un hueco largo) solo fija la línea base. */
fun registrarLluvia(seg: MutableMap<String, Any?>, eid: String, valor: Double, ahora: Double, umbral: Double, huecoMaxS: Double?) {
    var previo = seg[eid] as? Map<String, Any?>
    if (previo != null && huecoMaxS != null) {
        val tsLect = (previo["ts_lectura"] as? Number)?.toDouble() ?: ahora
        if (ahora - tsLect > huecoMaxS) previo = null
    }
    var ts = if (previo != null) previo["ts_subida"].dbl() else 0.0
    if (previo != null && hayLluviaNueva(previo["valor"].dbl(), valor, umbral)) ts = ahora
    seg[eid] = dict("valor" to valor, "ts_subida" to ts, "ts_lectura" to ahora)
}

fun lluviaReciente(seg: Map<String, Any?>, eid: String, ahora: Double, ventanaS: Double): Boolean {
    val ts = ((seg[eid] as? Map<String, Any?>)?.get("ts_subida") as? Number)?.toDouble() ?: 0.0
    return ts > 0 && ahora - ts >= 0 && ahora - ts <= ventanaS
}

/** estado = ACTIVA | CONGELADA | SIN_FECHA | SIN_LECTURA. */
fun estadoEstacion(datos: Map<String, Any?>?, nombre: String, ahora: Double, aj: Ajustes = Ajustes()): Dict {
    val maxMin = (aj.edadMaxDatosS / 60).toLong()
    fun base(estado: String, edadMin: Long?, mensaje: String) =
        dict("nombre" to nombre, "max_min" to maxMin, "edad_min" to edadMin, "estado" to estado, "mensaje" to mensaje)
    val nom = if (nombre.isEmpty()) "esta estación" else nombre
    if (!datosValidos(datos)) return base("SIN_LECTURA", null, "AVAMET no devolvió una lectura completa de $nom.")
    val edad = edadLecturaS(datos, ahora, aj)
        ?: return base("SIN_FECHA", null, "La fecha de la última lectura de $nom falta o no se puede interpretar, así que no se puede saber si es reciente.")
    val edadMin = floor(edad / 60).toLong()
    if (edad > aj.edadMaxDatosS)
        return base("CONGELADA", edadMin, "El sensor de esta estación lleva $edadMin minutos sin enviar datos a AVAMET (aunque tu red funciona bien).")
    return base("ACTIVA", edadMin, "")
}

fun avisoSinDatos(est: Map<String, Any?>): String {
    val nombre = (est["nombre"] as? String)?.takeIf { it.isNotEmpty() } ?: "esta estación"
    if (est["estado"] == "CONGELADA")
        return "⚠️ SIN DATOS FÍABLES: El sensor de $nombre no se actualiza desde hace ${numStr(est["edad_min"])} min. " +
            "(Tu conexión a Internet y la web de AVAMET funcionan correctamente, pero este sensor está inactivo)."
    if (est["estado"] == "SIN_FECHA")
        return "⚠️ SIN DATOS FÍABLES: AVAMET no indica una fecha válida para la última lectura de $nombre, " +
            "así que no se puede saber si es reciente. (Tu conexión a Internet y la web de AVAMET responden)."
    return ""
}

fun alternativasActivas(radio: List<Map<String, Any?>>, lecturas: Map<String, Map<String, Any?>>, ahora: Double, maximo: Int = 6, aj: Ajustes = Ajustes()): List<Dict> {
    val res = ArrayList<Dict>()
    for (est in radio) {
        val datos = lecturas[est["id"] as String]
        if (datos == null || datos.isEmpty() || datos.containsKey("Error")) continue
        val e = estadoEstacion(datos, (est["nombre"] as? String) ?: "", ahora, aj)
        if (e["estado"] == "ACTIVA") res.add(dict("id" to est["id"], "nombre" to ((est["nombre"] as? String) ?: ""), "distancia_km" to est["distancia_km"], "edad_min" to e["edad_min"]))
    }
    val ord = res.sortedBy { (it["distancia_km"] as? Number)?.toDouble() ?: 1e9 }
    return ord.take(maximo)
}
