package es.avamet.aviso

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import es.avamet.aviso.core.Wms
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Capa «Nubes IR» (Meteosat 10.8 µm, WMS público de EUMETView): SOLO MOSTRAR; no entra en alarmas ni en el veredicto.
 * No necesita ordenador ni claves. NO verificado contra el servidor real (ver core/Wms.kt).
 */
object MapaIr {
    private class Fuente(nombre: String, private val capa: String, private val tiempo: String?) :
        OnlineTileSourceBase(nombre, 0, 19, 256, ".png", arrayOf(Wms.BASE)) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            Wms.urlTesela(capa, MapTileIndex.getZoom(pMapTileIndex), MapTileIndex.getX(pMapTileIndex), MapTileIndex.getY(pMapTileIndex), tiempo)
    }

    /** Hora del último barrido publicado de la capa (hilo de fondo; ~7 kB). null si no se pudo saber. */
    fun ultimoTiempo(capa: String): String? = try {
        Wms.urlCapabilitiesCapa(capa)?.let { Wms.tiempoPorDefecto(Descargas.texto(it)) }
    } catch (e: Exception) { null }

    /** "20:00" en la hora del móvil a partir de "2026-11-03T19:00:00Z"; el texto tal cual si no se entiende. */
    fun horaLocal(tiempo: String): String = try {
        java.time.Instant.parse(tiempo).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5)
    } catch (e: Exception) { tiempo }

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
    fun sonda(capa: String, tiempo: String?): String = try {
        val url = Wms.urlTesela(capa, 5, 16, 12, tiempo)
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

    /** Overlay de teselas del barrido `tiempo`. El nombre del origen lleva ese barrido (o, sin él, la franja de 15 min) para que la
     *  caché de osmdroid no mezcle imágenes de barridos distintos.
     *  El proveedor de teselas es propio (no el del mapa), así que hay que darle el «avisador» del mapa: sin él las teselas
     *  se descargan pero NADIE pide repintar y la capa no aparece hasta que el usuario mueve el mapa (lo hace igual
     *  MapView.setTileProvider con su propio proveedor). */
    fun crearOverlay(c: Context, mapa: MapView, capa: String, tiempo: String?, opacidad: Int): TilesOverlay {
        val marca = tiempo?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() } ?: "f${Wms.franjaCache(System.currentTimeMillis())}"
        val fuente = Fuente("EUMETSAT_IR_$marca", capa, tiempo)
        val proveedor = MapTileProviderBasic(c.applicationContext, fuente)
        proveedor.tileRequestCompleteHandlers.add(mapa.tileRequestCompleteHandler)
        val o = TilesOverlay(proveedor, c)
        o.loadingBackgroundColor = Color.TRANSPARENT
        o.loadingLineColor = Color.TRANSPARENT
        ajustarOpacidad(o, opacidad)
        return o
    }

    /** Quita la capa del mapa y para su proveedor (hilos de descarga y caché en memoria). Quitarla de la lista no lo hace. */
    fun quitar(mapa: MapView, o: TilesOverlay?) {
        if (o == null) return
        mapa.overlays.remove(o)
        o.onDetach(mapa)
    }

    fun ajustarOpacidad(o: TilesOverlay, porcentaje: Int) {
        val m = ColorMatrix(); m.setScale(1f, 1f, 1f, porcentaje.coerceIn(10, 100) / 100f)
        o.setColorFilter(ColorMatrixColorFilter(m))
    }
}
