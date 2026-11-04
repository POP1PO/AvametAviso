package es.avamet.aviso

import android.content.Context

/** Preferencias sencillas (SharedPreferences). */
object Config {
    private fun p(c: Context) = c.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun intervaloMin(c: Context): Int = p(c).getInt("intervaloMin", 5).coerceIn(1, 60)
    fun setIntervaloMin(c: Context, v: Int) = p(c).edit().putInt("intervaloMin", v.coerceIn(1, 60)).apply()

    fun alarmaSonido(c: Context): Boolean = p(c).getBoolean("alarmaSonido", true)
    fun setAlarmaSonido(c: Context, v: Boolean) = p(c).edit().putBoolean("alarmaSonido", v).apply()

    fun alarmaPantalla(c: Context): Boolean = p(c).getBoolean("alarmaPantalla", true)
    fun setAlarmaPantalla(c: Context, v: Boolean) = p(c).edit().putBoolean("alarmaPantalla", v).apply()

    /** true mientras el usuario quiere vigilar; se apaga al detener (o al deslizar la app). Si sigue en true y no hay latido, el sistema mató el servicio. */
    fun vigilanciaDeseada(c: Context): Boolean = p(c).getBoolean("deseada", false)
    fun setVigilanciaDeseada(c: Context, v: Boolean) = p(c).edit().putBoolean("deseada", v).apply()

    fun ultimoLatido(c: Context): Long = p(c).getLong("latido", 0L)
    fun setUltimoLatido(c: Context, ms: Long) = p(c).edit().putLong("latido", ms).apply()

    /** Estación de referencia elegida a mano (id de AVAMET); null = automática (la más cercana a tu posición). */
    fun estacionFija(c: Context): String? = p(c).getString("estacionFija", null)?.takeIf { it.isNotEmpty() }
    fun setEstacionFija(c: Context, id: String?) = p(c).edit().putString("estacionFija", id ?: "").apply()

    /** Tema de la app: "claro", "oscuro" o "rojo" (nocturno). */
    fun tema(c: Context): String = p(c).getString("tema", "oscuro") ?: "oscuro"
    fun setTema(c: Context, t: String) = p(c).edit().putString("tema", t).apply()
}
