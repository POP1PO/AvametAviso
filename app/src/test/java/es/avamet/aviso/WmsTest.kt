package es.avamet.aviso

import es.avamet.aviso.core.Wms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WmsTest {
    @Test fun teselaMundialCubreTodoElMundo() {
        val b = Wms.bboxTesela(0, 0, 0)
        assertEquals(-20037508.342789244, b[0], 1e-6); assertEquals(20037508.342789244, b[3], 1e-6)
        assertEquals(-b[0], b[2], 1e-6); assertEquals(-b[3], b[1], 1e-6)
    }

    @Test fun teselasContiguasYSinHuecos() {
        val a = Wms.bboxTesela(5, 16, 12); val d = Wms.bboxTesela(5, 17, 12); val s = Wms.bboxTesela(5, 16, 13)
        assertEquals(a[2], d[0], 1e-6)            // derecha de una = izquierda de la siguiente
        assertEquals(a[1], s[3], 1e-6)            // abajo de una = arriba de la de debajo
        assertEquals(a[2] - a[0], a[3] - a[1], 1e-6)   // cuadrada
    }

    @Test fun teselaConocidaZ1() {                  // z=1: cuatro teselas de 20037508.34 m de lado; la 1/0 es el cuadrante noreste
        val b = Wms.bboxTesela(1, 1, 0)
        assertEquals(0.0, b[0], 1e-6); assertEquals(0.0, b[1], 1e-6)
        assertEquals(20037508.342789244, b[2], 1e-6); assertEquals(20037508.342789244, b[3], 1e-6)
    }

    @Test fun urlDeTesela() {
        val u = Wms.urlTesela("msg_fes:ir108", 0, 0, 0)
        assertTrue(u.startsWith("https://view.eumetsat.int/geoserver/msg_fes/ir108/wms?service=WMS&version=1.3.0&request=GetMap&layers=ir108&styles="))
        assertTrue(Wms.urlTesela("ir108", 0, 0, 0).startsWith("https://view.eumetsat.int/geoserver/wms?service=WMS&version=1.3.0&request=GetMap&layers=ir108&"))
        assertTrue("crs=EPSG:3857" in u && "width=256&height=256" in u && "transparent=true" in u && "format=image/png" in u)
        assertTrue("bbox=-2.0037508342789244E7,-2.0037508342789244E7,2.0037508342789244E7,2.0037508342789244E7" in u)
    }

    @Test fun urlDeTeselaConTiempo() {
        val u = Wms.urlTesela("msg_fes:ir108", 7, 63, 48, "2026-11-03T19:00:00Z")
        assertTrue(u.endsWith("transparent=true&time=2026-11-03T19:00:00Z"))
        assertTrue("time=" !in Wms.urlTesela("msg_fes:ir108", 7, 63, 48))
        assertTrue("time=" !in Wms.urlTesela("msg_fes:ir108", 7, 63, 48, " "))
    }

    @Test fun fotogramasDeLaAnimacion() {
        assertEquals(listOf("2026-11-03T23:30:00Z", "2026-11-03T23:45:00Z", "2026-11-04T00:00:00Z", "2026-11-04T00:15:00Z"),
            Wms.fotogramas("2026-11-04T00:15:00Z", 4))                       // cruza la medianoche
        assertEquals(listOf("2026-11-04T00:15:00Z"), Wms.fotogramas("2026-11-04T00:15:00Z", 1))
        assertEquals(8, Wms.fotogramas("2026-11-04T00:15:00Z").size)
        assertTrue(Wms.fotogramas(null).isEmpty() && Wms.fotogramas("basura").isEmpty())
    }

    @Test fun capabilitiesDeUnaSolaCapa() {
        assertEquals("https://view.eumetsat.int/geoserver/msg_fes/ir108/wms?service=WMS&version=1.3.0&request=GetCapabilities",
            Wms.urlCapabilitiesCapa("msg_fes:ir108"))
        assertNull(Wms.urlCapabilitiesCapa("ir108")); assertNull(Wms.urlCapabilitiesCapa(":ir108")); assertNull(Wms.urlCapabilitiesCapa("a:b:c"))
    }

    @Test fun tiempoPorDefectoConLaRespuestaReal() {
        // Fragmento literal de .../geoserver/msg_fes/ir108/wms?...GetCapabilities del 3-nov-2026
        val real = """<Layer queryable="1" opaque="0"><Name>ir108</Name>
            <Dimension name="time" default="2026-11-03T19:00:00Z" units="ISO8601" nearestValue="1">2020-09-01T00:00:00.000Z/2026-11-03T19:00:00.000Z/PT15M</Dimension>
            <Style><Name>raster</Name></Style></Layer>"""
        assertEquals("2026-11-03T19:00:00Z", Wms.tiempoPorDefecto(real))
        assertEquals("X", Wms.tiempoPorDefecto("""<Dimension name="elevation" default="0">0</Dimension><Dimension default="X" name="time">t</Dimension>"""))
        assertNull(Wms.tiempoPorDefecto("<Layer><Name>a</Name></Layer>"))
        assertNull(Wms.tiempoPorDefecto("""<Dimension name="time" units="ISO8601">t</Dimension>"""))
    }

    private val XML = """<Capability><Layer><Name>mumi:worldcloudmap_ir108</Name><Layer><Name>msg_fes:rgb_fog</Name></Layer>
        <Layer><Name> msg_fes:ir108 </Name></Layer><Layer><Name>msg_fes:ir108</Name></Layer><Layer><Name>overlay:ir108_overlay</Name></Layer></Layer></Capability>"""

    @Test fun nombresSinRepetirYSinEspacios() {
        assertEquals(listOf("mumi:worldcloudmap_ir108", "msg_fes:rgb_fog", "msg_fes:ir108", "overlay:ir108_overlay"), Wms.nombresDeCapas(XML))
    }

    @Test fun eligeLaCapaIrCorrecta() {
        assertEquals("msg_fes:ir108", Wms.elegirCapaIr(Wms.nombresDeCapas(XML)))
        assertEquals("msg_fes:ir_108_bt", Wms.elegirCapaIr(listOf("mumi:worldcloudmap_ir108", "msg_fes:ir_108_bt")))
        assertEquals("otra:ir108", Wms.elegirCapaIr(listOf("mumi:worldcloudmap_ir108", "otra:ir108")))
        assertEquals("mumi:worldcloudmap_ir108", Wms.elegirCapaIr(listOf("mumi:worldcloudmap_ir108", "msg_fes:rgb_fog")))   // último recurso
        assertNull(Wms.elegirCapaIr(listOf("msg_fes:rgb_fog")))
    }

    @Test fun franjaDeCacheCambiaCada15Min() {
        assertEquals(Wms.franjaCache(0), Wms.franjaCache(14 * 60_000L + 59_000))
        assertTrue(Wms.franjaCache(15 * 60_000L) == Wms.franjaCache(0) + 1)
    }
}

class ReferenciaManualTest {
    private val coords = mapOf(
        "a1" to mapOf<String, Any?>("lat" to 40.0, "lon" to 0.0, "nombre" to "Mas del Tonto", "municipio" to "les Coves de Vinromà"),
        "a2" to mapOf<String, Any?>("lat" to 40.1, "lon" to 0.0, "nombre" to "Carrer Nou", "municipio" to "les Coves de Vinromà"),
        "b1" to mapOf<String, Any?>("lat" to 39.9, "lon" to -0.1, "nombre" to "Vila-real", "municipio" to "Vila-real"))

    @Test fun fijaManda_sobreLaMasCercana() {
        val auto = es.avamet.aviso.core.elegirReferencia(40.1, 0.0, coords, null, 1.0)!!
        org.junit.Assert.assertEquals("a2", auto.first)
        val fija = es.avamet.aviso.core.elegirReferencia(40.1, 0.0, coords, "a2", 1.0, fijaId = "b1")!!
        org.junit.Assert.assertEquals("b1", fija.first); org.junit.Assert.assertTrue(fija.second > 10.0)
        org.junit.Assert.assertEquals("a2", es.avamet.aviso.core.elegirReferencia(40.1, 0.0, coords, null, 1.0, fijaId = "NO_EXISTE")!!.first)   // id desconocido: automática
        org.junit.Assert.assertEquals("a2", es.avamet.aviso.core.elegirReferencia(40.1, 0.0, coords, null, 1.0, fijaId = "")!!.first)
    }

    @Test fun busquedaPorIdNombreYAcentos() {
        val b = { q: String -> es.avamet.aviso.core.buscarEstaciones(coords, q).map { it.first } }
        org.junit.Assert.assertEquals(listOf("a1"), b("A1"))
        org.junit.Assert.assertEquals(listOf("a2", "a1"), b("coves de vinroma"))     // ordenadas por nombre: Carrer Nou, Mas del Tonto; sin acentos
        org.junit.Assert.assertEquals(listOf("b1"), b("vila-real"))
        org.junit.Assert.assertTrue(b("zzz").isEmpty() && b("  ").isEmpty())
    }
}
