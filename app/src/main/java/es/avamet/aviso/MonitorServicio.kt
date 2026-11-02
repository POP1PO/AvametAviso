package es.avamet.aviso

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Servicio en primer plano (tipo `location`). Sigue la posición del GPS, ejecuta un ciclo cada N minutos y dispara la alarma
 * cuando el nivel pasa a PELIGRO. Se detiene al deslizar la app (stopWithTask=true) o con el botón Detener.
 */
class MonitorServicio : Service() {
    companion object {
        const val ACCION_PARAR = "es.avamet.aviso.PARAR"
        const val ACCION_SILENCIAR = "es.avamet.aviso.SILENCIAR"

        fun iniciar(c: Context) = ContextCompat.startForegroundService(c, Intent(c, MonitorServicio::class.java))
        fun parar(c: Context) { c.startService(Intent(c, MonitorServicio::class.java).setAction(ACCION_PARAR)) }
    }

    private var monitor: Monitor? = null
    private var ejecutor: ScheduledExecutorService? = null
    private var lm: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nivelPrevio = "SEGURO"
    private var ultimoOk = 0L
    private var avisoSinDatosMs = 0L

    private val oyenteGps = object : LocationListener {
        override fun onLocationChanged(l: Location) { Estado.ubicacion = mejor(Estado.ubicacion, l) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("API antigua")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** Se prefiere la nueva salvo que la anterior sea de GPS, reciente (< 3 min) y más precisa que la nueva. */
    private fun mejor(anterior: Location?, nueva: Location): Location {
        if (anterior == null) return nueva
        val edadAnt = nueva.time - anterior.time
        if (edadAnt > 3 * 60_000L) return nueva
        return if (nueva.hasAccuracy() && anterior.hasAccuracy() && nueva.accuracy > anterior.accuracy * 2) anterior else nueva
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_PARAR -> { detener(); return START_NOT_STICKY }
            ACCION_SILENCIAR -> {
                Alarma.silenciar()
                if (!Estado.activo) { stopSelf(); return START_NOT_STICKY }
                return START_STICKY
            }
        }
        if (Estado.activo) return START_STICKY            // ya en marcha
        Alarma.crearCanales(this)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29)
                startForeground(Alarma.ID_VIGILANCIA, Alarma.notificacionVigilancia(this, "Buscando posición…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else
                startForeground(Alarma.ID_VIGILANCIA, Alarma.notificacionVigilancia(this, "Buscando posición…"))
        } catch (e: Exception) {
            Estado.mensaje = "No se pudo iniciar el servicio: ${e.message}"
            Estado.notificar()
            stopSelf()
            return START_NOT_STICKY
        }
        Estado.activo = true
        Estado.log("Servicio iniciado")
        ultimoOk = System.currentTimeMillis()
        Config.setVigilanciaDeseada(this, true)
        Config.setUltimoLatido(this, System.currentTimeMillis())
        Estado.mensaje = ""

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "avametaviso:vigilancia").also { it.setReferenceCounted(false); it.acquire() }
        } catch (_: Exception) { }

        pedirUbicaciones()
        monitor = Monitor(this)
        val min = Config.intervaloMin(this).toLong()
        ejecutor = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ cicloSeguro() }, 0, min, TimeUnit.MINUTES)
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "vigilante", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<VigilanteWorker>(15, TimeUnit.MINUTES).build())
        Estado.notificar()
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun pedirUbicaciones() {
        val l = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = l
        try {
            for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (l.isProviderEnabled(prov)) {
                    l.requestLocationUpdates(prov, 60_000L, 100f, oyenteGps, Looper.getMainLooper())
                    l.getLastKnownLocation(prov)?.let { Estado.ubicacion = mejor(Estado.ubicacion, it) }
                }
            }
        } catch (e: SecurityException) {
            Estado.mensaje = "Falta el permiso de ubicación"
        }
    }

    private fun hora(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun cicloSeguro() {
        try {
            Config.setUltimoLatido(this, System.currentTimeMillis())
            val loc = Estado.ubicacion
            if (loc == null) {
                Estado.log("Sin posición todavía (¿GPS activado? ¿permiso de ubicación?)")
                Estado.mensaje = "Esperando posición GPS…"
                actualizarNotificacion("Esperando posición GPS…")
            } else {
                Estado.log("Posición: ${loc.provider} precisión ${if (loc.hasAccuracy()) loc.accuracy.toInt().toString() + " m" else "?"}")
                val r = monitor!!.ciclo(loc.latitude, loc.longitude) { p -> Estado.resultado = p; Estado.notificar() }
                Estado.resultado = r
                Estado.mensaje = ""
                val estadoRef = ((r.analisis["referencia"] as? Map<*, *>)?.get("estado") as? Map<*, *>)?.get("estado")
                if (estadoRef == "ACTIVA" && r.errores.isEmpty()) ultimoOk = r.ahoraMs
                val mov = r.analisis["movimiento"] as Map<*, *>
                val txt = (if (r.datosFiables || r.nivel == "PELIGRO") r.nivel else "SIN DATOS FIABLES") + " · nubes hacia ${mov["direccion_destino"]} ${mov["velocidad_kmh"]} km/h · ${hora(r.ahoraMs)}" +
                    (if (r.errores.isNotEmpty()) "\n⚠️ " + r.errores.first() else "")
                actualizarNotificacion(txt)
                if (r.nivel == "PELIGRO" && nivelPrevio != "PELIGRO") Alarma.disparar(this, r.motivos)
                if (r.nivel != "PELIGRO" && nivelPrevio == "PELIGRO") Alarma.quitarAlarma(this)
                nivelPrevio = r.nivel
            }
        } catch (e: Exception) {
            Estado.mensaje = "Error en el ciclo: ${e.javaClass.simpleName}: ${e.message}"
            Estado.log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
        comprobarSinDatos()
        Estado.notificar()
    }

    /** Si hace mucho que no hay una lectura fiable de la estación de referencia, avisa (sin alarma): no se puede garantizar el aviso. */
    private fun comprobarSinDatos() {
        val ahora = System.currentTimeMillis()
        val limite = maxOf(15, Config.intervaloMin(this) * 3) * 60_000L
        if (ahora - ultimoOk > limite && ahora - avisoSinDatosMs > limite) {
            avisoSinDatosMs = ahora
            Alarma.aviso(this, "Sin datos fiables", "Hace más de ${limite / 60_000} min que no hay una lectura fiable. No se puede garantizar el aviso de peligro.")
        }
    }

    private fun actualizarNotificacion(texto: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(Alarma.ID_VIGILANCIA, Alarma.notificacionVigilancia(this, texto))
    }

    private fun detener() {
        Config.setVigilanciaDeseada(this, false)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        detener()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Parada ordenada (botón, deslizar la app): no es un fallo, no hay que avisar.
        Config.setVigilanciaDeseada(this, false)
        try { lm?.removeUpdates(oyenteGps) } catch (_: Exception) { }
        ejecutor?.shutdownNow()
        monitor?.cerrar()
        Alarma.silenciar()
        Alarma.quitarAlarma(this)
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        WorkManager.getInstance(this).cancelUniqueWork("vigilante")
        Estado.activo = false
        Estado.notificar()
        super.onDestroy()
    }
}
