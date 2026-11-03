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
                else -> Toast.makeText(this, "Varias coinciden, concreta: " + r.take(5).joinToString("; ") { "${it.first} (${it.second})" }, Toast.LENGTH_LONG).show()
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
        val brujula = BrujulaView(this, grados, color)
        filaMov.addView(brujula, LinearLayout.LayoutParams(dp(110), dp(110)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(texto("${mov["direccion_destino"] ?: "—"} · ${grados?.let { fmt(it, 0) + "°" } ?: "?"}", 22f, true))
        col.addView(texto("${mov["velocidad_kmh"] ?: "?"} km/h · ${mov["categoria"] ?: "?"}", 14f, false, APAGADO))
        col.addView(texto("Hacia dónde se desplazan", 11f, false, APAGADO))
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
        caja.addView(chk); caja.addView(barra); caja.addView(irEstado)
        val detalle = listOf<View>(barra, irEstado)
        fun aplicar(on: Boolean) {
            detalle.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
            MapaIr.setVisible(this, on)
            if (on) activarIr() else { MapaIr.quitar(mapa, irOverlay); irOverlay = null; mapa.invalidate() }
        }
        chk.setOnCheckedChangeListener { _, on -> aplicar(on) }
        barra.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) { irOpacidad = v.coerceIn(10, 100); irOverlay?.let { MapaIr.ajustarOpacidad(it, irOpacidad); mapa.invalidate() } }
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

    override fun onResume() {
        super.onResume(); mapa.onResume(); Estado.escuchar(oyente); refrescar()
        if (irOverlay != null) actualizarIr()       // renueva la capa IR si EUMETSAT ha publicado otro barrido (cada 15 min)
    }
    override fun onPause() { Estado.dejarDeEscuchar(oyente); mapa.onPause(); super.onPause() }
}


/** Brújula: flecha hacia donde se mueven las nubes (0° = norte). */
class BrujulaView(ctx: Context, private val grados: Double?, private val acento: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2; val r = minOf(w, h) / 2 - 6f
        p.style = Paint.Style.FILL; p.color = 0xFF1C2128.toInt(); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF30363D.toInt(); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL; p.color = 0xFF8B949E.toInt(); p.textSize = r * 0.28f; p.textAlign = Paint.Align.CENTER
        c.drawText("N", cx, cy - r + p.textSize + 2f, p)
        c.drawText("S", cx, cy + r - 6f, p)
        c.drawText("E", cx + r - p.textSize * 0.5f, cy + p.textSize * 0.35f, p)
        c.drawText("O", cx - r + p.textSize * 0.5f, cy + p.textSize * 0.35f, p)
        if (grados == null) return
        c.save()
        c.rotate(grados.toFloat(), cx, cy)
        val flecha = Path()
        val largo = r * 0.62f; val ancho = r * 0.22f
        flecha.moveTo(cx, cy - largo)
        flecha.lineTo(cx + ancho, cy)
        flecha.lineTo(cx + ancho * 0.35f, cy)
        flecha.lineTo(cx + ancho * 0.35f, cy + largo * 0.7f)
        flecha.lineTo(cx - ancho * 0.35f, cy + largo * 0.7f)
        flecha.lineTo(cx - ancho * 0.35f, cy)
        flecha.lineTo(cx - ancho, cy)
        flecha.close()
        p.style = Paint.Style.FILL; p.color = if (acento == 0xFF8B949E.toInt()) 0xFF58A6FF.toInt() else acento
        c.drawPath(flecha, p)
        c.restore()
    }
}
