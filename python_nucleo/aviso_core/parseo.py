"""Lectura de los datos: convierte el texto de AVAMET y el JSON de aviationweather.gov en diccionarios simples.

La descarga (HTTP) NO está aquí: la hace quien llame (Python con requests, Kotlin con OkHttp). Este módulo recibe el texto.
Diferencia con backend/avamet.py: allí el texto sale de BeautifulSoup (`" ".join(soup.stripped_strings)`); aquí `texto_de_html`
hace lo mismo solo con la biblioteca estándar (en Kotlin: Jsoup o equivalente).
"""
import re
from datetime import datetime
from html.parser import HTMLParser

from .ajustes import AJUSTES

NUM = r"(\d+(?:[.,]\d+)?)"
CLAVES_MINIMAS = ("Temperatura", "Humedad", "Fecha lectura")     # la presión NO es imprescindible

# Ej.: "01-11-2026 05:25 15,1° mín 15,1° màx 17,7° Humitat 90 %" (el primer valor es la temperatura ACTUAL)
RE_FECHA_TEMPS = re.compile(
    r"(\d{2}-\d{2}-\d{4})\s+(\d{2}:\d{2})\s+"
    r"(-?\d+(?:[.,]\d+)?)\s*°\s*mín\s+(-?\d+(?:[.,]\d+)?)\s*°\s*màx\s+(-?\d+(?:[.,]\d+)?)\s*°"
)


# ---- HTML -> texto -------------------------------------------------------------------------------------------------
class _Textos(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.partes, self._ignorar = [], 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self._ignorar += 1

    def handle_endtag(self, tag):
        if tag in ("script", "style") and self._ignorar:
            self._ignorar -= 1

    def handle_data(self, data):
        if not self._ignorar:
            t = data.strip()
            if t:
                self.partes.append(t)
    # los comentarios llegan por handle_comment (no se recogen), igual que stripped_strings


def texto_de_html(html: str) -> str:
    """Equivale a " ".join(BeautifulSoup(html).stripped_strings): textos visibles, sin espacios sobrantes, unidos por un espacio."""
    p = _Textos()
    p.feed(html)
    p.close()
    return " ".join(p.partes)


# ---- Números -------------------------------------------------------------------------------------------------------
def _punto(texto: str) -> str:
    return texto.replace(",", ".")


def a_float(texto):
    """'1.021' -> 1021.0 ; '1021,3' -> 1021.3 ; '12,5' -> 12.5 ; None si no es válido."""
    t = str(texto).strip()
    if "," in t:
        t = t.replace(".", "").replace(",", ".")
    elif re.fullmatch(r"\d{1,2}\.\d{3}", t):
        t = t.replace(".", "")
    try:
        return float(t)
    except ValueError:
        return None


def num(valor) -> float:
    """'12.5 mm' -> 12.5 ; '' o ilegible -> 0.0 (como alertas._num)."""
    try:
        return float(str(valor).split()[0].replace(",", "."))
    except (ValueError, IndexError):
        return 0.0


# ---- Estación AVAMET -----------------------------------------------------------------------------------------------
def parsear_estacion(texto: str, ajustes: dict | None = None) -> dict:
    """Texto visible de la ficha de una estación -> {"Fecha lectura", "Temperatura", ..., "Lluvia hoy", ...}.
    Mismas claves y formatos que backend/avamet.obtener_datos_tiempo_real."""
    aj = ajustes or AJUSTES
    datos = {}
    m = RE_FECHA_TEMPS.search(texto)
    if m:
        datos["Fecha lectura"] = f"{m.group(1)} {m.group(2)}"
        datos["Temperatura"] = _punto(m.group(3)) + " °C"
        datos["Temp mín"] = _punto(m.group(4)) + " °C"
        datos["Temp máx"] = _punto(m.group(5)) + " °C"
    else:
        m = re.search(r"(-?\d+(?:[.,]\d+)?)\s*°\s*mín", texto)       # formato antiguo: solo temperatura, SIN fecha
        if m:
            datos["Temperatura"] = _punto(m.group(1)) + " °C"
    m = re.search(r"Humitat\s+(\d+)\s*%", texto)
    if m: datos["Humedad"] = m.group(1) + " %"
    m = re.search(rf"Vent\s+{NUM}\s*km/h\s+([A-Z]+)", texto)
    if m: datos["Viento"] = f"{_punto(m.group(1))} km/h {m.group(2)}"
    m = re.search(rf"Vent\s+màx\s+{NUM}\s*km/h", texto)
    if m: datos["Viento máximo"] = _punto(m.group(1)) + " km/h"
    m = re.search(rf"Pluja\s+hui\s+{NUM}\s*mm", texto)
    if m: datos["Lluvia hoy"] = _punto(m.group(1)) + " mm"
    m = re.search(rf"Pluja\s+mensual\s+{NUM}\s*mm", texto)
    if m: datos["Lluvia mensual"] = _punto(m.group(1)) + " mm"
    m = re.search(rf"Pluja\s+anual\s+{NUM}\s*mm", texto)
    if m: datos["Lluvia anual"] = _punto(m.group(1)) + " mm"
    m = re.search(r"Pressió\s+al\s+nivell\s+de\s+la\s+mar\s+([\d.,]+)\s*hPa", texto)
    if m:
        p = a_float(m.group(1))
        if p is not None and aj["PRESION_MIN_HPA"] <= p <= aj["PRESION_MAX_HPA"]:
            datos["Presión"] = f"{p:g} hPa"
        else:
            datos["Presión descartada"] = m.group(1) + " hPa"
    return datos


def datos_validos(datos) -> bool:
    return isinstance(datos, dict) and "Error" not in datos and all(k in datos for k in CLAVES_MINIMAS)


def edad_lectura_s(datos: dict, ahora: float, ajustes: dict | None = None):
    """Segundos desde 'Fecha lectura' (hora local de la estación) hasta `ahora` (epoch). None si falta, es ilegible o está en
    el futuro más de 5 min."""
    aj = ajustes or AJUSTES
    texto = (datos or {}).get("Fecha lectura")
    if not texto:
        return None
    try:
        dt = datetime.strptime(texto, "%d-%m-%Y %H:%M")
    except ValueError:
        return None
    try:
        from zoneinfo import ZoneInfo
        dt = dt.replace(tzinfo=ZoneInfo(aj["ZONA_HORARIA"]))
    except Exception:                      # sin base de datos de zonas: hora local del sistema
        pass
    edad = ahora - dt.timestamp()
    if edad < -300:
        return None
    return max(0.0, edad)


# ---- METAR (aviationweather.gov) -----------------------------------------------------------------------------------
COVER_TRAD = {"FEW": "Pocas nubes", "SCT": "Nubes dispersas", "BKN": "Nublado (Fragmentado)", "OVC": "Cielo Cubierto",
              "SKC": "Despejado", "CLR": "Despejado", "NSC": "Sin nubes significativas"}
FENOMENOS = [("RA", "Lluvia"), ("DZ", "Llovizna"), ("SN", "Nieve"), ("SG", "Cinarra"), ("PL", "Hielo granulado"),
             ("GR", "Granizo"), ("GS", "Granizo pequeño"), ("BR", "Neblina"), ("FG", "Niebla"), ("FU", "Humo"),
             ("VA", "Ceniza volcánica"), ("DU", "Polvo"), ("SA", "Arena"), ("HZ", "Calima"), ("TS", "Tormenta"),
             ("SH", "Chubascos"), ("FZ", "Engelante"), ("SQ", "Turbonadas"), ("FC", "Tornado/Tromba")]


def seguro_float(val, defecto=None):
    """'VRB', vacío o ausente -> None (NUNCA 0.0: 0° sería Norte)."""
    try:
        return float(val)
    except (TypeError, ValueError):
        return defecto


def traducir_nubosidad(clouds: list) -> dict:
    niveles = {"baja": [], "media": [], "alta": []}
    for c in clouds or []:
        cover = c.get("cover", "")
        base_ft = c.get("base") or 0           # (backend/aeropuertos.py falla si 'base' es null; aquí se trata como 0)
        if not cover or cover in ("SKC", "CLR", "NSC"):
            continue
        texto = COVER_TRAD.get(cover, cover)
        base_m = base_ft * 0.3048
        if base_ft:
            texto += f" a {base_ft} ft ({base_m:.0f} m)"
        if base_m < 2000: niveles["baja"].append(texto)
        elif base_m < 6000: niveles["media"].append(texto)
        else: niveles["alta"].append(texto)
    return niveles


def traducir_fenomenos(wx) -> str:
    if not wx:
        return "Ninguno"
    partes = [nombre for code, nombre in FENOMENOS if code in wx]
    return ", ".join(partes) if partes else wx


def parsear_metars(lista: list) -> dict:
    """JSON de aviationweather.gov/api/data/metar?format=json -> {icao: datos}. Igual que backend/aeropuertos.obtener_metars."""
    res = {}
    for m in lista or []:
        icao = m.get("icaoId")
        if not icao:
            continue
        res[icao] = {
            "temperatura": seguro_float(m.get("temp")),
            "punto_rocio": seguro_float(m.get("dewp")),
            "viento_nudos": seguro_float(m.get("wspd")),
            "viento_dir": seguro_float(m.get("wdir")),
            "visibilidad": m.get("visib"),
            "nubosidad": traducir_nubosidad(m.get("clouds", [])),
            "fenomenos": traducir_fenomenos(m.get("wxString", "")),
            "actualizado": m.get("reportTime"),
        }
    return res
