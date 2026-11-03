package es.avamet.aviso

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import es.avamet.aviso.core.Wms
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Capa «Nubes IR» (Meteosat 10.8 µm, WMS público de EUMETView): SOLO MOSTRAR; no entra en alarmas ni en el veredicto.
 * No necesita ordenador ni claves. NO verificado contra el servidor real (ver core/Wms.kt).
 */
object MapaIr {
    private class Fuente(nombre: String, private val capa: String) :
        OnlineTileSourceBase(nombre, 0, 19, 256, ".png", arrayOf(Wms.BASE)) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            Wms.urlTesela(capa, MapTileIndex.getZoom(pMapTileIndex), MapTileIndex.getX(pMapTileIndex), MapTileIndex.getY(pMapTileIndex))
    }

    private fun prefs(c: Context) = c.getSharedPreferences("config", Context.MODE_PRIVATE)
    fun capaGuardada(c: Context): String = prefs(c).getString("capaIr", null) ?: Wms.CAPA_POR_DEFECTO
    fun visible(c: Context): Boolean = prefs(c).getBoolean("irVisible", false)
    fun setVisible(c: Context, v: Boolean) = prefs(c).edit().putBoolean("irVisible", v).apply()

    /** Pregunta al servidor qué capa IR 10.8 existe (hilo de fondo). Devuelve (capa, mensaje): capa nula si no hay ninguna o falla la consulta. */
    fun detectarCapa(c: Context): Pair<String?, String> = try {
        val nombres = Wms.nombresDeCapas(Descargas.texto(Wms.urlCapabilities()))
        val capa = Wms.elegirCapaIr(nombres)
        if (capa != null) { prefs(c).edit().putString("capaIr", capa).apply(); capa to "Capa IR: $capa (${nombres.size} capas en el servidor)" }
        else null to "El servidor no lista ninguna capa IR 10.8 (${nombres.size} capas)"
    } catch (e: Exception) {
        // sin red o servidor caído: se usa la última capa conocida (o la suposición por defecto)
        capaGuardada(c) to "No se pudo leer la lista de capas (${Descargas.textoError(e)}); uso ${capaGuardada(c)}"
    }

    /** Pide UNA tesela (z5, sobre el Mediterráneo occidental) y cuenta qué llega: código HTTP, tipo, tamaño y % de píxeles no transparentes.
     *  Hilo de fondo. Sirve para ver si EUMETSAT contesta algo útil (o un XML de error, p. ej. proyección no soportada). */
    fun sonda(capa: String): String = try {
        val url = Wms.urlTesela(capa, 5, 16, 12)
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 20000; c.setRequestProperty("User-Agent", Descargas.USER_AGENT)
        try {
            val code = c.responseCode
            val tipo = c.contentType ?: "?"
            val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code in 200..299 && tipo.startsWith("image/")) {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null) "Tesela de prueba: HTTP $code, $tipo, ${bytes.size} B, pero no se puede decodificar"
                else {
                    val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val visibles = px.count { (it ushr 24) > 0 } * 100 / px.size
                    "Tesela de prueba: HTTP $code, $tipo, ${bytes.size} B, $visibles % de píxeles con imagen" + if (visibles == 0) " (¡vacía!)" else ""
                }
            } else "Tesela de prueba: HTTP $code, $tipo: " + String(bytes, Charsets.UTF_8).replace(Regex("\\s+"), " ").take(200)
        } finally { c.disconnect() }
    } catch (e: Exception) { "Tesela de prueba falló: ${Descargas.textoError(e)}" }

    /** Overlay de teselas. El nombre del origen lleva la franja de 15 min para que la caché de osmdroid se renueve con cada barrido. */
    fun crearOverlay(c: Context, capa: String, opacidad: Int): TilesOverlay {
        val fuente = Fuente("EUMETSAT_IR_${Wms.franjaCache(System.currentTimeMillis())}", capa)
        val o = TilesOverlay(MapTileProviderBasic(c.applicationContext, fuente), c)
        o.loadingBackgroundColor = Color.TRANSPARENT
        o.loadingLineColor = Color.TRANSPARENT
        ajustarOpacidad(o, opacidad)
        return o
    }

    fun ajustarOpacidad(o: TilesOverlay, porcentaje: Int) {
        val m = ColorMatrix(); m.setScale(1f, 1f, 1f, porcentaje.coerceIn(10, 100) / 100f)
        o.setColorFilter(ColorMatrixColorFilter(m))
    }
}
