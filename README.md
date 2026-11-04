# AVAMET Aviso (Android)

App nativa (Kotlin) que, con el GPS del móvil, vigila **qué se acerca a tu posición**: estaciones de AVAMET de alrededor, METAR de
aeropuertos y mapa con la dirección de las nubes. Si el nivel pasa a **PELIGRO**, suena una alarma (silenciable) y enciende la pantalla.
Sigue vigilando con la pantalla apagada hasta que deslizas la app fuera de recientes o pulsas «Detener vigilancia».

No incluye Telegram, servidor HTTP, N.I.N.A./Alpaca/Boltwood ni base de datos.

## Estado honesto de este proyecto
- **Compilado y probado aquí:** el cálculo (`app/src/main/java/es/avamet/aviso/core`), con el compilador de Kotlin 2.0.21 y JUnit 4. Los 5 tests de
  `VectoresTest` pasan: Kotlin da los mismos resultados que el núcleo Python (a su vez verificado contra el programa de escritorio) con ~270 casos.
  También se comprobó que los tests fallan si se altera un vector. Además se compilaron `Descargas`, `Monitor` y `Estado` (con piezas de Android simuladas)
  y un ciclo completo sin red no lanza excepciones.
- **NO compilado:** servicio, alarma, pantallas, mapa, vigilante (`MainActivity`, `MonitorServicio`, `Alarma`, `AlertaActivity`, `Config`, `VigilanteWorker`).
  El entorno donde se escribió no tiene el SDK de Android ni acceso a Maven/Google. La primera ejecución de GitHub Actions lo comprueba; si hay errores de
  compilación, copia el log y se corrigen.
- **Solo se puede comprobar en teléfonos reales:** segundo plano (Doze, restricciones de cada fabricante), GPS con pantalla apagada, que la alarma encienda
  la pantalla, la descarga real de AVAMET desde el móvil (codificación del texto, bloqueos). Ver «Prueba de 4 horas».

## Cómo obtener el APK (GitHub Actions)
1. Crea un repositorio en GitHub y sube **todo el contenido de esta carpeta** (rama `main`).
2. Pestaña **Actions** → «Construir APK» → se ejecuta sola con cada push (o «Run workflow»).
3. Al terminar (verde), abre la ejecución y descarga el artefacto **AvametAviso-apk** (un .zip con `app-release.apk`).
4. Si falla: abre el paso rojo, copia el log y pásamelo.

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

## Prueba de 4 horas (pendiente)
En cada marca (Xiaomi, Samsung, Google, Oppo): iniciar vigilancia, pantalla apagada, móvil quieto 4 h+. Comprobar: (a) la notificación sigue, (b) hay ciclos
(hora en la notificación), (c) «Probar la alarma» suena y enciende la pantalla, (d) al deslizar la app se detiene el servicio y desaparece la notificación.
Si el sistema mata el servicio, el vigilante (WorkManager, ~15 min) avisa «La vigilancia se detuvo», aunque algunos fabricantes también lo frenan.

## Estructura
- `app/src/main/java/es/avamet/aviso/core` — cálculo (puro, probado con vectores).
- `app/src/main/java/es/avamet/aviso` — servicio, descargas, alarma, pantallas.
- `python_nucleo` — núcleo Python de referencia, pruebas y generador de vectores (`LEEME.md`). Los vectores se copian a `app/src/test/resources`.

## Capa «Nubes IR» en el mapa (solo mostrar)
Casilla en la pestaña Mapa. Descarga teselas WMS públicas de EUMETView (Meteosat IR 10.8 µm) directamente en el móvil: sin ordenador y sin claves.
No entra en alarmas ni en el veredicto. Es una imagen ya coloreada (no da temperaturas). **No verificado contra el servidor real**: la capa IR se
detecta leyendo GetCapabilities (`core/Wms.kt`, `MapaIr.kt`) y la proyección EPSG:3857 es una suposición habitual de GeoServer.
