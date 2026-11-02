package es.avamet.aviso

import android.location.Location
import android.os.Handler
import android.os.Looper
import es.avamet.aviso.core.Dict
import java.util.concurrent.CopyOnWriteArrayList

/** Resultado de un ciclo de vigilancia. */
class Resultado(
    val ahoraMs: Long,
    val lat: Double,
    val lon: Double,
    val analisis: Dict,                 // salida de analizar()
    val peligro: Dict,                  // salida de evaluarPeligro(): {nivel, motivos}
    val metarMostrar: Map<String, Pair<Long, Map<String, Any?>>>,   // icao -> (instante de descarga ms, datos); hasta 3 h
    val errores: List<String>,          // problemas de red de este ciclo (texto para el usuario)
    val descargas: Int,                 // peticiones HTTP hechas en el ciclo
) {
    val nivel: String get() = peligro["nivel"] as String

    /** false si la estación de referencia no tiene una lectura reciente: entonces "SEGURO" no significa nada. */
    val datosFiables: Boolean get() =
        (((analisis["referencia"] as? Map<*, *>)?.get("estado") as? Map<*, *>)?.get("estado")) == "ACTIVA"
    @Suppress("UNCHECKED_CAST")
    val motivos: List<String> get() = (peligro["motivos"] as List<Any?>).map { it.toString() }
}

/** Estado compartido entre el servicio y las pantallas (mismo proceso). */
object Estado {
    @Volatile var resultado: Resultado? = null
    @Volatile var ubicacion: Location? = null
    @Volatile var activo: Boolean = false
    @Volatile var sonando: Boolean = false
    @Volatile var mensaje: String = ""
    private val oyentes = CopyOnWriteArrayList<() -> Unit>()
    private val ui = Handler(Looper.getMainLooper())

    fun escuchar(f: () -> Unit) { oyentes.add(f) }
    fun dejarDeEscuchar(f: () -> Unit) { oyentes.remove(f) }
    fun notificar() { ui.post { for (f in oyentes) f() } }
}
