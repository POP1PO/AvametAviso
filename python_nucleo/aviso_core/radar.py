"""Radar predictivo: ¿qué lluvia o nubes bajas vienen hacia MI posición y en cuánto llegan?

Misma lógica que el bucle de backend/main.py (estaciones con lluvia reciente + aeropuertos con mal tiempo + red de arrastre),
pero pura: no descarga nada. Las estaciones del camino cuya lectura aún no tienes se devuelven en `faltan`; descárgalas y vuelve
a llamar a analizar() con esas lecturas añadidas.
"""
from math import sqrt

from . import estacion, parseo
from .geo import haversine_km, rumbo_grados, diferencia_angular


def procesar_arrastre(aero: dict, lat0: float, lon0: float, mov: dict, coords: dict, lecturas: dict,
                      seg: dict, ahora: float, aj: dict) -> dict:
    """Estaciones situadas entre el aeropuerto y mi posición ("red de arrastre") y si alguna confirma lluvia en tierra.
    Modifica `seg` (registra el acumulado de las estaciones del camino cuya lectura está en `lecturas`)."""
    vel = max(mov["velocidad_kmh"], 10)
    distancia_total = aero["distancia_km"]
    eta_base = int((distancia_total / vel) * 60)
    d_lat = lat0 - aero["lat"]
    d_lon = lon0 - aero["lon"]
    dist_cuadrada = d_lat ** 2 + d_lon ** 2
    candidatas = []
    if dist_cuadrada > 0:
        for eid, info in coords.items():
            s_lat = info["lat"] - aero["lat"]
            s_lon = info["lon"] - aero["lon"]
            t = (s_lat * d_lat + s_lon * d_lon) / dist_cuadrada
            if 0.1 < t < 0.9:
                proj_lat = aero["lat"] + t * d_lat
                proj_lon = aero["lon"] + t * d_lon
                dist_km = sqrt((info["lat"] - proj_lat) ** 2 + (info["lon"] - proj_lon) ** 2) * 111.0
                if dist_km < 15.0:
                    candidatas.append({"id": eid, "nombre": info["nombre"], "lat": info["lat"], "lon": info["lon"],
                                       "dist_a_mi": haversine_km(lat0, lon0, info["lat"], info["lon"]),
                                       "progreso": t, "dist_a_linea": dist_km})
    candidatas.sort(key=lambda x: x["progreso"])
    camino = []
    for c in candidatas:
        if not camino:
            camino.append(c)
        elif (c["progreso"] - camino[-1]["progreso"]) * distancia_total > 8.0:
            camino.append(c)
    camino = camino[:6]

    confirmado, eta = False, eta_base
    faltan = []
    resultados = []
    for est in camino:
        d = lecturas.get(est["id"])
        if d is None:
            faltan.append(est["id"])
            resultados.append({**est, "lluvia": None, "lluvia_hoy": None})
            continue
        if "Error" in d:
            resultados.append({**est, "lluvia": None, "lluvia_hoy": None})
            continue
        lluvia = parseo.num(d.get("Lluvia hoy", "0"))
        estacion.registrar_lluvia(seg, est["id"], lluvia, ahora, aj["UMBRAL_LLUVIA_MM"], aj["GAP_MAX_SEGUIMIENTO_S"])
        reciente = estacion.lluvia_reciente(seg, est["id"], ahora, aj["VENTANA_LLUVIA_RADAR_S"])
        resultados.append({**est, "lluvia": lluvia if reciente else 0.0, "lluvia_hoy": lluvia})
    for res in resultados:
        if (res["lluvia"] or 0) > 0:
            confirmado = True
            e2 = int((res["dist_a_mi"] / vel) * 60)
            if e2 < eta:
                eta = e2
    return {"confirmado": confirmado, "eta": eta, "estaciones_camino": resultados, "faltan": faltan}


def en_camino(est: dict, lat0: float, lon0: float, mov: dict, aj: dict) -> bool:
    """¿Esta estación está en la dirección de la que vienen las nubes y a menos de ETA_MAX_ESTACIONES_MIN minutos?
    Solo estas estaciones pueden generar un aviso: el resto no hace falta descargarlas para predecir."""
    if mov["velocidad_kmh"] <= aj["VELOCIDAD_MIN_KMH"]:
        return False
    rumbo = rumbo_grados(est["lat"], est["lon"], lat0, lon0)
    if diferencia_angular(rumbo, mov["grados_destino"]) >= aj["ANGULO_ESTACIONES_GRADOS"]:
        return False
    return int((est["distancia_km"] / mov["velocidad_kmh"]) * 60) < aj["ETA_MAX_ESTACIONES_MIN"]


def evaluar_radar(lat0: float, lon0: float, mov: dict, radio: list, lecturas: dict, aeropuertos: list, metars: dict,
                  coords: dict, seg: dict, ahora: float, aj: dict) -> dict:
    """-> {"alertas_predictivas": [str], "radares": [..], "radares_aeropuertos": [..], "faltan": [ids]}.
    `radio`: estaciones de alrededor [{id, nombre, lat, lon, distancia_km}] ; `lecturas`: {id: datos} ya descargadas."""
    alertas_pred, radares, radares_aero, faltan = [], [], [], []
    if mov["velocidad_kmh"] > aj["VELOCIDAD_MIN_KMH"]:
        for est in radio:
            if not en_camino(est, lat0, lon0, mov, aj):
                continue
            d = lecturas.get(est["id"])
            if d is None:                      # está en el camino y aún no la has descargado
                faltan.append(est["id"])
                continue
            if not d or "Error" in d:
                continue
            if estacion.lluvia_reciente(seg, est["id"], ahora, aj["VENTANA_LLUVIA_RADAR_S"]):
                minutos = int((est["distancia_km"] / mov["velocidad_kmh"]) * 60)
                alertas_pred.append(f"Lluvia en {est['nombre']} a {est['distancia_km']} km. Llegada en ~{minutos} min.")
                radares.append({"nombre": est["nombre"], "lat": est["lat"], "lon": est["lon"],
                                "distancia": est["distancia_km"], "eta": minutos})
        for aero in aeropuertos:
            m = metars.get(aero["icao"], {})
            if not m:
                continue
            tiempo_malo = m.get("fenomenos") != "Ninguno"
            nubes_bajas = len(m.get("nubosidad", {}).get("baja", [])) > 0
            if tiempo_malo or nubes_bajas:
                rumbo = rumbo_grados(aero["lat"], aero["lon"], lat0, lon0)
                if diferencia_angular(rumbo, mov["grados_destino"]) < aj["ANGULO_AEROPUERTOS_GRADOS"]:
                    arr = procesar_arrastre(aero, lat0, lon0, mov, coords, lecturas, seg, ahora, aj)
                    for i in arr["faltan"]:
                        if i not in faltan:
                            faltan.append(i)
                    motivo = "Lluvia/Tormenta" if tiempo_malo else "Nubes bajas"
                    texto = f"[Largo Alcance] {motivo} en {aero['nombre']}."
                    texto = (f"[CONFIRMADO EN TIERRA] {motivo} en {aero['nombre']}." if arr["confirmado"]
                             else texto + " (Sin confirmar).")
                    alertas_pred.append(f"{texto} Llegada en ~{arr['eta']} min.")
                    radares_aero.append({"nombre": aero["nombre"], "lat": aero["lat"], "lon": aero["lon"],
                                         "distancia": aero["distancia_km"], "eta": arr["eta"], "motivo": motivo,
                                         "confirmado": arr["confirmado"], "estaciones_camino": arr["estaciones_camino"]})
    return {"alertas_predictivas": alertas_pred, "radares": radares, "radares_aeropuertos": radares_aero, "faltan": faltan}
