"""PARIDAD: el núcleo debe dar los MISMOS resultados que el backend de escritorio (AVAMET Monitor, carpeta lote3).

Se comparan, con muchos casos aleatorios (semilla fija), las funciones del núcleo contra las del backend real:
lectura de estaciones, de METAR, geometría, movimiento de las nubes, estado de la estación y el radar predictivo completo
(el bucle de backend/main.py + la red de arrastre). Si el backend no está junto a esta carpeta, las pruebas se OMITEN
(se avisa en pantalla); se puede indicar su ubicación con la variable AVISO_BACKEND.

Ejecutar: python tests/test_paridad.py
"""
import copy, os, random, sys, types
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
BACKEND = Path(os.getenv("AVISO_BACKEND") or RAIZ.parent / "lote3")
if not (BACKEND / "backend" / "main.py").is_file():
    print(f"SKIP test_paridad: no se encuentra el backend en {BACKEND} (indica AVISO_BACKEND=ruta)")
    sys.exit(0)
sys.path.insert(0, str(BACKEND))

# main.py importa FastAPI; aquí solo se necesitan sus funciones puras, así que se sustituye por un doble mínimo.
if "fastapi" not in sys.modules:
    class _HTTPException(Exception):
        def __init__(self, status_code, detail=None, headers=None): self.status_code, self.detail = status_code, detail
    class _App:
        def __init__(self, *a, **k): pass
        def _d(self, *a, **k): return lambda f: f
        get = put = post = mount = _d
        def add_middleware(self, *a, **k): pass
    fa = types.ModuleType("fastapi")
    fa.FastAPI = _App; fa.Request = object; fa.Body = lambda *a, **k: None; fa.HTTPException = _HTTPException; fa.BackgroundTasks = object
    fr = types.ModuleType("fastapi.responses"); fr.FileResponse = fr.StreamingResponse = fr.Response = object
    sys.modules["fastapi"] = fa; sys.modules["fastapi.responses"] = fr

from backend import avamet, aeropuertos as b_aero, alertas as b_alertas, movimiento as b_mov, main as b_main   # noqa: E402
from aviso_core import geo, parseo, movimiento, estacion, motor, cargar_coordenadas, cargar_aeropuertos       # noqa: E402

AHORA = 1_800_000_030.0
COORDS = cargar_coordenadas()
AEROS = cargar_aeropuertos()
ROSA = movimiento.ROSA


def fecha(edad_s, ahora=AHORA):
    return datetime.fromtimestamp(ahora - edad_s, ZoneInfo("Europe/Madrid")).strftime("%d-%m-%Y %H:%M")


# ---- 1) lectura de una estación -----------------------------------------------------------------------------------
class _Resp:
    def __init__(self, text=None, js=None): self.text, self._js = text, js
    def raise_for_status(self): pass
    def json(self): return self._js


def _texto_aleatorio(rnd):
    num = lambda: rnd.choice([f"{rnd.randint(0, 40)},{rnd.randint(0, 9)}", f"{rnd.randint(0, 40)}.{rnd.randint(0, 9)}", str(rnd.randint(0, 40))])
    partes = ["Estación de prueba"]
    if rnd.random() < 0.9:
        partes.append(f"{rnd.randint(1, 28):02d}-{rnd.randint(1, 12):02d}-2026 {rnd.randint(0, 23):02d}:{rnd.randint(0, 59):02d} "
                      f"-{num()}° mín {num()}° màx {num()}°")
    elif rnd.random() < 0.5:
        partes.append(f"{num()}° mín")
    if rnd.random() < 0.9: partes.append(f"Humitat {rnd.randint(0, 100)} %")
    if rnd.random() < 0.8: partes.append(f"Pressió al nivell de la mar {rnd.choice(['1.021,3', '1021,3', '1.021', '1013', '5000', '300', 'x1.2'])} hPa")
    if rnd.random() < 0.9: partes.append(f"Vent {num()} km/h {rnd.choice(ROSA + ['VRB', 'W', 'XYZ'])}")
    if rnd.random() < 0.8: partes.append(f"Vent màx {num()} km/h")
    if rnd.random() < 0.9: partes.append(f"Pluja hui {num()} mm")
    if rnd.random() < 0.6: partes.append(f"Pluja mensual {num()} mm")
    if rnd.random() < 0.6: partes.append(f"Pluja anual {num()} mm")
    return " ".join(partes)


def test_paridad_lectura_estacion():
    rnd = random.Random(1)
    original = avamet.requests.get
    try:
        for _ in range(400):
            texto = _texto_aleatorio(rnd)
            html = f"<html><head><script>var x=1;</script></head><body><div><p>{texto}</p><!-- c --><span> </span></div></body></html>"
            avamet.requests.get = lambda *a, **k: _Resp(text=html)
            esperado = avamet.obtener_datos_tiempo_real("x")
            obtenido = parseo.parsear_estacion(parseo.texto_de_html(html))
            assert obtenido == esperado, (texto, esperado, obtenido)
    finally:
        avamet.requests.get = original


# ---- 2) METAR --------------------------------------------------------------------------------------------------------
def test_paridad_metar():
    rnd = random.Random(2)
    original = b_aero.requests.get
    try:
        for i in range(300):
            lista = []
            for icao in rnd.sample(list(AEROS), rnd.randint(0, 6)):
                lista.append({"icaoId": icao,
                              "temp": rnd.choice([None, rnd.randint(-5, 40), "M03"]), "dewp": rnd.choice([None, rnd.randint(-5, 30)]),
                              "wspd": rnd.choice([None, rnd.randint(0, 40)]), "wdir": rnd.choice(["VRB", None, rnd.randint(0, 360)]),
                              "visib": rnd.choice(["6+", 9999, None]), "wxString": rnd.choice(["", None, "RA", "-TSRA BR", "FG", "VCSH", "XX"]),
                              "clouds": [{"cover": rnd.choice(["FEW", "SCT", "BKN", "OVC", "CLR", "NSC", "SKC", "CAVOK"]),
                                          "base": rnd.choice([0, 500, 1500, 3000, 9000, 25000])} for _ in range(rnd.randint(0, 3))],
                              "reportTime": "2026-11-02 04:00:00"})
            b_aero._CACHE_METAR.clear()
            b_aero.requests.get = lambda *a, **k: _Resp(js=lista)
            esperado = b_aero.obtener_metars(["LEVC"], ahora=AHORA)
            assert parseo.parsear_metars(lista) == esperado, (i, lista)
    finally:
        b_aero.requests.get = original


# ---- 3) geometría ------------------------------------------------------------------------------------------------------
def test_paridad_geometria():
    rnd = random.Random(3)
    for _ in range(2000):
        a = (rnd.uniform(-60, 60), rnd.uniform(-180, 180), rnd.uniform(-60, 60), rnd.uniform(-180, 180))
        assert geo.haversine_km(*a) == b_main._haversine(*a) == b_aero._haversine(*a)
        assert geo.rumbo_grados(*a) == b_main._bearing(*a)
    for _ in range(200):
        lat, lon = rnd.uniform(36, 43), rnd.uniform(-8, 3)
        esp = avamet.encontrar_mas_cercana(lat, lon, COORDS)
        got = geo.estacion_mas_cercana(lat, lon, COORDS)
        assert esp[0] == got[0] and esp[2] == got[2]
        assert geo.aeropuertos_en_radio(lat, lon, AEROS, 120.0) == b_aero.buscar_aeropuertos_cercanos(lat, lon, 120.0)


# ---- 4) movimiento de las nubes ------------------------------------------------------------------------------------------
def _metars_aleatorios(rnd, aeros):
    res = {}
    for a in aeros:
        if rnd.random() < 0.85:
            res[a["icao"]] = {
                "temperatura": rnd.choice([None, 15.0]), "punto_rocio": None,
                "viento_nudos": rnd.choice([None, float(rnd.randint(0, 35))]), "viento_dir": rnd.choice([None, float(rnd.randint(0, 359))]),
                "visibilidad": "9999", "actualizado": "t",
                "nubosidad": {"baja": rnd.choice([[], ["Nublado a 1500 ft"]]), "media": [], "alta": []},
                "fenomenos": rnd.choice(["Ninguno", "Ninguno", "Lluvia", "Tormenta, Chubascos"])}
    return res


def test_paridad_movimiento():
    rnd = random.Random(4)
    for _ in range(1500):
        aeros = [{"icao": k, "distancia_km": round(rnd.uniform(0, 120), 1)} for k in rnd.sample(list(AEROS), rnd.randint(0, 4))]
        ref = {"Viento": rnd.choice([f"{rnd.randint(0, 50)}.{rnd.randint(0, 9)} km/h {rnd.choice(ROSA)}", "5 km/h VRB", "calma", "", "x km/h N"])}
        mt = _metars_aleatorios(rnd, aeros)
        assert movimiento.estimar_movimiento(ref, mt, aeros) == b_mov.estimar_movimiento_nubes(ref, mt, aeros), (ref, mt)


# ---- 5) estado de la estación ------------------------------------------------------------------------------------------------
def _lectura(edad_s, lluvia="0.0", viento="5 km/h N", ahora=AHORA, extra=None):
    d = {"Fecha lectura": fecha(edad_s, ahora), "Temperatura": "15.1 °C", "Humedad": "70 %", "Viento": viento,
         "Viento máximo": "8 km/h", "Lluvia hoy": f"{lluvia} mm"}
    d.update(extra or {})
    return d


def test_paridad_estado_estacion():
    rnd = random.Random(5)
    for _ in range(600):
        edad = rnd.choice([0, 100, 300, 540, 700, 900, 3600, -3600, -100])
        d = _lectura(edad)
        if rnd.random() < 0.15: d["Fecha lectura"] = rnd.choice(["basura", ""])
        if rnd.random() < 0.1: d = {"Error": "x"}
        esp = b_alertas.estado_estacion(d, "Nombre", AHORA)
        assert estacion.estado_estacion(d, "Nombre", AHORA) == esp
        assert estacion.aviso_sin_datos(esp) == b_alertas.aviso_sin_datos_estacion(esp)
    radio = [{"id": f"e{i}", "nombre": f"E{i}", "distancia_km": round(rnd.uniform(1, 25), 1),
              "datos": rnd.choice([_lectura(rnd.choice([60, 300, 700, 3000])), {"Error": "x"}])} for i in range(30)]
    esp = b_alertas.estaciones_alternativas(radio, AHORA)
    got = estacion.alternativas_activas([{k: v for k, v in e.items() if k != "datos"} for e in radio], {e["id"]: e["datos"] for e in radio}, AHORA)
    assert got == esp


# ---- 6) radar predictivo completo (bucle de main.py + red de arrastre) ------------------------------------------------------
def _referencia_escritorio(plan, lecturas, metars, seg0, ahora, datos_ref):
    """Lo que hace backend/main.py en un ciclo, con sus propias funciones (_procesar_red_arrastre incluida). Solo el 'pegamento'
    del bucle está copiado de main._bucle_principal; todo lo demás se llama del backend real."""
    SEG = b_main._SEGUIMIENTO_LLUVIA
    SEG.clear(); SEG.update(copy.deepcopy(seg0))
    original_get = avamet.obtener_datos_tiempo_real
    original_time = b_main.time
    avamet.obtener_datos_tiempo_real = lambda eid: lecturas[eid]
    b_main.time = types.SimpleNamespace(time=lambda: ahora)
    try:
        lat0, lon0 = plan["lat"], plan["lon"]
        radio_cache = [{**e, "datos": lecturas[e["id"]]} for e in plan["radio"]]
        aero_snap = plan["aeropuertos"]
        mov = b_main._estimar_movimiento_nubes(datos_ref, b_aero.utiles_para_calculo(metars), aero_snap)
        for est in radio_cache:
            if "Error" not in est["datos"]:
                b_alertas.registrar_lluvia_estacion(SEG, est["id"], b_alertas._num(est["datos"].get("Lluvia hoy", "0")), ahora)
        alertas_pred, radares, radares_aero = [], [], []
        if mov["velocidad_kmh"] > 5:
            for est in radio_cache:
                if "Error" in est["datos"]:
                    continue
                if b_alertas.lluvia_reciente(SEG, est["id"], ahora):
                    bearing = b_main._bearing(est["lat"], est["lon"], lat0, lon0)
                    diff = abs(bearing - mov["grados_destino"])
                    if diff > 180: diff = 360 - diff
                    if diff < 30:
                        minutos = int((est["distancia_km"] / mov["velocidad_kmh"]) * 60)
                        if minutos < 120:
                            alertas_pred.append(f"Lluvia en {est['nombre']} a {est['distancia_km']} km. Llegada en ~{minutos} min.")
                            radares.append({"nombre": est["nombre"], "lat": est["lat"], "lon": est["lon"], "distancia": est["distancia_km"], "eta": minutos})
        if mov["velocidad_kmh"] > 5:
            for aero in aero_snap:
                m = b_aero.utiles_para_calculo(metars).get(aero["icao"], {})
                if not m:
                    continue
                tiempo_malo = m.get("fenomenos") != "Ninguno"
                nubes_bajas = len(m.get("nubosidad", {}).get("baja", [])) > 0
                if tiempo_malo or nubes_bajas:
                    bearing = b_main._bearing(aero["lat"], aero["lon"], lat0, lon0)
                    diff = abs(bearing - mov["grados_destino"])
                    if diff > 180: diff = 360 - diff
                    if diff < 45:
                        info = b_main._procesar_red_arrastre(aero, lat0, lon0, mov, COORDS)
                        motivo = "Lluvia/Tormenta" if tiempo_malo else "Nubes bajas"
                        texto = f"[Largo Alcance] {motivo} en {aero['nombre']}."
                        texto = f"[CONFIRMADO EN TIERRA] {motivo} en {aero['nombre']}." if info["confirmado"] else texto + " (Sin confirmar)."
                        alertas_pred.append(f"{texto} Llegada en ~{info['eta']} min.")
                        radares_aero.append({"nombre": aero["nombre"], "lat": aero["lat"], "lon": aero["lon"], "distancia": aero["distancia_km"],
                                             "eta": info["eta"], "motivo": motivo, "confirmado": info["confirmado"],
                                             "estaciones_camino": info["estaciones_camino"]})
        return {"mov": mov, "alertas_predictivas": alertas_pred, "radares": radares, "radares_aeropuertos": radares_aero,
                "seg": {k: (v["valor"], v["ts_subida"]) for k, v in SEG.items()}}
    finally:
        avamet.obtener_datos_tiempo_real = original_get
        b_main.time = original_time


def _escenario(rnd):
    lat, lon = rnd.uniform(39.2, 40.8), rnd.uniform(-1.4, 0.4)
    plan = motor.planificar(lat, lon, COORDS, AEROS)
    seg0, lecturas = {}, {}
    for eid in COORDS:
        base = None
        if rnd.random() < 0.5:
            base = round(rnd.uniform(0, 5), 1)
            seg0[eid] = {"valor": base, "ts_subida": rnd.choice([0.0, AHORA - rnd.randint(0, 3000)])}
        if rnd.random() < 0.08:
            lecturas[eid] = {"Error": "timeout"}
        else:
            llueve = base is not None and rnd.random() < 0.35
            valor = round((base if base is not None else rnd.uniform(0, 5)) + (rnd.uniform(0.1, 1.0) if llueve else 0.0), 1)
            lecturas[eid] = _lectura(rnd.choice([60, 200, 500]), f"{valor}", f"{rnd.randint(0, 30)} km/h {rnd.choice(ROSA)}")
    rid = plan["referencia"]["id"]
    lecturas[rid] = _lectura(rnd.choice([60, 200, 500]), lecturas[rid].get("Lluvia hoy", "0.0 mm").split()[0] if "Error" not in lecturas[rid] else "0.0",
                             f"{rnd.randint(0, 40)}.{rnd.randint(0, 9)} km/h {rnd.choice(ROSA)}")
    metars = _metars_aleatorios(rnd, plan["aeropuertos"])
    return plan, lecturas, metars, seg0


def test_paridad_radar_completo():
    rnd = random.Random(6)
    con_estaciones = con_aeropuertos = confirmados = 0
    for i in range(400):
        plan, lecturas, metars, seg0 = _escenario(rnd)
        ref = _referencia_escritorio(plan, lecturas, metars, seg0, AHORA, lecturas[plan["referencia"]["id"]])
        r = motor.analizar(plan, lecturas, metars, seg0, AHORA, COORDS)
        assert r["movimiento"] == ref["mov"], (i, r["movimiento"], ref["mov"])
        assert r["alertas_predictivas"] == ref["alertas_predictivas"], (i, r["alertas_predictivas"], ref["alertas_predictivas"])
        assert r["radares"] == ref["radares"], i
        assert r["radares_aeropuertos"] == ref["radares_aeropuertos"], i
        # El escritorio no anota la lluvia de la estación de referencia en este seguimiento (la núcleo sí: diferencia intencionada,
        # sirve para "lluvia_ahora"); se compara todo lo demás.
        rid = plan["referencia"]["id"]
        for k, v in ref["seg"].items():
            if k != rid:
                assert (r["seguimiento"][k]["valor"], r["seguimiento"][k]["ts_subida"]) == v, (i, k)
        assert r["faltan"] == [], (i, r["faltan"])                  # con todas las lecturas disponibles no falta nada
        con_estaciones += bool(ref["radares"]); con_aeropuertos += bool(ref["radares_aeropuertos"])
        confirmados += any(x["confirmado"] for x in ref["radares_aeropuertos"])
    # que la prueba cubra de verdad los casos interesantes
    assert con_estaciones >= 25 and con_aeropuertos >= 25 and confirmados >= 5, (con_estaciones, con_aeropuertos, confirmados)
    print(f"   cobertura: {con_estaciones} escenarios con aviso de estación, {con_aeropuertos} con aeropuerto, {confirmados} confirmados en tierra")


def test_descarga_por_etapas_equivale_a_tener_todo():
    """Pidiendo solo lo que dice `faltan` se llega al mismo resultado que teniendo todas las lecturas desde el principio."""
    rnd = random.Random(7)
    comprobados = 0
    for _ in range(150):
        plan, lecturas, metars, seg0 = _escenario(rnd)
        rid = plan["referencia"]["id"]
        completo = motor.analizar(plan, lecturas, metars, seg0, AHORA, COORDS)
        tenidas = {rid: lecturas[rid]}
        seg = seg0
        for _vuelta in range(5):
            r = motor.analizar(plan, tenidas, metars, seg, AHORA, COORDS)
            seg = r["seguimiento"]
            if not r["faltan"]:
                break
            for eid in r["faltan"]:
                tenidas[eid] = lecturas[eid]
        assert not r["faltan"]
        assert r["alertas_predictivas"] == completo["alertas_predictivas"] and r["radares"] == completo["radares"]
        assert r["radares_aeropuertos"] == completo["radares_aeropuertos"] and r["movimiento"] == completo["movimiento"]
        comprobados += bool(r["alertas_predictivas"])
        assert len(tenidas) <= len(lecturas)
    assert comprobados >= 10, comprobados


if __name__ == "__main__":
    n = 0
    for nombre, f in list(globals().items()):
        if nombre.startswith("test_") and callable(f):
            f(); n += 1; print("OK", nombre)
    print(f"{n} pruebas de paridad OK")
