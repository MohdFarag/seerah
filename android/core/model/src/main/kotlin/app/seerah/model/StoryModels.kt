package app.seerah.model

/**
 * Qualitative screen-independent framing size in words ([ADR 0005](../../../docs/adr/0005-content-model-exploration-and-story.md)).
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class ShotSize(val wireName: String) {
    WIDE("wide"),
    MEDIUM("medium"),
    CLOSE("close"),
}

/**
 * Camera transition hint between consecutive story steps.
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class CameraTransition(val wireName: String) {
    AUTO("auto"),
    CUT("cut"),
    FLY("fly"),
    PAN("pan"),
}

/**
 * Typed target identifier referenced inside story camera framing, highlights, or reveals.
 */
sealed interface MapTargetRef {
    val id: String

    /** Reference to a [Place.id] (`"place:<id>"`). */
    data class PlaceRef(override val id: String) : MapTargetRef {
        init {
            require(id.isNotBlank()) { "PlaceRef id must not be blank." }
        }
    }

    /** Reference to an [Area.id] (`"area:<id>"`). */
    data class AreaRef(override val id: String) : MapTargetRef {
        init {
            require(id.isNotBlank()) { "AreaRef id must not be blank." }
        }
    }

    /** Reference to a [SeerahEvent.id] (`"event:<id>"`). */
    data class EventRef(override val id: String) : MapTargetRef {
        init {
            require(id.isNotBlank()) { "EventRef id must not be blank." }
        }
    }

    /** Reference to a [Route.id] (`"route:<id>"`). */
    data class RouteRef(override val id: String) : MapTargetRef {
        init {
            require(id.isNotBlank()) { "RouteRef id must not be blank." }
        }
    }
}

/**
 * Camera move directed for one [StoryStep].
 */
sealed interface StepCamera {
    /** Frames one or more target entities at a symbolic [shot]. */
    data class Frame(
        val targets: List<MapTargetRef>,
        val shot: ShotSize,
        val transition: CameraTransition = CameraTransition.AUTO,
    ) : StepCamera {
        init {
            require(targets.isNotEmpty()) { "StepCamera.Frame requires at least one target." }
        }
    }

    /** Follows a documented [routeId] segment from [fromStationIndex] through [toStationIndex]. */
    data class Follow(
        val routeId: String,
        val fromStationIndex: Int,
        val toStationIndex: Int,
        val shot: ShotSize? = null,
        val transition: CameraTransition = CameraTransition.AUTO,
    ) : StepCamera {
        init {
            require(routeId.isNotBlank()) { "StepCamera.Follow routeId must not be blank." }
            require(fromStationIndex in 0..toStationIndex) {
                "Invalid follow station range $fromStationIndex..$toStationIndex."
            }
        }
    }
}

/**
 * Temporal directive of a [StoryStep].
 */
sealed interface StepTime {
    /** Sets the map timeline to a single canonical [point]. */
    data class At(val point: TimelinePoint) : StepTime

    /** Passes through time chronologically from [from] to [to]. */
    data class TimeLapse(val from: TimelinePoint, val to: TimelinePoint) : StepTime {
        init {
            require(from.asQueryUpperBoundKey() <= to.asQueryUpperBoundKey()) {
                "TimeLapse from ($from) must not exceed to ($to)."
            }
        }
    }
}

/**
 * Route drawing segment rendered during a [StoryStep].
 */
data class StepRouteDrawing(val routeId: String, val fromStationIndex: Int, val toStationIndex: Int) {
    init {
        require(routeId.isNotBlank()) { "StepRouteDrawing routeId must not be blank." }
        require(fromStationIndex in 0..toStationIndex) {
            "Invalid draw_route range $fromStationIndex..$toStationIndex."
        }
    }
}

/**
 * Movement arrow drawn between two places during a [StoryStep].
 */
data class StepMovement(val fromPlaceId: String, val toPlaceId: String, val routeId: String? = null) {
    init {
        require(fromPlaceId.isNotBlank() && toPlaceId.isNotBlank()) {
            "StepMovement place IDs must not be blank."
        }
        require(routeId == null || routeId.isNotBlank()) {
            "StepMovement routeId must be null or non-blank."
        }
    }
}

/**
 * Text/card panel shown beside or over the map in one [StoryStep].
 *
 * @property titleCard Whether to show the scene title, date, and place header card.
 * @property textIds Optional list of [ContentText.id] narrations shown in the panel.
 * @property linkingSentence Optional reviewed editorial transition text.
 * @property exploreHere Whether this step invites free exploration at the current spot.
 */
data class StepPanel(
    val titleCard: Boolean,
    val textIds: List<String> = emptyList(),
    val linkingSentence: String? = null,
    val exploreHere: Boolean = false,
) {
    init {
        require(textIds.all { it.isNotBlank() }) { "StepPanel contains blank textIds." }
        require(linkingSentence == null || linkingSentence.isNotBlank()) {
            "StepPanel linkingSentence must be null or non-blank."
        }
    }
}

/**
 * Self-contained complete snapshot representing one directed step inside a [StoryScene].
 */
data class StoryStep(
    val id: String,
    val order: Int,
    val camera: StepCamera,
    val time: StepTime,
    val highlight: List<MapTargetRef> = emptyList(),
    val focusMode: Boolean = false,
    val reveal: List<MapTargetRef> = emptyList(),
    val drawRoute: StepRouteDrawing? = null,
    val showMovement: StepMovement? = null,
    val showAreaId: String? = null,
    val panel: StepPanel,
) {
    init {
        require(id.isNotBlank()) { "StoryStep id must not be blank." }
        require(order >= 1) { "StoryStep $id order must be >= 1, got $order." }
        require(showAreaId == null || showAreaId.isNotBlank()) {
            "StoryStep $id showAreaId must be null or non-blank."
        }
    }
}

/**
 * Directed scene inside a [StoryChapter], optionally anchored to an exploration event.
 */
data class StoryScene(
    val id: String,
    val eventId: String? = null,
    val order: Int,
    val title: String,
    val steps: List<StoryStep>,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank()) { "StoryScene id and title must not be blank." }
        require(eventId == null || eventId.isNotBlank()) { "StoryScene eventId must be null or non-blank." }
        require(order >= 1) { "StoryScene $id order must be >= 1, got $order." }
        require(steps.isNotEmpty()) { "StoryScene $id must contain at least one step." }
    }
}

/**
 * Chapter grouping scenes inside a [Story].
 */
data class StoryChapter(
    val id: String,
    val phaseId: String? = null,
    val order: Int,
    val title: String,
    val scenes: List<StoryScene>,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank()) { "StoryChapter id and title must not be blank." }
        require(phaseId == null || phaseId.isNotBlank()) { "StoryChapter phaseId must be null or non-blank." }
        require(order >= 1) { "StoryChapter $id order must be >= 1, got $order." }
        require(scenes.isNotEmpty()) { "StoryChapter $id must contain at least one scene." }
    }
}

/**
 * Full directed follow-along story script compiled cleanly from `stories/<story-id>.yaml`.
 */
data class Story(val id: String, val title: String, val chapters: List<StoryChapter>) {
    init {
        require(id.isNotBlank() && title.isNotBlank()) { "Story id and title must not be blank." }
        require(chapters.isNotEmpty()) { "Story $id must contain at least one chapter." }
    }
}

/**
 * Lightweight catalog entry for listing stories without loading full nested step trees.
 */
data class StorySummary(val id: String, val sortOrder: Int, val title: String, val chapterCount: Int) {
    init {
        require(id.isNotBlank() && title.isNotBlank()) { "StorySummary id and title must not be blank." }
        require(sortOrder >= 0) { "StorySummary sortOrder must be >= 0, got $sortOrder." }
        require(chapterCount >= 1) { "StorySummary chapterCount must be >= 1, got $chapterCount." }
    }
}
