package es.avamet.aviso

import es.avamet.aviso.core.*
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Los mismos vectores dorados que pasa el núcleo Python (python_nucleo/vectores (archivos .json), generados por Python y verificados contra
 * el backend de escritorio). Si esta prueba falla, el port a Kotlin NO da lo mismo que Python.
 */
class VectoresTest {
    private fun recurso(nombre: String): Any? =
        MiniJson.parse(javaClass.classLoader!!.getResourceAsStream(nombre)!!.readBytes().toString(Charsets.UTF_8))

    private fun asset(nombre: String): Any? = MiniJson.parse(File("src/main/assets/$nombre").readText(Charsets.UTF_8))

    /** null si son iguales; si no, descripción de la primera diferencia. Números: tolerancia relativa 1e-9. */
    private fun diferencia(a: Any?, b: Any?, ruta: String): String? {
        if (a == null || b == null) return if (a == b) null else "$ruta: $a != $b"
        if (a is Number && b is Number) {
            val x = a.toDouble(); val y = b.toDouble()
            return if (Math.abs(x - y) <= 1e-9 * maxOf(1.0, Math.abs(x), Math.abs(y))) null else "$ruta: $x != $y"
        }
        if (a is Map<*, *> && b is Map<*, *>) {
            if (a.keys != b.keys) return "$ruta: claves ${a.keys} != ${b.keys}"
            for (k in a.keys) diferencia(a[k], b[k], "$ruta.$k")?.let { return it }
            return null
        }
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return "$ruta: longitud ${a.size} != ${b.size}"
            for (i in a.indices) diferencia(a[i], b[i], "$ruta[$i]")?.let { return it }
            return null
        }
        return if (a == b) null else "$ruta: '$a' != '$b'"
    }

    private fun igual(esperado: Any?, obtenido: Any?, ruta: String) = assertNull(diferencia(esperado, obtenido, ruta), diferencia(esperado, obtenido, ruta))

    @Test fun parseoEstacion() {
        for ((i, c0) in recurso("estacion_parseo.json").l().withIndex()) {
            val c = c0.d()
            igual(c["esperado"], parsearEstacion(textoDeHtml(c["html"] as String)), "estacion[$i]")
        }
    }

    @Test fun parseoMetar() {
        for ((i, c0) in recurso("metar_parseo.json").l().withIndex()) {
            val c = c0.d()
            igual(c["esperado"], parsearMetars(c["json"].l()), "metar[$i]")
        }
    }

    @Test fun movimiento() {
        for ((i, c0) in recurso("movimiento.json").l().withIndex()) {
            val c = c0.d()
            igual(c["esperado"], estimarMovimiento(c["referencia"].d(), c["metars"].d(), c["aeropuertos"].l().map { it.d() }), "mov[$i]")
        }
    }

    @Test fun motorYPeligro() {
        @Suppress("UNCHECKED_CAST")
        val coords = completarMunicipios(asset("coordenadas.json").d())
        @Suppress("UNCHECKED_CAST")
        val aeros = asset("aeropuertos.json").d() as Map<String, Map<String, Any?>>
        for ((i, c0) in recurso("motor.json").l().withIndex()) {
            val c = c0.d()
            val plan = planificar(c["lat"].dbl(), c["lon"].dbl(), coords, aeros)
            igual(c["esperado_plan"], plan, "plan[$i]")
            @Suppress("UNCHECKED_CAST")
            val r = analizar(plan, c["lecturas"] as Lecturas, c["metars"].d(), c["seguimiento"].d(), c["ahora"].dbl(), coords)
            igual(c["esperado"], r, "motor[$i]")
            igual(c["esperado_peligro"], evaluarPeligro(r), "peligro[$i]")
        }
    }

    @Test fun peligro() {
        for ((i, c0) in recurso("peligro.json").l().withIndex()) {
            val c = c0.d()
            igual(c["esperado"], evaluarPeligro(c["res"].d()), "peligro[$i]")
        }
    }
}
