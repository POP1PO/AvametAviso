"""Estado de una ESTACIÓN (¿su sensor envía datos recientes?) y seguimiento de la lluvia. Misma lógica que backend/alertas.py."""
from . import parseo
from .ajustes import AJUSTES


# ---- Seguimiento de la lluvia (estado que el llamante conserva entre ciclos) ----------------------------------------
# seg[eid] = {"valor": último acumulado diario (mm), "ts_subida": epoch del último aumento (0.0 = ninguno visto)}
def hay_lluvia_nueva(anterior, actual: float, umbral: float) -> bool:
    return anterior is not None and round(actual - anterior, 2) >= umbral


def registrar_lluvia(seg: dict, eid: str, valor: float, ahora: float, umbral: float, hueco_max_s: float | None = None) -> None:
    """Anota el acumulado de una estación. La primera lectura solo fija la línea base (no cuenta como aumento).
    `hueco_max_s`: si la lectura anterior es más vieja que esto, se descarta como línea base (si no, la lluvia caída durante el
    hueco parecería lluvia "reciente"). En el programa de escritorio nunca ocurre (lee todas las estaciones en cada ciclo);
    en el móvil sí, porque solo se consultan las estaciones que están en el camino de las nubes."""
    previo = seg.get(eid)
    if previo is not None and hueco_max_s is not None and ahora - previo.get("ts_lectura", ahora) > hueco_max_s:
        previo = None
    ts = previo["ts_subida"] if previo else 0.0
    if previo is not None and hay_lluvia_nueva(previo["valor"], valor, umbral):
        ts = ahora
    seg[eid] = {"valor": valor, "ts_subida": ts, "ts_lectura": ahora}


def lluvia_reciente(seg: dict, eid: str, ahora: float, ventana_s: float) -> bool:
    ts = (seg.get(eid) or {}).get("ts_subida", 0.0)
    return ts > 0 and 0 <= ahora - ts <= ventana_s


# ---- Estado de la estación ------------------------------------------------------------------------------------------
def estado_estacion(datos: dict, nombre: str, ahora: float, ajustes: dict | None = None) -> dict:
    """{"estado", "edad_min", "max_min", "nombre", "mensaje"}; estado = ACTIVA | CONGELADA | SIN_FECHA | SIN_LECTURA."""
    aj = ajustes or AJUSTES
    max_min = aj["EDAD_MAX_DATOS_S"] // 60
    base = {"nombre": nombre, "max_min": max_min, "edad_min": None}
    if not parseo.datos_validos(datos):
        return {**base, "estado": "SIN_LECTURA", "mensaje": f"AVAMET no devolvió una lectura completa de {nombre or 'esta estación'}."}
    edad = parseo.edad_lectura_s(datos, ahora, aj)
    if edad is None:
        return {**base, "estado": "SIN_FECHA",
                "mensaje": f"La fecha de la última lectura de {nombre or 'esta estación'} falta o no se puede interpretar, "
                           f"así que no se puede saber si es reciente."}
    edad_min = int(edad // 60)
    if edad > aj["EDAD_MAX_DATOS_S"]:
        return {**base, "estado": "CONGELADA", "edad_min": edad_min,
                "mensaje": f"El sensor de esta estación lleva {edad_min} minutos sin enviar datos a AVAMET (aunque tu red funciona bien)."}
    return {**base, "estado": "ACTIVA", "edad_min": edad_min, "mensaje": ""}


def aviso_sin_datos(est: dict) -> str:
    nombre = est.get("nombre") or "esta estación"
    if est.get("estado") == "CONGELADA":
        return (f"⚠️ SIN DATOS FÍABLES: El sensor de {nombre} no se actualiza desde hace {est['edad_min']} min. "
                f"(Tu conexión a Internet y la web de AVAMET funcionan correctamente, pero este sensor está inactivo).")
    if est.get("estado") == "SIN_FECHA":
        return (f"⚠️ SIN DATOS FÍABLES: AVAMET no indica una fecha válida para la última lectura de {nombre}, "
                f"así que no se puede saber si es reciente. (Tu conexión a Internet y la web de AVAMET responden).")
    return ""


def alternativas_activas(radio: list, lecturas: dict, ahora: float, maximo: int = 6, ajustes: dict | None = None) -> list:
    """Estaciones del radio con lectura reciente AHORA, de la más cercana a la más lejana."""
    res = []
    for est in radio:
        datos = lecturas.get(est["id"])
        if not datos or "Error" in datos:
            continue
        e = estado_estacion(datos, est.get("nombre", ""), ahora, ajustes)
        if e["estado"] == "ACTIVA":
            res.append({"id": est["id"], "nombre": est.get("nombre", ""), "distancia_km": est.get("distancia_km"), "edad_min": e["edad_min"]})
    res.sort(key=lambda x: (x["distancia_km"] if isinstance(x["distancia_km"], (int, float)) else 1e9))
    return res[:maximo]
