package es.avamet.aviso

import es.avamet.aviso.core.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Descargas HTTP (sin dependencias). Lanzan IOException si fallan; los métodos `estacion` y `metars` la convierten en texto de error. */
object Descargas {
    // Identificable a propósito: si AVAMET quiere contactar o limitar el tráfico, sabe quién es.
    const val USER_AGENT = "AvametAviso/0.1 (app personal de aficionados, sin fines comerciales)"
    private const val AVAMET = "https://www.avamet.org/mxo_i.php?id="
    private const val METAR = "https://aviationweather.gov/api/data/metar?format=json&ids="

    private val hilos = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }

    /** Cada descarga tiene un tope DURO de 25 s (un DNS o un TLS colgado no respeta los timeouts de la conexión). */
    private fun descargar(url: String): String {
        val f = hilos.submit(java.util.concurrent.Callable { descargarDirecto(url) })
        try {
            return f.get(25, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            f.cancel(true)
            throw java.net.SocketTimeoutException("Sin respuesta tras 25 s")
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause
            if (c is Exception) throw c
            throw java.io.IOException("${c?.javaClass?.simpleName}: ${c?.message}")
        }
    }

    private fun descargarDirecto(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 12_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code == 204) return ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val buf = ByteArrayOutputStream()
            c.inputStream.use { it.copyTo(buf) }
            return decodificar(buf.toByteArray(), c.contentType)
        } finally {
            c.disconnect()
        }
    }

    /** Charset de la cabecera si existe; si no, UTF-8 estricto y, si no es UTF-8 válido, ISO-8859-1. (No he podido comprobar qué envía AVAMET.) */
    private fun decodificar(b: ByteArray, contentType: String?): String {
        val m = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType ?: "")
        if (m != null) try { return String(b, charset(m.groupValues[1])) } catch (_: Exception) { }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (_: Exception) {
            String(b, Charsets.ISO_8859_1)
        }
    }

    fun textoError(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Sin conexión a Internet (o no se resuelve el servidor)"
        is java.net.SocketTimeoutException -> "Tiempo de espera agotado"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Lectura de una estación, o {"Error": texto}. */
    fun estacion(id: String): Dict = try {
        val datos = parsearEstacion(textoDeHtml(descargar(AVAMET + id)))
        if (datos.isEmpty()) dict("Error" to "La página de la estación no contiene datos") else datos
    } catch (e: Exception) {
        dict("Error" to textoError(e))
    }

    /** METAR parseados {icao: datos}, o lanza IOException. */
    fun metars(icaos: List<String>): Dict {
        if (icaos.isEmpty()) return dict()
        val txt = descargar(METAR + icaos.joinToString(","))
        if (txt.isBlank()) return dict()
        @Suppress("UNCHECKED_CAST")
        return parsearMetars(MiniJson.parse(txt) as? List<Any?>)
    }
}
