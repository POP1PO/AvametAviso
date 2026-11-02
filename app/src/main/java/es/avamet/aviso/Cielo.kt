package es.avamet.aviso

import es.avamet.aviso.core.*
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * "¿Cielo bueno para observar?" calculado SOLO con los datos que ya recoge la app:
 * METAR de aeropuertos cercanos (nubes, fenómenos), estación de referencia AVAMET (temperatura, humedad, racha, lluvia),
 * avisos de nubes de lluvia en camino y fase de la Luna (calculada).
 * Es el estado de AHORA, no un pronóstico. Los umbrales son un criterio propio, NO un estudio validado.
 */
object Cielo {
    class Informe(val veredicto: String, val lineas: List<String>)

    /** Fracción iluminada de la Luna (mes sinódico medio desde la luna nueva del 2000-01-06 18:14 UTC); error de horas. */
    fun lunaIluminada(ahoraMs: Long): Double {
        val p = 29.530588853
        val d = (ahoraMs - 947_182_440_000L) / 86_400_000.0
        val edad = d - floor(d / p) * p
        return (1 - cos(2 * PI * edad / p)) / 2
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
            l.add("Nubes: sin METAR reciente (≤ 60 min); no se puede valorar la nubosidad")
            puntos += 1
        } else {
            l.add("Nubes (METAR, peor aeropuerto): ${(peor * 100).roundToInt()} % aprox. · $detalle")
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
        val ilum = lunaIluminada(r.ahoraMs)
        l.add("Luna iluminada: ${(ilum * 100).roundToInt()} %" + (if (ilum > 0.6) " · molesta para cielo profundo" else ""))
        l.add("Seeing (turbulencia): no se puede valorar con los datos de la app")
        l.add("Estado de ahora, no pronóstico. Criterio propio y aproximado, no un estudio validado.")

        val v = when { malo || puntos >= 4 -> "MALO"; puntos <= 1 -> "BUENO"; else -> "REGULAR" }
        return Informe(v, l)
    }
}
