package es.avamet.aviso.core

import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Port de python_nucleo/aviso_core. Los datos se representan como en Python: Map<String, Any?>, List<Any?>, String,
 * Number (Long o Double), Boolean y null. Así cada función se puede comparar con los vectores de prueba tal cual.
 */
typealias Dict = MutableMap<String, Any?>

fun dict(vararg p: Pair<String, Any?>): Dict {
    val m = LinkedHashMap<String, Any?>()
    for ((k, v) in p) m[k] = v
    return m
}

@Suppress("UNCHECKED_CAST")
fun Any?.d(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.l(): List<Any?> = this as List<Any?>

fun Any?.dbl(): Double = (this as Number).toDouble()

/** Python round(x, n): redondeo a PAR sobre el valor binario exacto (no `Math.round`). */
fun pyRound(x: Double, n: Int): Double = BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toDouble()

/** Python round(x) -> int. */
fun pyRoundInt(x: Double): Long = BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).toLong()

/** Python f"{x:.nf}". */
fun fmt(x: Double, n: Int): String = BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toPlainString()

/** Python f"{x:g}" (6 cifras significativas, sin ceros finales). Solo para valores normales (aquí: presión 850-1100). */
fun fmtG(x: Double): String {
    if (x == 0.0) return "0"
    val bd = java.math.BigDecimal(x).round(java.math.MathContext(6, RoundingMode.HALF_EVEN)).stripTrailingZeros()
    return bd.toPlainString()
}

/** Python str(x) para un número del JSON: enteros sin decimales, decimales como "3.4". */
fun numStr(v: Any?): String = when (v) {
    is Long, is Int -> v.toString()
    is Double -> v.toString()
    else -> v.toString()
}

private val RE_FLOAT = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

/** float(texto) de Python, solo formatos numéricos normales; null si no es válido. */
fun parseFloatEstricto(t: String): Double? {
    val s = t.trim()
    return if (RE_FLOAT.matches(s)) s.toDouble() else null
}

// ---- JSON mínimo que CONSERVA EL ORDEN de las claves (org.json no lo garantiza, y el orden decide los empates al ordenar) ----
object MiniJson {
    fun parse(texto: String): Any? {
        val p = Parser(texto)
        p.espacios()
        val v = p.valor()
        p.espacios()
        if (p.i != texto.length) throw IllegalArgumentException("JSON: texto sobrante en ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun espacios() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun valor(): Any? {
            espacios()
            if (i >= s.length) throw IllegalArgumentException("JSON: fin inesperado")
            return when (val c = s[i]) {
                '{' -> objeto()
                '[' -> lista()
                '"' -> cadena()
                't' -> { esperar("true"); true }
                'f' -> { esperar("false"); false }
                'n' -> { esperar("null"); null }
                else -> if (c == '-' || c.isDigit()) numero() else throw IllegalArgumentException("JSON: carácter '$c' en $i")
            }
        }
        fun esperar(p: String) {
            if (!s.startsWith(p, i)) throw IllegalArgumentException("JSON: se esperaba $p en $i")
            i += p.length
        }
        fun objeto(): Any? {
            val m = LinkedHashMap<String, Any?>()
            i++
            espacios()
            if (s[i] == '}') { i++; return m }
            while (true) {
                espacios()
                val k = cadena()
                espacios()
                if (s[i] != ':') throw IllegalArgumentException("JSON: falta ':' en $i")
                i++
                m[k] = valor()
                espacios()
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw IllegalArgumentException("JSON: objeto mal cerrado en $i")
            }
        }
        fun lista(): Any? {
            val l = ArrayList<Any?>()
            i++
            espacios()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(valor())
                espacios()
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw IllegalArgumentException("JSON: lista mal cerrada en $i")
            }
        }
        fun cadena(): String {
            if (s[i] != '"') throw IllegalArgumentException("JSON: se esperaba '\"' en $i")
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun numero(): Any? {
            val ini = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(ini, i)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong()
        }
    }
}

/** Copia profunda de mapas/listas (equivale a copy.deepcopy para estos datos). */
fun copiaProfunda(x: Any?): Any? = when (x) {
    is Map<*, *> -> { val m = LinkedHashMap<String, Any?>(); for ((k, v) in x) m[k as String] = copiaProfunda(v); m }
    is List<*> -> x.map { copiaProfunda(it) }.toMutableList()
    else -> x
}

/** math.radians / math.degrees de Python (x * (pi/180)), para dar el mismo resultado bit a bit. */
fun rad(x: Double): Double = x * (Math.PI / 180.0)
fun deg(x: Double): Double = x * (180.0 / Math.PI)
