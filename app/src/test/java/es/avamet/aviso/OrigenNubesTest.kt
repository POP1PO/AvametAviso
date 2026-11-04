package es.avamet.aviso

import es.avamet.aviso.core.origenDeNubes
import org.junit.Assert.assertEquals
import org.junit.Test

class OrigenNubesTest {
    @Test fun origenEsLaDireccionOpuestaAlDestino() {
        assertEquals(343 to "NNO", origenDeNubes(163.0))     // van hacia el SSE -> vienen del NNO
        assertEquals(270 to "O", origenDeNubes(90.0))         // van al este -> vienen del oeste
        assertEquals(180 to "S", origenDeNubes(0.0))          // van al norte -> vienen del sur
        assertEquals(0 to "N", origenDeNubes(180.0))
        assertEquals(170 to "S", origenDeNubes(350.0))        // cruza el 0°
        assertEquals(225 to "SO", origenDeNubes(45.0))
    }
}
