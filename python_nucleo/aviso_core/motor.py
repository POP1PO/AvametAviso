"""Motor: une todo. Dos pasos, sin red por medio (la descarga la hace el llamante):

    plan = planificar(lat, lon, coords, aeropuertos, actual_id=None)      # qué estaciones y METAR hay que descargar
    ... el llamante descarga plan["descargar_estaciones"] y plan["descargar_icaos"] ...
    res = analizar(plan, lecturas, metars, seguimiento, ahora, coords)    # resultado completo
    si res["faltan"] no está vacío: descarga esas estaciones, añádelas a `lecturas` y vuelve a llamar a analizar()
    con el MISMO `seguimiento` que devolvió la llamada anterior (la repetición no duplica nada).

`lecturas`: {id_estacion: dict de parseo.parsear_estacion, o {"Error": "..."} si falló la descarga}
`metars`:   {icao: dict de parseo.parsear_metars}
`seguimiento`: estado de la lluvia entre ciclos ({} la primera vez). analizar() NO lo modifica: devuelve una copia nueva.
"""
import copy

from . import estacion, movimiento, parseo, radar
from .ajustes import AJUSTES
from .geo import aeropuertos_en_radio, elegir_referencia, estaciones_en_radio


def planificar(lat: float, lon: float, coords: dict, aeropuertos: dict, actual_id: str | None = None,
               ajustes: dict | None = None) -> dict:
    aj = ajustes or AJUSTES
    ref = elegir_referencia(lat, lon, coords, actual_id, aj["HISTERESIS_REFERENCIA_KM"])
    if ref is None:
        return {"lat": lat, "lon": lon, "referencia": None, "radio": [], "aeropuertos": [],
                "descargar_estaciones": [], "descargar_icaos": []}
    rid, d_ref = ref
    info = coords[rid]
    radio = estaciones_en_radio(lat, lon, coords, aj["RADIO_KM"], excluir=rid)
    aero = aeropuertos_en_radio(lat, lon, aeropuertos, aj["RADIO_AEROPUERTOS_KM"])
    return {
        "lat": lat, "lon": lon,
        "referencia": {"id": rid, "nombre": info.get("municipio") or info.get("nombre", rid), "nombre_estacion": info.get("nombre", rid),
                       "lat": info["lat"], "lon": info["lon"], "distancia_km": round(d_ref, 1)},
        "radio": radio, "aeropuertos": aero,
        "descargar_estaciones": [rid] + [e["id"] for e in radio],
        "descargar_icaos": [a["icao"] for a in aero],
    }


def _resumen_estacion(est: dict, datos: dict | None, seg: dict, ahora: float, aj: dict) -> dict:
    out = {"id": est["id"], "nombre": est["nombre"], "lat": est["lat"], "lon": est["lon"], "distancia_km": est["distancia_km"]}
    if not datos or "Error" in datos:
        return {**out, "estado": "SIN_LECTURA", "edad_min": None, "lluvia_reciente": False, "lluvia_hoy": None,
                "viento": None, "temperatura": None}
    e = estacion.estado_estacion(datos, est["nombre"], ahora, aj)
    return {**out, "estado": e["estado"], "edad_min": e["edad_min"],
            "lluvia_reciente": estacion.lluvia_reciente(seg, est["id"], ahora, aj["VENTANA_LLUVIA_RADAR_S"]),
            "lluvia_hoy": parseo.num(datos["Lluvia hoy"]) if "Lluvia hoy" in datos else None,
            "viento": datos.get("Viento"), "temperatura": datos.get("Temperatura")}


def analizar(plan: dict, lecturas: dict, metars: dict, seguimiento: dict, ahora: float, coords: dict,
             ajustes: dict | None = None) -> dict:
    aj = ajustes or AJUSTES
    seg = copy.deepcopy(seguimiento or {})
    lat0, lon0 = plan["lat"], plan["lon"]
    ref = plan.get("referencia")
    if ref is None:
        return {"posicion": {"lat": lat0, "lon": lon0}, "referencia": None, "aviso_estacion": "", "alternativas": [],
                "radio": [], "aeropuertos": [], "movimiento": movimiento.estimar_movimiento({}, {}, []), "lluvia_ahora": [],
                "alertas_predictivas": [], "radares": [], "radares_aeropuertos": [], "faltan": [], "seguimiento": seg}
    radio = plan["radio"]
    datos_ref = lecturas.get(ref["id"]) or {"Error": "sin lectura"}

    # 1) seguimiento de la lluvia: referencia + estaciones del radio con lectura válida
    for e in [{"id": ref["id"]}] + radio:
        d = lecturas.get(e["id"])
        if d and "Error" not in d:
            estacion.registrar_lluvia(seg, e["id"], parseo.num(d.get("Lluvia hoy", "0")), ahora, aj["UMBRAL_LLUVIA_MM"], aj["GAP_MAX_SEGUIMIENTO_S"])

    # 2) estado de la estación de referencia (y alternativas si su sensor está parado)
    est_ref = estacion.estado_estacion(datos_ref, ref["nombre"], ahora, aj)
    inactiva = est_ref["estado"] in ("CONGELADA", "SIN_FECHA")
    alternativas = estacion.alternativas_activas(radio, lecturas, ahora, 6, aj) if est_ref["estado"] != "ACTIVA" else []

    # 3) movimiento de las nubes: el viento de la referencia solo cuenta si su lectura es reciente
    viento_ref = datos_ref if est_ref["estado"] == "ACTIVA" else {}
    mov = movimiento.estimar_movimiento(viento_ref, metars, plan["aeropuertos"])

    # 4) radar predictivo
    rad = radar.evaluar_radar(lat0, lon0, mov, radio, lecturas, plan["aeropuertos"], metars, coords, seg, ahora, aj)

    # 5) resúmenes para pantalla
    resumen_radio = [_resumen_estacion(e, lecturas.get(e["id"]), seg, ahora, aj) for e in radio]
    ref_res = {"id": ref["id"], "nombre": ref["nombre"], "lat": ref["lat"], "lon": ref["lon"], "distancia_km": ref["distancia_km"],
               "estado": est_ref, "datos": datos_ref if "Error" not in datos_ref else None}
    lluvia_ahora = []
    if est_ref["estado"] == "ACTIVA" and estacion.lluvia_reciente(seg, ref["id"], ahora, aj["VENTANA_LLUVIA_RADAR_S"]):
        lluvia_ahora.append({"id": ref["id"], "nombre": ref["nombre"], "distancia_km": ref["distancia_km"], "es_referencia": True})
    for r in resumen_radio:
        if r["estado"] == "ACTIVA" and r["lluvia_reciente"]:
            lluvia_ahora.append({"id": r["id"], "nombre": r["nombre"], "distancia_km": r["distancia_km"], "es_referencia": False})
    return {
        "posicion": {"lat": lat0, "lon": lon0},
        "referencia": ref_res,
        "aviso_estacion": estacion.aviso_sin_datos(est_ref) if inactiva else "",
        "alternativas": alternativas,
        "radio": resumen_radio,
        "aeropuertos": [{**a, "metar": metars.get(a["icao"])} for a in plan["aeropuertos"]],
        "movimiento": mov,
        "lluvia_ahora": lluvia_ahora,
        "alertas_predictivas": rad["alertas_predictivas"], "radares": rad["radares"], "radares_aeropuertos": rad["radares_aeropuertos"],
        "faltan": rad["faltan"],
        "seguimiento": seg,
    }
