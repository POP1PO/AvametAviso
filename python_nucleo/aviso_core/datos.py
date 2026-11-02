"""Carga de los datos fijos (coordenadas de estaciones y aeropuertos)."""
import json
from pathlib import Path

AQUI = Path(__file__).resolve().parent.parent / "datos"


def municipio_de(nombre: str) -> str:
    """'Almassora (Nord)' -> 'Almassora'."""
    return str(nombre).split("(")[0].strip()


def completar_municipios(coords: dict) -> dict:
    for info in coords.values():
        if isinstance(info, dict) and not info.get("municipio"):
            info["municipio"] = municipio_de(info.get("nombre", ""))
    return coords


def cargar_coordenadas(ruta=None) -> dict:
    with open(ruta or AQUI / "coordenadas.json", "r", encoding="utf-8") as f:
        return completar_municipios(json.load(f))


def cargar_aeropuertos(ruta=None) -> dict:
    with open(ruta or AQUI / "aeropuertos.json", "r", encoding="utf-8") as f:
        return json.load(f)
