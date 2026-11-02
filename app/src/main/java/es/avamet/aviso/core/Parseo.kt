package es.avamet.aviso.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private const val NUM = "(\\d+(?:[.,]\\d+)?)"
val CLAVES_MINIMAS = listOf("Temperatura", "Humedad", "Fecha lectura")

private val RE_FECHA_TEMPS = Regex(
    "(?U)(\\d{2}-\\d{2}-\\d{4})\\s+(\\d{2}:\\d{2})\\s+" +
        "(-?\\d+(?:[.,]\\d+)?)\\s*°\\s*mín\\s+(-?\\d+(?:[.,]\\d+)?)\\s*°\\s*màx\\s+(-?\\d+(?:[.,]\\d+)?)\\s*°"
)
private val RE_COMENTARIO = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val RE_SCRIPT = Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val RE_ETIQUETA = Regex("<[A-Za-z/!?][^>]*>")
private val RE_ENTIDAD = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z]+);")

private fun desescapar(t: String): String = RE_ENTIDAD.replace(t) { m ->
    val e = m.groupValues[1]
    when {
        e.startsWith("#x") || e.startsWith("#X") -> String(Character.toChars(e.substring(2).toInt(16)))
        e.startsWith("#") -> String(Character.toChars(e.substring(1).toInt()))
        e == "amp" -> "&"; e == "lt" -> "<"; e == "gt" -> ">"; e == "quot" -> "\""; e == "apos" -> "'"; e == "nbsp" -> " "
        else -> m.value
    }
}

/** Textos visibles de la página, sin espacios sobrantes, unidos por un espacio (equivale a " ".join(soup.stripped_strings)). */
fun textoDeHtml(html: String): String {
    var t = RE_COMENTARIO.replace(html, "<x>")
    t = RE_SCRIPT.replace(t, "<x>")
    return RE_ETIQUETA.split(t).map { desescapar(it).trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}

private fun punto(t: String) = t.replace(",", ".")

/** '1.021' -> 1021.0 ; '1021,3' -> 1021.3 ; null si no es válido. */
fun aFloat(texto: String): Double? {
    var t = texto.trim()
    if (t.contains(",")) t = t.replace(".", "").replace(",", ".")
    else if (Regex("\\d{1,2}\\.\\d{3}").matches(t)) t = t.replace(".", "")
    return parseFloatEstricto(t)
}

/** '12.5 mm' -> 12.5 ; vacío o ilegible -> 0.0. */
fun num(valor: Any?): Double {
    val partes = valor.toString().trim().split(Regex("\\s+"))
    if (partes.isEmpty() || partes[0].isEmpty()) return 0.0
    return parseFloatEstricto(partes[0].replace(",", ".")) ?: 0.0
}

fun parsearEstacion(texto: String, aj: Ajustes = Ajustes()): Dict {
    val datos = dict()
    var m = RE_FECHA_TEMPS.find(texto)
    if (m != null) {
        datos["Fecha lectura"] = "${m.groupValues[1]} ${m.groupValues[2]}"
        datos["Temperatura"] = punto(m.groupValues[3]) + " °C"
        datos["Temp mín"] = punto(m.groupValues[4]) + " °C"
        datos["Temp máx"] = punto(m.groupValues[5]) + " °C"
    } else {
        m = Regex("(?U)(-?\\d+(?:[.,]\\d+)?)\\s*°\\s*mín").find(texto)
        if (m != null) datos["Temperatura"] = punto(m.groupValues[1]) + " °C"
    }
    Regex("(?U)Humitat\\s+(\\d+)\\s*%").find(texto)?.let { datos["Humedad"] = it.groupValues[1] + " %" }
    Regex("(?U)Vent\\s+$NUM\\s*km/h\\s+([A-Z]+)").find(texto)?.let { datos["Viento"] = "${punto(it.groupValues[1])} km/h ${it.groupValues[2]}" }
    Regex("(?U)Vent\\s+màx\\s+$NUM\\s*km/h").find(texto)?.let { datos["Viento máximo"] = punto(it.groupValues[1]) + " km/h" }
    Regex("(?U)Pluja\\s+hui\\s+$NUM\\s*mm").find(texto)?.let { datos["Lluvia hoy"] = punto(it.groupValues[1]) + " mm" }
    Regex("(?U)Pluja\\s+mensual\\s+$NUM\\s*mm").find(texto)?.let { datos["Lluvia mensual"] = punto(it.groupValues[1]) + " mm" }
    Regex("(?U)Pluja\\s+anual\\s+$NUM\\s*mm").find(texto)?.let { datos["Lluvia anual"] = punto(it.groupValues[1]) + " mm" }
    Regex("(?U)Pressió\\s+al\\s+nivell\\s+de\\s+la\\s+mar\\s+([\\d.,]+)\\s*hPa").find(texto)?.let {
        val p = aFloat(it.groupValues[1])
        if (p != null && p >= aj.presionMinHpa && p <= aj.presionMaxHpa) datos["Presión"] = fmtG(p) + " hPa"
        else datos["Presión descartada"] = it.groupValues[1] + " hPa"
    }
    return datos
}

fun datosValidos(datos: Map<String, Any?>?): Boolean = datos != null && !datos.containsKey("Error") && CLAVES_MINIMAS.all { datos.containsKey(it) }

private val FMT_FECHA = DateTimeFormatter.ofPattern("d-M-uuuu H:m").withResolverStyle(ResolverStyle.STRICT)

/** Segundos desde 'Fecha lectura' (hora local de la estación) hasta `ahora` (epoch). null si falta, es ilegible o está en el futuro > 5 min. */
fun edadLecturaS(datos: Map<String, Any?>?, ahora: Double, aj: Ajustes = Ajustes()): Double? {
    val texto = datos?.get("Fecha lectura") as? String
    if (texto.isNullOrEmpty()) return null
    val ldt = try { LocalDateTime.parse(texto, FMT_FECHA) } catch (e: Exception) { return null }
    val ts = ZonedDateTime.of(ldt, ZoneId.of(aj.zonaHoraria)).toEpochSecond().toDouble()
    val edad = ahora - ts
    if (edad < -300) return null
    return maxOf(0.0, edad)
}

// ---- METAR (aviationweather.gov) ----
private val COVER_TRAD = mapOf("FEW" to "Pocas nubes", "SCT" to "Nubes dispersas", "BKN" to "Nublado (Fragmentado)", "OVC" to "Cielo Cubierto",
    "SKC" to "Despejado", "CLR" to "Despejado", "NSC" to "Sin nubes significativas")
private val FENOMENOS = listOf("RA" to "Lluvia", "DZ" to "Llovizna", "SN" to "Nieve", "SG" to "Cinarra", "PL" to "Hielo granulado",
    "GR" to "Granizo", "GS" to "Granizo pequeño", "BR" to "Neblina", "FG" to "Niebla", "FU" to "Humo", "VA" to "Ceniza volcánica",
    "DU" to "Polvo", "SA" to "Arena", "HZ" to "Calima", "TS" to "Tormenta", "SH" to "Chubascos", "FZ" to "Engelante",
    "SQ" to "Turbonadas", "FC" to "Tornado/Tromba")

/** 'VRB', vacío o ausente -> null (NUNCA 0.0: 0° sería Norte). */
fun seguroFloat(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> parseFloatEstricto(v)
    else -> null
}

fun traducirNubosidad(clouds: List<Any?>?): Dict {
    val baja = ArrayList<Any?>(); val media = ArrayList<Any?>(); val alta = ArrayList<Any?>()
    for (c0 in clouds ?: emptyList()) {
        val c = c0.d()
        val cover = (c["cover"] as? String) ?: ""
        val baseV = c["base"]
        val baseFt: Number = if (baseV is Number && baseV.toDouble() != 0.0) baseV else 0L
        if (cover.isEmpty() || cover == "SKC" || cover == "CLR" || cover == "NSC") continue
        var texto = COVER_TRAD[cover] ?: cover
        val baseM = baseFt.toDouble() * 0.3048
        if (baseFt.toDouble() != 0.0) texto += " a ${numStr(baseFt)} ft (${fmt(baseM, 0)} m)"
        if (baseM < 2000) baja.add(texto) else if (baseM < 6000) media.add(texto) else alta.add(texto)
    }
    return dict("baja" to baja, "media" to media, "alta" to alta)
}

fun traducirFenomenos(wx: Any?): String {
    if (wx !is String || wx.isEmpty()) return "Ninguno"
    val partes = FENOMENOS.filter { wx.contains(it.first) }.map { it.second }
    return if (partes.isNotEmpty()) partes.joinToString(", ") else wx
}

/** JSON de aviationweather.gov (lista de METAR) -> {icao: datos}. */
fun parsearMetars(lista: List<Any?>?): Dict {
    val res = dict()
    for (m0 in lista ?: emptyList()) {
        val m = m0.d()
        val icao = m["icaoId"] as? String
        if (icao.isNullOrEmpty()) continue
        res[icao] = dict(
            "temperatura" to seguroFloat(m["temp"]), "punto_rocio" to seguroFloat(m["dewp"]),
            "viento_nudos" to seguroFloat(m["wspd"]), "viento_dir" to seguroFloat(m["wdir"]),
            "visibilidad" to m["visib"], "nubosidad" to traducirNubosidad(m["clouds"] as? List<Any?>),
            "fenomenos" to traducirFenomenos(m["wxString"]), "actualizado" to m["reportTime"],
        )
    }
    return res
}
