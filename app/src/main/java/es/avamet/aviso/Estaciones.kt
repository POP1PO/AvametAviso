package es.avamet.aviso

import android.content.Context
import es.avamet.aviso.core.*

/** Acceso a la lista de estaciones (assets/coordenadas.json) para los Ajustes. */
object Estaciones {
    @Volatile private var coords: Map<String, Map<String, Any?>>? = null
    private fun cargar(c: Context): Map<String, Map<String, Any?>> = coords ?: synchronized(this) {
        coords ?: completarMunicipios(MiniJson.parse(c.assets.open("coordenadas.json").use { it.readBytes().toString(Charsets.UTF_8) }).d()).also { coords = it }
    }
    fun buscar(c: Context, consulta: String): List<Pair<String, String>> = buscarEstaciones(cargar(c), consulta)
}
