package app.seerah.model

private const val MIN_LON_DEG = -180.0
private const val MAX_LON_DEG = 180.0
private const val MAX_WEBMERCATOR_LAT_DEG = 85.0511287798066
private const val BOUNDS_TOLERANCE_DEG = 1e-9
private const val SUPPORTED_TILE_SCHEMA = 1
private const val MIN_CAMERA_ZOOM = 0.0
private const val MAX_CAMERA_ZOOM = 22.0
private const val REQUIRED_SOURCE_MIN_ZOOM = 0
private const val MIN_SOURCE_MAX_ZOOM = 6
private const val MAX_SOURCE_MAX_ZOOM = 12
private const val MIN_REQUIRED_CREDITS = 2
private const val REQUIRED_LAYER_LAND = "land"
private const val REQUIRED_LAYER_COAST = "coast"

/**
 * Geographic bounding box in WGS84 degrees within WebMercator latitude bounds.
 *
 * @property west Western longitude in degrees (`-180.0..180.0`), strictly less than [east].
 * @property south Southern latitude in degrees (`-85.0511287798066..85.0511287798066`), strictly less than [north].
 * @property east Eastern longitude in degrees (`-180.0..180.0`).
 * @property north Northern latitude in degrees (`-85.0511287798066..85.0511287798066`).
 */
data class GeoBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    init {
        require(west.isFinite() && south.isFinite() && east.isFinite() && north.isFinite()) {
            "Bounds coordinates must be finite: $this"
        }
        require(west in MIN_LON_DEG..MAX_LON_DEG && east in MIN_LON_DEG..MAX_LON_DEG && west < east) {
            "Longitude bounds must satisfy -180 <= west < east <= 180: ($west, $east)"
        }
        require(
            south in -MAX_WEBMERCATOR_LAT_DEG..MAX_WEBMERCATOR_LAT_DEG &&
                north in -MAX_WEBMERCATOR_LAT_DEG..MAX_WEBMERCATOR_LAT_DEG &&
                south < north,
        ) {
            "Latitude bounds must satisfy WebMercator limits and south < north: ($south, $north)"
        }
    }

    /** Returns true when this box covers [inner] within [BOUNDS_TOLERANCE_DEG] degrees. */
    internal fun covers(inner: GeoBounds): Boolean = west <= inner.west + BOUNDS_TOLERANCE_DEG &&
        south <= inner.south + BOUNDS_TOLERANCE_DEG &&
        east >= inner.east - BOUNDS_TOLERANCE_DEG &&
        north >= inner.north - BOUNDS_TOLERANCE_DEG
}

/**
 * Encoding of relief terrain tiles inside a `tile_schema: 1` map package.
 *
 * @property wireName Canonical string stored in `data/map.json` `sources.relief.encoding`.
 * @property tileFormat Matching image format string stored in `sources.relief.tile_format`.
 */
enum class ReliefEncoding(val wireName: String, val tileFormat: String) {
    /** Terrarium RGB elevation values shaded dynamically at runtime using a color ramp. */
    TERRARIUM_VALUE("terrarium-value", "png"),

    /** Pre-shaded Style C relief images stored directly as RGBA WebP tiles. */
    PAINTED_RGBA("painted-rgba", "webp"),
}

/**
 * Vector base tile archive descriptor from `data/map.json` `sources.base`.
 *
 * @property file Relative filename inside the package `data/` directory (e.g., `base.pmtiles`).
 * @property layers Layer names present in the vector archive; must include `land` and `coast`.
 * @property minZoom Lowest zoom level built into the base archive (always `0`).
 * @property maxZoom Highest zoom level built into the base archive (`6..12`).
 */
data class BaseSourceMetadata(val file: String, val layers: Set<String>, val minZoom: Int, val maxZoom: Int) {
    init {
        require(file.isNotBlank()) { "Base source file name must not be blank." }
        require(
            REQUIRED_LAYER_LAND in layers &&
                REQUIRED_LAYER_COAST in layers &&
                layers.all { it.isNotBlank() },
        ) {
            "Base source layers must be non-blank and contain '$REQUIRED_LAYER_LAND' and '$REQUIRED_LAYER_COAST'."
        }
        require(minZoom == REQUIRED_SOURCE_MIN_ZOOM && maxZoom in MIN_SOURCE_MAX_ZOOM..MAX_SOURCE_MAX_ZOOM) {
            "Base source zoom range must start at 0 with maxZoom in 6..12: ($minZoom..$maxZoom)"
        }
    }
}

/**
 * Relief raster tile archive descriptor from `data/map.json` `sources.relief`.
 *
 * @property file Relative filename inside the package `data/` directory (e.g., `relief.pmtiles`).
 * @property encoding How relief pixels are encoded (`TERRARIUM_VALUE` or `PAINTED_RGBA`).
 * @property tileFormat Image container format matching [ReliefEncoding.tileFormat].
 * @property minZoom Lowest zoom level built into the relief archive (always `0`).
 * @property maxZoom Highest zoom level built into the relief archive (`6..12`).
 */
data class ReliefSourceMetadata(
    val file: String,
    val encoding: ReliefEncoding,
    val tileFormat: String,
    val minZoom: Int,
    val maxZoom: Int,
) {
    init {
        require(file.isNotBlank()) { "Relief source file name must not be blank." }
        require(tileFormat == encoding.tileFormat) {
            "Relief tileFormat '$tileFormat' does not match encoding ${encoding.name} ('${encoding.tileFormat}')."
        }
        require(minZoom == REQUIRED_SOURCE_MIN_ZOOM && maxZoom in MIN_SOURCE_MAX_ZOOM..MAX_SOURCE_MAX_ZOOM) {
            "Relief source zoom range must start at 0 with maxZoom in 6..12: ($minZoom..$maxZoom)"
        }
    }
}

/**
 * Required `sources` block parsed from `data/map.json`.
 *
 * @property base Vector base archive metadata.
 * @property relief Relief raster/DEM archive metadata.
 */
data class MapSourcesMetadata(val base: BaseSourceMetadata, val relief: ReliefSourceMetadata)

/**
 * Data-source attribution credit from `data/map.json`.
 *
 * @property id Stable identifier slug (e.g., `copernicus-worlddem-30`, `natural-earth`).
 * @property source Human-readable dataset name.
 * @property attribution Required attribution notice for this dataset.
 * @property liability Optional legal liability notice required by the source licence.
 */
data class MapCredit(val id: String, val source: String, val attribution: String, val liability: String? = null) {
    init {
        require(id.isNotBlank() && source.isNotBlank() && attribution.isNotBlank()) {
            "Credit id, source, and attribution must not be blank."
        }
        require(liability == null || liability.isNotBlank()) {
            "Credit liability notice must be null or non-blank."
        }
    }
}

/**
 * Validated metadata from a `tile_schema: 1` `data/map.json` file.
 *
 * @property tileSchema Schema version of the map package (always `1`).
 * @property cameraBounds Permitted MapLibre camera bounds, covered by [dataExtent].
 * @property dataExtent Geographic extent covered by the built map tiles.
 * @property minZoom Minimum interactive MapLibre camera zoom (`>= 0.0`, less than [maxZoom]).
 * @property maxZoom Maximum interactive MapLibre camera zoom (`<= 22.0`).
 * @property sources Vector base and relief archive descriptors.
 * @property credits Attribution and liability notices shipped with the map package.
 */
data class MapMetadata(
    val tileSchema: Int,
    val cameraBounds: GeoBounds,
    val dataExtent: GeoBounds,
    val minZoom: Double,
    val maxZoom: Double,
    val sources: MapSourcesMetadata,
    val credits: List<MapCredit>,
) {
    init {
        require(tileSchema == SUPPORTED_TILE_SCHEMA) {
            "Unsupported tileSchema $tileSchema; expected $SUPPORTED_TILE_SCHEMA."
        }
        require(
            minZoom.isFinite() &&
                maxZoom.isFinite() &&
                MIN_CAMERA_ZOOM <= minZoom &&
                minZoom < maxZoom &&
                maxZoom <= MAX_CAMERA_ZOOM,
        ) {
            "Map zoom range must satisfy 0.0 <= minZoom < maxZoom <= 22.0: ($minZoom..$maxZoom)"
        }
        require(dataExtent.covers(cameraBounds)) {
            "dataExtent ($dataExtent) must cover cameraBounds ($cameraBounds)."
        }
        require(credits.size >= MIN_REQUIRED_CREDITS) {
            "MapMetadata requires at least $MIN_REQUIRED_CREDITS credits, got ${credits.size}."
        }
    }
}
