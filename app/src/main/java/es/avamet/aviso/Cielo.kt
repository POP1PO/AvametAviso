package es.avamet.aviso

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Estimación de "¿buen cielo para observar esta noche?".
 * Datos: pronóstico de Open-Meteo.com (licencia CC BY 4.0, uso gratuito solo no comercial).
 * IMPORTANTE: los umbrales de abajo son un criterio propio y sencillo, NO proceden de un estudio científico validado.
 * La corriente en chorro (250 hPa) como indicador de turbulencia (seeing) es una regla práctica habitual entre aficionados;
 * aquí no se ha verificado su fiabilidad.
 */
object Cielo {
    class Informe(
        val veredicto: String,            // BUENO / REGULAR / MALO
        val lineas: List<String>,
        val horas: Int,
        val actualizadoMs: Long,
    )

    private const val BASE = "https://api.open-meteo.com/v1/forecast?"
    private const val VARS = "cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,temperature_2m,dew_point_2m,relative_humidity_2m,wind_speed_10m,wind_gusts_10m"

    /** Edad de la Luna (días) y fracción iluminada, con el mes sinódico medio. Aproximado (error de horas). */
    fun luna(ahoraMs: Long): Pair<Double, Double> {
        val nuevaRef = 947_182_440_000L   // luna nueva del 2000-01-06 18:14 UTC
        val periodo = 29.530588853
        val dias = (ahoraMs - nuevaRef) / 86_400_000.0
        val edad = dias - floor(dias / periodo) * periodo
        val ilum = (1 - cos(2 * PI * edad / periodo)) / 2
        return Pair(edad, ilum)
    }

    private fun consulta(lat: Double, lon: Double, chorro: Boolean): JSONObject {
        val v = if (chorro) "$VARS,wind_speed_250hPa" else VARS
        val url = "${BASE}latitude=${"%.3f".format(Locale.US, lat)}&longitude=${"%.3f".format(Locale.US, lon)}&hourly=$v&timezone=auto&forecast_days=2"
        return JSONObject(Descargas.texto(url))
    }

    fun obtener(lat: Double, lon: Double, ahoraMs: Long): Informe {
        var tieneChorro = true
        val j = try { consulta(lat, lon, true) } catch (e: Exception) { tieneChorro = false; consulta(lat, lon, false) }
        val h = j.getJSONObject("hourly")
        val tiempos = h.getJSONArray("time")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone(j.optString("timezone", "UTC"))
        // Horas de noche (21:00–05:59 locales) que aún no han pasado
        val idx = ArrayList<Int>()
        for (i in 0 until tiempos.length()) {
            val t = tiempos.getString(i)
            val hora = t.substring(11, 13).toInt()
            val ms = fmt.parse(t)?.time ?: continue
            if ((hora >= 21 || hora <= 5) && ms >= ahoraMs - 3_600_000L && idx.size < 9) idx.add(i)
        }
        if (idx.isEmpty()) throw java.io.IOException("El pronóstico no cubre la próxima noche")
        fun media(k: String): Double? {
            val a = h.optJSONArray(k) ?: return null
            val xs = idx.mapNotNull { if (a.isNull(it)) null else a.getDouble(it) }
            return if (xs.isEmpty()) null else xs.average()
        }
        fun maximo(k: String): Double? {
            val a = h.optJSONArray(k) ?: return null
            return idx.mapNotNull { if (a.isNull(it)) null else a.getDouble(it) }.maxOrNull()
        }
        val nub = media("cloud_cover") ?: throw java.io.IOException("Sin dato de nubosidad")
        val baja = media("cloud_cover_low")
        val media_ = media("cloud_cover_mid")
        val alta = media("cloud_cover_high")
        val t = media("temperature_2m")
        val td = media("dew_point_2m")
        val hr = media("relative_humidity_2m")
        val racha = maximo("wind_gusts_10m")
        val chorro = if (tieneChorro) media("wind_speed_250hPa") else null
        val (_, ilum) = luna(ahoraMs)

        var puntos = 0   // 0 = bueno; más = peor
        val l = ArrayList<String>()
        l.add("Nubosidad (media de la noche): ${nub.roundToInt()} %" +
            (if (baja != null && media_ != null && alta != null) " · baja ${baja.roundToInt()} / media ${media_.roundToInt()} / alta ${alta.roundToInt()}" else ""))
        puntos += when { nub < 20 -> 0; nub < 50 -> 1; nub < 80 -> 2; else -> 3 }
        if (t != null && td != null) {
            val dif = t - td
            l.add("Rocío: temperatura − punto de rocío = ${"%.1f".format(Locale.US, dif)} °C" + (if (hr != null) " · humedad ${hr.roundToInt()} %" else ""))
            if (dif < 2.0) { puntos += 1; l.add("⚠️ Riesgo de rocío o niebla (usa calefactor de óptica)") }
        }
        if (racha != null) {
            l.add("Racha máxima: ${racha.roundToInt()} km/h")
            if (racha > 30) { puntos += 1; l.add("⚠️ Viento fuerte para fotografía de larga exposición") }
        }
        if (chorro != null) {
            l.add("Viento a 250 hPa (corriente en chorro): ${chorro.roundToInt()} km/h")
            if (chorro > 150) { puntos += 1; l.add("⚠️ Chorro intenso: es probable turbulencia (mal seeing) en alta resolución") }
        } else l.add("Corriente en chorro: no disponible")
        l.add("Luna iluminada: ${(ilum * 100).roundToInt()} %" + (if (ilum > 0.6) " · molesta para cielo profundo" else ""))
        val veredicto = when {
            nub >= 80 || puntos >= 4 -> "MALO"
            puntos <= 1 -> "BUENO"
            else -> "REGULAR"
        }
        l.add("Datos: Open-Meteo.com (CC BY 4.0). Criterio propio y aproximado, no un estudio validado.")
        return Informe(veredicto, l, idx.size, ahoraMs)
    }
}
