"""Genera los VECTORES DORADOS (vectores/*.json): entradas + salida esperada del núcleo Python.
El port a Kotlin debe reproducirlos (ver LEEME.md). Se ejecuta con el backend de escritorio junto a esta carpeta, porque
reutiliza los generadores de escenarios de tests/test_paridad.py (que ya verifican núcleo == backend)."""
import json, random, sys
from pathlib import Path
RAIZ = Path(__file__).resolve().parent
sys.path.insert(0, str(RAIZ)); sys.path.insert(0, str(RAIZ / "tests"))
import test_paridad as T
from aviso_core import parseo, movimiento, motor, evaluar_peligro

SAL = RAIZ / "vectores"; SAL.mkdir(exist_ok=True)

def guardar(nombre, casos):
    (SAL / nombre).write_text(json.dumps(casos, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")
    print(f"{nombre}: {len(casos)} casos")

rnd = random.Random(2026)
# 1) texto de estación -> dict
casos = []
for _ in range(60):
    t = T._texto_aleatorio(rnd)
    casos.append({"html": f"<html><body><p>{t}</p></body></html>", "esperado": parseo.parsear_estacion(t)})
guardar("estacion_parseo.json", casos)

# 2) METAR
casos = []
for _ in range(40):
    lista = [{"icaoId": k, "temp": rnd.choice([None, rnd.randint(-5, 40)]), "dewp": rnd.choice([None, 10]), "wspd": rnd.choice([None, rnd.randint(0, 40)]),
              "wdir": rnd.choice(["VRB", None, rnd.randint(0, 360)]), "visib": rnd.choice(["6+", 9999, None]),
              "wxString": rnd.choice(["", None, "RA", "-TSRA BR", "FG"]),
              "clouds": [{"cover": rnd.choice(["FEW", "SCT", "BKN", "OVC", "CLR", "NSC"]), "base": rnd.choice([None, 0, 500, 3000, 9000, 25000])}
                         for _ in range(rnd.randint(0, 3))], "reportTime": "2026-11-02 04:00:00"}
             for k in rnd.sample(list(T.AEROS), rnd.randint(0, 4))]
    casos.append({"json": lista, "esperado": parseo.parsear_metars(lista)})
guardar("metar_parseo.json", casos)

# 3) movimiento
casos = []
for _ in range(120):
    aeros = [{"icao": k, "distancia_km": round(rnd.uniform(0, 120), 1)} for k in rnd.sample(list(T.AEROS), rnd.randint(0, 4))]
    ref = {"Viento": rnd.choice([f"{rnd.randint(0, 50)}.{rnd.randint(0, 9)} km/h {rnd.choice(T.ROSA)}", "5 km/h VRB", "calma", ""])}
    mt = T._metars_aleatorios(rnd, aeros)
    casos.append({"referencia": ref, "metars": mt, "aeropuertos": aeros, "esperado": movimiento.estimar_movimiento(ref, mt, aeros)})
guardar("movimiento.json", casos)

# 4) motor completo (planificar + analizar con todas las lecturas)
casos = []
while len(casos) < 14:
    plan, lecturas, metars, seg0 = T._escenario(rnd)
    r = motor.analizar(plan, lecturas, metars, seg0, T.AHORA, T.COORDS)
    if len(casos) < 6 or r["alertas_predictivas"]:
        casos.append({"lat": plan["lat"], "lon": plan["lon"], "ahora": T.AHORA, "lecturas": lecturas, "metars": metars, "seguimiento": seg0,
                      "esperado_plan": plan, "esperado": r, "esperado_peligro": evaluar_peligro(r)})
guardar("motor.json", casos)

# 5) peligro (entradas mínimas, mezcla de niveles)
casos = []
for _ in range(80):
    est = rnd.choice(["ACTIVA", "ACTIVA", "CONGELADA", "SIN_LECTURA"])
    res = {"referencia": {"estado": {"estado": est}, "datos": rnd.choice([None, {"Viento": f"{rnd.randint(0, 60)} km/h N", "Viento máximo": f"{rnd.randint(0, 70)} km/h"}])},
           "lluvia_ahora": [{"nombre": f"E{i}", "distancia_km": round(rnd.uniform(1, 40), 1), "es_referencia": rnd.random() < 0.1} for i in range(rnd.choice([0, 0, 0, 1, 2]))],
           "alertas_predictivas": [f"Lluvia en X a 5 km. Llegada en ~{rnd.randint(1, 90)} min." for _ in range(rnd.choice([0, 0, 1]))]}
    casos.append({"res": res, "esperado": evaluar_peligro(res)})
guardar("peligro.json", casos)
