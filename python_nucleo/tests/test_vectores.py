"""Comprueba que el núcleo sigue reproduciendo los vectores dorados (los mismos que debe reproducir Kotlin). No necesita el backend."""
import json, sys
from pathlib import Path
RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
from aviso_core import parseo, movimiento, motor, evaluar_peligro, cargar_coordenadas, cargar_aeropuertos
V = RAIZ / "vectores"
def cargar(n): return json.loads((V / n).read_text(encoding="utf-8"))

def test_estacion():
    for c in cargar("estacion_parseo.json"): assert parseo.parsear_estacion(parseo.texto_de_html(c["html"])) == c["esperado"]
def test_metar():
    for c in cargar("metar_parseo.json"): assert parseo.parsear_metars(c["json"]) == c["esperado"]
def test_movimiento():
    for c in cargar("movimiento.json"): assert movimiento.estimar_movimiento(c["referencia"], c["metars"], c["aeropuertos"]) == c["esperado"]
def test_motor():
    co, ae = cargar_coordenadas(), cargar_aeropuertos()
    for c in cargar("motor.json"):
        plan = motor.planificar(c["lat"], c["lon"], co, ae)
        assert json.loads(json.dumps(plan)) == c["esperado_plan"]
        r = motor.analizar(plan, c["lecturas"], c["metars"], c["seguimiento"], c["ahora"], co)
        assert json.loads(json.dumps(r)) == c["esperado"]
        assert evaluar_peligro(r) == c["esperado_peligro"]

def test_peligro():
    for c in cargar("peligro.json"): assert evaluar_peligro(c["res"]) == c["esperado"]

if __name__ == "__main__":
    n = 0
    for k, f in list(globals().items()):
        if k.startswith("test_") and callable(f): f(); n += 1; print("OK", k)
    print(f"{n} pruebas de vectores OK")
