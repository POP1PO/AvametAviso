package es.avamet.aviso.core

/** Búsqueda de estaciones por identificador exacto o por trozo del nombre/municipio (sin distinguir mayúsculas ni acentos). Devuelve (id, nombre). */
fun buscarEstaciones(coords: Map<String, Map<String, Any?>>, consulta: String): List<Pair<String, String>> {
    fun norm(t: String) = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
    val q = norm(consulta)
    if (q.isEmpty()) return emptyList()
    coords.keys.firstOrNull { it.equals(consulta.trim(), true) }?.let { id -> return listOf(id to (coords[id]?.get("nombre")?.toString() ?: id)) }
    return coords.filter { (id, v) -> norm(id).contains(q) || norm(v["nombre"]?.toString() ?: "").contains(q) || norm(v["municipio"]?.toString() ?: "").contains(q) }
        .map { (id, v) -> id to (v["nombre"]?.toString() ?: id) }.sortedBy { it.second }
}
