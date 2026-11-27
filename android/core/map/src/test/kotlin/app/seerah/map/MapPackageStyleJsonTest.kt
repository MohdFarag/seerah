package app.seerah.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.seerah.model.BaseSourceMetadata
import app.seerah.model.GeoBounds
import app.seerah.model.MapCredit
import app.seerah.model.MapMetadata
import app.seerah.model.MapSourcesMetadata
import app.seerah.model.ReliefEncoding
import app.seerah.model.ReliefSourceMetadata
import java.io.File
import kotlin.test.assertEquals
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapPackageStyleJsonTest {
    private val colours = MapColours(
        sea = 0xFF9FC3D8.toInt(),
        land = 0xFFF1E6C8.toInt(),
        coast = 0xFF5B7F95.toInt(),
        ink = 0xFF967648.toInt(),
    )

    private val fade = ZoomRange(start = 13.5, end = 15.5)

    private fun sampleMetadata(encoding: ReliefEncoding): MapMetadata = MapMetadata(
        tileSchema = 1,
        cameraBounds = GeoBounds(west = 34.0, south = 12.0, east = 56.0, north = 32.0),
        dataExtent = GeoBounds(west = 33.5, south = 11.5, east = 56.5, north = 32.5),
        minZoom = 4.0,
        maxZoom = 14.0,
        sources = MapSourcesMetadata(
            base = BaseSourceMetadata("base.pmtiles", setOf("land", "coast"), minZoom = 0, maxZoom = 10),
            relief = ReliefSourceMetadata("relief.pmtiles", encoding, encoding.tileFormat, minZoom = 0, maxZoom = 11),
        ),
        credits = listOf(
            MapCredit("copernicus-worlddem-30", "Copernicus", "Attr", "No liability."),
            MapCredit("natural-earth", "Natural Earth", "Public domain."),
        ),
    )

    @Test
    fun terrariumValueMetadata_producesValidColorReliefStyleJson() {
        val dataDir = File("/tmp/seerah-map-json-test/data")
        val metadata = sampleMetadata(ReliefEncoding.TERRARIUM_VALUE)
        val style = JSONObject(styleJson(resolveMapFiles(dataDir, metadata), metadata.toStyleSpec(colours, fade)))

        val reliefSource = style.getJSONObject("sources").getJSONObject("relief")
        assertEquals("raster-dem", reliefSource.getString("type"))
        assertEquals(256, reliefSource.getInt("tileSize"))
        assertEquals(11, reliefSource.getInt("maxzoom"))
        assertEquals("terrarium", reliefSource.getString("encoding"))

        val layers = style.getJSONArray("layers")
        val reliefLayer = (0 until layers.length())
            .map { layers.getJSONObject(it) }
            .first { it.getString("id") == "relief" }
        assertEquals("color-relief", reliefLayer.getString("type"))
        assertEquals(
            15,
            reliefLayer.getJSONObject("paint").getJSONArray("color-relief-color").length(),
        )
    }

    @Test
    fun paintedRgbaMetadata_producesValidRasterStyleJson() {
        val dataDir = File("/tmp/seerah-map-json-test/data")
        val metadata = sampleMetadata(ReliefEncoding.PAINTED_RGBA)
        val style = JSONObject(styleJson(resolveMapFiles(dataDir, metadata), metadata.toStyleSpec(colours, fade)))

        val reliefSource = style.getJSONObject("sources").getJSONObject("relief")
        assertEquals("raster", reliefSource.getString("type"))
        assertEquals(256, reliefSource.getInt("tileSize"))
        assertEquals(11, reliefSource.getInt("maxzoom"))

        val layers = style.getJSONArray("layers")
        val reliefLayer = (0 until layers.length())
            .map { layers.getJSONObject(it) }
            .first { it.getString("id") == "relief" }
        assertEquals("raster", reliefLayer.getString("type"))
    }
}
