package es.avamet.aviso

import android.graphics.Color
import android.graphics.ColorMatrix

/** Temas de la app: CLARO, OSCURO y ROJO (modo nocturno: solo tonos rojos, para no perder la adaptación a la oscuridad). */
object Paleta {
    const val CLARO = "claro"; const val OSCURO = "oscuro"; const val ROJO = "rojo"

    var modo = OSCURO; private set
    val esRojo get() = modo == ROJO
    val esClaro get() = modo == CLARO

    var bg = 0; var panel = 0; var panel2 = 0; var borde = 0; var texto = 0; var apagado = 0
    var verde = 0; var ambar = 0; var rojo = 0; var azul = 0; var morado = 0
    var naranja = 0; var textoAlerta = 0; var fondoAlerta = 0; var temp = 0; var panelMapa = 0; var sobre = 0

    init { aplicar(OSCURO) }

    fun aplicar(m: String) {
        modo = if (m == CLARO || m == ROJO) m else OSCURO
        when (modo) {
            CLARO -> {
                bg = 0xFFF6F8FA.toInt(); panel = 0xFFFFFFFF.toInt(); panel2 = 0xFFEAEEF2.toInt(); borde = 0xFFD0D7DE.toInt()
                texto = 0xFF1F2328.toInt(); apagado = 0xFF656D76.toInt()
                verde = 0xFF1A7F37.toInt(); ambar = 0xFF9A6700.toInt(); rojo = 0xFFCF222E.toInt(); azul = 0xFF0969DA.toInt(); morado = 0xFF8250DF.toInt()
                naranja = 0xFFEF6C00.toInt(); textoAlerta = 0xFFCF222E.toInt(); fondoAlerta = 0xFFFFEBE9.toInt(); temp = 0xFFBC4C00.toInt()
                panelMapa = 0xE6FFFFFF.toInt(); sobre = Color.WHITE
            }
            ROJO -> {   // solo canal rojo (G = B = 0): los matices se distinguen por el brillo
                bg = 0xFF000000.toInt(); panel = 0xFF140000.toInt(); panel2 = 0xFF240000.toInt(); borde = 0xFF5A0000.toInt()
                texto = 0xFFE02020.toInt(); apagado = 0xFF8A1010.toInt()
                verde = 0xFF7A0000.toInt(); ambar = 0xFFA80000.toInt(); rojo = 0xFFE00000.toInt(); azul = 0xFFC01010.toInt(); morado = 0xFF900000.toInt()
                naranja = 0xFFB00000.toInt(); textoAlerta = 0xFFFF3030.toInt(); fondoAlerta = 0xFF200000.toInt(); temp = 0xFFD01818.toInt()
                panelMapa = 0xE6140000.toInt(); sobre = 0xFFFF3030.toInt()
            }
            else -> {
                bg = 0xFF0D1117.toInt(); panel = 0xFF161B22.toInt(); panel2 = 0xFF1C2128.toInt(); borde = 0xFF30363D.toInt()
                texto = 0xFFE6EDF3.toInt(); apagado = 0xFF8B949E.toInt()
                verde = 0xFF2EA043.toInt(); ambar = 0xFFD29922.toInt(); rojo = 0xFFDA3633.toInt(); azul = 0xFF58A6FF.toInt(); morado = 0xFFA371F7.toInt()
                naranja = 0xFFEF6C00.toInt(); textoAlerta = 0xFFFF7B72.toInt(); fondoAlerta = 0xFF2A1215.toInt(); temp = 0xFFFFA657.toInt()
                panelMapa = 0xE6161B22.toInt(); sobre = Color.WHITE
            }
        }
    }

    /** Color de un marcador o flecha del mapa: en modo rojo se pasa a un rojo con el brillo del color original. */
    fun mapa(c: Int): Int {
        if (!esRojo) return c
        val l = (0.30 * Color.red(c) + 0.59 * Color.green(c) + 0.11 * Color.blue(c)).toInt().coerceIn(60, 255)
        return Color.rgb(l, 0, 0)
    }

    /** Matriz que pasa una imagen a tonos rojos (luminosidad en el canal rojo, atenuada) y aplica la opacidad `alfa` (0..1). null si no es modo rojo y alfa = 1. */
    fun matriz(alfa: Float): ColorMatrix {
        val m = ColorMatrix()
        if (esRojo) {
            m.set(floatArrayOf(
                0.24f, 0.47f, 0.09f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, alfa, 0f))
        } else m.setScale(1f, 1f, 1f, alfa)
        return m
    }
}
