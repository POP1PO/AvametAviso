package es.avamet.aviso.core

/** Versión publicada en la release «ultima» de GitHub: número de versión (versionCode) y URL del APK. */
data class VersionPublicada(val codigo: Long, val urlApk: String)

/**
 * Lee de la respuesta de la API de GitHub (releases/tags/ultima) el número de versión que escribe el workflow en las notas
 * («versionCode=NN») y la URL de descarga del asset AvametAviso.apk. null si falta alguno de los dos.
 */
fun leerVersionPublicada(json: String): VersionPublicada? {
    val codigo = Regex("versionCode=(\\d{1,9})").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    val url = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+/AvametAviso\\.apk)\"").find(json)?.groupValues?.get(1) ?: return null
    if (!url.startsWith("https://")) return null
    return VersionPublicada(codigo, url)
}

/** Hay que ofrecer actualización solo si la publicada es MAYOR que la instalada (nunca para bajar de versión). */
fun hayVersionNueva(instalada: Long, publicada: VersionPublicada?): Boolean = publicada != null && publicada.codigo > instalada
