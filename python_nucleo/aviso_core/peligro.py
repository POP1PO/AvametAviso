"""Nivel de peligro para la app móvil. Reglas del escritorio (alertas.evaluar / cond_viento) + una propia del móvil (ETA).

nivel: SEGURO | VIGILANCIA | PELIGRO.   Diferencias con el escritorio (intencionadas): no hay retención del viento ni vigilancia por
humedad; y un aviso predictivo que llega en menos de ETA_PELIGRO_MIN minutos también es PELIGRO (el móvil no tiene sensor propio)."""
import re

from . import parseo
from .ajustes import AJUSTES


def cond_viento(viento: float, racha: float, aj: dict | None = None) -> int:
    aj = aj or AJUSTES
    if viento > aj["UMBRAL_VIENTO_PELIGRO_KMH"] or racha > aj["UMBRAL_RACHA_PELIGRO_KMH"]:
        return 3
    if viento >= aj["UMBRAL_VIENTO_KMH"] or racha >= aj["UMBRAL_VIENTO_MAX_KMH"]:
        return 2
    return 1


def evaluar_peligro(res: dict, aj: dict | None = None) -> dict:
    """res = salida de motor.analizar. -> {"nivel": str, "motivos": [str]} (lista vacía si todo está tranquilo)."""
    aj = aj or AJUSTES
    motivos, nivel = [], "SEGURO"
    ref = res.get("referencia")
    if ref and ref["estado"]["estado"] == "ACTIVA" and ref.get("datos"):
        viento = parseo.num(ref["datos"].get("Viento", "0"))
        racha = parseo.num(ref["datos"].get("Viento máximo", "0"))
        cv = cond_viento(viento, racha, aj)
        if cv == 3:
            motivos.append(f"Viento muy fuerte: {viento:.0f} km/h (racha {racha:.0f})")
            nivel = "PELIGRO"
        elif cv == 2:
            motivos.append(f"Viento fuerte: {viento:.0f} km/h (máx {racha:.0f})")
            nivel = "VIGILANCIA"
    for r in res.get("lluvia_ahora", []):
        if r["es_referencia"]:
            motivos.append(f"Lluvia en tu estación de referencia ({r['nombre']})")
            nivel = "PELIGRO"
        elif r["distancia_km"] <= aj["RADIO_LLUVIA_PELIGRO_KM"]:
            motivos.append(f"Lluvia a {r['distancia_km']:.1f} km en {r['nombre']}")
            nivel = "PELIGRO"
    for a in res.get("alertas_predictivas", []):
        m = re.search(r"Llegada en ~(\d+) min", a)
        if m and int(m.group(1)) < aj["ETA_PELIGRO_MIN"]:
            motivos.append(a)
            nivel = "PELIGRO"
    return {"nivel": nivel, "motivos": motivos}
