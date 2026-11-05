package es.avamet.aviso.core

private val RE_LLUVIA = Regex("Lluvia|Llovizna|Chubascos|Tormenta|Granizo|Nieve|Cinarra|Hielo")

/** Radio (km) hasta el que la lluvia en un aeropuerto sube el estado a VIGILANCIA. */
const val RADIO_AVISO_AEROPUERTO_KM = 60.0

/**
 * Solo móvil: si un METAR reciente de un aeropuerto a ≤ [radioKm] km informa lluvia (o chubascos, tormenta...), el estado no puede quedarse en SEGURO:
 * pasa a VIGILANCIA (sin alarma). Nunca baja un nivel y no toca PELIGRO. Motivo: el pluviómetro de una estación tarda en registrar el inicio de la lluvia
 * y las estaciones publican con unos minutos de retraso; el aeropuerto avisa antes.
 */
@Suppress("UNCHECKED_CAST")
fun ampliarConAeropuertos(res: Map<String, Any?>, peligro: Dict, radioKm: Double = RADIO_AVISO_AEROPUERTO_KM): Dict {
    val motivos = ArrayList<Any?>((peligro["motivos"] as? List<Any?>) ?: emptyList())
    var nivel = peligro["nivel"] as? String ?: "SEGURO"
    for (a0 in (res["aeropuertos"] as? List<Any?>) ?: emptyList()) {
        val a = a0 as? Map<String, Any?> ?: continue
        val metar = a["metar"] as? Map<String, Any?> ?: continue
        val fen = metar["fenomenos"] as? String ?: continue
        val dist = (a["distancia_km"] as? Number)?.toDouble() ?: continue
        if (dist <= radioKm && RE_LLUVIA.containsMatchIn(fen)) {
            motivos.add("${fen} en el aeropuerto ${a["nombre"] ?: a["icao"]} a ${fmt(dist, 0)} km (aviso)")
            if (nivel == "SEGURO") nivel = "VIGILANCIA"
        }
    }
    return dict("nivel" to nivel, "motivos" to motivos)
}
