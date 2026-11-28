package app.seerah.model

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class MapMetadataTest {
    private val validCameraBounds = GeoBounds(west = 34.0, south = 12.0, east = 56.0, north = 32.0)
    private val validDataExtent = GeoBounds(west = 33.5, south = 11.5, east = 56.5, north = 32.5)
    private val validBase = BaseSourceMetadata(
        file = "base.pmtiles",
        layers = setOf("land", "coast"),
        minZoom = 0,
        maxZoom = 10,
    )
    private val validRelief = ReliefSourceMetadata(
        file = "relief.pmtiles",
        encoding = ReliefEncoding.TERRARIUM_VALUE,
        tileFormat = "png",
        minZoom = 0,
        maxZoom = 12,
    )
    private val validCredits = listOf(
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
    )

    private fun validMetadata(
        tileSchema: Int = 1,
        cameraBounds: GeoBounds = validCameraBounds,
        dataExtent: GeoBounds = validDataExtent,
        minZoom: Double = 4.0,
        maxZoom: Double = 14.0,
        sources: MapSourcesMetadata = MapSourcesMetadata(validBase, validRelief),
        credits: List<MapCredit> = validCredits,
    ) = MapMetadata(
        tileSchema = tileSchema,
        cameraBounds = cameraBounds,
        dataExtent = dataExtent,
        minZoom = minZoom,
        maxZoom = maxZoom,
        sources = sources,
        credits = credits,
    )

    @Test
    fun validMetadata_constructsCleanly() {
        val meta = validMetadata()
        assertEquals(1, meta.tileSchema)
        assertEquals(ReliefEncoding.TERRARIUM_VALUE, meta.sources.relief.encoding)
    }

    @Test
    fun bounds_rejectNonFiniteOrInvertedCoordinates() {
        assertFailsWith<IllegalArgumentException> { GeoBounds(Double.NaN, 10.0, 20.0, 20.0) }
        assertFailsWith<IllegalArgumentException> { GeoBounds(10.0, Double.POSITIVE_INFINITY, 20.0, 20.0) }
        assertFailsWith<IllegalArgumentException> { GeoBounds(20.0, 10.0, 20.0, 20.0) }
        assertFailsWith<IllegalArgumentException> { GeoBounds(10.0, 20.0, 20.0, 10.0) }
        assertFailsWith<IllegalArgumentException> { GeoBounds(-181.0, 10.0, 20.0, 20.0) }
        assertFailsWith<IllegalArgumentException> { GeoBounds(10.0, -86.0, 20.0, 20.0) }
        assertFailsWith<IllegalArgumentException> { GeoBounds(10.0, 10.0, 20.0, 86.0) }
    }

    @Test
    fun baseSource_rejectsInvalidFileLayersOrZooms() {
        assertFailsWith<IllegalArgumentException> { BaseSourceMetadata(" ", setOf("land", "coast"), 0, 10) }
        assertFailsWith<IllegalArgumentException> { BaseSourceMetadata("base.pmtiles", setOf("land"), 0, 10) }
        assertFailsWith<IllegalArgumentException> {
            BaseSourceMetadata("base.pmtiles", setOf("land", "coast", " "), 0, 10)
        }
        assertFailsWith<IllegalArgumentException> {
            BaseSourceMetadata("base.pmtiles", setOf("land", "coast"), 1, 10)
        }
        assertFailsWith<IllegalArgumentException> {
            BaseSourceMetadata("base.pmtiles", setOf("land", "coast"), 0, 5)
        }
        assertFailsWith<IllegalArgumentException> {
            BaseSourceMetadata("base.pmtiles", setOf("land", "coast"), 0, 13)
        }
    }

    @Test
    fun reliefSource_rejectsMismatchedFormatOrInvalidZooms() {
        assertFailsWith<IllegalArgumentException> {
            ReliefSourceMetadata("", ReliefEncoding.PAINTED_RGBA, "webp", 0, 12)
        }
        assertFailsWith<IllegalArgumentException> {
            ReliefSourceMetadata("relief.pmtiles", ReliefEncoding.TERRARIUM_VALUE, "webp", 0, 12)
        }
        assertFailsWith<IllegalArgumentException> {
            ReliefSourceMetadata("relief.pmtiles", ReliefEncoding.PAINTED_RGBA, "png", 0, 12)
        }
        assertFailsWith<IllegalArgumentException> {
            ReliefSourceMetadata("relief.pmtiles", ReliefEncoding.PAINTED_RGBA, "webp", 1, 12)
        }
        assertFailsWith<IllegalArgumentException> {
            ReliefSourceMetadata("relief.pmtiles", ReliefEncoding.PAINTED_RGBA, "webp", 0, 13)
        }
    }

    @Test
    fun mapCredit_rejectsBlankRequiredOrBlankOptionalLiability() {
        assertFailsWith<IllegalArgumentException> { MapCredit("", "s", "a") }
        assertFailsWith<IllegalArgumentException> { MapCredit("id", " ", "a") }
        assertFailsWith<IllegalArgumentException> { MapCredit("id", "s", "") }
        assertFailsWith<IllegalArgumentException> { MapCredit("id", "s", "a", "   ") }
    }

    @Test
    fun mapMetadata_acceptsDataExtentWithinToleranceAndRejectsUncoveredCameraBounds() {
        val tightExtent = GeoBounds(
            west = validCameraBounds.west + 5e-10,
            south = validCameraBounds.south + 5e-10,
            east = validCameraBounds.east - 5e-10,
            north = validCameraBounds.north - 5e-10,
        )
        validMetadata(dataExtent = tightExtent)

        val smallerExtent = GeoBounds(west = 35.0, south = 12.0, east = 56.0, north = 32.0)
        assertFailsWith<IllegalArgumentException> { validMetadata(dataExtent = smallerExtent) }
    }

    @Test
    fun mapMetadata_rejectsInvalidSchemaZoomsOrTooFewCredits() {
        assertFailsWith<IllegalArgumentException> { validMetadata(tileSchema = 2) }
        assertFailsWith<IllegalArgumentException> { validMetadata(minZoom = -0.1) }
        assertFailsWith<IllegalArgumentException> { validMetadata(minZoom = 10.0, maxZoom = 10.0) }
        assertFailsWith<IllegalArgumentException> { validMetadata(maxZoom = 22.1) }
        assertFailsWith<IllegalArgumentException> { validMetadata(minZoom = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { validMetadata(credits = validCredits.take(1)) }
    }
}
