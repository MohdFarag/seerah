package app.seerah.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StyleJsonTest {
    private val files = MapFiles(File("/data/t/relief.pmtiles"))

    private val fadeRange = ZoomRange(start = 11.5, end = 13.5)

    private fun fadeOf(range: ZoomRange) = """["interpolate",["linear"],["zoom"],${range.start},1,${range.end},0]"""

    private fun spec(relief: ReliefSource = ReliefSource.Painted(maxZoom = 12), reliefFade: ZoomRange = fadeRange) =
        MapStyleSpec(
            relief = relief,
            reliefFade = reliefFade,
            colours =
                MapColours(
                    sea = 0xFF9FC3D8.toInt(),
                    land = 0xFFF1E6C8.toInt(),
                    coast = 0xFF5B7F95.toInt(),
                    ink = 0xFF967648.toInt(),
                ),
        )

    private fun valueSpec(ramp: List<RampStep> = PAIRED_RAMP) = spec(ReliefSource.Value(maxZoom = 12, ramp = ramp))

    private fun style(spec: MapStyleSpec) = JSONObject(styleJson(files, spec))

    private fun JSONObject.layer(id: String): JSONObject {
        val layers = getJSONArray("layers")
        return (0 until layers.length()).map { layers.getJSONObject(it) }.first { it.getString("id") == id }
    }

    private fun JSONObject.paint(layer: String, key: String) =
        layer(layer).getJSONObject("paint").getJSONArray(key).toString()

    @Test
    fun paintedStyle_parsesAndUsesARasterLayer() {
        val style = style(spec())

        val layers = style.getJSONArray("layers")
        assertEquals(8, style.getInt("version"))
        assertEquals(
            listOf("background", "relief"),
            (0 until layers.length()).map {
                layers.getJSONObject(it).getString("id")
            },
        )
        assertEquals("background", style.layer("background").getString("type"))
        assertEquals("raster", style.layer("relief").getString("type"))
        assertEquals(RELIEF_SOURCE_ID, style.layer("relief").getString("source"))
    }

    @Test
    fun paintedStyle_fadesOverReliefFade() {
        assertEquals(fadeOf(fadeRange), style(spec()).paint("relief", "raster-opacity"))

        val later = ZoomRange(start = 13.5, end = 15.5)
        assertEquals(fadeOf(later), style(spec(reliefFade = later)).paint("relief", "raster-opacity"))
    }

    @Test
    fun reliefSource_usesPmtilesFileUrl_tileSize256_andMaxZoom() {
        val source = style(spec()).getJSONObject("sources").getJSONObject("relief")
        assertEquals("pmtiles://file:///data/t/relief.pmtiles", source.getString("url"))
        assertEquals("raster", source.getString("type"))
        assertEquals(256, source.getInt("tileSize"))
        assertEquals(12, source.getInt("maxzoom"))

        val lower = style(spec(ReliefSource.Painted(maxZoom = 11))).getJSONObject("sources").getJSONObject("relief")
        assertEquals(11, lower.getInt("maxzoom"))
    }

    @Test
    fun valueStyle_usesTerrariumRasterDem_andColorRelief() {
        val style = style(valueSpec())

        val source = style.getJSONObject("sources").getJSONObject("relief")
        assertEquals("raster-dem", source.getString("type"))
        assertEquals("terrarium", source.getString("encoding"))
        assertEquals("color-relief", style.layer("relief").getString("type"))
        assertEquals(RELIEF_SOURCE_ID, style.layer("relief").getString("source"))
        assertEquals(fadeOf(fadeRange), style.paint("relief", "color-relief-opacity"))
        assertEquals("pmtiles://file:///data/t/relief.pmtiles", source.getString("url"))
        assertEquals(256, source.getInt("tileSize"))
        assertEquals(12, source.getInt("maxzoom"))
    }

    @Test
    fun valueStyle_hasPairedRampStops() {
        val expected =
            """["interpolate",["linear"],["elevation"],""" +
                """42,"rgba(150, 118, 72, 0)",43,"rgba(150, 118, 72, 0.2)",""" +
                """127,"rgba(150, 118, 72, 0.2)",128,"rgba(150, 118, 72, 0.4)",""" +
                """212,"rgba(150, 118, 72, 0.4)",213,"rgba(150, 118, 72, 0.6)"]"""

        assertEquals(expected, style(valueSpec()).paint("relief", "color-relief-color"))
    }

    @Test
    fun valueStyle_rejectsARampThatIsNotAscending() {
        val descending = PAIRED_RAMP.reversed()
        val repeated = listOf(RampStep(42.0, 0x00967648), RampStep(42.0, 0x33967648))

        assertFailsWith<IllegalArgumentException> { styleJson(files, valueSpec(descending)) }
        assertFailsWith<IllegalArgumentException> { styleJson(files, valueSpec(repeated)) }
    }

    @Test
    fun valueStyle_rejectsAnEmptyRamp() {
        assertFailsWith<IllegalArgumentException> { styleJson(files, valueSpec(emptyList())) }
    }

    @Test
    fun background_isLandColour() {
        assertEquals(
            "rgba(241, 230, 200, 1)",
            style(spec()).layer("background").getJSONObject("paint").getString("background-color"),
        )
    }

    private companion object {
        const val RELIEF_SOURCE_ID = "relief"

        val PAIRED_RAMP =
            listOf(
                RampStep(42.0, 0x00967648),
                RampStep(43.0, 0x33967648),
                RampStep(127.0, 0x33967648),
                RampStep(128.0, 0x66967648),
                RampStep(212.0, 0x66967648),
                RampStep(213.0, 0x99967648.toInt()),
            )
    }
}
