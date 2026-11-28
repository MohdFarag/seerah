package app.seerah.content

import app.seerah.model.BaseSourceMetadata
import app.seerah.model.GeoBounds
import app.seerah.model.MapCredit
import app.seerah.model.MapMetadata
import app.seerah.model.MapSourcesMetadata
import app.seerah.model.ReliefEncoding
import app.seerah.model.ReliefSourceMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

private const val MAX_LON_DEG = 180.0
private const val MAX_WEBMERCATOR_LAT_DEG = 85.0511287798066
private const val BOUNDS_TOLERANCE_DEG = 1e-9
private const val SUPPORTED_TILE_SCHEMA = 1
private const val MIN_CAMERA_ZOOM = 0.0
private const val MAX_CAMERA_ZOOM = 22.0
private const val REQUIRED_BASE_FILE = "base.pmtiles"
private const val REQUIRED_RELIEF_FILE = "relief.pmtiles"
private const val REQUIRED_TILE_SIZE = 256
private const val MIN_SOURCE_MAX_ZOOM = 6
private const val MAX_SOURCE_MAX_ZOOM = 12

private val CREDIT_ID_REGEX = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

/**
 * Validates and converts `data/map.json` into `:core:model`'s [MapMetadata] without throwing
 * unhandled exceptions on untrusted or corrupt packages.
 */
internal object MapMetadataParser {
    private val wireJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
    }

    @Serializable
    private data class BoundsDto(val west: Double, val south: Double, val east: Double, val north: Double)

    @Serializable
    private data class BaseSourceDto(
        val file: String,
        val layers: List<String>,
        @SerialName("min_zoom")
        val minZoom: Int,
        @SerialName("max_zoom")
        val maxZoom: Int,
    )

    @Serializable
    private data class ReliefSourceDto(
        val file: String,
        val encoding: String,
        @SerialName("tile_format")
        val tileFormat: String,
        @SerialName("tile_size")
        val tileSize: JsonPrimitive,
        @SerialName("min_zoom")
        val minZoom: Int,
        @SerialName("max_zoom")
        val maxZoom: Int,
    )

    @Serializable
    private data class SourcesDto(val base: BaseSourceDto, val relief: ReliefSourceDto)

    @Serializable
    private data class CreditDto(
        val id: String,
        val source: String,
        val attribution: String,
        val liability: String? = null,
    )

    @Serializable
    private data class MapJsonDto(
        @SerialName("tile_schema")
        val tileSchema: JsonPrimitive,
        @SerialName("camera_bounds")
        val cameraBounds: BoundsDto,
        @SerialName("data_extent")
        val dataExtent: BoundsDto,
        @SerialName("min_zoom")
        val minZoom: Double,
        @SerialName("max_zoom")
        val maxZoom: Double,
        val sources: SourcesDto,
        val credits: List<CreditDto>,
    )

    fun parseAndValidate(rawBytes: ByteArray): PackageResult<MapMetadata> {
        if (rawBytes.isEmpty() || rawBytes.size > ONE_MIB_BYTES) {
            return invalidMap("data/map.json size ${rawBytes.size} outside 1..$ONE_MIB_BYTES bytes.")
        }
        val text = decodeStrictUtf8(rawBytes)
            ?: return invalidMap("data/map.json is not valid UTF-8.")

        val rootElement = try {
            wireJson.parseToJsonElement(text)
        } catch (exc: SerializationException) {
            return invalidMap("Invalid data/map.json JSON: ${exc.message}")
        } catch (exc: IllegalArgumentException) {
            return invalidMap("Invalid data/map.json JSON: ${exc.message}")
        }
        if (rootElement !is JsonObject) {
            return invalidMap("data/map.json root must be a JSON object.")
        }

        // Validate tile_schema before reconstructed_areas so unsupported schemas fail cleanly as INVALID_MAP_METADATA.
        val rawTileSchema = rootElement["tile_schema"] as? JsonPrimitive
            ?: return invalidMap("data/map.json missing numeric 'tile_schema'.")
        if (!isExactJsonInteger(rawTileSchema, SUPPORTED_TILE_SCHEMA)) {
            return invalidMap("Unsupported tile_schema '$rawTileSchema'; expected integer $SUPPORTED_TILE_SCHEMA.")
        }

        // Check reconstructed_areas strictly as an untyped array BEFORE model checks (D5 / open-decisions).
        val reconstructedElem = rootElement["reconstructed_areas"]
            ?: return invalidMap("data/map.json missing required 'reconstructed_areas'.")
        if (reconstructedElem !is JsonArray) {
            return invalidMap("data/map.json 'reconstructed_areas' must be a JSON array.")
        }
        if (reconstructedElem.isNotEmpty()) {
            return PackageResult.Failure(PackageFailure.UnsupportedReconstructedAreas(reconstructedElem.size))
        }

        val dto = try {
            wireJson.decodeFromJsonElement<MapJsonDto>(rootElement)
        } catch (exc: SerializationException) {
            return invalidMap("data/map.json schema mismatch: ${exc.message}")
        } catch (exc: IllegalArgumentException) {
            return invalidMap("data/map.json schema mismatch: ${exc.message}")
        }

        return validateDecoded(rootElement, dto)
    }

    private fun validateDecoded(rootElement: JsonObject, dto: MapJsonDto): PackageResult<MapMetadata> {
        if (!isExactJsonInteger(dto.tileSchema, SUPPORTED_TILE_SCHEMA)) {
            return invalidMap("Unsupported tile_schema '${dto.tileSchema}'; expected integer $SUPPORTED_TILE_SCHEMA.")
        }

        val cameraBounds = validateBounds("camera_bounds", dto.cameraBounds)
            ?: return invalidMap("Invalid camera_bounds in data/map.json.")
        val dataExtent = validateBounds("data_extent", dto.dataExtent)
            ?: return invalidMap("Invalid data_extent in data/map.json.")
        if (!covers(dataExtent, cameraBounds)) {
            return invalidMap("data_extent does not cover camera_bounds within $BOUNDS_TOLERANCE_DEG degrees.")
        }

        if (
            !dto.minZoom.isFinite() ||
            !dto.maxZoom.isFinite() ||
            dto.minZoom < MIN_CAMERA_ZOOM ||
            dto.minZoom >= dto.maxZoom ||
            dto.maxZoom > MAX_CAMERA_ZOOM
        ) {
            return invalidMap("Invalid camera zoom range (${dto.minZoom}..${dto.maxZoom}).")
        }

        val base = validateBaseSource(dto.sources.base)
            ?: return invalidMap("Invalid sources.base in data/map.json.")
        val relief = validateReliefSource(dto.sources.relief)
            ?: return invalidMap("Invalid sources.relief in data/map.json.")
        val credits = validateCredits(rootElement["credits"] as? JsonArray, dto.credits)
            ?: return invalidMap("Invalid or missing required credits in data/map.json.")

        return PackageResult.Success(
            MapMetadata(
                tileSchema = SUPPORTED_TILE_SCHEMA,
                cameraBounds = cameraBounds,
                dataExtent = dataExtent,
                minZoom = dto.minZoom,
                maxZoom = dto.maxZoom,
                sources = MapSourcesMetadata(base = base, relief = relief),
                credits = credits,
            ),
        )
    }

    private fun validateBounds(label: String, dto: BoundsDto): GeoBounds? {
        if (!dto.west.isFinite() || !dto.south.isFinite() || !dto.east.isFinite() || !dto.north.isFinite()) {
            return null
        }
        if (dto.west < -MAX_LON_DEG || dto.east > MAX_LON_DEG || dto.west >= dto.east) {
            return null
        }
        if (
            dto.south < -MAX_WEBMERCATOR_LAT_DEG ||
            dto.north > MAX_WEBMERCATOR_LAT_DEG ||
            dto.south >= dto.north
        ) {
            return null
        }
        return GeoBounds(dto.west, dto.south, dto.east, dto.north)
    }

    private fun covers(outer: GeoBounds, inner: GeoBounds): Boolean = outer.west <= inner.west + BOUNDS_TOLERANCE_DEG &&
        outer.south <= inner.south + BOUNDS_TOLERANCE_DEG &&
        outer.east >= inner.east - BOUNDS_TOLERANCE_DEG &&
        outer.north >= inner.north - BOUNDS_TOLERANCE_DEG

    private fun validateBaseSource(dto: BaseSourceDto): BaseSourceMetadata? {
        if (dto.file != REQUIRED_BASE_FILE) return null
        if (dto.minZoom != 0 || dto.maxZoom !in MIN_SOURCE_MAX_ZOOM..MAX_SOURCE_MAX_ZOOM) return null
        if (dto.layers.any { it.isBlank() }) return null
        val unique = dto.layers.toSet()
        if (unique.size != dto.layers.size) return null
        if ("land" !in unique || "coast" !in unique) return null
        return BaseSourceMetadata(
            file = dto.file,
            layers = unique,
            minZoom = dto.minZoom,
            maxZoom = dto.maxZoom,
        )
    }

    private fun validateReliefSource(dto: ReliefSourceDto): ReliefSourceMetadata? {
        if (dto.file != REQUIRED_RELIEF_FILE) return null
        if (!isExactJsonInteger(dto.tileSize, REQUIRED_TILE_SIZE)) return null
        if (dto.minZoom != 0 || dto.maxZoom !in MIN_SOURCE_MAX_ZOOM..MAX_SOURCE_MAX_ZOOM) return null
        val encoding = ReliefEncoding.entries.firstOrNull { it.wireName == dto.encoding } ?: return null
        if (dto.tileFormat != encoding.tileFormat) return null
        return ReliefSourceMetadata(
            file = dto.file,
            encoding = encoding,
            tileFormat = dto.tileFormat,
            minZoom = dto.minZoom,
            maxZoom = dto.maxZoom,
        )
    }

    private fun validateCredits(rawCredits: JsonArray?, dtos: List<CreditDto>): List<MapCredit>? {
        if (rawCredits == null || rawCredits.size != dtos.size || dtos.size < 2) return null
        val seenIds = HashSet<String>(dtos.size)
        val credits = ArrayList<MapCredit>(dtos.size)

        for (i in dtos.indices) {
            val rawObj = rawCredits[i] as? JsonObject ?: return null
            if ("liability" in rawObj && rawObj["liability"] is JsonNull) return null
            val dto = dtos[i]
            if (!CREDIT_ID_REGEX.matches(dto.id)) return null
            if (!seenIds.add(dto.id)) return null
            if (dto.source.isBlank() || dto.attribution.isBlank()) return null
            if (dto.liability != null && dto.liability.isBlank()) return null
            credits.add(
                MapCredit(
                    id = dto.id,
                    source = dto.source,
                    attribution = dto.attribution,
                    liability = dto.liability,
                ),
            )
        }

        val copernicus = credits.firstOrNull { it.id == "copernicus-worlddem-30" } ?: return null
        if (copernicus.liability.isNullOrBlank()) return null
        if (credits.none { it.id == "natural-earth" }) return null
        return credits
    }

    internal fun isExactJsonInteger(primitive: JsonPrimitive, expected: Int): Boolean {
        if (primitive is JsonNull || primitive.isString) return false
        val raw = primitive.content
        if ('.' in raw || 'e' in raw || 'E' in raw) return false
        return primitive.intOrNull == expected
    }

    private fun invalidMap(detail: String): PackageResult.Failure =
        PackageResult.Failure(PackageFailure.InvalidPackage(InvalidPackageCause.INVALID_MAP_METADATA, detail))
}
