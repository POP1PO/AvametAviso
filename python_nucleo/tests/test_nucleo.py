"""Pruebas unitarias del núcleo (casos calculables a mano). Sin red. Ejecutar: python tests/test_nucleo.py"""
import sys, time
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from aviso_core import geo, parseo, movimiento, estacion, radar, motor, cargar_coordenadas, cargar_aeropuertos
from aviso_core.ajustes import AJUSTES

AHORA = 1_800_000_030.0     # 30 s dentro del minuto: la "Fecha lectura" va al minuto


def fecha(edad_s, ahora=AHORA):
    return datetime.fromtimestamp(ahora - edad_s, ZoneInfo("Europe/Madrid")).strftime("%d-%m-%Y %H:%M")


def lectura(edad_s=60, lluvia="0.0", viento="5 km/h N", ahora=AHORA):
    return {"Fecha lectura": fecha(edad_s, ahora), "Temperatura": "15.1 °C", "Humedad": "70 %", "Viento": viento,
            "Viento máximo": "8 km/h", "Lluvia hoy": f"{lluvia} mm"}


def test_geo():
    assert abs(geo.haversine_km(0, 0, 0, 1) - 111.195) < 0.01                 # 1 grado de longitud en el ecuador
    assert round(geo.rumbo_grados(0, 0, 1, 0)) == 0 and round(geo.rumbo_grados(0, 0, 0, 1)) == 90
    assert round(geo.rumbo_grados(0, 0, -1, 0)) == 180 and round(geo.rumbo_grados(0, 0, 0, -1)) == 270
    assert geo.diferencia_angular(350, 10) == 20 and geo.diferencia_angular(10, 350) == 20 and geo.diferencia_angular(0, 180) == 180
    coords = {"a": {"lat": 40, "lon": 0, "nombre": "A"}, "b": {"lat": 40.1, "lon": 0, "nombre": "B"}}
    assert geo.estacion_mas_cercana(40.09, 0, coords)[0] == "b"
    assert geo.estacion_mas_cercana(1, 1, {}) is None


def test_referencia_con_histeresis():
    coords = {"a": {"lat": 40.000, "lon": 0, "nombre": "A"}, "b": {"lat": 40.020, "lon": 0, "nombre": "B"}}
    # Entre las dos (más cerca de B por ~0.2 km): si ya usaba A, se queda con A; sin estación previa, elige B
    assert geo.elegir_referencia(40.0110, 0, coords, "a", 1.0)[0] == "a"
    assert geo.elegir_referencia(40.0110, 0, coords, None, 1.0)[0] == "b"
    # Mucho más cerca de B: cambia aunque hubiera A
    assert geo.elegir_referencia(40.0200, 0, coords, "a", 1.0)[0] == "b"


def test_parsear_estacion_texto_real():
    t = ("Almassora 01-11-2026 05:25 15,1° mín 15,1° màx 17,7° Humitat 90 % Pressió al nivell de la mar 1.021,3 hPa "
         "Vent 10 km/h ONO Vent màx 18 km/h Pluja hui 0,4 mm Pluja mensual 12,5 mm Pluja anual 301,2 mm")
    d = parseo.parsear_estacion(t)
    assert d == {"Fecha lectura": "01-11-2026 05:25", "Temperatura": "15.1 °C", "Temp mín": "15.1 °C", "Temp máx": "17.7 °C",
                 "Humedad": "90 %", "Viento": "10 km/h ONO", "Viento máximo": "18 km/h", "Lluvia hoy": "0.4 mm",
                 "Lluvia mensual": "12.5 mm", "Lluvia anual": "301.2 mm", "Presión": "1021.3 hPa"}
    assert "Presión descartada" in parseo.parsear_estacion(t.replace("1.021,3", "5000"))
    assert parseo.datos_validos(d) and not parseo.datos_validos({"Temperatura": "1 °C"}) and not parseo.datos_validos({"Error": "x"})


def test_texto_de_html():
    html = "<html><head><style>p{}</style><script>var a=1;</script></head><body><p> Hola <b>mundo</b></p><!-- no --><div>  fin  </div></body></html>"
    assert parseo.texto_de_html(html) == "Hola mundo fin"


def test_edad_lectura():
    assert abs(parseo.edad_lectura_s(lectura(300), AHORA) - 300) < 60                  # la fecha va al minuto
    assert parseo.edad_lectura_s({"Fecha lectura": "basura"}, AHORA) is None
    assert parseo.edad_lectura_s(lectura(-3600), AHORA) is None                          # futuro > 5 min: zona horaria mal
    assert parseo.edad_lectura_s({}, AHORA) is None


def test_metar():
    j = [{"icaoId": "LEVC", "temp": 18, "dewp": 12, "wspd": 10, "wdir": "VRB", "visib": "6+", "wxString": "-RA BR",
          "clouds": [{"cover": "BKN", "base": 1500}, {"cover": "FEW", "base": None}, {"cover": "CLR"}], "reportTime": "t"}, {"x": 1}]
    r = parseo.parsear_metars(j)
    assert list(r) == ["LEVC"] and r["LEVC"]["viento_dir"] is None and r["LEVC"]["viento_nudos"] == 10.0
    assert r["LEVC"]["fenomenos"] == "Lluvia, Neblina"
    assert r["LEVC"]["nubosidad"]["baja"] == ["Nublado (Fragmentado) a 1500 ft (457 m)", "Pocas nubes"]
    assert parseo.traducir_fenomenos("") == "Ninguno" and parseo.traducir_fenomenos("+XX") == "+XX"


def test_movimiento():
    # Viento DEL oeste a 20 km/h -> las nubes van hacia el este (90 grados)
    m = movimiento.estimar_movimiento({"Viento": "20 km/h O"}, {}, [])
    assert m == {"direccion_destino": "E", "grados_destino": 90, "velocidad_kmh": 20.0, "categoria": "Moderado"}
    # VRB o dirección desconocida: se descarta (nunca 0 grados)
    assert movimiento.estimar_movimiento({"Viento": "20 km/h VRB"}, {}, [])["direccion_destino"] == "Desconocida"
    # Dos puntos que se oponen: media simple -> se anulan (vel ~ 0)
    aero = [{"icao": "X", "distancia_km": 5}]
    m = movimiento.estimar_movimiento({"Viento": "10 km/h O"}, {"X": {"viento_nudos": 10 / 1.852, "viento_dir": 90.0}}, aero)
    assert m["velocidad_kmh"] < 0.01 and m["categoria"] == "Lento"


def test_seguimiento_lluvia():
    seg = {}
    estacion.registrar_lluvia(seg, "a", 1.0, AHORA, 0.1, 1800)                   # línea base
    assert seg["a"]["ts_subida"] == 0.0 and not estacion.lluvia_reciente(seg, "a", AHORA, 1800)
    estacion.registrar_lluvia(seg, "a", 1.2, AHORA + 180, 0.1, 1800)             # sube 0,2
    assert estacion.lluvia_reciente(seg, "a", AHORA + 180, 1800)
    assert not estacion.lluvia_reciente(seg, "a", AHORA + 180 + 1801, 1800)      # caduca a los 30 min
    # Hueco largo: la lectura anterior ya no vale como línea base -> no hay "lluvia nueva" falsa
    estacion.registrar_lluvia(seg, "b", 1.0, AHORA, 0.1, 1800)
    estacion.registrar_lluvia(seg, "b", 3.0, AHORA + 7200, 0.1, 1800)
    assert seg["b"]["ts_subida"] == 0.0
    estacion.registrar_lluvia(seg, "c", 1.0, AHORA, 0.1)                           # sin límite de hueco: como el escritorio
    estacion.registrar_lluvia(seg, "c", 3.0, AHORA + 7200, 0.1)
    assert seg["c"]["ts_subida"] == AHORA + 7200


def test_estado_estacion():
    assert estacion.estado_estacion(lectura(120), "X", AHORA)["estado"] == "ACTIVA"
    e = estacion.estado_estacion(lectura(11 * 60 + 5), "X", AHORA)
    assert e["estado"] == "CONGELADA" and e["edad_min"] == 11
    assert estacion.estado_estacion({"Error": "x"}, "X", AHORA)["estado"] == "SIN_LECTURA"
    assert estacion.aviso_sin_datos(e).startswith("⚠️ SIN DATOS FÍABLES: El sensor de X no se actualiza desde hace 11 min.")


def _escenario_lluvia_en_camino():
    """Referencia en (40, 0). Viento del oeste (nubes hacia el este). Estación 'oeste' a ~10 km al OESTE con lluvia reciente."""
    coords = {"ref": {"lat": 40.0, "lon": 0.0, "nombre": "Ref", "municipio": "Ref"},
              "oeste": {"lat": 40.0, "lon": -0.1170, "nombre": "Oeste", "municipio": "Oeste"},
              "este": {"lat": 40.0, "lon": 0.1170, "nombre": "Este", "municipio": "Este"}}
    plan = motor.planificar(40.0, 0.0, coords, {})
    return coords, plan


def test_aviso_lluvia_que_llega():
    coords, plan = _escenario_lluvia_en_camino()
    assert plan["referencia"]["id"] == "ref" and [e["id"] for e in plan["radio"]] == ["oeste", "este"] or sorted(e["id"] for e in plan["radio"]) == ["este", "oeste"]
    l1 = {"ref": lectura(60, "0.0", "20 km/h O"), "oeste": lectura(60, "1.0"), "este": lectura(60, "1.0")}
    r1 = motor.analizar(plan, l1, {}, {}, AHORA, coords)
    assert r1["alertas_predictivas"] == [] and r1["movimiento"]["direccion_destino"] == "E"          # línea base: aún no hay aviso
    l2 = {"ref": lectura(60, "0.0", "20 km/h O", AHORA + 180), "oeste": lectura(60, "1.4", ahora=AHORA + 180), "este": lectura(60, "1.4", ahora=AHORA + 180)}
    r2 = motor.analizar(plan, l2, {}, r1["seguimiento"], AHORA + 180, coords)
    dist = [e for e in plan["radio"] if e["id"] == "oeste"][0]["distancia_km"]
    minutos = int(dist / 20.0 * 60)
    assert r2["alertas_predictivas"] == [f"Lluvia en Oeste a {dist} km. Llegada en ~{minutos} min."]       # solo el oeste: el este queda a sotavento
    assert [x["nombre"] for x in r2["lluvia_ahora"]] == ["Oeste", "Este"]
    assert r2["radares"][0]["eta"] == minutos


def test_solo_se_piden_estaciones_en_camino():
    coords, plan = _escenario_lluvia_en_camino()
    solo_ref = {"ref": lectura(60, "0.0", "20 km/h O")}
    r = motor.analizar(plan, solo_ref, {}, {}, AHORA, coords)
    assert r["faltan"] == ["oeste"]                      # el "este" (a sotavento) no hace falta descargarlo
    r = motor.analizar(plan, {**solo_ref, "oeste": lectura(60, "1.0")}, {}, {}, AHORA, coords)
    assert r["faltan"] == []


def test_referencia_congelada_no_aporta_viento():
    coords, plan = _escenario_lluvia_en_camino()
    l = {"ref": lectura(15 * 60, "0.0", "20 km/h O"), "oeste": lectura(60, "0.0"), "este": lectura(2 * 60, "0.0")}
    r = motor.analizar(plan, l, {}, {}, AHORA, coords)
    assert r["referencia"]["estado"]["estado"] == "CONGELADA" and r["aviso_estacion"].startswith("⚠️ SIN DATOS FÍABLES")
    assert r["movimiento"]["direccion_destino"] == "Desconocida"                      # el viento de un sensor parado no cuenta
    assert [a["id"] for a in r["alternativas"]] == sorted([a["id"] for a in r["alternativas"]], key=lambda i: [e for e in plan["radio"] if e["id"] == i][0]["distancia_km"])
    assert {a["id"] for a in r["alternativas"]} == {"oeste", "este"}


def test_sin_lectura_de_la_referencia():
    coords, plan = _escenario_lluvia_en_camino()
    r = motor.analizar(plan, {"ref": {"Error": "timeout"}}, {}, {}, AHORA, coords)
    assert r["referencia"]["estado"]["estado"] == "SIN_LECTURA" and r["referencia"]["datos"] is None


def test_sin_estaciones():
    r = motor.analizar(motor.planificar(40, 0, {}, {}), {}, {}, {}, AHORA, {})
    assert r["referencia"] is None and r["radio"] == []


def test_datos_reales_cargan():
    c = cargar_coordenadas(); a = cargar_aeropuertos()
    assert len(c) > 900 and len(a) == 35 and all("municipio" in v for v in c.values())
    p = motor.planificar(39.988, -0.051, c, a)
    assert p["referencia"]["distancia_km"] < 5 and p["descargar_estaciones"][0] == p["referencia"]["id"]
    assert {"LECH", "LEVC"} <= {x["icao"] for x in p["aeropuertos"]}


if __name__ == "__main__":
    n = 0
    for nombre, f in list(globals().items()):
        if nombre.startswith("test_") and callable(f):
            f(); n += 1; print("OK", nombre)
    print(f"{n} pruebas OK")
