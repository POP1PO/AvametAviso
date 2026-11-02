package es.avamet.aviso

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun texto(t: String, tam: Float = 15f, negrita: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = tam
        v.setTextColor(Color.BLACK)
        if (negrita) v.paint.isFakeBoldText = true
        v.setPadding(dp(4), dp(4), dp(4), dp(4))
        return v
    }

    private fun boton(t: String, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener { f() }
        return b
    }

    private fun desplazable(contenido: View): ScrollView {
        val s = ScrollView(this)
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
        raiz.setBackgroundColor(Color.WHITE)

        cabecera = texto("AVAMET Aviso", 18f, true)
        cabecera.setTextColor(Color.WHITE)
        cabecera.setBackgroundColor(Color.GRAY)
        cabecera.setPadding(dp(12), dp(28), dp(12), dp(12))
        raiz.addView(cabecera, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        contenedor = FrameLayout(this)
        raiz.addView(contenedor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // --- Resumen ---
        val colRes = LinearLayout(this)
        colRes.orientation = LinearLayout.VERTICAL
        colRes.setPadding(dp(8), dp(8), dp(8), dp(8))
        btnVigilancia = boton("Iniciar vigilancia") { alternarVigilancia() }
        colRes.addView(btnVigilancia)
        btnSilenciar = boton("Silenciar sonido") { Alarma.silenciar() }
        colRes.addView(btnSilenciar)
        txtResumen = texto("")
        colRes.addView(txtResumen)
        paginas.add(desplazable(colRes))

        // --- Mapa ---
        mapa = MapView(this)
        mapa.setTileSource(TileSourceFactory.MAPNIK)
        mapa.setMultiTouchControls(true)
        mapa.controller.setZoom(10.0)
        mapa.controller.setCenter(GeoPoint(39.99, -0.05))
        paginas.add(mapa)

        // --- Estaciones / Aeropuertos ---
        txtEstaciones = texto("")
        paginas.add(desplazable(txtEstaciones))
        txtAeropuertos = texto("")
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

        col.addView(texto("Consulta cada (minutos):", 15f, true))
        val intervalo = EditText(this)
        intervalo.inputType = InputType.TYPE_CLASS_NUMBER
        intervalo.setText(Config.intervaloMin(this).toString())
        col.addView(intervalo)
        col.addView(boton("Guardar intervalo") {
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
        sonido.setOnCheckedChangeListener { _, v -> Config.setAlarmaSonido(this, v) }
        col.addView(sonido)
        val pantalla = CheckBox(this)
        pantalla.text = "Encender la pantalla en caso de peligro"
        pantalla.isChecked = Config.alarmaPantalla(this)
        pantalla.setOnCheckedChangeListener { _, v -> Config.setAlarmaPantalla(this, v) }
        col.addView(pantalla)
        col.addView(boton("Probar la alarma") {
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

    private fun refrescar() {
        val r = Estado.resultado
        btnVigilancia.text = if (Estado.activo) "Detener vigilancia" else "Iniciar vigilancia"
        btnSilenciar.visibility = if (Estado.sonando) View.VISIBLE else View.GONE
        val (color, titulo) = when {
            !Estado.activo -> Pair(Color.GRAY, "Vigilancia detenida")
            r == null -> Pair(Color.GRAY, "Vigilando… (esperando primer resultado)")
            r.nivel == "PELIGRO" -> Pair(Color.parseColor("#C62828"), "⚠️ PELIGRO")
            !r.datosFiables -> Pair(Color.parseColor("#EF6C00"), "⚠️ SIN DATOS FIABLES")
            r.nivel == "VIGILANCIA" -> Pair(Color.parseColor("#F9A825"), "VIGILANCIA")
            else -> Pair(Color.parseColor("#2E7D32"), "SEGURO")
        }
        cabecera.setBackgroundColor(color)
        cabecera.text = titulo

        val sb = StringBuilder()
        if (Estado.mensaje.isNotEmpty()) sb.append("⚠️ ").append(Estado.mensaje).append("\n\n")
        if (r == null) {
            sb.append(if (Estado.ubicacion == null) "Sin posición todavía. Activa el GPS y pulsa «Iniciar vigilancia»." else "Calculando…")
            txtResumen.text = sb.toString()
            txtEstaciones.text = ""; txtAeropuertos.text = ""
            return
        }
        sb.append("Actualizado a las ${hora(r.ahoraMs)} · posición ${fmt(r.lat, 4)}, ${fmt(r.lon, 4)}\n")
        if (r.errores.isNotEmpty()) sb.append("\n⚠️ Problemas de red:\n").append(r.errores.joinToString("\n") { "• $it" }).append("\n")
        if (r.motivos.isNotEmpty()) sb.append("\nMotivos:\n").append(r.motivos.joinToString("\n") { "• $it" }).append("\n")
        val a = r.analisis
        val aviso = a["aviso_estacion"] as String
        if (aviso.isNotEmpty()) sb.append("\n").append(aviso).append("\n")
        val ref = a["referencia"] as? Map<*, *>
        if (ref != null) {
            sb.append("\nEstación de referencia: ${ref["nombre"]} (${ref["distancia_km"]} km)\n")
            @Suppress("UNCHECKED_CAST")
            val d = ref["datos"] as? Map<String, Any?>
            if (d != null) sb.append("  ${d["Temperatura"] ?: "?"} · humedad ${d["Humedad"] ?: "?"} · viento ${d["Viento"] ?: "?"} (máx ${d["Viento máximo"] ?: "?"}) · lluvia hoy ${d["Lluvia hoy"] ?: "?"}\n")
            val est = ref["estado"] as Map<*, *>
            sb.append("  Lectura: ${est["estado"]}${if (est["edad_min"] != null) " (hace ${est["edad_min"]} min)" else ""}\n")
        }
        val mov = a["movimiento"] as Map<*, *>
        sb.append("\nNubes: hacia ${mov["direccion_destino"]} (${mov["grados_destino"]}°) a ${mov["velocidad_kmh"]} km/h · ${mov["categoria"]}\n")
        val pred = a["alertas_predictivas"].l()
        if (pred.isNotEmpty()) sb.append("\nAvisos predictivos:\n").append(pred.joinToString("\n") { "• $it" }).append("\n")
        val alt = a["alternativas"].l()
        if (alt.isNotEmpty()) sb.append("\nEstaciones cercanas con datos recientes:\n").append(alt.joinToString("\n") { x ->
            val m = x.d(); "• ${m["nombre"]} (${m["distancia_km"]} km, hace ${m["edad_min"]} min)" }).append("\n")
        sb.append("\nPeticiones en este ciclo: ${r.descargas}")
        txtResumen.text = sb.toString()

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
            se.append("• ${e["nombre"]} — ${e["distancia_km"]} km — $estado")
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

    private fun dibujarMapa(r: Resultado) {
        val ov = mapa.overlays
        ov.clear()
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

    override fun onResume() { super.onResume(); mapa.onResume(); Estado.escuchar(oyente); refrescar() }
    override fun onPause() { Estado.dejarDeEscuchar(oyente); mapa.onPause(); super.onPause() }
}
