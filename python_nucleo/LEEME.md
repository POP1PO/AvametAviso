# Núcleo de AVAMET Aviso (Fase 1) — contrato para el port a Kotlin

Python puro (solo biblioteca estándar). **No hace E/S**: la app descarga, el núcleo calcula.

## Flujo en dos pasos
1. `planificar(lat, lon, coords, aeropuertos)` → estación de referencia (con histéresis), estaciones en 25 km, aeropuertos en 120 km, y qué descargar.
2. La app descarga y llama a `analizar(plan, lecturas, metars, seguimiento, ahora, coords)`.
   Si `faltan` no está vacío: descarga esas estaciones, añádelas a `lecturas` y vuelve a llamar con el `seguimiento` devuelto. Repite hasta que `faltan` sea vacío (en las pruebas bastan 2 vueltas).
3. Guarda `seguimiento` entre ciclos (es el estado de la lluvia; hueco >1800 s lo reinicia).

## Carga a AVAMET
La app móvil descarga en cada ciclo (5 min por defecto) la estación de referencia y **todas las del radio de 25 km**, las más cercanas primero
(`estacionesDelRadio`): sin ellas no se puede avisar de lluvia a distancia. Antes solo se pedían las "en camino" y una estación vecina con lluvia
pasaba inadvertida (caso real del 3-nov-2026). En Vila-real hay 83 estaciones en 25 km y en Les Coves de Vinromà 54; el escritorio ya hace lo mismo cada 3 min.
Es bastante carga multiplicada por cada usuario: conviene contactar con AVAMET antes de distribuir la app. **No puedo confirmar** sus condiciones de uso.

## Reglas que Kotlin debe replicar
- Redondeo de Python (`round`) = a par (banker's): `Math.rint`, no `Math.round`.
- Comparar decimales con tolerancia; los vectores se guardan tal cual los da Python.
- Valores `None` de METAR (`base: null`) → 0 (diferencia intencionada respecto al escritorio, ver abajo).

## Diferencias intencionadas con el escritorio
1. Una referencia con sensor congelado no aporta su viento al movimiento.
2. `base: null` en nubes de METAR se trata como 0.
3. El seguimiento de lluvia también anota la estación de referencia (el escritorio no); no cambia ninguna alerta.
4. La lluvia en una estación vecina da AVISO (VIGILANCIA, sin alarma) entre 10 y 25 km y PELIGRO (alarma) a ≤10 km; tu estación de referencia da PELIGRO. El escritorio usa todo el radio.
(Las pruebas de paridad cubren el resto: 7 pruebas contra el backend real, 400 escenarios aleatorios.)

## Pruebas
- `python tests/test_nucleo.py` — unitarias (15).
- `python tests/test_paridad.py` — núcleo == backend de escritorio (necesita `../lote3`, o `AVISO_BACKEND=ruta`).
- `python tests/test_vectores.py` — vectores dorados (`vectores/*.json`). Kotlin debe pasar los mismos.
- `python generar_vectores.py` — regenera los vectores.
