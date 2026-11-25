package es.avamet.aviso

import es.avamet.aviso.core.MiniJson
import es.avamet.aviso.core.haversineKm

/**
 * Observaciones de las estaciones oficiales de AEMET (AEMET OpenData) más cercanas. OPCIONAL: requiere la clave del usuario.
 * AEMET responde en dos pasos: un JSON con el campo «datos» (una URL temporal) y luego hay que descargar esa URL.
 * La clave viaja solo en la cabecera «api_key». Solo informativo: no entra en las alarmas.
 * Atribución (nota legal de AEMET): «© AEMET» / «Fuente: AEMET».
 */
object Aemet {
    const val ATRIBUCION = "© AEMET · Fuente: AEMET (Agencia Estatal de Meteorología). Solo informativo; esta app no está respaldada por AEMET."
    private const val BASE = "https://opendata.aemet.es/opendata/api"
    private const val RUTA = "/observacion/convencional/todas"

    class Estacion(val idema: String, val nombre: String, val km: Double, val fintUtc: String,
                   val lluviaMm: Double?, val tempC: Double?, val humedad: Double?, val vientoKmh: Double?)

    class ErrorAemet(mensaje: String) : Exception(mensaje)

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }?.takeIf { !it.isNaN() }

    /** Función pura (probada sin red): de la lista de observaciones, las n estaciones más cercanas con su última lectura. */
    fun cercanas(registros: List<Any?>, lat: Double, lon: Double, radioKm: Double = 60.0, n: Int = 5): List<Estacion> {
        val ultima = HashMap<String, Map<*, *>>()
        for (r in registros) {
            val m = r as? Map<*, *> ?: continue
            val id = m["idema"]?.toString() ?: continue
            val f = m["fint"]?.toString() ?: continue
            val previa = ultima[id]?.get("fint")?.toString() ?: ""
            if (f > previa) ultima[id] = m
        }
        val res = ArrayList<Estacion>()
        for ((id, m) in ultima) {
            val la = num(m["lat"]) ?: continue
            val lo = num(m["lon"]) ?: continue
            val d = haversineKm(lat, lon, la, lo)
            if (d > radioKm) continue
            val vv = num(m["vv"])
            res.add(Estacion(id, m["ubi"]?.toString() ?: id, Math.round(d * 10) / 10.0, m["fint"].toString(),
                num(m["prec"]), num(m["ta"]), num(m["hr"]), vv?.let { Math.round(it * 3.6 * 10) / 10.0 }))
        }
        return res.sortedBy { it.km }.take(n)
    }

    /** Dos pasos con la clave en cabecera. Lanza [ErrorAemet] con un texto que NUNCA contiene la clave. */
    fun consultar(clave: String, lat: Double, lon: Double, radioKm: Double = 60.0): List<Estacion> {
        if (clave.isBlank()) throw ErrorAemet("Falta la clave de AEMET")
        val paso1 = try { MiniJson.parse(Descargas.textoConCabeceras(BASE + RUTA, mapOf("api_key" to clave, "Accept" to "application/json"))) }
        catch (e: Exception) { throw ErrorAemet(textoRed(e)) }
        val m = paso1 as? Map<*, *> ?: throw ErrorAemet("Respuesta inesperada de AEMET")
        val url = m["datos"]?.toString()
        if (num(m["estado"])?.toInt() != 200 || url.isNullOrBlank())
            throw ErrorAemet("AEMET no dio los datos" + (m["descripcion"]?.toString()?.let { ": " + it.take(120) } ?: ""))
        if (!url.startsWith("https://opendata.aemet.es/")) throw ErrorAemet("Dirección de datos inesperada")     // solo se descarga de AEMET
        val datos = try { MiniJson.parse(Descargas.textoConCabeceras(url, mapOf("Accept" to "application/json"))) }
        catch (e: Exception) { throw ErrorAemet(textoRed(e)) }
        return cercanas(datos as? List<Any?> ?: throw ErrorAemet("Formato de datos inesperado"), lat, lon, radioKm)
    }

    private fun textoRed(e: Exception): String {
        val t = Descargas.textoError(e)
        return if (t.startsWith("HTTP 401") || t.startsWith("HTTP 403")) "AEMET rechazó la clave: puede estar mal copiada o caducada (valen 3 meses)"
        else if (t.startsWith("HTTP 429")) "AEMET indica demasiadas consultas; se reintentará más tarde"
        else t
    }

    /** ¿Tiene la forma de una clave? (letras, números, punto, _ o -; sin espacios). */
    fun claveValida(c: String) = Regex("^[A-Za-z0-9_.-]{20,2000}$").matches(c)
}
