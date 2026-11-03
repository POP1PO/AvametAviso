package es.avamet.aviso

import es.avamet.aviso.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Caso real del 3-nov-2026 (Les Coves de Vinromà): llovía en Mas del Tonto (c05m050e03), a 7,5 km de Carrer Nou (c05m050e01), y la app no
 * avisó porque solo descargaba la referencia y las estaciones "del camino de las nubes". Regla: ≤10 km alarma, 10–25 km aviso.
 * Misma prueba que python_nucleo/tests/test_nucleo.py::test_lluvia_de_estacion_vecina_aviso_y_alarma.
 */
class RadioTest {
    private val AHORA = 1_800_000_030.0
    private val E01 = "c05m050e01"
    private val E03 = "c05m050e03"

    private fun asset(nombre: String): Any? = MiniJson.parse(File("src/main/assets/$nombre").readText(Charsets.UTF_8))

    private fun lectura(ahora: Double, lluvia: String): Map<String, Any?> {
        val f = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(ZoneId.of("Europe/Madrid")).format(Instant.ofEpochSecond((ahora - 60).toLong()))
        return dict("Fecha lectura" to f, "Temperatura" to "15.1 °C", "Humedad" to "70 %", "Viento" to "5 km/h N", "Viento máximo" to "8 km/h", "Lluvia hoy" to "$lluvia mm")
    }

    @Suppress("UNCHECKED_CAST")
    private fun escenario(): Triple<Map<String, Map<String, Any?>>, Map<String, Any?>, Map<String, Double>> {
        val coords = completarMunicipios(asset("coordenadas.json").d())
        val aeros = asset("aeropuertos.json").d() as Map<String, Map<String, Any?>>
        val plan = planificar(coords[E01]!!["lat"].dbl(), coords[E01]!!["lon"].dbl(), coords, aeros)
        val radio = plan["radio"].l().map { it.d() }.associate { (it["id"] as String) to it["distancia_km"].dbl() }
        return Triple(coords, plan, radio)
    }

    @Test fun laAppDescargaTodoElRadioConLasCercanasPrimero() {
        val (_, plan, radio) = escenario()
        val ids = estacionesDelRadio(plan)
        assertEquals("tiene que pedir TODAS las del radio", radio.keys, ids.toSet())
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("Mas del Tonto (7,5 km) debe estar en la lista", E03 in ids)
        assertEquals(7.5, radio[E03]!!, 0.1)
        val dist = ids.map { radio[it]!! }
        assertEquals("las más cercanas primero", dist.sorted(), dist)
        assertTrue("la referencia no se repite", E01 !in ids)
        assertEquals(setOf(E03), estacionesDelRadio(plan, ids.toSet() - E03).toSet())     // solo pide las que faltan
    }

    @Test fun lluviaEnEstacionVecinaDaAlarmaYAvisoSegunDistancia() {
        val (coords, plan, radio) = escenario()
        fun ciclo(lec: Map<String, Map<String, Any?>>, ahora: Double, seg: Map<String, Any?>) = analizar(plan, lec, emptyMap(), seg, ahora, coords)
        // a 7,5 km: sube 0,4 mm entre dos ciclos de 5 min -> PELIGRO
        val r1 = ciclo(mapOf(E01 to lectura(AHORA, "0.0"), E03 to lectura(AHORA, "0.0")), AHORA, emptyMap())
        val r2 = ciclo(mapOf(E01 to lectura(AHORA + 300, "0.0"), E03 to lectura(AHORA + 300, "0.4")), AHORA + 300, r1["seguimiento"].d())
        assertEquals(listOf(E03), r2["lluvia_ahora"].l().map { it.d()["id"] })
        val p = evaluarPeligro(r2)
        assertEquals("PELIGRO", p["nivel"]); assertTrue(p["motivos"].l()[0].toString().contains("7.5 km"))
        // con lo que bajaba la app antes (solo la referencia) no se veía nada
        val r3 = ciclo(mapOf(E01 to lectura(AHORA + 300, "0.0")), AHORA + 300, emptyMap())
        assertTrue(r3["lluvia_ahora"].l().isEmpty()); assertEquals("SEGURO", evaluarPeligro(r3)["nivel"])
        // entre 10 y 25 km: aviso (VIGILANCIA), sin alarma
        val lejana = radio.entries.first { it.value > 11 && it.value < 24 }.key
        val a1 = ciclo(mapOf(E01 to lectura(AHORA, "0.0"), lejana to lectura(AHORA, "0.0")), AHORA, emptyMap())
        val a2 = ciclo(mapOf(E01 to lectura(AHORA + 300, "0.0"), lejana to lectura(AHORA + 300, "0.5")), AHORA + 300, a1["seguimiento"].d())
        val pa = evaluarPeligro(a2)
        assertEquals("VIGILANCIA", pa["nivel"]); assertTrue(pa["motivos"].l()[0].toString().endsWith("(aviso)"))
    }
}
