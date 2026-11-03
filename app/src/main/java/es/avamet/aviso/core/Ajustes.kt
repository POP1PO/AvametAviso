package es.avamet.aviso.core

/** Parámetros del cálculo. Mismos valores y nombres (en camelCase) que python_nucleo/aviso_core/ajustes.py. */
data class Ajustes(
    val radioKm: Double = 25.0,
    val radioAeropuertosKm: Double = 120.0,
    val edadMaxDatosS: Int = 600,
    val ventanaLluviaRadarS: Double = 1800.0,
    val umbralLluviaMm: Double = 0.1,
    val presionMinHpa: Double = 850.0,
    val presionMaxHpa: Double = 1100.0,
    val velocidadMinKmh: Double = 5.0,
    val etaMaxEstacionesMin: Int = 120,
    val anguloEstacionesGrados: Double = 30.0,
    val anguloAeropuertosGrados: Double = 45.0,
    val gapMaxSeguimientoS: Double = 1800.0,
    val histeresisReferenciaKm: Double = 1.0,
    val umbralVientoKmh: Double = 40.0,
    val umbralVientoMaxKmh: Double = 45.0,
    val umbralVientoPeligroKmh: Double = 45.0,
    val umbralRachaPeligroKmh: Double = 55.0,
    val radioLluviaPeligroKm: Double = 10.0,     // lluvia en una estación a ≤ esto => PELIGRO (suena la alarma)
    val radioLluviaAvisoKm: Double = 25.0,       // lluvia entre peligro y esto => VIGILANCIA con aviso (sin alarma)
    val etaPeligroMin: Int = 30,
    val zonaHoraria: String = "Europe/Madrid",
)
