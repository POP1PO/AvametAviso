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
        assertTrue(u.startsWith("https://view.eumetsat.int/geoserver/wms?service=WMS&version=1.3.0&request=GetMap&layers=msg_fes:ir108"))
        assertTrue("crs=EPSG:3857" in u && "width=256&height=256" in u && "transparent=true" in u && "format=image/png" in u)
        assertTrue("bbox=-2.0037508342789244E7,-2.0037508342789244E7,2.0037508342789244E7,2.0037508342789244E7" in u)
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
