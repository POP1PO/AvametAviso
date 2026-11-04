package es.avamet.aviso

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import es.avamet.aviso.core.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var cabecera: TextView
    private lateinit var contenedor: FrameLayout
    private val paginas = ArrayList<View>()
    private lateinit var txtResumen: TextView
    private lateinit var txtEstaciones: TextView
    private lateinit var txtAeropuertos: TextView
    private lateinit var mapa: MapView
    private lateinit var btnVigilancia: Button
    private lateinit var btnSilenciar: Button
    private val oyente: () -> Unit = { refrescar() }
    private var centrado = false
    private lateinit var contResumen: LinearLayout
    private var registroAbierto = false
    @Volatile private var seeing: Cielo.Seeing? = null
    @Volatile private var seeingError: String? = null
    @Volatile private var seeingIntentoMs = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Paleta del programa de escritorio (tema oscuro)
    private val BG = 0xFF0D1117.toInt()
    private val PANEL = 0xFF161B22.toInt()
    private val PANEL2 = 0xFF1C2128.toInt()
    private val BORDE = 0xFF30363D.toInt()
    private val TEXTO = 0xFFE6EDF3.toInt()
    private val APAGADO = 0xFF8B949E.toInt()
    private val VERDE = 0xFF2EA043.toInt()
    private val AMBAR = 0xFFD29922.toInt()
    private val ROJO = 0xFFDA3633.toInt()
    private val AZUL = 0xFF58A6FF.toInt()
    private val MORADO = 0xFFA371F7.toInt()

    private fun texto(t: String, tam: Float = 15f, negrita: Boolean = false, color: Int = TEXTO): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = tam
        v.setTextColor(color)
        if (negrita) v.paint.isFakeBoldText = true
        v.setPadding(dp(4), dp(2), dp(4), dp(2))
        return v
    }

    private fun fondo(color: Int, borde: Int, radio: Int = 10): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radio).toFloat()
        g.setStroke(dp(1), borde)
        return g
    }

    private fun boton(t: String, acento: Int? = null, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setTextColor(if (acento != null) Color.WHITE else TEXTO)
        b.background = fondo(acento ?: PANEL2, acento ?: BORDE)
        b.setOnClickListener { f() }
        return b
    }

    private fun margenes(v: View, l: Int, t: Int, r: Int, b: Int): View {
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(dp(l), dp(t), dp(r), dp(b)) }
        return v
    }

    /** Tarjeta con título en minúsculas pequeñas, como en el programa de escritorio. */
    private fun tarjeta(titulo: String, borde: Int = BORDE, fondoColor: Int = PANEL): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.background = fondo(fondoColor, borde)
        margenes(c, 10, 8, 10, 0)
        c.addView(texto(titulo.uppercase(), 11f, true, APAGADO))
        return c
    }

    /** Cuadrícula de azulejos (etiqueta, valor, color del valor), 3 por fila. */
    private fun azulejos(items: List<Triple<String, String, Int>>): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        for (grupo in items.chunked(3)) {
            val fila = LinearLayout(this)
            fila.orientation = LinearLayout.HORIZONTAL
            for ((et, valor, color) in grupo) {
                val t = LinearLayout(this)
                t.orientation = LinearLayout.VERTICAL
                t.setPadding(dp(10), dp(8), dp(10), dp(8))
                t.background = fondo(PANEL2, BORDE, 8)
                t.addView(texto(et.uppercase(), 10f, false, APAGADO))
                t.addView(texto(valor, 17f, true, color))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dp(3), dp(3), dp(3), dp(3))
                fila.addView(t, lp)
            }
            for (i in grupo.size until 3) fila.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            col.addView(fila)
        }
        return col
    }

    private fun etiqueta(t: String, color: Int): TextView {
        val v = texto(t, 12f, true, Color.WHITE)
        v.background = fondo(color, color, 12)
        v.setPadding(dp(10), dp(3), dp(10), dp(3))
        return v
    }

    /** En los datos de AVAMET el municipio y el lugar vienen pegados ("MoncofaRiu Belcaire"): se separan solo al mostrarlos. */
    private fun bonito(n: Any?): String = n.toString().replace(Regex("([a-zà-ÿ])([A-ZÀ-Ý])"), "$1 · $2")

    private fun desplazable(contenido: View): ScrollView {
        val s = ScrollView(this)
        s.setBackgroundColor(BG)
        s.addView(contenido)
        return s
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Configuration.getInstance()
        cfg.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        cfg.userAgentValue = Descargas.USER_AGENT
        cfg.osmdroidBasePath = File(cacheDir, "osm")
        cfg.osmdroidTileCache = File(cacheDir, "osm/tiles")

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setBackgroundColor(BG)

        cabecera = texto("AVAMET Aviso", 18f, true)
        cabecera.setTextColor(Color.WHITE)
        cabecera.setBackgroundColor(APAGADO)
        cabecera.setPadding(dp(12), dp(28), dp(12), dp(12))
        raiz.addView(cabecera, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        contenedor = FrameLayout(this)
        raiz.addView(contenedor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // --- Resumen ---
        val colRes = LinearLayout(this)
        colRes.orientation = LinearLayout.VERTICAL
        colRes.setPadding(0, 0, 0, dp(16))
        btnVigilancia = boton("Iniciar vigilancia", VERDE) { alternarVigilancia() }
        colRes.addView(margenes(btnVigilancia, 10, 10, 10, 0))
        btnSilenciar = boton("🔇 Silenciar sonido", ROJO) { Alarma.silenciar() }
        colRes.addView(margenes(btnSilenciar, 10, 6, 10, 0))
        contResumen = LinearLayout(this)
        contResumen.orientation = LinearLayout.VERTICAL
        colRes.addView(contResumen)
        txtResumen = texto("")
        paginas.add(desplazable(colRes))

        // --- Mapa ---
        mapa = MapView(this)
        mapa.setTileSource(TileSourceFactory.MAPNIK)
        mapa.setMultiTouchControls(true)
        mapa.controller.setZoom(10.0)
        mapa.controller.setCenter(GeoPoint(39.99, -0.05))
        val pagMapa = FrameLayout(this)
        pagMapa.addView(mapa, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pagMapa.addView(construirControlIr(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).also { it.setMargins(dp(8), dp(8), dp(8), 0) })
        paginas.add(pagMapa)

        // --- Estaciones / Aeropuertos ---
        txtEstaciones = texto("")
        txtEstaciones.setPadding(dp(12), dp(10), dp(12), dp(10))
        paginas.add(desplazable(txtEstaciones))
        txtAeropuertos = texto("")
        txtAeropuertos.setPadding(dp(12), dp(10), dp(12), dp(10))
        paginas.add(desplazable(txtAeropuertos))

        // --- Ajustes ---
        paginas.add(desplazable(construirAjustes()))

        for (p in paginas) {
            p.visibility = View.GONE
            contenedor.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val barra = LinearLayout(this)
        barra.orientation = LinearLayout.HORIZONTAL
        val nombres = listOf("Resumen", "Mapa", "Estaciones", "Aeropuertos", "Ajustes")
        nombres.forEachIndexed { i, n ->
            val b = boton(n) { mostrar(i) }
            b.setTextColor(TEXTO)
            b.textSize = 11f
            b.setPadding(0, 0, 0, 0)
            barra.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        raiz.addView(barra)
        setContentView(raiz)
        mostrar(0)
    }

    private fun mostrar(i: Int) {
        paginas.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
    }

    // ---------------------------------------------------------------- Vigilancia y permisos
    private fun alternarVigilancia() {
        if (Estado.activo) { MonitorServicio.parar(this); return }
        val faltan = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.ACCESS_FINE_LOCATION)
            faltan.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            faltan.add(Manifest.permission.POST_NOTIFICATIONS)
        if (faltan.isNotEmpty()) requestPermissions(faltan.toTypedArray(), 1) else MonitorServicio.iniciar(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) MonitorServicio.iniciar(this)
        else Toast.makeText(this, "Sin permiso de ubicación no se puede vigilar tu posición", Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- Ajustes
    private fun construirAjustes(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(8), dp(8), dp(8), dp(8))

        col.addView(texto("Estación de referencia", 15f, true))
        val refTxt = texto("", 13f, false, APAGADO)
        fun refrescarRef() { refTxt.text = Config.estacionFija(this)?.let { "Manual: $it (no cambia sola aunque te muevas)" } ?: "Automática: la más cercana a tu posición" }
        refrescarRef()
        col.addView(refTxt)
        val refId = EditText(this)
        refId.hint = "Identificador o nombre (p. ej. c05m050e03)"
        refId.setTextColor(TEXTO); refId.setHintTextColor(APAGADO)
        col.addView(refId)
        col.addView(boton("Usar esta estación como referencia", null) {
            val q = refId.text.toString().trim()
            val r = Estaciones.buscar(this, q)
            when {
                q.isEmpty() -> Toast.makeText(this, "Escribe un identificador o parte del nombre", Toast.LENGTH_SHORT).show()
                r.size == 1 -> { Config.setEstacionFija(this, r[0].first); refrescarRef()
                    Toast.makeText(this, "Referencia: ${r[0].second}. Se aplica en el próximo ciclo.", Toast.LENGTH_LONG).show() }
                r.isEmpty() -> Toast.makeText(this, "No hay ninguna estación que coincida con «$q»", Toast.LENGTH_LONG).show()
                else -> {
                    val lista = r.take(60)
                    android.app.AlertDialog.Builder(this)
                        .setTitle("${r.size} estaciones coinciden con «$q»" + if (r.size > lista.size) " (primeras ${lista.size})" else "")
                        .setItems(lista.map { "${it.second}  ·  ${it.first}" }.toTypedArray()) { _, i ->
                            Config.setEstacionFija(this, lista[i].first); refrescarRef()
                            Toast.makeText(this, "Referencia: ${lista[i].second}. Se aplica en el próximo ciclo.", Toast.LENGTH_LONG).show()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
        })
        col.addView(boton("Volver a automática", null) { Config.setEstacionFija(this, null); refrescarRef()
            Toast.makeText(this, "Referencia automática", Toast.LENGTH_SHORT).show() })

        col.addView(texto("Consulta cada (minutos):", 15f, true))
        val intervalo = EditText(this)
        intervalo.inputType = InputType.TYPE_CLASS_NUMBER
        intervalo.setText(Config.intervaloMin(this).toString())
        intervalo.setTextColor(TEXTO)
        col.addView(intervalo)
        col.addView(boton("Guardar intervalo", null) {
            val v = intervalo.text.toString().toIntOrNull()
            if (v == null) Toast.makeText(this, "Número no válido", Toast.LENGTH_SHORT).show()
            else {
                Config.setIntervaloMin(this, v)
                Toast.makeText(this, "Guardado: ${Config.intervaloMin(this)} min. Se aplica al volver a iniciar la vigilancia.", Toast.LENGTH_LONG).show()
            }
        })

        val sonido = CheckBox(this)
        sonido.text = "Alarma con sonido (se puede silenciar)"
        sonido.isChecked = Config.alarmaSonido(this)
        sonido.setTextColor(TEXTO)
        sonido.setOnCheckedChangeListener { _, v -> Config.setAlarmaSonido(this, v) }
        col.addView(sonido)
        val pantalla = CheckBox(this)
        pantalla.text = "Encender la pantalla en caso de peligro"
        pantalla.isChecked = Config.alarmaPantalla(this)
        pantalla.setTextColor(TEXTO)
        pantalla.setOnCheckedChangeListener { _, v -> Config.setAlarmaPantalla(this, v) }
        col.addView(pantalla)
        col.addView(boton("Probar la alarma", AMBAR) {
            Alarma.crearCanales(this)
            Alarma.disparar(this, listOf("Prueba de alarma"))
        })

        col.addView(texto("Para que no se detenga con la pantalla apagada", 15f, true))
        col.addView(texto("Hay que quitar las restricciones de batería a esta app. Los nombres de los menús cambian según la marca y la versión; " +
            "si un botón no abre nada, usa «Ajustes de la app» y busca Batería / Inicio automático.", 13f))
        col.addView(boton("Batería: permitir sin restricciones") { pedirSinRestriccionesBateria() })
        col.addView(boton("Ajustes de la app") { abrirAjustesApp() })
        col.addView(boton("Inicio automático / batería del fabricante") { abrirAjustesFabricante() })
        if (Build.VERSION.SDK_INT >= 34) col.addView(boton("Permitir notificaciones a pantalla completa") {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { abrirAjustesApp() }
        })

        // --- Actualizaciones desde GitHub ---
        col.addView(texto("Actualizaciones", 15f, true))
        val actTxt = texto("Versión instalada: ${Actualizador.nombreVersion(this)} (nº ${Actualizador.versionInstalada(this)}). " +
            "La app mira sola si hay una nueva al abrirla (como mucho cada 6 h). Android pide confirmar la instalación con un toque; " +
            "se instala encima de la anterior, sin borrar nada.", 12f, false, APAGADO)
        col.addView(actTxt)
        col.addView(boton("Buscar actualización ahora") { buscarActualizacion(true, actTxt) })

        col.addView(texto("Datos: AVAMET (avamet.org) y METAR de aviationweather.gov. El mapa es de OpenStreetMap. " +
            "Esta app es una ayuda: no sustituye a un sensor de lluvia propio.", 12f))
        return col
    }

    private fun pedirSinRestriccionesBateria() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Ya está sin restricciones de optimización de batería", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun abrirAjustesApp() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** Pantallas de "inicio automático / batería" de algunos fabricantes. Los nombres de componente NO están verificados: si no existen, se abre Ajustes de la app. */
    private fun abrirAjustesFabricante() {
        val candidatos = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),              // Xiaomi
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),        // Oppo
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),                          // Oppo (antiguo)
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),                     // Samsung
        )
        for (c in candidatos) {
            try { startActivity(Intent().setComponent(c)); return } catch (_: Exception) { }
        }
        Toast.makeText(this, "No se encontró la pantalla de tu fabricante; se abren los ajustes de la app", Toast.LENGTH_LONG).show()
        abrirAjustesApp()
    }

    // ---------------------------------------------------------------- Pantallas
    private fun hora(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

    private fun colorNivel(n: String) = when (n) { "PELIGRO" -> ROJO; "VIGILANCIA" -> AMBAR; else -> VERDE }

    private fun registroTarjeta(): View {
        val c = tarjeta("Registro (toca para ${if (registroAbierto) "ocultar" else "mostrar"})")
        c.setOnClickListener { registroAbierto = !registroAbierto; refrescar() }
        if (registroAbierto) c.addView(texto(Estado.registro(), 11f, false, APAGADO))
        return c
    }

    private fun refrescar() {
        val r = Estado.resultado
        btnVigilancia.text = if (Estado.activo) "Detener vigilancia" else "Iniciar vigilancia"
        btnVigilancia.background = fondo(if (Estado.activo) ROJO else VERDE, if (Estado.activo) ROJO else VERDE)
        btnSilenciar.visibility = if (Estado.sonando) View.VISIBLE else View.GONE
        val (color, titulo) = when {
            !Estado.activo -> Pair(APAGADO, "Vigilancia detenida")
            r == null -> Pair(APAGADO, "Vigilando… (esperando primer resultado)")
            r.nivel == "PELIGRO" -> Pair(ROJO, "⚠️ PELIGRO")
            !r.datosFiables -> Pair(0xFFEF6C00.toInt(), "⚠️ SIN DATOS FIABLES")
            r.nivel == "VIGILANCIA" -> Pair(AMBAR, "VIGILANCIA")
            else -> Pair(VERDE, "SEGURO")
        }
        cabecera.setBackgroundColor(color)
        cabecera.text = titulo

        contResumen.removeAllViews()
        if (Estado.mensaje.isNotEmpty()) {
            val c = tarjeta("Aviso", AMBAR)
            c.addView(texto(Estado.mensaje, 14f))
            contResumen.addView(c)
        }
        if (r == null) {
            val c = tarjeta("Estado")
            c.addView(texto(if (Estado.ubicacion == null) "Sin posición todavía. Activa el GPS y pulsa «Iniciar vigilancia»." else "Posición obtenida. Descargando datos…", 14f))
            contResumen.addView(c)
            contResumen.addView(registroTarjeta())
            txtEstaciones.text = ""; txtAeropuertos.text = ""
            return
        }
        val a = r.analisis
        val nivelFinal = if (!r.datosFiables) "SIN DATOS" else r.nivel

        // Tarjeta de estado general
        val cEstado = tarjeta("Estado actual", color)
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        fila.addView(etiqueta(nivelFinal, if (!r.datosFiables) 0xFFEF6C00.toInt() else colorNivel(r.nivel)))
        fila.addView(texto("Actualizado ${hora(r.ahoraMs)}", 12f, false, APAGADO))
        cEstado.addView(fila)
        cEstado.addView(texto("Posición ${fmt(r.lat, 4)}, ${fmt(r.lon, 4)}", 12f, false, APAGADO))
        for (m in r.motivos) cEstado.addView(texto("• $m", 14f, false, if (r.nivel == "PELIGRO") 0xFFFF7B72.toInt() else TEXTO))
        val aviso = a["aviso_estacion"] as String
        if (aviso.isNotEmpty()) cEstado.addView(texto(aviso, 13f, false, AMBAR))
        contResumen.addView(cEstado)

        // Estación de referencia: azulejos
        val ref = a["referencia"] as? Map<*, *>
        if (ref != null) {
            val c = tarjeta("Estación de referencia · ${bonito(ref["nombre"])} (${ref["distancia_km"]} km)")
            @Suppress("UNCHECKED_CAST")
            val d = ref["datos"] as? Map<String, Any?>
            val est = ref["estado"] as Map<*, *>
            if (d != null) {
                fun v(k: String) = (d[k] ?: "?").toString()
                c.addView(azulejos(listOf(
                    Triple("Temperatura", v("Temperatura"), 0xFFFFA657.toInt()),
                    Triple("Humedad", v("Humedad"), AZUL),
                    Triple("Presión", v("Presión"), TEXTO),
                    Triple("Viento", v("Viento"), VERDE),
                    Triple("Racha máx.", v("Viento máximo"), AMBAR),
                    Triple("Lluvia hoy", v("Lluvia hoy"), MORADO),
                )))
            }
            val ok = est["estado"] == "ACTIVA"
            c.addView(etiqueta("Lectura: ${est["estado"]}${if (est["edad_min"] != null) " · hace ${est["edad_min"]} min" else ""}", if (ok) VERDE else 0xFFEF6C00.toInt()))
            contResumen.addView(c)
        }

        // Movimiento de las nubes: brújula
        val mov = a["movimiento"] as Map<*, *>
        val cMov = tarjeta("Movimiento de las nubes")
        val filaMov = LinearLayout(this)
        filaMov.orientation = LinearLayout.HORIZONTAL
        filaMov.gravity = Gravity.CENTER_VERTICAL
        val grados = (mov["grados_destino"] as? Number)?.toDouble()
        val brujula = BrujulaView(this, grados, color, r.lat, r.lon)
        filaMov.addView(brujula, LinearLayout.LayoutParams(dp(110), dp(110)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        if (grados != null) {
            val (gO, nO) = origenDeNubes(grados)
            col.addView(texto("VIENEN DEL $nO · $gO°", 20f, true))
            col.addView(texto("Se mueven hacia el ${mov["direccion_destino"] ?: "—"} (${fmt(grados, 0)}°)", 13f, false))
            col.addView(texto("${mov["velocidad_kmh"] ?: "?"} km/h · ${mov["categoria"] ?: "?"}", 14f, false, APAGADO))
            col.addView(texto("Con el móvil plano, la N roja señala el norte (norte verdadero: se corrige la declinación magnética de tu zona) y la flecha con la nube señala de dónde vienen. Si no gira, tu móvil no tiene brújula: el norte queda arriba.", 11f, false, APAGADO))
            val calib = texto("", 12f, true, AMBAR)
            brujula.alCambiarPrecision = { nivel ->
                calib.text = when {
                    nivel >= 3 -> "✔ Brújula calibrada"
                    nivel == 2 -> "Brújula: precisión media. Para afinar, mueve el móvil dibujando un 8 en el aire."
                    else -> "⚠ Brújula SIN CALIBRAR: mueve el móvil dibujando un 8 en el aire varias veces; hasta entonces el norte puede salir desviado."
                }
                calib.setTextColor(if (nivel >= 3) VERDE else AMBAR)
            }
            col.addView(calib)
        } else {
            col.addView(texto("Dirección desconocida", 20f, true))
            col.addView(texto("No hay viento suficiente para estimar de dónde vienen las nubes.", 12f, false, APAGADO))
        }
        filaMov.addView(col)
        cMov.addView(filaMov)
        contResumen.addView(cMov)

        // Cielo para observar (solo con datos de la app)
        val ci = try { Cielo.evaluar(r) } catch (e: Throwable) { null }
        val cCielo = tarjeta("Cielo para observar (ahora)", if (ci != null) colorNivel(when (ci.veredicto) { "MALO" -> "PELIGRO"; "REGULAR" -> "VIGILANCIA"; else -> "SEGURO" }) else BORDE)
        if (ci != null) {
            cCielo.addView(etiqueta(ci.veredicto, when (ci.veredicto) { "MALO" -> ROJO; "REGULAR" -> AMBAR; else -> VERDE }))
            for (x in ci.lineas) cCielo.addView(texto(x, 13f, false, if (x.startsWith("Estado de ahora") || x.startsWith("Seeing")) APAGADO else TEXTO))
        } else cCielo.addView(texto("No se pudo valorar el cielo", 13f, false, APAGADO))
        // Seeing: pronóstico externo de 7Timer, cada 60 min como máximo (si falla, reintento a los 10 min)
        val ahoraS = System.currentTimeMillis()
        if (ahoraS - seeingIntentoMs > 60 * 60_000L) {
            seeingIntentoMs = ahoraS
            val la = r.lat; val lo = r.lon
            Thread {
                try { seeing = Cielo.seeing(la, lo, System.currentTimeMillis()); seeingError = null }
                catch (e: Throwable) { seeingError = "${e.javaClass.simpleName}: ${e.message}"; seeingIntentoMs = System.currentTimeMillis() - 50 * 60_000L }
                runOnUiThread { try { refrescar() } catch (_: Throwable) { } }
            }.also { it.isDaemon = true }.start()
        }
        val sg = seeing
        cCielo.addView(texto("SEEING Y TRANSPARENCIA (7TIMER)", 11f, true, APAGADO))
        if (sg != null) for (x in sg.lineas) cCielo.addView(texto(x, 13f, false, if (x.startsWith("Pronóstico externo")) APAGADO else TEXTO))
        else cCielo.addView(texto(seeingError?.let { "No disponible ($it)" } ?: "Consultando 7Timer…", 13f, false, APAGADO))
        contResumen.addView(cCielo)

        // Radar: solo si hay avisos predictivos
        val pred = a["alertas_predictivas"].l()
        if (pred.isNotEmpty()) {
            val c = tarjeta("Radar · nubes en camino", ROJO, 0xFF2A1215.toInt())
            for (x in pred) c.addView(texto("⚠️ $x", 14f, true, 0xFFFF7B72.toInt()))
            contResumen.addView(c)
        }

        // Estaciones cercanas con datos recientes
        val alt = a["alternativas"].l()
        if (alt.isNotEmpty()) {
            val c = tarjeta("Estaciones cercanas con datos recientes")
            for (x in alt) { val m = x.d(); c.addView(texto("• ${m["nombre"]} · ${m["distancia_km"]} km · hace ${m["edad_min"]} min", 13f)) }
            contResumen.addView(c)
        }

        // Aeropuertos (compacto)
        val aerosR = a["aeropuertos"].l().map { it.d() }
        if (aerosR.isNotEmpty()) {
            val c = tarjeta("Aeropuertos (METAR)")
            for (ap in aerosR) {
                val m = r.metarMostrar[ap["icao"] as String]?.second
                val txt = if (m == null) "sin METAR" else "${m["temperatura"] ?: "?"} °C · ${m["viento_nudos"] ?: "?"} kt · ${m["fenomenos"]}"
                c.addView(texto("${ap["icao"]} · ${ap["distancia_km"]} km — $txt", 13f))
            }
            contResumen.addView(c)
        }

        if (r.errores.isNotEmpty()) {
            val c = tarjeta("Problemas de red", AMBAR)
            for (e in r.errores) c.addView(texto("• $e", 13f, false, AMBAR))
            contResumen.addView(c)
        }
        contResumen.addView(texto("Peticiones en este ciclo: ${r.descargas}", 11f, false, APAGADO))
        contResumen.addView(registroTarjeta())

        // Estaciones
        val radio = a["radio"].l().map { it.d() }
        val se = StringBuilder("Estaciones a menos de 25 km (${radio.size}). Solo se consultan las que están en el camino de las nubes.\n\n")
        for (e in radio) {
            val estado = when {
                e["estado"] == "SIN_LECTURA" && e["lluvia_hoy"] == null -> "no consultada"
                e["lluvia_reciente"] == true -> "🌧 LLUEVE (subió el acumulado en 30 min)"
                e["estado"] == "ACTIVA" -> "activa"
                e["estado"] == "CONGELADA" -> "sensor parado"
                else -> e["estado"].toString()
            }
            se.append("• ${bonito(e["nombre"])} — ${e["distancia_km"]} km — $estado")
            if (e["lluvia_hoy"] != null) se.append(" · hoy ${e["lluvia_hoy"]} mm")
            if (e["viento"] != null) se.append(" · ${e["viento"]}")
            se.append("\n")
        }
        txtEstaciones.text = se.toString()

        // Aeropuertos
        val sa = StringBuilder()
        val aeros = a["aeropuertos"].l().map { it.d() }
        if (aeros.isEmpty()) sa.append("Ningún aeropuerto con METAR a menos de 120 km.")
        for (ap in aeros) {
            val icao = ap["icao"] as String
            val guardado = r.metarMostrar[icao]
            sa.append("${ap["nombre"]} ($icao) — ${ap["distancia_km"]} km\n")
            if (guardado == null) { sa.append("  sin METAR\n\n"); continue }
            val m = guardado.second
            val edad = (r.ahoraMs - guardado.first) / 60_000
            sa.append("  Temp ${m["temperatura"] ?: "?"} °C · rocío ${m["punto_rocio"] ?: "?"} °C · viento ${m["viento_nudos"] ?: "?"} kt de ${m["viento_dir"] ?: "variable"}°\n")
            sa.append("  Fenómenos: ${m["fenomenos"]}\n")
            val nub = m["nubosidad"].d()
            for (nivel in listOf("baja", "media", "alta")) for (x in nub[nivel].l()) sa.append("  Nubes $nivel: $x\n")
            sa.append("  Descargado hace $edad min" + (if (edad > 30) " (más de 30 min: NO se usa para predecir)" else "") + " · METAR ${m["actualizado"] ?: "?"}\n\n")
        }
        txtAeropuertos.text = sa.toString()

        dibujarMapa(r)
    }

    // ---------------------------------------------------------------- Mapa
    private fun puntoDestino(lat: Double, lon: Double, rumboGrados: Double, km: Double): GeoPoint {
        val d = km / R_TIERRA_KM
        val b = rad(rumboGrados)
        val la1 = rad(lat)
        val lo1 = rad(lon)
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(b))
        val lo2 = lo1 + atan2(sin(b) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        return GeoPoint(deg(la2), deg(lo2))
    }

    private fun circulo(color: Int, tam: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        g.setStroke(2, Color.WHITE)
        g.setSize(dp(tam), dp(tam))
        return g
    }

    private fun marca(lat: Double, lon: Double, titulo: String, color: Int, tam: Int, detalle: String = ""): Marker {
        val m = Marker(mapa)
        m.position = GeoPoint(lat, lon)
        m.icon = circulo(color, tam)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.title = titulo
        m.snippet = detalle
        return m
    }

    private fun linea(color: Int, ancho: Float, vararg p: GeoPoint): Polyline {
        val l = Polyline(mapa)
        l.setPoints(p.toList())
        l.outlinePaint.color = color
        l.outlinePaint.strokeWidth = ancho
        l.outlinePaint.strokeCap = Paint.Cap.ROUND
        return l
    }

    private fun flecha(overlays: MutableList<org.osmdroid.views.overlay.Overlay>, desdeLat: Double, desdeLon: Double, haciaLat: Double, haciaLon: Double, color: Int, ancho: Float) {
        overlays.add(linea(color, ancho, GeoPoint(desdeLat, desdeLon), GeoPoint(haciaLat, haciaLon)))
        val rumbo = rumboGrados(desdeLat, desdeLon, haciaLat, haciaLon)
        val largo = max(1.0, haversineKm(desdeLat, desdeLon, haciaLat, haciaLon) * 0.18)
        for (giro in listOf(150.0, -150.0)) {
            val p = puntoDestino(haciaLat, haciaLon, rumbo + giro, largo)
            overlays.add(linea(color, ancho, GeoPoint(haciaLat, haciaLon), p))
        }
    }

    // ---------------------------------------------------------------- Capa «Nubes IR» (solo mostrar)
    private var irOverlay: org.osmdroid.views.overlay.TilesOverlay? = null
    private var irOpacidad = 60
    private var irTiempo: String? = null          // barrido que muestra la capa (p. ej. "2026-11-03T19:00:00Z"); null = sin hora
    private lateinit var irEstado: TextView
    private lateinit var irBotonAnim: android.widget.Button
    private var irAnim: List<org.osmdroid.views.overlay.TilesOverlay> = emptyList()     // una capa por barrido mientras dura la animación
    private var irAnimIdx = 0
    private var irAnimOn = false
    private val irHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val irPaso = object : Runnable {
        override fun run() {
            if (!irAnimOn || irAnim.isEmpty()) return
            irMostrarFotograma(irAnimIdx)
            val ultimo = irAnimIdx == irAnim.size - 1
            irAnimIdx = if (ultimo) 0 else irAnimIdx + 1
            irHandler.postDelayed(this, if (ultimo) 1800L else 650L)          // pausa más larga en la imagen más reciente
        }
    }

    private fun irMostrarFotograma(i: Int) {
        irAnim.forEachIndexed { k, o -> if (k == i) MapaIr.ajustarOpacidad(o, irOpacidad) else MapaIr.ocultar(o) }
        val horas = Wms.fotogramas(irTiempo)
        irEstado.text = "Animación de las últimas ${horas.size / 4} h: imagen de las ${horas.getOrNull(i)?.let { MapaIr.horaLocal(it) } ?: "?"}"
        mapa.invalidate()
    }

    /** Pone en marcha la animación: una capa por barrido (todas dibujadas a la vez pero transparentes salvo la que toca). */
    private fun irIniciarAnimacion() {
        val capa = MapaIr.capaGuardada(this)
        val horas = Wms.fotogramas(irTiempo)
        if (irOverlay == null || horas.size < 2) { irEstado.text = "Aún no hay imagen para animar: espera a que cargue la capa."; return }
        irDetenerAnimacion()
        MapaIr.ocultar(irOverlay!!)
        irAnim = horas.map { t -> MapaIr.crearOverlay(this, mapa, capa, t, irOpacidad).also { MapaIr.ocultar(it); mapa.overlays.add(0, it) } }
        irAnimOn = true; irAnimIdx = 0
        irBotonAnim.text = "⏸ Parar animación"
        irHandler.post(irPaso)
    }

    /** Para la animación, quita sus capas y vuelve a la imagen más reciente. */
    private fun irDetenerAnimacion() {
        val estabaActiva = irAnimOn || irAnim.isNotEmpty()
        irAnimOn = false
        irHandler.removeCallbacks(irPaso)
        irAnim.forEach { MapaIr.quitar(mapa, it) }
        irAnim = emptyList()
        if (::irBotonAnim.isInitialized) irBotonAnim.text = "▶ Animar nubes (últimas 2 h)"
        if (estabaActiva) {
            irOverlay?.let { MapaIr.ajustarOpacidad(it, irOpacidad) }
            if (::irEstado.isInitialized) irEstado.text = "Capa IR: ${MapaIr.capaGuardada(this)}\n" + textoHoraIr(irTiempo)
            mapa.invalidate()
        }
    }

    private fun textoHoraIr(tiempo: String?): String =
        if (tiempo != null) "Imagen de las ${MapaIr.horaLocal(tiempo)} (último barrido publicado)"
        else "No se pudo saber la hora del último barrido: el servidor puede mezclar teselas antiguas"

    private fun construirControlIr(): View {
        val caja = LinearLayout(this)
        caja.orientation = LinearLayout.VERTICAL
        caja.setPadding(dp(10), dp(8), dp(10), dp(8))
        caja.background = fondo(0xE6161B22.toInt(), BORDE)
        val chk = android.widget.CheckBox(this)
        chk.text = "🛰️ Nubes IR (satélite)"
        chk.setTextColor(TEXTO)
        chk.isChecked = MapaIr.visible(this)
        val barra = android.widget.SeekBar(this)
        barra.max = 100; barra.progress = irOpacidad
        irEstado = texto("", 11f, false, APAGADO)
        irEstado.text = "Imagen de EUMETSAT, solo informativa (no entra en las alarmas)."
        irBotonAnim = android.widget.Button(this)
        irBotonAnim.text = "▶ Animar nubes (últimas 2 h)"
        irBotonAnim.isAllCaps = false
        irBotonAnim.setOnClickListener { if (irAnimOn) irDetenerAnimacion() else irIniciarAnimacion() }
        val irAtribucion = texto("© EUMETSAT Meteosat data ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} · Meteosat IR 10,8 µm", 11f, false, APAGADO)
        caja.addView(chk); caja.addView(barra); caja.addView(irBotonAnim); caja.addView(irEstado); caja.addView(irAtribucion)
        val detalle = listOf<View>(barra, irBotonAnim, irEstado, irAtribucion)
        fun aplicar(on: Boolean) {
            detalle.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
            MapaIr.setVisible(this, on)
            if (on) activarIr() else { irDetenerAnimacion(); MapaIr.quitar(mapa, irOverlay); irOverlay = null; mapa.invalidate() }
        }
        chk.setOnCheckedChangeListener { _, on -> aplicar(on) }
        barra.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) { irOpacidad = v.coerceIn(10, 100); if (irAnimOn) irMostrarFotograma(maxOf(0, if (irAnimIdx == 0) irAnim.size - 1 else irAnimIdx - 1)) else irOverlay?.let { MapaIr.ajustarOpacidad(it, irOpacidad); mapa.invalidate() } }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        detalle.forEach { it.visibility = if (chk.isChecked) View.VISIBLE else View.GONE }
        if (chk.isChecked) mapa.post { activarIr() }
        return caja
    }

    private fun activarIr() {
        irEstado.text = "Buscando la capa IR en EUMETSAT…"
        Thread {
            val (capa, msg) = MapaIr.detectarCapa(this)
            val tiempo = capa?.let { MapaIr.ultimoTiempo(it) }
            val prueba = if (capa != null) MapaIr.sonda(capa, tiempo) else ""
            runOnUiThread {
                irEstado.text = if (capa == null) msg else listOf(msg, textoHoraIr(tiempo), prueba).filter { it.isNotEmpty() }.joinToString("\n")
                if (capa == null || !MapaIr.visible(this)) return@runOnUiThread
                irDetenerAnimacion()
                MapaIr.quitar(mapa, irOverlay)
                irTiempo = tiempo
                irOverlay = MapaIr.crearOverlay(this, mapa, capa, tiempo, irOpacidad).also { mapa.overlays.add(0, it) }
                mapa.invalidate()
            }
        }.start()
    }

    /** Al volver a la app: pregunta solo la hora del último barrido (~7 kB) y renueva la capa si hay imagen nueva. */
    private fun actualizarIr() {
        val capa = MapaIr.capaGuardada(this)
        Thread {
            val tiempo = MapaIr.ultimoTiempo(capa)
            runOnUiThread {
                if (irOverlay == null || !MapaIr.visible(this)) return@runOnUiThread
                if (tiempo != null && tiempo == irTiempo) return@runOnUiThread       // misma imagen: nada que hacer
                irDetenerAnimacion()
                MapaIr.quitar(mapa, irOverlay)
                irTiempo = tiempo
                irOverlay = MapaIr.crearOverlay(this, mapa, capa, tiempo, irOpacidad).also { mapa.overlays.add(0, it) }
                irEstado.text = "Capa IR: $capa\n" + textoHoraIr(tiempo)
                mapa.invalidate()
            }
        }.start()
    }

    private fun dibujarMapa(r: Resultado) {
        val ov = mapa.overlays
        ov.clear()
        irOverlay?.let { ov.add(0, it) }          // clear() quita también la capa IR: se vuelve a poner debajo de los marcadores
        irAnim.forEach { ov.add(0, it) }          // ...y las de la animación, si está en marcha
        val a = r.analisis
        val mov = a["movimiento"] as Map<*, *>

        // Estaciones
        for (e0 in a["radio"].l()) {
            val e = e0.d()
            val color = when {
                e["lluvia_reciente"] == true -> Color.RED
                e["estado"] == "ACTIVA" -> Color.parseColor("#2E7D32")
                e["estado"] == "CONGELADA" -> Color.parseColor("#EF6C00")
                else -> Color.LTGRAY
            }
            ov.add(marca(e["lat"].dbl(), e["lon"].dbl(), e["nombre"].toString(), color, 12, "${e["distancia_km"]} km · ${e["viento"] ?: "sin lectura"}"))
        }
        // Aeropuertos
        for (ap0 in a["aeropuertos"].l()) {
            val ap = ap0.d()
            val m = ap["metar"] as? Map<*, *>
            val malo = m != null && m["fenomenos"] != "Ninguno"
            ov.add(marca(ap["lat"].dbl(), ap["lon"].dbl(), "${ap["icao"]} ${ap["nombre"]}", if (malo) Color.parseColor("#6A1B9A") else Color.parseColor("#1565C0"), 18,
                "${ap["distancia_km"]} km" + (if (m != null) " · ${m["fenomenos"]}" else " · sin METAR")))
        }
        // Avisos: de la estación/aeropuerto con lluvia hacia mí
        for (x in a["radares"].l()) { val q = x.d(); flecha(ov, q["lat"].dbl(), q["lon"].dbl(), r.lat, r.lon, Color.RED, 7f) }
        for (x in a["radares_aeropuertos"].l()) {
            val q = x.d()
            flecha(ov, q["lat"].dbl(), q["lon"].dbl(), r.lat, r.lon, if (q["confirmado"] == true) Color.RED else Color.parseColor("#6A1B9A"), 7f)
        }
        // Dirección general de las nubes: flecha larga que llega a mi posición
        if (mov["velocidad_kmh"].dbl() > 0) {
            val destino = mov["grados_destino"].dbl()
            val origen = puntoDestino(r.lat, r.lon, (destino + 180) % 360, 40.0)
            flecha(ov, origen.latitude, origen.longitude, r.lat, r.lon, Color.parseColor("#0D47A1"), 10f)
        }
        // Yo
        ov.add(marca(r.lat, r.lon, "Tú", Color.BLACK, 16))
        if (!centrado) { mapa.controller.setCenter(GeoPoint(r.lat, r.lon)); mapa.controller.setZoom(10.0); centrado = true }
        mapa.invalidate()
    }

    // ---------------------------------------------------------------- Actualizaciones
    private var avisoVersionMostrado = 0L

    /** Mira en GitHub si hay una versión más nueva. `manual`: el usuario lo pidió (siempre se contesta); si no, solo se avisa si hay nueva. */
    private fun buscarActualizacion(manual: Boolean, estado: TextView? = null) {
        if (manual) estado?.text = "Buscando en GitHub…"
        Thread {
            val res = try { Result.success(Actualizador.buscar(this)) } catch (e: Exception) { Result.failure<es.avamet.aviso.core.VersionPublicada?>(e) }
            if (res.isSuccess) Actualizador.marcarConsulta(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val nueva = res.getOrNull()
                when {
                    res.isFailure -> if (manual) estado?.text = "No se pudo consultar GitHub (${(res.exceptionOrNull() as? Exception)?.let { Descargas.textoError(it) } ?: "error"})."
                    nueva == null -> if (manual) estado?.text = "Ya tienes la última versión (nº ${Actualizador.versionInstalada(this)})."
                    manual || nueva.codigo != avisoVersionMostrado -> {
                        avisoVersionMostrado = nueva.codigo
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Hay una versión nueva (nº ${nueva.codigo})")
                            .setMessage("Tienes la nº ${Actualizador.versionInstalada(this)}. Se descargará (~3 MB) y Android te pedirá confirmar la instalación; tus ajustes se conservan.")
                            .setPositiveButton("Descargar e instalar") { _, _ -> descargarEInstalar(nueva, estado) }
                            .setNegativeButton("Ahora no", null).show()
                    }
                }
            }
        }.start()
    }

    private fun descargarEInstalar(v: es.avamet.aviso.core.VersionPublicada, estado: TextView?) {
        Toast.makeText(this, "Descargando la versión nº ${v.codigo}…", Toast.LENGTH_SHORT).show()
        estado?.text = "Descargando la versión nº ${v.codigo}…"
        Thread {
            val res = try { Result.success(Actualizador.descargar(this, v)) } catch (e: Exception) { Result.failure<java.io.File>(e) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val apk = res.getOrNull()
                if (apk == null) { val m = "No se pudo descargar la actualización (${(res.exceptionOrNull() as? Exception)?.let { Descargas.textoError(it) } ?: "error"})."; estado?.text = m; Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
                else try { val m = Actualizador.instalar(this, apk); estado?.text = m; Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
                catch (e: Exception) { estado?.text = "No se pudo abrir el instalador: ${e.message}" }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume(); mapa.onResume(); Estado.escuchar(oyente); refrescar()
        if (irOverlay != null) actualizarIr()       // renueva la capa IR si EUMETSAT ha publicado otro barrido (cada 15 min)
        if (Actualizador.tocaConsultar(this)) buscarActualizacion(false)
    }
    override fun onPause() { irDetenerAnimacion(); Estado.dejarDeEscuchar(oyente); mapa.onPause(); super.onPause() }
}


/** Brújula REAL: usa el sensor de orientación del móvil para que la N roja señale siempre el norte verdadero (sea cual sea hacia
 *  dónde gires el móvil) y la flecha señale DE DÓNDE VIENEN las nubes en el mundo real. `grados` = hacia dónde van las nubes
 *  (0° = norte); el origen es el opuesto (+180°). Si el móvil no tiene sensor, el norte queda arriba. */
class BrujulaView(ctx: Context, private val grados: Double?, private val acento: Int, lat: Double = 0.0, lon: Double = 0.0) : View(ctx), android.hardware.SensorEventListener {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Declinación magnética del lugar (grados, + al este): el sensor da el norte MAGNÉTICO y se pasa al verdadero sumándola. */
    private val declinacion: Float = try { android.hardware.GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()).declination } catch (e: Exception) { 0f }
    /** Se llama con la precisión del sensor (0 = no fiable ... 3 = alta) cada vez que cambia. */
    var alCambiarPrecision: ((Int) -> Unit)? = null
    private var precision = -1
    private var rumboMovil: Float? = null          // hacia dónde mira el móvil, en grados desde el norte (null = sin sensor)
    private val matriz = FloatArray(9); private val remap = FloatArray(9); private val orient = FloatArray(3)

    private fun gestor() = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val g = gestor() ?: return
        g.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)?.let { g.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
    }
    override fun onDetachedFromWindow() { gestor()?.unregisterListener(this); super.onDetachedFromWindow() }
    override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) { avisarPrecision(a) }
    private fun avisarPrecision(a: Int) { if (a != precision) { precision = a; alCambiarPrecision?.invoke(a) } }
    override fun onSensorChanged(e: android.hardware.SensorEvent) {
        avisarPrecision(e.accuracy)
        android.hardware.SensorManager.getRotationMatrixFromVector(matriz, e.values)
        // Móvil plano (como una brújula): azimut directo. Móvil en vertical: se usa la dirección a la que apunta la parte de atrás.
        val vertical = kotlin.math.abs(matriz[8]) < 0.6f
        val m = if (vertical) { android.hardware.SensorManager.remapCoordinateSystem(matriz, android.hardware.SensorManager.AXIS_X, android.hardware.SensorManager.AXIS_Z, remap); remap } else matriz
        android.hardware.SensorManager.getOrientation(m, orient)
        val nuevo = ((Math.toDegrees(orient[0].toDouble()) + declinacion + 360.0) % 360.0).toFloat()
        val viejo = rumboMovil
        // suavizado (por el camino corto, para que no dé saltos al pasar de 359° a 0°)
        rumboMovil = if (viejo == null) nuevo else (viejo + ((nuevo - viejo + 540f) % 360f - 180f) * 0.25f + 360f) % 360f
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2; val r = minOf(w, h) / 2 - 6f
        val giro = -(rumboMovil ?: 0f)             // el dial gira al contrario que el móvil: la N queda en el norte real
        p.style = Paint.Style.FILL; p.color = 0xFF1C2128.toInt(); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF30363D.toInt(); c.drawCircle(cx, cy, r, p)
        // Dial (N, E, S, O) girado
        c.save(); c.rotate(giro, cx, cy)
        p.style = Paint.Style.FILL; p.color = 0xFFF85149.toInt()
        val tri = Path(); tri.moveTo(cx, cy - r + 2f); tri.lineTo(cx - r * 0.1f, cy - r * 0.78f); tri.lineTo(cx + r * 0.1f, cy - r * 0.78f); tri.close()
        c.drawPath(tri, p)
        p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true; p.textSize = r * 0.34f
        c.drawText("N", cx, cy - r + p.textSize + r * 0.2f, p)
        p.isFakeBoldText = false; p.color = 0xFF8B949E.toInt(); p.textSize = r * 0.26f
        c.drawText("S", cx, cy + r - 6f, p)
        c.drawText("E", cx + r - p.textSize * 0.5f, cy + p.textSize * 0.35f, p)
        c.drawText("O", cx - r + p.textSize * 0.5f, cy + p.textSize * 0.35f, p)
        c.restore()
        // Marca fija: hacia donde apunta la parte de arriba del móvil
        if (rumboMovil != null) {
            p.color = 0xFFC9D1D9.toInt(); p.style = Paint.Style.FILL
            val t = Path(); t.moveTo(cx, cy - r - 4f); t.lineTo(cx - r * 0.07f, cy - r + r * 0.07f); t.lineTo(cx + r * 0.07f, cy - r + r * 0.07f); t.close()
            c.drawPath(t, p)
        }
        if (grados == null) return
        val color = if (acento == 0xFF8B949E.toInt()) 0xFF58A6FF.toInt() else acento
        // Flecha hacia el ORIGEN de las nubes (norte real + 180° del destino), girada con el dial
        c.save(); c.rotate(((grados + 180.0) % 360.0).toFloat() + giro, cx, cy)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.12f; p.strokeCap = Paint.Cap.ROUND; p.color = color
        c.drawLine(cx, cy + r * 0.05f, cx, cy - r * 0.4f, p)
        p.style = Paint.Style.FILL
        val punta = Path()
        punta.moveTo(cx, cy - r * 0.62f); punta.lineTo(cx - r * 0.22f, cy - r * 0.3f); punta.lineTo(cx + r * 0.22f, cy - r * 0.3f); punta.close()
        c.drawPath(punta, p)
        c.restore()
        // Nube en el borde, en la dirección de donde vienen (sin girar para que se lea bien)
        val ang = Math.toRadians(((grados + 180.0) % 360.0) + giro)
        val mx = cx + (sin(ang) * r * 0.82).toFloat(); val my = cy - (cos(ang) * r * 0.82).toFloat()
        p.color = 0xFF0D1117.toInt(); c.drawCircle(mx, my, r * 0.19f, p)
        p.color = color; p.textAlign = Paint.Align.CENTER; p.textSize = r * 0.28f
        c.drawText("☁", mx, my + p.textSize * 0.33f, p)
    }
}
