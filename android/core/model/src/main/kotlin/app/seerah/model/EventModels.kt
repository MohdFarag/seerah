package app.seerah.model

private const val MIN_EVENT_PROMINENCE = 1
private const val MAX_EVENT_PROMINENCE = 3

/**
 * Top-level period phase dividing the covered Seerah arc (for example, 5 phases in v1).
 *
 * @property id Stable identifier slug.
 * @property order Strictly positive display ordering (`>= 1`).
 * @property title Arabic phase title.
 * @property skippable Whether follow-along permits skipping introductory phases.
 */
data class Phase(val id: String, val order: Int, val title: String, val skippable: Boolean = false) {
    init {
        require(id.isNotBlank() && title.isNotBlank()) { "Phase id and title must not be blank." }
        require(order >= 1) { "Phase $id order must be >= 1, got $order." }
    }
}

/**
 * Primary classification of an event ([ADR 0005](../../../docs/adr/0005-content-model-exploration-and-story.md)).
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class EventKind(val wireName: String) {
    CALL("call"),
    OPPOSITION("opposition"),
    PLEDGE("pledge"),
    MIGRATION("migration"),
    FOUNDING("founding"),
    LEGISLATION("legislation"),
    EXPEDITION("expedition"),
}

/**
 * Authored detail tier of an event.
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class EventDepth(val wireName: String) {
    DEEP("deep"),
    MEDIUM("medium"),
    BRIEF("brief"),
}

/**
 * Optional distinction on [EventKind.EXPEDITION] events between campaigns led by the Prophet ﷺ
 * and dispatched detachments.
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class ExpeditionRole(val wireName: String) {
    PROPHET_LED("prophet-led"),
    DISPATCHED("dispatched"),
}

/**
 * Typed relation linking one historical event to another.
 *
 * @property wireName Canonical serialized value in compiled packages.
 */
enum class RelationType(val wireName: String) {
    CAUSE_OF("cause-of"),
    RESULT_OF("result-of"),
    CONTINUES("continues"),
}

/**
 * Place reference with explicit positional certainty (for example inside a [Situation]).
 */
sealed interface PlaceReference {
    /** Linked to a known place identifier with [accuracy]. */
    data class Known(val accuracy: KnownAccuracy, val placeId: String) : PlaceReference {
        init {
            require(placeId.isNotBlank()) { "PlaceReference.Known placeId must not be blank." }
        }
    }

    /** Situation location is unknown (never guessed or plotted). */
    data object Unknown : PlaceReference
}

/**
 * Geographic anchor of an event (`where` block in `data/events.json`).
 */
sealed interface EventLocation {
    /** Anchored to one place. */
    data class SinglePlace(val placeId: String, val accuracy: KnownAccuracy) : EventLocation {
        init {
            require(placeId.isNotBlank()) { "SinglePlace placeId must not be blank." }
        }
    }

    /** Anchored to multiple places (for example opposing positions). */
    data class MultiPlace(val placeIds: List<String>, val accuracy: KnownAccuracy) : EventLocation {
        init {
            require(placeIds.isNotEmpty() && placeIds.all { it.isNotBlank() }) {
                "MultiPlace requires non-empty, non-blank placeIds."
            }
        }
    }

    /** Anchored generally to an area/region. */
    data class AreaTarget(val areaId: String) : EventLocation {
        init {
            require(areaId.isNotBlank()) { "AreaTarget areaId must not be blank." }
        }

        val accuracy: KnownAccuracy get() = KnownAccuracy.GENERAL
    }

    /** Anchored along this event's documented/stationed route. */
    data class RouteTarget(val accuracy: KnownAccuracy) : EventLocation

    /** Event location is unknown; remains reachable in index/reader with no map marker. */
    data object Unknown : EventLocation
}

/**
 * Focused situation within an event presenting its chosen original narration text(s).
 *
 * @property id Stable identifier slug.
 * @property order Strictly positive display order within the parent event.
 * @property title Arabic situation title.
 * @property place Optional specific place certainty note inside the event.
 * @property textIds Ordered [ContentText.id] values attached to this situation.
 */
data class Situation(
    val id: String,
    val order: Int,
    val title: String,
    val place: PlaceReference? = null,
    val textIds: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank() && title.isNotBlank()) { "Situation id and title must not be blank." }
        require(order >= 1) { "Situation $id order must be >= 1, got $order." }
        require(textIds.all { it.isNotBlank() }) { "Situation $id contains blank textId." }
    }
}

/**
 * Typed relation pointing from one event to [targetEventId].
 *
 * @property targetEventId Identifier of the related target event.
 * @property type Semantic link type (`cause-of`, `result-of`, `continues`).
 */
data class EventRelation(val targetEventId: String, val type: RelationType) {
    init {
        require(targetEventId.isNotBlank()) { "EventRelation targetEventId must not be blank." }
    }
}

/**
 * Core Seerah event record placed on the timeline and linked to exploration locations, situations,
 * and sourced texts.
 */
data class SeerahEvent(
    val id: String,
    val phaseId: String,
    val parentId: String? = null,
    val order: Int,
    val kind: EventKind,
    val depth: EventDepth,
    val expeditionRole: ExpeditionRole? = null,
    val title: String,
    val date: HistoricalDate,
    val where: EventLocation,
    val visibility: Visibility,
    val prominence: Int,
    val leadIn: String? = null,
    val outcome: String? = null,
    val situations: List<Situation> = emptyList(),
    val eventTextIds: List<String> = emptyList(),
    val related: List<EventRelation> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank() && phaseId.isNotBlank() && title.isNotBlank()) {
            "SeerahEvent id, phaseId, and title must not be blank."
        }
        require(parentId == null || (parentId.isNotBlank() && parentId != id)) {
            "SeerahEvent $id invalid parentId '$parentId'."
        }
        require(order >= 1) { "SeerahEvent $id order must be >= 1, got $order." }
        require(expeditionRole == null || kind == EventKind.EXPEDITION) {
            "SeerahEvent $id sets expeditionRole without EventKind.EXPEDITION."
        }
        require(prominence in MIN_EVENT_PROMINENCE..MAX_EVENT_PROMINENCE) {
            "SeerahEvent $id prominence $prominence outside $MIN_EVENT_PROMINENCE..$MAX_EVENT_PROMINENCE."
        }
        require(leadIn == null || leadIn.isNotBlank()) { "SeerahEvent $id leadIn must be null or non-blank." }
        require(outcome == null || outcome.isNotBlank()) { "SeerahEvent $id outcome must be null or non-blank." }
        require(eventTextIds.all { it.isNotBlank() }) { "SeerahEvent $id contains blank eventTextIds." }
        require(tags.all { it.isNotBlank() }) { "SeerahEvent $id contains blank tags." }
    }
}

/**
 * Complete lazy-loaded detail bundle for opening a single event in the reader screen.
 *
 * @property event Core event fields and situations.
 * @property parentEvent Parent event when [SeerahEvent.parentId] is non-null.
 * @property childEvents Direct `in-context` or nested sub-events ordered chronologically/by order.
 * @property route Optional route attached to this event.
 * @property texts All texts attached to this event and full texts resolved via `excerptOfTextId`.
 * @property relatedEvents Target events resolved cleanly alongside their relation metadata.
 * @property sources Map of cited [SourceWork.id] entries for all citations in [texts].
 */
data class EventDetail(
    val event: SeerahEvent,
    val parentEvent: SeerahEvent? = null,
    val childEvents: List<SeerahEvent> = emptyList(),
    val route: Route? = null,
    val texts: List<ContentText> = emptyList(),
    val relatedEvents: List<Pair<EventRelation, SeerahEvent>> = emptyList(),
    val sources: Map<String, SourceWork> = emptyMap(),
)
