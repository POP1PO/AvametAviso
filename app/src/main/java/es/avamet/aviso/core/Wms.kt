package es.avamet.aviso.core


/**
 * Capa IR 10.8 µm de EUMETView (WMS público de EUMETSAT), SOLO PARA MOSTRAR en el mapa. No interviene en alarmas.
 * NO verificado contra el servidor real: el nombre de la capa IR se detecta leyendo GetCapabilities (ver elegirCapaIr) y la proyección
 * EPSG:3857 es la habitual de GeoServer, pero no he podido comprobar que EUMETView la sirva. Es una imagen ya coloreada: no da temperaturas.
 */
object Wms {
    const val BASE = "https://view.eumetsat.int/geoserver/wms"
    private const val RAIZ = "https://view.eumetsat.int/geoserver"
    const val CAPA_POR_DEFECTO = "msg_fes:ir108"          // suposición; se sustituye por la detectada en GetCapabilities
    private const val ORIGEN = 20037508.342789244          // semiancho del mundo en EPSG:3857 (m)

    fun urlCapabilities(): String = "$BASE?service=WMS&version=1.3.0&request=GetCapabilities"

    /** GetCapabilities de UNA capa ("espacio:nombre" -> servicio virtual /geoserver/espacio/nombre/wms de GeoServer).
     *  Comprobado el 3-nov-2026 con msg_fes:ir108: ~7 kB frente a ~280 kB del global, con la misma <Dimension name="time">.
     *  null si el nombre no lleva espacio. */
    fun urlCapabilitiesCapa(capa: String): String? {
        val partes = capa.trim().split(":")
        if (partes.size != 2 || partes.any { it.isBlank() }) return null
        return "$RAIZ/${partes[0]}/${partes[1]}/wms?service=WMS&version=1.3.0&request=GetCapabilities"
    }

    /** Último barrido publicado: atributo default de la primera <Dimension name="time"> (p. ej. "2026-11-03T19:00:00Z"). null si no hay. */
    fun tiempoPorDefecto(xml: String): String? {
        for (m in Regex("<Dimension\\b[^>]*>").findAll(xml)) {
            if (!Regex("\\bname\\s*=\\s*\"time\"", RegexOption.IGNORE_CASE).containsMatchIn(m.value)) continue
            return Regex("\\bdefault\\s*=\\s*\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** (minX, minY, maxX, maxY) en metros EPSG:3857 de la tesela z/x/y del esquema XYZ habitual (y=0 arriba). */
    fun bboxTesela(z: Int, x: Int, y: Int): DoubleArray {
        val n = 1 shl z
        val t = 2 * ORIGEN / n
        val minX = -ORIGEN + x * t
        val maxY = ORIGEN - y * t
        return doubleArrayOf(minX, maxY - t, minX + t, maxY)
    }

    /** GetMap 1.3.0 en EPSG:3857 (con CRS:3857 el orden de ejes es x,y), PNG transparente, 256x256.
     *  Hay que pasar `tiempo` (el de tiempoPorDefecto): comprobado el 3-nov-2026 que SIN time (y también con time=current)
     *  EUMETView sirve teselas de su caché con fechas mezcladas (23-jul, 1-ago, 19-ago y del día, una al lado de otra),
     *  y el mapa sale cortado en bandas. Con un time explícito todas las teselas son del mismo barrido. */
    fun urlTesela(capa: String, z: Int, x: Int, y: Int, tiempo: String? = null): String {
        val b = bboxTesela(z, x, y)
        return "$BASE?service=WMS&version=1.3.0&request=GetMap&layers=$capa&styles=&crs=EPSG:3857" +
            "&bbox=${b[0]},${b[1]},${b[2]},${b[3]}&width=256&height=256&format=image/png&transparent=true" +
            (if (tiempo.isNullOrBlank()) "" else "&time=${tiempo.trim()}")
    }

    /** Nombres de capa (<Name>) de un GetCapabilities, en orden y sin repetir. */
    fun nombresDeCapas(xml: String): List<String> =
        Regex("<Name>\\s*([^<\\s]+)\\s*</Name>").findAll(xml).map { it.groupValues[1] }.distinct().toList()

    /**
     * Elige la capa IR 10.8: prioriza msg_fes:ir108 exacto, luego cualquier msg_fes:* con ir108/ir_108, luego otra que no sea
     * un compuesto mundial ("worldcloudmap", de cadencia de 3 h) y por último cualquier ir108. null si no hay ninguna.
     */
    fun elegirCapaIr(nombres: List<String>): String? {
        val ir = nombres.filter { Regex("ir_?108", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        return ir.firstOrNull { it.equals("msg_fes:ir108", true) }
            ?: ir.firstOrNull { it.startsWith("msg_fes:", true) && !it.contains("overlay", true) }
            ?: ir.firstOrNull { !it.contains("worldcloudmap", true) && !it.contains("overlay", true) }
            ?: ir.firstOrNull()
    }

    /** Ventana de 15 min (cadencia de SEVIRI) para renovar la caché de teselas. */
    fun franjaCache(ahoraMs: Long): Long = ahoraMs / (15 * 60_000L)

}
