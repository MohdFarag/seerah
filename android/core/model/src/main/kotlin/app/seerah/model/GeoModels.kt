package app.seerah.model

private const val MIN_LON_DEG = -180.0
private const val MAX_LON_DEG = 180.0
private const val MAX_WEBMERCATOR_LAT_DEG = 85.0511287798066
private const val MIN_PROMINENCE = 1
private const val MAX_PROMINENCE = 3
private const val MIN_POLYGON_VERTICES = 4
private const val MIN_ROUTE_POINTS = 2

/**
 * Single geographic coordinate in WGS84 degrees within WebMercator bounds.
 *
 * @property lat Latitude in degrees (`-85.0511287798066..85.0511287798066`).
 * @property lon Longitude in degrees (`-180.0..180.0`).
 */
data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat.isFinite() && lon.isFinite()) { "GeoPoint coordinates must be finite: $this" }
        require(lat in -MAX_WEBMERCATOR_LAT_DEG..MAX_WEBMERCATOR_LAT_DEG) {
            "GeoPoint latitude $lat outside WebMercator limits."
        }
        require(lon in MIN_LON_DEG..MAX_LON_DEG) { "GeoPoint longitude $lon outside -180..180." }
    }
}

/**
 * Physical category of a geographic place used for styling and filtering.
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class PlaceKind(val wireName: String) {
    SETTLEMENT("settlement"),
    MOUNTAIN("mountain"),
    VALLEY("valley"),
    WELL("well"),
    HOUSE("house"),
    MOSQUE("mosque"),
    CAVE("cave"),
    PORT("port"),
    OTHER("other"),
}

/**
 * Established positional certainty for a known place or event reference.
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class KnownAccuracy(val wireName: String) {
    EXACT("exact"),
    GENERAL("general"),
}

/**
 * Exploration visibility rule ([ADR 0005](../../../docs/adr/0005-content-model-exploration-and-story.md) decision 5).
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class Visibility(val wireName: String) {
    /** Visible on the map during free exploration whenever its time state applies. */
    ALWAYS("always"),

    /** Shown on the map only while its owning/parent event or an explicit story step reveals it. */
    IN_CONTEXT("in-context"),
}

/**
 * Provenance basis for a route distance figure.
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class DistanceBasis(val wireName: String) {
    SOURCE("source"),
    ESTIMATE("estimate"),
}

/**
 * Location representation for a [Place], making `accuracy == unknown` coordinates unrepresentable.
 */
sealed interface PlaceLocation {
    /** Named place plotted within the v1 map extent. */
    data class OnMap(val accuracy: KnownAccuracy, val point: GeoPoint) : PlaceLocation

    /** Known place outside the v1 Peninsula map coverage (shown via its own reader presentation). */
    data class OffMap(val accuracy: KnownAccuracy, val point: GeoPoint? = null) : PlaceLocation

    /** Place whose geographic location is unknown (kept in index/events, never plotted). */
    data object Unknown : PlaceLocation
}

/**
 * Point from which a time-scoped [PlaceState] or [AreaState] applies.
 */
sealed interface StateStart : Comparable<StateStart> {
    /** Initial state already established at the beginning of the covered historical period. */
    data object Baseline : StateStart

    /** State change beginning at [point] (month/day omitted acts as start-of-year/month). */
    data class Dated(val point: TimelinePoint) : StateStart

    /** Returns true when this state has taken effect on or before [queryUpperBound]. */
    fun appliesAt(queryUpperBound: ChronologicalKey): Boolean = when (this) {
        Baseline -> true
        is Dated -> point.asStateStartKey() <= queryUpperBound
    }

    override fun compareTo(other: StateStart): Int = when {
        this === other -> 0

        this is Baseline -> -1

        other is Baseline -> 1

        else -> {
            val a = (this as Dated).point
            val b = (other as Dated).point
            compareValuesBy(
                a,
                b,
                TimelinePoint::continuousYear,
                { it.precision.rank },
                { it.month ?: 0 },
                { it.day ?: 0 },
            )
        }
    }
}

/**
 * Time-scoped appearance and naming state for a [Place].
 *
 * @property start When this state begins taking effect.
 * @property name Arabic display name during this period.
 * @property exists Whether the place/landmark exists at this state.
 * @property kindOverride Optional category change (for instance a landmark emerging later).
 */
data class PlaceState(
    val start: StateStart,
    val name: String,
    val exists: Boolean,
    val kindOverride: PlaceKind? = null,
) {
    init {
        require(name.isNotBlank()) { "PlaceState name must not be blank." }
    }
}

/**
 * Geographic entity with one stable identity and a chronological history of states.
 *
 * @property id Stable identifier slug.
 * @property kind Baseline place category.
 * @property location Spatial certainty and optional coordinates.
 * @property visibility Whether the place displays globally or only in event/story context.
 * @property prominence Zoom prominence rank (`1..3`).
 * @property states Non-empty, non-decreasing chronological state history.
 */
data class Place(
    val id: String,
    val kind: PlaceKind,
    val location: PlaceLocation,
    val visibility: Visibility,
    val prominence: Int,
    val states: List<PlaceState>,
) {
    init {
        require(id.isNotBlank()) { "Place id must not be blank." }
        require(prominence in MIN_PROMINENCE..MAX_PROMINENCE) {
            "Place prominence $prominence outside $MIN_PROMINENCE..$MAX_PROMINENCE."
        }
        require(states.isNotEmpty()) { "Place $id must have at least one state." }
        requireNonDecreasing(states.map { it.start }, "Place $id states")
    }
}

/**
 * Time-scoped naming and existence state for an [Area].
 *
 * @property start When this region state takes effect.
 * @property name Arabic display name of the region.
 * @property exists Whether the region label/tint is active.
 */
data class AreaState(val start: StateStart, val name: String, val exists: Boolean) {
    init {
        require(name.isNotBlank()) { "AreaState name must not be blank." }
    }
}

/**
 * Named historical region with soft placement and chronological states.
 *
 * @property id Stable identifier slug.
 * @property labelPoint Optional label anchor coordinate.
 * @property polygonRing Optional closed geographic ring (`>= 4` points with `first == last`).
 * @property offMap Whether the region lies outside the v1 Arabian Peninsula map extent.
 * @property states Non-empty chronological state history.
 */
data class Area(
    val id: String,
    val labelPoint: GeoPoint? = null,
    val polygonRing: List<GeoPoint> = emptyList(),
    val offMap: Boolean = false,
    val states: List<AreaState>,
) {
    init {
        require(id.isNotBlank()) { "Area id must not be blank." }
        require(
            polygonRing.isEmpty() ||
                (polygonRing.size >= MIN_POLYGON_VERTICES && polygonRing.first() == polygonRing.last()),
        ) {
            "Area $id polygonRing must be empty or a closed ring of at least $MIN_POLYGON_VERTICES points."
        }
        require(states.isNotEmpty()) { "Area $id must have at least one state." }
        requireNonDecreasing(states.map { it.start }, "Area $id states")
    }
}

/**
 * Geometry policy for a [Route], separating undocumented station lists from documented polylines.
 */
sealed interface RouteGeometry {
    /** Sourced route polyline aligned with each station index. */
    data class Documented(
        val coordinates: List<GeoPoint>,
        val stationWaypointIndices: List<Int>,
        val accuracyNote: String,
    ) : RouteGeometry {
        init {
            require(coordinates.size >= MIN_ROUTE_POINTS) {
                "Documented route requires at least $MIN_ROUTE_POINTS coordinates, got ${coordinates.size}."
            }
            require(stationWaypointIndices.size >= MIN_ROUTE_POINTS) {
                "Documented route requires at least $MIN_ROUTE_POINTS station waypoint indices."
            }
            require(stationWaypointIndices.all { it in coordinates.indices }) {
                "Route stationWaypointIndices out of bounds for ${coordinates.size} coordinates."
            }
            require(
                stationWaypointIndices.zipWithNext().all { (a, b) -> a <= b },
            ) {
                "Route stationWaypointIndices must be monotonically non-decreasing."
            }
            require(accuracyNote.isNotBlank()) { "Documented route requires a non-blank accuracyNote." }
        }
    }

    /** Stations and sequence only; no line may be drawn between stations on the map. */
    data object Undocumented : RouteGeometry
}

/**
 * Ordered historical route attached to an owning event.
 *
 * @property id Stable identifier slug.
 * @property eventId Owning [SeerahEvent.id].
 * @property stationPlaceIds Ordered list of station [Place.id] values (`>= 2`).
 * @property geometry Documented polyline geometry or [RouteGeometry.Undocumented].
 * @property distanceKm Optional route distance in kilometres (requires [distanceBasis]).
 * @property distanceBasis Provenance for [distanceKm] (`source` or `estimate`).
 * @property days Optional travel duration in days when well-founded in sources.
 */
data class Route(
    val id: String,
    val eventId: String,
    val stationPlaceIds: List<String>,
    val geometry: RouteGeometry,
    val distanceKm: Double? = null,
    val distanceBasis: DistanceBasis? = null,
    val days: Int? = null,
) {
    init {
        require(id.isNotBlank() && eventId.isNotBlank()) { "Route id and eventId must not be blank." }
        require(stationPlaceIds.size >= MIN_ROUTE_POINTS && stationPlaceIds.all { it.isNotBlank() }) {
            "Route $id requires at least $MIN_ROUTE_POINTS non-blank station place IDs."
        }
        if (geometry is RouteGeometry.Documented) {
            require(geometry.stationWaypointIndices.size == stationPlaceIds.size) {
                "Route $id waypoint count (${geometry.stationWaypointIndices.size}) " +
                    "must match stations (${stationPlaceIds.size})."
            }
        }
        require((distanceKm == null) == (distanceBasis == null)) {
            "Route $id distanceKm and distanceBasis must either both be null or both present."
        }
        require(distanceKm == null || (distanceKm.isFinite() && distanceKm > 0.0)) {
            "Route $id distanceKm must be finite and > 0, got $distanceKm."
        }
        require(days == null || days >= 1) { "Route $id days must be >= 1 when set, got $days." }
    }
}

private fun requireNonDecreasing(starts: List<StateStart>, label: String) {
    for (i in 0 until starts.lastIndex) {
        require(starts[i] <= starts[i + 1]) {
            "$label must be non-decreasing in chronological order at index $i (${starts[i]} -> ${starts[i + 1]})."
        }
    }
}
