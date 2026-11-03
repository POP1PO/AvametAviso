"""Parámetros del cálculo. Valores = los de backend/config.py de AVAMET Monitor (mismos nombres, para poder compararlos)."""

AJUSTES = {
    "RADIO_KM": 25.0,                 # estaciones consideradas "de alrededor"
    "RADIO_AEROPUERTOS_KM": 120.0,    # aeropuertos con METAR considerados
    "EDAD_MAX_DATOS_S": 600,          # una lectura de estación más vieja de esto se considera congelada
    "VENTANA_LLUVIA_RADAR_S": 1800,   # "llueve" = el acumulado subió en los últimos 30 min
    "UMBRAL_LLUVIA_MM": 0.1,          # subida mínima del acumulado diario para contar como lluvia nueva
    "PRESION_MIN_HPA": 850,
    "PRESION_MAX_HPA": 1100,
    "VELOCIDAD_MIN_KMH": 5,           # por debajo, no se predice llegada (las nubes casi no avanzan)
    "ETA_MAX_ESTACIONES_MIN": 120,    # avisos de estaciones: solo si llegan en menos de esto
    "ANGULO_ESTACIONES_GRADOS": 30,   # estación "en el camino" si su rumbo a mí difiere menos de esto del avance
    "ANGULO_AEROPUERTOS_GRADOS": 45,
    "GAP_MAX_SEGUIMIENTO_S": 1800,    # si entre dos lecturas de una estación pasa más que esto, la anterior ya no sirve de línea base
    "HISTERESIS_REFERENCIA_KM": 1.0,  # no cambiar de estación de referencia por menos de esto
    "UMBRAL_VIENTO_KMH": 40, "UMBRAL_VIENTO_MAX_KMH": 45,           # viento sostenido / racha => VIGILANCIA (como el escritorio)
    "UMBRAL_VIENTO_PELIGRO_KMH": 45, "UMBRAL_RACHA_PELIGRO_KMH": 55,  # => PELIGRO (como el escritorio)
    "RADIO_LLUVIA_PELIGRO_KM": 10.0,  # lluvia reciente en una estación a menos de esto => PELIGRO (suena la alarma). SOLO MÓVIL (el escritorio usa todo el radio)
    "RADIO_LLUVIA_AVISO_KM": 25.0,    # lluvia reciente entre PELIGRO_KM y esto => VIGILANCIA con aviso (sin alarma). SOLO MÓVIL
    "ETA_PELIGRO_MIN": 30,            # aviso predictivo (CONFIRMADO o de estación) que llega en menos de esto => PELIGRO. SOLO MÓVIL
    "ZONA_HORARIA": "Europe/Madrid",  # hora local de la "Fecha lectura" de AVAMET
}
