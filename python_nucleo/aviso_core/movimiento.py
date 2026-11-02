"""Movimiento de las nubes a partir del viento (estación de referencia + METAR de aeropuertos). Media SIMPLE de vectores.
Idéntico a backend/movimiento.py salvo que no imprime nada. Un dato de dirección que no se entiende (VRB, vacío...) NO es 0°: se descarta."""
import re
from math import radians, sin, cos, atan2, sqrt, degrees

ROSA = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO"]
_DIR_A_GRADOS = {**{n: i * 22.5 for i, n in enumerate(ROSA)},
                 "W": 270.0, "WNW": 292.5, "NW": 315.0, "NNW": 337.5, "WSW": 247.5, "SW": 225.0, "SSW": 202.5}
_RE_VIENTO = re.compile(r"^\s*(\d+(?:[.,]\d+)?)\s*km/h\s*([A-Za-zÑñ]+)?\s*$")


def direccion_a_grados(texto):
    if texto is None:
        return None
    return _DIR_A_GRADOS.get(str(texto).strip().upper())


def _punto_referencia(datos: dict):
    texto = datos.get("Viento", "")
    if not isinstance(texto, str) or "km/h" not in texto:
        return None
    m = _RE_VIENTO.match(texto)
    if not m:
        return None
    grados = direccion_a_grados(m.group(2))
    if grados is None:
        return None
    return {"dist": 0.1, "vel": float(m.group(1).replace(",", ".")), "dir": grados}


def estimar_movimiento(datos_ref: dict, metars: dict, aeropuertos_cerca: list) -> dict:
    """-> {"direccion_destino": 'ONO', "grados_destino": 292, "velocidad_kmh": 12.3, "categoria": 'Moderado'}.
    `grados_destino` = hacia DÓNDE van las nubes (no de dónde vienen)."""
    puntos = []
    p = _punto_referencia(datos_ref or {})
    if p:
        puntos.append(p)
    for aero in aeropuertos_cerca:
        m = metars.get(aero["icao"])
        if m and m.get("viento_nudos") is not None and m.get("viento_dir") is not None:
            puntos.append({"dist": max(aero["distancia_km"], 0.1), "vel": m["viento_nudos"] * 1.852, "dir": m["viento_dir"]})
    if not puntos:
        return {"direccion_destino": "Desconocida", "grados_destino": 0, "velocidad_kmh": 0, "categoria": "No estimable"}
    sum_u = sum_v = 0.0
    for p in puntos:
        rad = radians(p["dir"])
        sum_u += -p["vel"] * sin(rad)
        sum_v += -p["vel"] * cos(rad)
    n = float(len(puntos))
    u, v = sum_u / n, sum_v / n
    dir_origen = (degrees(atan2(-u, -v)) + 360) % 360
    vel = sqrt(u ** 2 + v ** 2)
    dir_destino = (dir_origen + 180) % 360
    idx = round(dir_destino / 22.5) % 16
    cat = "Lento" if vel < 10 else ("Moderado" if vel < 30 else "Rápido")
    return {"direccion_destino": ROSA[idx], "grados_destino": round(dir_destino), "velocidad_kmh": round(vel, 1), "categoria": cat}
