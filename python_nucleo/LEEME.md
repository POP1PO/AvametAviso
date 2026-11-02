# Núcleo de AVAMET Aviso (Fase 1) — contrato para el port a Kotlin

Python puro (solo biblioteca estándar). **No hace E/S**: la app descarga, el núcleo calcula.

## Flujo en dos pasos
1. `planificar(lat, lon, coords, aeropuertos)` → estación de referencia (con histéresis), estaciones en 25 km, aeropuertos en 120 km, y qué descargar.
2. La app descarga y llama a `analizar(plan, lecturas, metars, seguimiento, ahora, coords)`.
   Si `faltan` no está vacío: descarga esas estaciones, añádelas a `lecturas` y vuelve a llamar con el `seguimiento` devuelto. Repite hasta que `faltan` sea vacío (en las pruebas bastan 2 vueltas).
3. Guarda `seguimiento` entre ciclos (es el estado de la lluvia; hueco >1800 s lo reinicia).

## Carga a AVAMET
Solo se piden las estaciones "en camino" (rumbo a ti dentro de ±30° de la dirección de la nube y ETA < 120 min). En Vila-real hay 83 estaciones en 25 km: pedirlas todas cada ciclo, multiplicado por los amigos, sería mucha carga. Recomendado: contactar con AVAMET antes de distribuir, intervalos largos y un User-Agent identificable. **No puedo confirmar** sus condiciones de uso.

## Reglas que Kotlin debe replicar
- Redondeo de Python (`round`) = a par (banker's): `Math.rint`, no `Math.round`.
- Comparar decimales con tolerancia; los vectores se guardan tal cual los da Python.
- Valores `None` de METAR (`base: null`) → 0 (diferencia intencionada respecto al escritorio, ver abajo).

## Diferencias intencionadas con el escritorio
1. Una referencia con sensor congelado no aporta su viento al movimiento.
2. `base: null` en nubes de METAR se trata como 0.
3. El seguimiento de lluvia también anota la estación de referencia (el escritorio no); no cambia ninguna alerta.
4. Solo se descargan las estaciones en camino (ver arriba).
(Las pruebas de paridad cubren el resto: 7 pruebas contra el backend real, 400 escenarios aleatorios.)

## Pruebas
- `python tests/test_nucleo.py` — unitarias (15).
- `python tests/test_paridad.py` — núcleo == backend de escritorio (necesita `../lote3`, o `AVISO_BACKEND=ruta`).
- `python tests/test_vectores.py` — vectores dorados (`vectores/*.json`). Kotlin debe pasar los mismos.
- `python generar_vectores.py` — regenera los vectores.
