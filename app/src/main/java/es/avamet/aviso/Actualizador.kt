package es.avamet.aviso

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import es.avamet.aviso.core.VersionPublicada
import es.avamet.aviso.core.leerVersionPublicada
import es.avamet.aviso.core.hayVersionNueva
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualizaciones desde GitHub (release «ultima» de POP1PO/AvametAviso). Android NO deja instalar en silencio una app que no
 * viene de Play Store: aquí se detecta la versión nueva, se descarga y el sistema pide al usuario un toque para instalar.
 * Como el APK va firmado con la misma clave y con un número de versión mayor, se instala ENCIMA de la anterior sin borrar nada.
 */
object Actualizador {
    private const val API = "https://api.github.com/repos/POP1PO/AvametAviso/releases/tags/ultima"
    private const val SEIS_HORAS_MS = 6 * 3600_000L

    @Suppress("DEPRECATION")
    fun versionInstalada(c: Context): Long = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (e: Exception) { 0L }

    fun nombreVersion(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    /** Hilo de fondo. Devuelve la versión publicada si es más nueva que la instalada; null si no hay nada nuevo. Lanza si falla la red. */
    fun buscar(c: Context): VersionPublicada? {
        val pub = leerVersionPublicada(Descargas.texto(API))
        return if (hayVersionNueva(versionInstalada(c), pub)) pub else null
    }

    /** ¿Toca consultar de nuevo? (como mucho cada 6 h, para no gastar las 60 consultas/hora que GitHub da sin identificarse). */
    fun tocaConsultar(c: Context): Boolean =
        System.currentTimeMillis() - c.getSharedPreferences("config", Context.MODE_PRIVATE).getLong("actualizacionConsultada", 0L) > SEIS_HORAS_MS

    fun marcarConsulta(c: Context) {
        c.getSharedPreferences("config", Context.MODE_PRIVATE).edit().putLong("actualizacionConsultada", System.currentTimeMillis()).apply()
    }

    /** Hilo de fondo. Descarga el APK a la caché de la app y lo devuelve. */
    fun descargar(c: Context, v: VersionPublicada): File {
        val dir = File(c.cacheDir, "actualizaciones").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val destino = File(dir, "AvametAviso.apk")
        val con = URL(v.urlApk).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 10_000; con.readTimeout = 60_000
            con.setRequestProperty("User-Agent", Descargas.USER_AGENT)
            if (con.responseCode !in 200..299) throw java.io.IOException("HTTP ${con.responseCode}")
            con.inputStream.use { entrada -> destino.outputStream().use { salida -> entrada.copyTo(salida) } }
        } finally { con.disconnect() }
        if (destino.length() < 100_000) { destino.delete(); throw java.io.IOException("el archivo descargado es demasiado pequeño") }
        return destino
    }

    /** Abre el instalador del sistema con el APK. Si la app aún no tiene permiso para instalar, abre antes esa pantalla de Ajustes. */
    fun instalar(c: Context, apk: File): String {
        if (!c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Activa «Permitir de esta fuente» para AVAMET Aviso y vuelve a pulsar Instalar."
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", apk)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Confirma la instalación en la pantalla del sistema."
    }
}
