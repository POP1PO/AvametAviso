package es.avamet.aviso

import es.avamet.aviso.core.ampliarConAeropuertos
import es.avamet.aviso.core.dict
import org.junit.Assert.assertEquals
import org.junit.Test

class AvisoTest {
    private fun aero(nombre: String, km: Double, fen: String?) =
        mapOf<String, Any?>("icao" to "X", "nombre" to nombre, "distancia_km" to km, "metar" to (fen?.let { mapOf<String, Any?>("fenomenos" to it) }))

    private fun res(vararg a: Map<String, Any?>) = mapOf<String, Any?>("aeropuertos" to a.toList())
    private val seguro get() = dict("nivel" to "SEGURO", "motivos" to ArrayList<Any?>())

    @Test fun lluviaEnAeropuertoCercanoSubeAVigilancia() {
        val r = ampliarConAeropuertos(res(aero("Valencia", 35.0, "Lluvia")), seguro)
        assertEquals("VIGILANCIA", r["nivel"])
        assertEquals(1, (r["motivos"] as List<*>).size)
    }

    @Test fun lejosOSinFenomenosNoCambiaNada() {
        assertEquals("SEGURO", ampliarConAeropuertos(res(aero("Lejos", 90.0, "Lluvia")), seguro)["nivel"])
        assertEquals("SEGURO", ampliarConAeropuertos(res(aero("Cerca", 20.0, "Ninguno")), seguro)["nivel"])
        assertEquals("SEGURO", ampliarConAeropuertos(res(aero("Niebla", 20.0, "Niebla")), seguro)["nivel"])     // niebla no es lluvia
        assertEquals("SEGURO", ampliarConAeropuertos(res(aero("SinMetar", 20.0, null)), seguro)["nivel"])
        assertEquals("SEGURO", ampliarConAeropuertos(mapOf("aeropuertos" to null), seguro)["nivel"])
    }

    @Test fun nuncaBajaDePeligroNiQuitaMotivos() {
        val p = dict("nivel" to "PELIGRO", "motivos" to arrayListOf<Any?>("Lluvia en tu estación"))
        val r = ampliarConAeropuertos(res(aero("Valencia", 35.0, "Chubascos")), p)
        assertEquals("PELIGRO", r["nivel"])
        assertEquals(2, (r["motivos"] as List<*>).size)
        assertEquals("VIGILANCIA", ampliarConAeropuertos(res(aero("V", 10.0, "Tormenta")), dict("nivel" to "VIGILANCIA", "motivos" to ArrayList<Any?>()))["nivel"])
    }
}
