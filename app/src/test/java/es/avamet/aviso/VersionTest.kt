package es.avamet.aviso

import es.avamet.aviso.core.VersionPublicada
import es.avamet.aviso.core.hayVersionNueva
import es.avamet.aviso.core.leerVersionPublicada
import org.junit.Assert.*
import org.junit.Test

class VersionTest {
    private val json = """{"tag_name":"ultima","name":"Última versión (nº 26)","body":"APK generado (commit abc). versionCode=26",
        "assets":[{"name":"AvametAviso.apk","browser_download_url":"https://github.com/POP1PO/AvametAviso/releases/download/ultima/AvametAviso.apk"}]}"""

    @Test fun leeCodigoYUrl() {
        val v = leerVersionPublicada(json)!!
        assertEquals(26L, v.codigo)
        assertEquals("https://github.com/POP1PO/AvametAviso/releases/download/ultima/AvametAviso.apk", v.urlApk)
    }

    @Test fun sinCodigoOSinApkNoHayVersion() {
        assertNull(leerVersionPublicada(json.replace("versionCode=26", "sin numero")))
        assertNull(leerVersionPublicada(json.replace("AvametAviso.apk\"}", "otra.apk\"}")))
        assertNull(leerVersionPublicada(json.replace("https://github", "http://github")))
        assertNull(leerVersionPublicada(""))
    }

    @Test fun soloSeOfreceSiLaPublicadaEsMayor() {
        val v = VersionPublicada(26, "https://x/AvametAviso.apk")
        assertTrue(hayVersionNueva(25, v))
        assertFalse(hayVersionNueva(26, v))       // la misma: nada
        assertFalse(hayVersionNueva(27, v))       // nunca bajar de versión
        assertFalse(hayVersionNueva(1, null))
    }
}
