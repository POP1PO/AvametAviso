"""aviso_core: núcleo puro (solo biblioteca estándar) de "qué se acerca a mi posición".

Sin red, sin FastAPI, sin Telegram, sin N.I.N.A., sin base de datos. Todo el cálculo recibe sus datos como argumentos y
devuelve datos simples (dict / list / str / números / None), para poder portarlo línea a línea a Kotlin y comprobar que
da los MISMOS resultados con los vectores de prueba de ../vectores/.

    from aviso_core import planificar, analizar, parsear_estacion, parsear_metars
"""
from .motor import planificar, analizar
from .peligro import evaluar_peligro
from .datos import cargar_coordenadas, cargar_aeropuertos
from .parseo import parsear_estacion, parsear_metars, texto_de_html

VERSION = "0.1.0"
__all__ = ["cargar_coordenadas", "cargar_aeropuertos", "planificar", "analizar", "evaluar_peligro", "parsear_estacion", "parsear_metars", "texto_de_html", "VERSION"]
