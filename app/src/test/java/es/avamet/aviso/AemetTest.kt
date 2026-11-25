package es.avamet.aviso

import es.avamet.aviso.core.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class AemetTest {
    private val json = """[
      {"idema":"8500A","ubi":"VALENCIA","lat":39.48,"lon":-0.37,"fint":"2026-11-25T09:00:00","prec":0.0,"ta":15.0,"vv":2.0},
      {"idema":"8500A","ubi":"VALENCIA","lat":39.48,"lon":-0.37,"fint":"2026-11-25T10:00:00","prec":1.2,"ta":14.0,"hr":90,"vv":5.0},
      {"idema":"9999Z","ubi":"LEJOS","lat":28.0,"lon":-15.0,"fint":"2026-11-25T10:00:00","prec":0.0},
      {"idema":"SINPOS","fint":"2026-11-25T10:00:00"}
    ]"""

    @Test fun ultimaLecturaDistanciaYUnidades() {
        val r = Aemet.cercanas(MiniJson.parse(json) as List<Any?>, 39.5, -0.4, 60.0)
        assertEquals(1, r.size)
        assertEquals("2026-11-25T10:00:00", r[0].fintUtc)
        assertEquals(1.2, r[0].lluviaMm!!, 1e-9)
        assertEquals(18.0, r[0].vientoKmh!!, 1e-9)          // 5 m/s * 3.6
        assertTrue(Aemet.cercanas(MiniJson.parse(json) as List<Any?>, 39.5, -0.4, 1.0).isEmpty())
    }

    @Test fun formatoDeClave() {
        assertTrue(Aemet.claveValida("a.b-c_" + "x".repeat(30)))
        assertFalse(Aemet.claveValida("corta"))
        assertFalse(Aemet.claveValida("con espacios " + "x".repeat(30)))
    }

    @Test fun sinClaveNoSeConsulta() {
        try { Aemet.consultar("", 39.5, -0.4); fail("debía fallar") } catch (e: Aemet.ErrorAemet) { assertTrue(e.message!!.contains("clave")) }
    }
}
