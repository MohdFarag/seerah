package app.seerah.map

import app.seerah.model.BaseSourceMetadata
import app.seerah.model.GeoBounds
import app.seerah.model.MapCredit
import app.seerah.model.MapMetadata
import app.seerah.model.MapSourcesMetadata
import app.seerah.model.ReliefEncoding
import app.seerah.model.ReliefSourceMetadata
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class MapPackageBindingTest {
    private val colours = MapColours(
        sea = 0xFF9FC3D8.toInt(),
        land = 0xFFF1E6C8.toInt(),
        coast = 0xFF5B7F95.toInt(),
        ink = 0xFF967648.toInt(),
    )

    private val fade = ZoomRange(start = 13.5, end = 15.5)

    private fun sampleMetadata(
        encoding: ReliefEncoding = ReliefEncoding.TERRARIUM_VALUE,
        baseFile: String = "base.pmtiles",
        reliefFile: String = "relief.pmtiles",
    ): MapMetadata = MapMetadata(
        tileSchema = 1,
        cameraBounds = GeoBounds(west = 34.5, south = 12.25, east = 55.5, north = 31.75),
        dataExtent = GeoBounds(west = 33.5, south = 11.5, east = 56.5, north = 32.5),
        minZoom = 4.0,
        maxZoom = 14.0,
        sources = MapSourcesMetadata(
            base = BaseSourceMetadata(
                file = baseFile,
                layers = setOf("land", "coast"),
                minZoom = 0,
                maxZoom = 10,
            ),
            relief = ReliefSourceMetadata(
                file = reliefFile,
                encoding = encoding,
                tileFormat = encoding.tileFormat,
                minZoom = 0,
                maxZoom = 12,
            ),
        ),
        credits = listOf(
            MapCredit(
                id = "copernicus-worlddem-30",
                source = "Copernicus DEM GLO-30",
                attribution = "produced using Copernicus WorldDEM-30",
                liability = "No liability.",
            ),
            MapCredit(
                id = "natural-earth",
                source = "Natural Earth",
                attribution = "Public domain.",
            ),
        ),
    )

    @Test
    fun toCameraLimits_mapsBoundsAndZoomRange() {
        val limits = sampleMetadata().toCameraLimits()
        assertEquals(
            CameraLimits(west = 34.5, south = 12.25, east = 55.5, north = 31.75, minZoom = 4.0, maxZoom = 14.0),
            limits,
        )
    }

    @Test
    fun resolveMapFiles_preservesReliefBeforeBaseAndRejectsEscapes() {
        val root = File("/tmp/seerah-map-test/data")
        val files = resolveMapFiles(root, sampleMetadata())

        assertEquals(File(root, "relief.pmtiles").canonicalFile, files.relief)
        assertEquals(File(root, "base.pmtiles").canonicalFile, files.base)

        assertFailsWith<IllegalArgumentException> {
            resolveMapFiles(root, sampleMetadata(baseFile = "../escape.pmtiles"))
        }
        assertFailsWith<IllegalArgumentException> {
            resolveMapFiles(root, sampleMetadata(reliefFile = "../data2/relief.pmtiles"))
        }
        assertFailsWith<IllegalArgumentException> {
            resolveMapFiles(root, sampleMetadata(reliefFile = "/etc/relief.pmtiles"))
        }
        assertFailsWith<IllegalArgumentException> {
            resolveMapFiles(root, sampleMetadata(baseFile = "."))
        }
    }

    @Test
    fun styleCValueRamp_masksInkAlphaAndKeepsSteppedBands() {
        val ramp = styleCValueRamp(0xFF967648.toInt())
        assertEquals(
            listOf(
                RampStep(42.0, 0x00967648),
                RampStep(43.0, 0x33967648),
                RampStep(127.0, 0x33967648),
                RampStep(128.0, 0x66967648),
                RampStep(212.0, 0x66967648),
                RampStep(213.0, 0x99967648.toInt()),
            ),
            ramp,
        )
    }

    @Test
    fun toStyleSpec_mapsBothEncodingsAndCustomRamp() {
        val valueSpec = sampleMetadata(ReliefEncoding.TERRARIUM_VALUE).toStyleSpec(colours, fade)
        assertEquals(ReliefSource.Value(maxZoom = 12, ramp = styleCValueRamp(colours.ink)), valueSpec.relief)
        assertEquals(fade, valueSpec.reliefFade)
        assertEquals(colours, valueSpec.colours)

        val customRamp = listOf(RampStep(10.0, 0x11223344), RampStep(20.0, 0x55223344))
        val customSpec = sampleMetadata(ReliefEncoding.TERRARIUM_VALUE).toStyleSpec(colours, fade, customRamp)
        assertEquals(ReliefSource.Value(maxZoom = 12, ramp = customRamp), customSpec.relief)

        val paintedSpec = sampleMetadata(ReliefEncoding.PAINTED_RGBA).toStyleSpec(colours, fade)
        assertEquals(ReliefSource.Painted(maxZoom = 12), paintedSpec.relief)
    }
}
