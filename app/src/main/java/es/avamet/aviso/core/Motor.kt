package es.avamet.aviso.core

/**
 * Dos pasos, sin red por medio:
 *   plan = planificar(lat, lon, coords, aeropuertos, actualId)
 *   ... descargar la estación de referencia y los METAR de plan["descargar_icaos"] ...
 *   res = analizar(plan, lecturas, metars, seguimiento, ahora, coords)
 *   si res["faltan"] no está vacío: descargar esas estaciones, añadirlas a `lecturas` y repetir analizar() con el seguimiento devuelto.
 */
fun planificar(lat: Double, lon: Double, coords: Map<String, Map<String, Any?>>, aeropuertos: Map<String, Map<String, Any?>>,
               actualId: String? = null, aj: Ajustes = Ajustes(), fijaId: String? = null): Dict {
    val ref = elegirReferencia(lat, lon, coords, actualId, aj.histeresisReferenciaKm, fijaId)
        ?: return dict("lat" to lat, "lon" to lon, "referencia" to null, "radio" to ArrayList<Any?>(), "aeropuertos" to ArrayList<Any?>(),
            "descargar_estaciones" to ArrayList<Any?>(), "descargar_icaos" to ArrayList<Any?>())
    val rid = ref.first
    val info = coords[rid]!!
    val radio = estacionesEnRadio(lat, lon, coords, aj.radioKm, rid)
    val aero = aeropuertosEnRadio(lat, lon, aeropuertos, aj.radioAeropuertosKm)
    val mun = info["municipio"]
    val nombre = if (mun is String && mun.isNotEmpty()) mun else (info["nombre"] ?: rid)
    val descargar = ArrayList<Any?>()
    descargar.add(rid)
    radio.forEach { descargar.add(it["id"]) }
    return dict(
        "lat" to lat, "lon" to lon,
        "referencia" to dict("id" to rid, "nombre" to nombre, "nombre_estacion" to (info["nombre"] ?: rid), "lat" to info["lat"], "lon" to info["lon"],
            "distancia_km" to pyRound(ref.second, 1)),
        "radio" to radio, "aeropuertos" to aero,
        "descargar_estaciones" to descargar, "descargar_icaos" to aero.map { it["icao"] },
    )
}

/** Estaciones del radio que hay que descargar en cada ciclo (además de la referencia): TODAS, las más cercanas primero.
 *  `analizar` solo ve la lluvia de las estaciones de las que hay lectura: si aquí faltara alguna, la lluvia a 25 km (aviso)
 *  o a 10 km (alarma) pasaría inadvertida. `yaDescargadas` = ids de las que ya se tienen en este ciclo. */
fun estacionesDelRadio(plan: Map<String, Any?>, yaDescargadas: Set<String> = emptySet()): List<String> =
    ((plan["radio"] as? List<*>) ?: emptyList<Any?>()).map { it.d() }
        .sortedBy { it["distancia_km"].dbl() }
        .map { it["id"] as String }
        .filter { it !in yaDescargadas }

private fun resumenEstacion(est: Map<String, Any?>, datos: Map<String, Any?>?, seg: Map<String, Any?>, ahora: Double, aj: Ajustes): Dict {
    val out = dict("id" to est["id"], "nombre" to est["nombre"], "lat" to est["lat"], "lon" to est["lon"], "distancia_km" to est["distancia_km"])
    if (datos == null || datos.isEmpty() || datos.containsKey("Error")) {
        out["estado"] = "SIN_LECTURA"; out["edad_min"] = null; out["lluvia_reciente"] = false; out["lluvia_hoy"] = null
        out["viento"] = null; out["temperatura"] = null
        return out
    }
    val e = estadoEstacion(datos, est["nombre"] as String, ahora, aj)
    out["estado"] = e["estado"]; out["edad_min"] = e["edad_min"]
    out["lluvia_reciente"] = lluviaReciente(seg, est["id"] as String, ahora, aj.ventanaLluviaRadarS)
    out["lluvia_hoy"] = if (datos.containsKey("Lluvia hoy")) num(datos["Lluvia hoy"]) else null
    out["viento"] = datos["Viento"]; out["temperatura"] = datos["Temperatura"]
    return out
}

@Suppress("UNCHECKED_CAST")
fun analizar(plan: Map<String, Any?>, lecturas: Lecturas, metars: Map<String, Any?>, seguimiento: Map<String, Any?>?, ahora: Double,
             coords: Map<String, Map<String, Any?>>, aj: Ajustes = Ajustes()): Dict {
    val seg = (copiaProfunda(seguimiento ?: emptyMap<String, Any?>()) as Map<String, Any?>).toMutableMap()
    val lat0 = plan["lat"].dbl()
    val lon0 = plan["lon"].dbl()
    val ref = plan["referencia"] as? Map<String, Any?>
    if (ref == null) {
        return dict("posicion" to dict("lat" to lat0, "lon" to lon0), "referencia" to null, "aviso_estacion" to "", "alternativas" to ArrayList<Any?>(),
            "radio" to ArrayList<Any?>(), "aeropuertos" to ArrayList<Any?>(), "movimiento" to estimarMovimiento(emptyMap(), emptyMap(), emptyList()),
            "lluvia_ahora" to ArrayList<Any?>(), "alertas_predictivas" to ArrayList<Any?>(), "radares" to ArrayList<Any?>(),
            "radares_aeropuertos" to ArrayList<Any?>(), "faltan" to ArrayList<Any?>(), "seguimiento" to seg)
    }
    val radio = plan["radio"].l().map { it.d() }
    val aeropuertos = plan["aeropuertos"].l().map { it.d() }
    val refId = ref["id"] as String
    val refLect = lecturas[refId]
    val datosRef: Map<String, Any?> = if (refLect == null || refLect.isEmpty()) dict("Error" to "sin lectura") else refLect

    // 1) seguimiento de la lluvia: referencia + estaciones del radio con lectura válida
    val ids = ArrayList<String>()
    ids.add(refId)
    radio.forEach { ids.add(it["id"] as String) }
    for (id in ids) {
        val d = lecturas[id]
        if (d != null && d.isNotEmpty() && !d.containsKey("Error"))
            registrarLluvia(seg, id, num(d["Lluvia hoy"] ?: "0"), ahora, aj.umbralLluviaMm, aj.gapMaxSeguimientoS)
    }

    // 2) estado de la estación de referencia (y alternativas si su sensor está parado)
    val estRef = estadoEstacion(datosRef, ref["nombre"] as String, ahora, aj)
    val inactiva = estRef["estado"] == "CONGELADA" || estRef["estado"] == "SIN_FECHA"
    val alternativas = if (estRef["estado"] != "ACTIVA") alternativasActivas(radio, lecturas, ahora, 6, aj) else ArrayList<Dict>()

    // 3) movimiento de las nubes: el viento de la referencia solo cuenta si su lectura es reciente
    val vientoRef: Map<String, Any?> = if (estRef["estado"] == "ACTIVA") datosRef else emptyMap()
    val mov = estimarMovimiento(vientoRef, metars, aeropuertos)

    // 4) radar predictivo
    val rad = evaluarRadar(lat0, lon0, mov, radio, lecturas, aeropuertos, metars, coords, seg, ahora, aj)

    // 5) resúmenes para pantalla
    val resumenRadio = radio.map { resumenEstacion(it, lecturas[it["id"] as String], seg, ahora, aj) }
    val refRes = dict("id" to ref["id"], "nombre" to ref["nombre"], "lat" to ref["lat"], "lon" to ref["lon"], "distancia_km" to ref["distancia_km"],
        "estado" to estRef, "datos" to (if (datosRef.containsKey("Error")) null else datosRef))
    val lluviaAhora = ArrayList<Any?>()
    if (estRef["estado"] == "ACTIVA" && lluviaReciente(seg, refId, ahora, aj.ventanaLluviaRadarS))
        lluviaAhora.add(dict("id" to refId, "nombre" to ref["nombre"], "distancia_km" to ref["distancia_km"], "es_referencia" to true))
    for (r in resumenRadio) {
        if (r["estado"] == "ACTIVA" && r["lluvia_reciente"] == true)
            lluviaAhora.add(dict("id" to r["id"], "nombre" to r["nombre"], "distancia_km" to r["distancia_km"], "es_referencia" to false))
    }
    return dict(
        "posicion" to dict("lat" to lat0, "lon" to lon0),
        "referencia" to refRes,
        "aviso_estacion" to (if (inactiva) avisoSinDatos(estRef) else ""),
        "alternativas" to alternativas,
        "radio" to resumenRadio,
        "aeropuertos" to aeropuertos.map { a -> val x = LinkedHashMap<String, Any?>(a); x["metar"] = metars[a["icao"] as String]; x as Dict },
        "movimiento" to mov,
        "lluvia_ahora" to lluviaAhora,
        "alertas_predictivas" to rad["alertas_predictivas"], "radares" to rad["radares"], "radares_aeropuertos" to rad["radares_aeropuertos"],
        "faltan" to rad["faltan"],
        "seguimiento" to seg,
    )
}
