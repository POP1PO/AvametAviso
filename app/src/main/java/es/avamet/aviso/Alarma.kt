package es.avamet.aviso

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager

/** Notificaciones, sonido de alarma (silenciable) y encendido de pantalla. */
object Alarma {
    const val CANAL_VIGILANCIA = "vigilancia"
    const val CANAL_ALARMA = "alarma"
    const val CANAL_AVISOS = "avisos"
    const val ID_VIGILANCIA = 1
    const val ID_ALARMA = 2
    const val ID_AVISO = 3

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun crearCanales(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CANAL_VIGILANCIA, "Vigilancia en curso", NotificationManager.IMPORTANCE_LOW))
        val alarma = NotificationChannel(CANAL_ALARMA, "Alarma de peligro", NotificationManager.IMPORTANCE_HIGH)
        alarma.setSound(null, null)                       // el sonido lo reproducimos nosotros para poder silenciarlo
        alarma.enableVibration(true)
        alarma.vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
        alarma.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(alarma)
        nm.createNotificationChannel(NotificationChannel(CANAL_AVISOS, "Avisos de la aplicación", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun abrir(c: Context, clase: Class<*>, req: Int): PendingIntent =
        PendingIntent.getActivity(c, req, Intent(c, clase).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun notificacionVigilancia(c: Context, texto: String): Notification =
        Notification.Builder(c, CANAL_VIGILANCIA)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AVAMET Aviso vigilando")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(abrir(c, MainActivity::class.java, 10))
            .setOngoing(true)
            .build()

    /** Peligro: notificación de pantalla completa (enciende la pantalla y se muestra sobre el bloqueo) + sonido + pantalla. */
    fun disparar(c: Context, motivos: List<String>) {
        val texto = if (motivos.isEmpty()) "Peligro" else motivos.joinToString("\n")
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val silenciar = PendingIntent.getService(c, 20, Intent(c, MonitorServicio::class.java).setAction(MonitorServicio.ACCION_SILENCIAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(c, CANAL_ALARMA)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ PELIGRO")
            .setContentText(texto.lines().first())
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(abrir(c, MainActivity::class.java, 11))
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_lock_silent_mode, "Silenciar sonido", silenciar)
        if (Config.alarmaPantalla(c)) b.setFullScreenIntent(abrir(c, AlertaActivity::class.java, 12), true)
        nm.notify(ID_ALARMA, b.build())
        if (Config.alarmaPantalla(c)) encenderPantalla(c)
        if (Config.alarmaSonido(c)) sonar(c)
    }

    fun aviso(c: Context, titulo: String, texto: String) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_AVISO, Notification.Builder(c, CANAL_AVISOS).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(titulo).setContentText(texto).setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(abrir(c, MainActivity::class.java, 13)).setAutoCancel(true).build())
    }

    fun quitarAlarma(c: Context) {
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_ALARMA)
    }

    @Synchronized
    fun sonar(c: Context) {
        if (player != null) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            mp.setDataSource(c.applicationContext, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
            Estado.sonando = true
            Estado.notificar()
        } catch (e: Exception) {
            Estado.mensaje = "No se pudo reproducir la alarma: ${e.message}"
        }
    }

    @Synchronized
    fun silenciar() {
        player?.let {
            try { it.stop() } catch (_: Exception) { }
            it.release()
        }
        player = null
        Estado.sonando = false
        Estado.notificar()
    }

    @Suppress("DEPRECATION")
    @Synchronized
    fun encenderPantalla(c: Context) {
        try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock?.let { if (it.isHeld) it.release() }
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "avametaviso:pantalla")
            wl.acquire(30_000L)
            wakeLock = wl
        } catch (_: Exception) { }
    }
}
