# AVAMET Aviso (Android)

App nativa (Kotlin) que, con el GPS del móvil, vigila **qué se acerca a tu posición**: estaciones de AVAMET de alrededor, METAR de
aeropuertos y mapa con la dirección de las nubes. Si el nivel pasa a **PELIGRO**, suena una alarma (silenciable) y enciende la pantalla.
Sigue vigilando con la pantalla apagada hasta que deslizas la app fuera de recientes o pulsas «Detener vigilancia».

No incluye Telegram, servidor HTTP, N.I.N.A./Alpaca/Boltwood ni base de datos.

## Qué hace
- **Vigilancia con el GPS**: servicio en primer plano que, cada pocos minutos, mira la estación AVAMET de referencia (la más cercana o la que elijas), las
  estaciones que están en el camino de las nubes y los METAR de aeropuertos hasta 120 km. Nivel **SEGURO / VIGILANCIA / PELIGRO**; en PELIGRO suena una alarma
  (silenciable) y se enciende la pantalla. Un vigilante (WorkManager) avisa si el sistema detiene el servicio.
- **Resumen**: estado actual, datos de la estación de referencia (temperatura, humedad, presión, viento, racha, lluvia) y la antigüedad de su última lectura.
- **Movimiento de las nubes**: brújula con el **norte real** (sensor del móvil, con corrección de la declinación magnética) y una flecha con una nube que marca
  **de dónde vienen** las nubes, más el texto «VIENEN DEL NNO · 343°». Avisa si el sensor está sin calibrar (mueve el móvil dibujando un 8).
- **Cielo para observar (ahora)**: veredicto con METAR, estación de referencia, avisos de lluvia en camino y fase de la Luna. Criterio propio, no un pronóstico.
- **Mapa**: estaciones, aeropuertos, posición y dirección de las nubes sobre OpenStreetMap.
  - **Nubes IR (satélite)**: capa Meteosat IR 10,8 µm de EUMETView, sin claves, con opacidad regulable y la hora del barrido.
  - **Animación**: botón para reproducir en bucle los últimos 8 barridos (2 h) y ver hacia dónde se mueven las nubes.
- **Estaciones y Aeropuertos**: listas con los datos que se están usando.
- **Ajustes**: estación de referencia manual, intervalo de consulta, prueba de la alarma, permisos de batería, notificaciones a pantalla completa.
- **Actualizaciones desde GitHub**: al abrir la app (como mucho cada 6 h) mira si hay una versión más nueva en la release «Última versión», la descarga y abre
  el instalador; se instala **encima** de la anterior sin borrar nada. Android exige confirmar con un toque; no se puede instalar en silencio.

No incluye Telegram, servidor HTTP, N.I.N.A./Alpaca/Boltwood ni base de datos (eso es el programa de escritorio).

## Limitaciones
- Cada versión se compila y se prueba automáticamente (GitHub Actions); si fallan las pruebas, no se genera el APK.
- La vigilancia en segundo plano depende del fabricante del móvil (ahorro de batería, Doze): conviene desactivar la optimización de batería para la app.
- La capa de nubes es solo informativa: no interviene en los avisos.

## Cómo obtener el APK (GitHub Actions)
1. Crea un repositorio en GitHub y sube **todo el contenido de esta carpeta** (rama `main`).
2. Pestaña **Actions** → «Construir APK» → se ejecuta sola con cada push (o «Run workflow»).
3. Al terminar (verde), el APK queda también en **Releases → «Última versión (nº N)»**; ese número (`versionCode`) sube solo en cada compilación y es el que usa
   la app para saber si hay una actualización. También puedes bajar el artefacto **AvametAviso-apk** (un .zip con `app-release.apk`).
4. Si falla: abre el paso rojo y revisa el log.

### Firma (recomendado antes de repartir el APK)
Sin clave propia se usa la clave PÚBLICA fija de `app/firma/aviso.jks`: las versiones nuevas se instalan encima de la anterior (el número de versión sube solo en cada compilación), pero cualquiera podría firmar una app con esa clave. Para repartir a más gente, usa una clave propia:
Crea una clave una sola vez (en tu PC, con Java instalado):

    keytool -genkeypair -v -keystore clave.jks -alias aviso -keyalg RSA -keysize 2048 -validity 10000
    base64 -w0 clave.jks        # en Windows PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("clave.jks"))

En el repositorio: Settings → Secrets and variables → Actions → New repository secret:
`KEYSTORE_BASE64` (el texto base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (aviso), `KEY_PASSWORD`. **Guarda clave.jks en un sitio seguro**:
si la pierdes, tus amigos tendrán que desinstalar y reinstalar.

## Instalar en el móvil
Pasa el .apk (WhatsApp, Drive…), ábrelo y permite «instalar apps desconocidas» para esa aplicación. Android puede avisar de que no es de Play Store.

## Primer uso (importante para que no se pare)
1. **Ajustes → Batería: permitir sin restricciones** y **Ajustes de la app** (batería «Sin restricciones»).
2. **Inicio automático / batería del fabricante** (Xiaomi, Oppo, Samsung…). Los nombres exactos de los menús cambian con la versión: no están verificados.
3. Android 14+: **Permitir notificaciones a pantalla completa** (para que la alarma encienda la pantalla).
4. Pulsa **Iniciar vigilancia** y acepta ubicación y notificaciones. «Probar la alarma» comprueba sonido y pantalla.

## Qué hace cada nivel
- **PELIGRO** (alarma): lluvia reciente (el acumulado subió en 30 min) en tu estación de referencia o en una estación a < 25 km; viento > 45 km/h o racha > 55 km/h;
  o un aviso de lluvia que llegue en < 30 min. Los umbrales de viento y de lluvia son los del programa de escritorio; el de 30 min es propio del móvil.
- **VIGILANCIA**: viento ≥ 40 km/h o racha ≥ 45 km/h.
- Si la estación de referencia lleva > 10 min sin datos, se avisa («SIN DATOS FÍABLES») y se proponen alternativas; no se usa su viento.

## Carga sobre AVAMET
Solo se consulta la estación de referencia y las estaciones que están **en el camino de las nubes** (no las ~80 de alrededor), cada 5 min por defecto.
Aun así, varios usuarios multiplican las peticiones. El User-Agent identifica la app. Conviene avisar a AVAMET; **no puedo confirmar sus condiciones de uso**.
El mapa usa los servidores de teselas de OpenStreetMap (uso moderado y con User-Agent propio, como pide su política; no la he revisado aquí).

## Estructura
- `app/src/main/java/es/avamet/aviso/core` — cálculo (puro, con pruebas).
- `app/src/main/java/es/avamet/aviso` — servicio, descargas, alarma, pantallas.

## Capa «Nubes IR» en el mapa (solo mostrar)
Casilla en la pestaña Mapa. Descarga teselas WMS públicas de EUMETView (Meteosat IR 10.8 µm) directamente en el móvil: sin ordenador y sin claves.
No entra en alarmas ni en el veredicto. Es una imagen ya coloreada (no da temperaturas).

- Imagen del satélite Meteosat con ~25–30 min de retraso respecto a la hora real.
- La animación muestra los últimos barridos (cada 15 min, últimas 2 h).

## Datos y licencias
- La capa «Nubes IR» muestra imágenes del visor público EUMETView de EUMETSAT (Meteosat IR 10,8 µm). La app solo las muestra: no guarda ni redistribuye los datos numéricos, y no se usan en los avisos.
- Atribución: «© EUMETSAT Meteosat data» (año en curso), visible en la app junto a la capa.
- Condiciones de EUMETSAT: [Data Policy](https://www-cdn.eumetsat.int/files/2026-01/45173%20-%20Data_Policy.pdf) y [registro y licencias](https://user.eumetsat.int/resources/user-guides/data-registration-and-licensing). Si redistribuyes la app o la usas en un servicio comercial, revisa esas condiciones.
- Los datos de estaciones proceden de AVAMET y los METAR de aviationweather.gov, con sus propias condiciones.

---

Creado por Roberto Ferrando.
