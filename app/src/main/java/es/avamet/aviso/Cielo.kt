package es.avamet.aviso

import es.avamet.aviso.core.*
import java.util.Locale
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * "¿Cielo bueno para observar?" calculado SOLO con los datos que ya recoge la app:
 * METAR de aeropuertos cercanos (nubes, fenómenos), estación de referencia AVAMET (temperatura, humedad, racha, lluvia),
 * avisos de nubes de lluvia en camino y fase de la Luna (calculada).
 * Es el estado de AHORA, no un pronóstico. Los umbrales son un criterio propio, NO un estudio validado.
 */
object Cielo {
    /** Radio máximo de aeropuertos, desde la posición GPS (el mismo que usa el análisis: 120 km). */
    const val RADIO_AEROPUERTOS_KM = 120.0

    class Informe(val veredicto: String, val lineas: List<String>)

    /**
     * Fracción iluminada de la Luna: ángulo de fase con la serie corta de Meeus (Astronomical Algorithms, cap. 48),
     * k = (1 + cos i) / 2. Precisión esperada: décimas de punto porcentual a un punto (no verificada contra efemérides en el móvil).
     */
    fun lunaIluminada(ms: Long): Double {
        val jd = ms / 86_400_000.0 + 2_440_587.5
        val t = (jd - 2_451_545.0) / 36525.0
        fun g(x: Double) = Math.toRadians(((x % 360.0) + 360.0) % 360.0)
        val d = g(297.8501921 + 445267.1114034 * t - 0.0018819 * t * t)
        val m = g(357.5291092 + 35999.0502909 * t - 0.0001536 * t * t)
        val mp = g(134.9633964 + 477198.8675055 * t + 0.0087414 * t * t)
        val i = Math.PI - d - Math.toRadians(6.289) * Math.sin(mp) + Math.toRadians(2.100) * Math.sin(m) -
            Math.toRadians(1.274) * Math.sin(2 * d - mp) - Math.toRadians(0.658) * Math.sin(2 * d) -
            Math.toRadians(0.214) * Math.sin(2 * mp) - Math.toRadians(0.110) * Math.sin(d)
        return (1 + Math.cos(i)) / 2
    }

    /** Instante de observación: ahora si es de noche (21:00–06:00 locales); si no, las 22:00 locales de hoy. */
    private fun instanteNoche(ahoraMs: Long): Pair<Long, Boolean> {
        val c = java.util.Calendar.getInstance(); c.timeInMillis = ahoraMs
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        if (h >= 21 || h < 6) return Pair(ahoraMs, true)
        c.set(java.util.Calendar.HOUR_OF_DAY, 22); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0)
        return Pair(c.timeInMillis, false)
    }


    /** Seeing y transparencia del pronóstico de 7Timer (producto "astro"). Es un dato EXTERNO, no de las estaciones de la app. */
    class Seeing(val lineas: List<String>)

    /**
     * Pronóstico de 7Timer! para la noche: seeing (1 = menos de 0,5″ … 8 = más de 2,5″), transparencia (1 = menos de 0,3 … 8 = más de 1
     * magnitud por masa de aire) y nubosidad (1–9). Formato según la wiki de 7Timer; no he podido probar la respuesta real desde aquí.
     */
    fun seeing(lat: Double, lon: Double, ahoraMs: Long): Seeing {
        val url = "https://www.7timer.info/bin/api.pl?lon=${"%.3f".format(Locale.US, lon)}&lat=${"%.3f".format(Locale.US, lat)}&product=astro&output=json&ac=0&unit=metric&tzshift=0"
        return interpretarSeeing(Descargas.texto(url), ahoraMs)
    }

    fun interpretarSeeing(texto: String, ahoraMs: Long): Seeing {
        val j = JSONObject(texto)
        val init = j.getString("init")   // AAAAMMDDHH en UTC
        val fmt = java.text.SimpleDateFormat("yyyyMMddHH", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val t0 = fmt.parse(init)?.time ?: throw java.io.IOException("init ilegible")
        val serie = j.getJSONArray("dataseries")
        val cal = java.util.Calendar.getInstance()
        val see = ArrayList<Int>(); val tra = ArrayList<Int>(); val nub = ArrayList<Int>()
        for (i in 0 until serie.length()) {
            val o = serie.getJSONObject(i)
            val ms = t0 + o.getLong("timepoint") * 3_600_000L
            cal.timeInMillis = ms
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            if (ms < ahoraMs - 3_600_000L || !(h >= 21 || h <= 5) || see.size >= 9) continue
            val s1 = o.optInt("seeing", -9999); val t1 = o.optInt("transparency", -9999); val c1 = o.optInt("cloudcover", -9999)
            if (s1 in 1..8) see.add(s1)
            if (t1 in 1..8) tra.add(t1)
            if (c1 in 1..9) nub.add(c1)
        }
        if (see.isEmpty()) throw java.io.IOException("7Timer no devuelve horas de noche con seeing")
        val edadH = (ahoraMs - t0) / 3_600_000
        val l = ArrayList<String>()
        l.add("Seeing: ${"%.1f".format(Locale.US, see.average())}/8 de media (peor ${see.max()}/8) · 1 = <0,5″ · 8 = >2,5″ · cuanto menor, mejor")
        if (tra.isNotEmpty()) l.add("Transparencia: ${"%.1f".format(Locale.US, tra.average())}/8 (peor ${tra.max()}/8) · 1 = <0,3 · 8 = >1 mag/masa de aire")
        if (nub.isNotEmpty()) l.add("Nubosidad 7Timer: ${"%.1f".format(Locale.US, nub.average())}/9 · 1 = 0–6 % · 9 = 94–100 %")
        l.add("Pronóstico externo de 7Timer (emitido hace $edadH h), no de nuestras estaciones; no entra en el veredicto.")
        return Seeing(l)
    }

    private fun numero(v: Any?): Double? =
        Regex("-?\\d+(\\.\\d+)?").find(v?.toString() ?: "")?.value?.toDoubleOrNull()

    /** Fracción de cielo cubierto según la capa más cubierta (FEW 1-2, SCT 3-4, BKN 5-7, OVC 8 octas; punto medio de cada rango). */
    private fun fraccion(txt: String): Double = when {
        txt.startsWith("Cielo Cubierto") -> 1.0
        txt.startsWith("Nublado") -> 0.75
        txt.startsWith("Nubes dispersas") -> 0.44
        txt.startsWith("Pocas nubes") -> 0.19
        else -> 0.5
    }

    /** Punto de rocío con la fórmula de Magnus (constantes de Sonntag 1990: a=17.62, b=243.12). */
    private fun rocio(t: Double, hr: Double): Double {
        val a = 17.62; val b = 243.12
        val g = ln(hr.coerceIn(1.0, 100.0) / 100.0) + a * t / (b + t)
        return b * g / (a - g)
    }

    fun evaluar(r: Resultado): Informe {
        var malo = false; var puntos = 0
        val l = ArrayList<String>()
        val a = r.analisis

        // 1) Nubes: METAR recientes (≤ 60 min) de los aeropuertos que da el análisis
        var peor: Double? = null; var detalle = ""
        var fenomeno = false
        for (ap in a["aeropuertos"].l().map { it.d() }) {
            if (ap["distancia_km"].dbl() > RADIO_AEROPUERTOS_KM) continue   // medido desde la posición GPS
            val g = r.metarMostrar[ap["icao"] as String] ?: continue
            val edad = (r.ahoraMs - g.first) / 60_000
            if (edad > 60) continue
            val nub = g.second["nubosidad"].d()
            val capas = listOf("baja", "media", "alta").flatMap { k -> nub[k].l().map { it.toString() } }
            val f = capas.maxOfOrNull { fraccion(it) } ?: 0.0
            if (peor == null || f > peor) { peor = f; detalle = "${ap["icao"]} (${ap["distancia_km"]} km): " + (if (capas.isEmpty()) "sin nubes significativas" else capas.joinToString("; ")) }
            val fen = g.second["fenomenos"]?.toString() ?: "Ninguno"
            if (fen != "Ninguno") { fenomeno = true; l.add("Fenómeno en ${ap["icao"]}: $fen") }
        }
        if (peor == null) {
            l.add("Nubes: sin METAR reciente (≤ 60 min) en 120 km; no se puede valorar la nubosidad")
            puntos += 1
        } else {
            l.add("Nubes (METAR a ≤ 120 km de tu posición, peor aeropuerto): ${(peor * 100).roundToInt()} % aprox. · $detalle")
            puntos += when { peor < 0.3 -> 0; peor < 0.6 -> 1; peor < 0.9 -> 2; else -> 3 }
        }
        if (fenomeno) puntos += 2

        // 2) Nubes de lluvia en camino (radar de la app)
        val pred = a["alertas_predictivas"].l()
        if (pred.isNotEmpty()) { malo = true; l.add("⚠️ Aviso de lluvia en camino: ${pred.first()}") }

        // 3) Estación de referencia: rocío, viento, lluvia
        val ref = a["referencia"] as? Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val d = ref?.get("datos") as? Map<String, Any?>
        if (d != null && r.datosFiables) {
            val t = numero(d["Temperatura"]); val hr = numero(d["Humedad"])
            if (t != null && hr != null) {
                val dif = t - rocio(t, hr)
                l.add("Rocío (estación): temperatura − punto de rocío ≈ ${"%.1f".format(Locale.US, dif)} °C · humedad ${hr.roundToInt()} %")
                if (dif < 2.0) { puntos += 1; l.add("⚠️ Riesgo de rocío o niebla: calefactor de óptica") }
            }
            val racha = numero(d["Viento máximo"])
            if (racha != null) {
                l.add("Racha máxima: ${racha.roundToInt()} km/h")
                if (racha > 30) { puntos += 1; l.add("⚠️ Viento fuerte para exposiciones largas") }
            }
            if ((numero(d["Lluvia hoy"]) ?: 0.0) > 0.0) l.add("Ha llovido hoy: ${d["Lluvia hoy"]}")
        } else l.add("Estación de referencia sin lectura fiable: no se valoran rocío ni viento")

        // 4) Luna
        val (tObs, esAhora) = instanteNoche(r.ahoraMs)
        val ilum = lunaIluminada(tObs)
        l.add("Luna iluminada ${if (esAhora) "ahora" else "a las 22:00"}: ${(ilum * 100).roundToInt()} %" + (if (ilum > 0.6) " · molesta para cielo profundo" else ""))
        l.add("Seeing (turbulencia): no se puede valorar con los datos de la app")
        l.add("Estado de ahora, no pronóstico. Criterio propio y aproximado, no un estudio validado.")

        val v = when { malo || puntos >= 4 -> "MALO"; puntos <= 1 -> "BUENO"; else -> "REGULAR" }
        return Informe(v, l)
    }
}
