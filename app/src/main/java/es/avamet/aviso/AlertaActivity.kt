package es.avamet.aviso

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Pantalla de alarma: se muestra sobre el bloqueo, enciende la pantalla y permite silenciar el sonido. */
class AlertaActivity : Activity() {
    private lateinit var texto: TextView
    private lateinit var btnSilenciar: Button
    private val oyente: () -> Unit = { refrescar() }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setBackgroundColor(Color.parseColor("#B71C1C"))
        col.setPadding(48, 48, 48, 48)

        val titulo = TextView(this)
        titulo.text = "⚠️ PELIGRO"
        titulo.textSize = 40f
        titulo.setTextColor(Color.WHITE)
        titulo.gravity = Gravity.CENTER
        col.addView(titulo)

        texto = TextView(this)
        texto.textSize = 20f
        texto.setTextColor(Color.WHITE)
        texto.gravity = Gravity.CENTER
        texto.setPadding(0, 32, 0, 48)
        col.addView(texto)

        btnSilenciar = Button(this)
        btnSilenciar.text = "Silenciar sonido"
        btnSilenciar.setOnClickListener { Alarma.silenciar() }
        col.addView(btnSilenciar)

        val abrir = Button(this)
        abrir.text = "Abrir la aplicación"
        abrir.setOnClickListener {
            startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        col.addView(abrir)

        val cerrar = Button(this)
        cerrar.text = "Cerrar"
        cerrar.setOnClickListener { Alarma.silenciar(); finish() }
        col.addView(cerrar)

        setContentView(col)
        refrescar()
    }

    private fun refrescar() {
        val r = Estado.resultado
        texto.text = if (r == null) "Peligro" else r.motivos.joinToString("\n").ifEmpty { "Peligro" }
        btnSilenciar.isEnabled = Estado.sonando
        btnSilenciar.text = if (Estado.sonando) "Silenciar sonido" else "Sonido silenciado"
    }

    override fun onStart() { super.onStart(); Estado.escuchar(oyente); refrescar() }
    override fun onStop() { Estado.dejarDeEscuchar(oyente); super.onStop() }
}
