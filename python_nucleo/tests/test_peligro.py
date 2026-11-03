import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from aviso_core import evaluar_peligro
from aviso_core.peligro import cond_viento

def ref(viento="5 km/h N", racha="8 km/h", estado="ACTIVA"):
    return {"estado": {"estado": estado}, "datos": {"Viento": viento, "Viento máximo": racha}}

def test_viento_igual_que_escritorio():
    assert [cond_viento(*a) for a in [(10, 10), (40, 10), (10, 45), (45, 10), (46, 10), (10, 55), (10, 56)]] == [1, 2, 2, 2, 3, 2, 3]

def test_niveles():
    assert evaluar_peligro({"referencia": ref()}) == {"nivel": "SEGURO", "motivos": []}
    assert evaluar_peligro({"referencia": ref("42 km/h N")})["nivel"] == "VIGILANCIA"
    assert evaluar_peligro({"referencia": ref("50 km/h N")})["nivel"] == "PELIGRO"
    assert evaluar_peligro({"referencia": ref("50 km/h N", estado="CONGELADA")})["nivel"] == "SEGURO"   # dato no fiable: no se usa

def test_lluvia_y_eta():
    def ll(d, es_ref=False): return {"nombre": "X", "distancia_km": d, "es_referencia": es_ref}
    # a 10 km o menos: ALARMA (PELIGRO); entre 10 y 25 km: AVISO (VIGILANCIA, no suena); más lejos: nada
    r = evaluar_peligro({"referencia": ref(), "lluvia_ahora": [ll(7.5)]})
    assert r["nivel"] == "PELIGRO" and "7.5 km" in r["motivos"][0]
    assert evaluar_peligro({"referencia": ref(), "lluvia_ahora": [ll(10.0)]})["nivel"] == "PELIGRO"
    r = evaluar_peligro({"referencia": ref(), "lluvia_ahora": [ll(12.0)]})
    assert r["nivel"] == "VIGILANCIA" and "12.0 km" in r["motivos"][0] and "aviso" in r["motivos"][0]
    assert evaluar_peligro({"referencia": ref(), "lluvia_ahora": [ll(25.0)]})["nivel"] == "VIGILANCIA"
    assert evaluar_peligro({"referencia": ref(), "lluvia_ahora": [ll(30.0)]})["nivel"] == "SEGURO"
    assert evaluar_peligro({"referencia": ref(), "lluvia_ahora": [ll(0.0, True)]})["nivel"] == "PELIGRO"      # tu estación: siempre PELIGRO
    # el aviso lejano nunca rebaja un PELIGRO, sea cual sea el orden
    assert evaluar_peligro({"referencia": ref(), "lluvia_ahora": [ll(20.0), ll(5.0)]})["nivel"] == "PELIGRO"
    assert evaluar_peligro({"referencia": ref(), "lluvia_ahora": [ll(5.0), ll(20.0)]})["nivel"] == "PELIGRO"
    assert evaluar_peligro({"referencia": ref("50 km/h N"), "lluvia_ahora": [ll(20.0)]})["nivel"] == "PELIGRO"   # viento fuerte + aviso
    assert evaluar_peligro({"alertas_predictivas": ["Lluvia en X a 5 km. Llegada en ~29 min."]})["nivel"] == "PELIGRO"
    assert evaluar_peligro({"alertas_predictivas": ["Lluvia en X a 5 km. Llegada en ~30 min."]})["nivel"] == "SEGURO"

if __name__ == "__main__":
    for k, f in list(globals().items()):
        if k.startswith("test_"): f(); print("OK", k)
