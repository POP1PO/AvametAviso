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
        OnlineTileSourceBase(nombre, 0, 8, 256, ".png", arrayOf(Wms.BASE)) {
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
