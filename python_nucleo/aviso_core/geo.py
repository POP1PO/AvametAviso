"""Geometría sobre la esfera. Mismas fórmulas que backend/main.py (_haversine, _bearing)."""
from math import radians, sin, cos, atan2, sqrt, degrees

R_TIERRA_KM = 6371.0


def haversine_km(lat1, lon1, lat2, lon2) -> float:
    p1, p2 = radians(lat1), radians(lat2)
    dp = radians(lat2 - lat1)
    dl = radians(lon2 - lon1)
    a = sin(dp / 2) ** 2 + cos(p1) * cos(p2) * sin(dl / 2) ** 2
    return 2 * R_TIERRA_KM * atan2(sqrt(a), sqrt(1 - a))


def rumbo_grados(lat1, lon1, lat2, lon2) -> float:
    """Rumbo inicial de (1) hacia (2), en grados 0-360 (0 = norte, 90 = este)."""
    f1, f2 = radians(lat1), radians(lat2)
    dl = radians(lon2 - lon1)
    y = sin(dl) * cos(f2)
    x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
    return (degrees(atan2(y, x)) + 360) % 360


def diferencia_angular(a: float, b: float) -> float:
    """Diferencia mínima entre dos rumbos, 0-180."""
    d = abs(a - b)
    return 360 - d if d > 180 else d


def estacion_mas_cercana(lat: float, lon: float, coords: dict):
    """(id, info, distancia_km) de la estación más cercana, o None. Empate: gana la primera en el orden del diccionario."""
    mejor, mejor_d = None, float("inf")
    for eid, info in coords.items():
        d = haversine_km(lat, lon, info["lat"], info["lon"])
        if d < mejor_d:
            mejor_d, mejor = d, (eid, info, d)
    return mejor


def estaciones_en_radio(lat: float, lon: float, coords: dict, radio_km: float, excluir: str | None = None) -> list:
    """Estaciones a <= radio_km, de la más cercana a la más lejana: [{id, nombre, distancia_km (1 decimal), lat, lon}]."""
    res = []
    for eid, inf in coords.items():
        if eid == excluir:
            continue
        d = haversine_km(lat, lon, inf["lat"], inf["lon"])
        if d <= radio_km:
            res.append({"id": eid, "nombre": inf["nombre"], "distancia_km": round(d, 1), "lat": inf["lat"], "lon": inf["lon"]})
    res.sort(key=lambda x: x["distancia_km"])
    return res


def aeropuertos_en_radio(lat: float, lon: float, aeropuertos: dict, radio_km: float = 120.0) -> list:
    """[{icao, nombre, distancia_km (1 decimal), lat, lon}] de los aeropuertos a <= radio_km, de menor a mayor distancia."""
    res = []
    for icao, info in aeropuertos.items():
        d = haversine_km(lat, lon, info["lat"], info["lon"])
        if d <= radio_km:
            res.append({"icao": icao, "nombre": info["nombre"], "distancia_km": round(d, 1), "lat": info["lat"], "lon": info["lon"]})
    res.sort(key=lambda x: x["distancia_km"])
    return res


def elegir_referencia(lat: float, lon: float, coords: dict, actual_id: str | None = None, histeresis_km: float = 1.0):
    """Estación de referencia para una posición. Si ya hay una (`actual_id`) y sigue a menos de `histeresis_km` más lejos que la
    más cercana, se mantiene (evita que salte de una a otra al caminar entre dos). Devuelve (id, distancia_km) o None."""
    cerca = estacion_mas_cercana(lat, lon, coords)
    if cerca is None:
        return None
    if actual_id and actual_id in coords and actual_id != cerca[0]:
        d_act = haversine_km(lat, lon, coords[actual_id]["lat"], coords[actual_id]["lon"])
        if d_act <= cerca[2] + histeresis_km:
            return actual_id, d_act
    return cerca[0], cerca[2]
