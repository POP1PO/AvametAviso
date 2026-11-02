package es.avamet.aviso

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Cada ~15 min (mínimo de WorkManager, y aproximado) comprueba el "latido" del servicio. Si el usuario quiere vigilar y el servicio
 * lleva demasiado sin dar señales, es que el sistema lo mató: se avisa. Es un mecanismo de mejor esfuerzo (algunos fabricantes
 * también detienen WorkManager).
 */
class VigilanteWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        val c = applicationContext
        if (!Config.vigilanciaDeseada(c)) return Result.success()
        val limite = maxOf(20, Config.intervaloMin(c) * 3) * 60_000L
        val sinLatido = System.currentTimeMillis() - Config.ultimoLatido(c)
        if (sinLatido > limite) {
            Alarma.crearCanales(c)
            Alarma.aviso(c, "⚠️ La vigilancia se detuvo",
                "El sistema cerró AVAMET Aviso hace ${sinLatido / 60_000} min sin avisar. Ábrela y pulsa «Iniciar vigilancia». Revisa los ajustes de batería de la app.")
            Config.setVigilanciaDeseada(c, false)
        }
        return Result.success()
    }
}
