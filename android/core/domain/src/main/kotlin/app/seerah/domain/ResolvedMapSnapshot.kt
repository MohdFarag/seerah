package app.seerah.domain

import app.seerah.model.ExplorationCorpus
import app.seerah.model.MapTargetRef
import app.seerah.model.StepCamera
import app.seerah.model.StepRouteDrawing
import app.seerah.model.StepTime
import app.seerah.model.StoryStep
import app.seerah.model.TimelinePoint

/**
 * Temporal range active on a resolved map snapshot.
 */
sealed interface SnapshotTime {
    /** Map displays entities at a single year or sourced month/day [point]. */
    data class SinglePoint(val point: TimelinePoint) : SnapshotTime

    /** Story step animates/steps across historical states from [from] to [to]. */
    data class TimeLapse(val from: TimelinePoint, val to: TimelinePoint) : SnapshotTime
}

/**
 * Unified pure-JVM snapshot consumed by both free exploration and guided follow-along modes,
 * proving that story mode reuses the same map fact/visibility resolution as free exploration
 * without coupling to MapLibre or screen-size zoom numbers.
 */
data class ResolvedMapSnapshot(
    val time: SnapshotTime,
    val visiblePlaces: List<ResolvedPlace>,
    val visibleAreas: List<ResolvedArea>,
    val visibleRoutes: List<ResolvedRoute>,
    val visibleEventMarkers: List<ResolvedEventMarker>,
    val highlightedTargets: List<MapTargetRef>,
    val certaintyNotes: List<CertaintyNote>,
    val activeStep: StoryStep? = null,
)

/**
 * Builds unified [ResolvedMapSnapshot] objects from verified exploration facts and optional story steps.
 */
object MapSnapshotBuilder {
    /** Builds the snapshot for free exploration at [query]. */
    fun forExploration(corpus: ExplorationCorpus, query: TimelineQuery): ResolvedMapSnapshot {
        val placesById = corpus.places.associateBy { it.id }
        val areasById = corpus.areas.associateBy { it.id }
        val routesByEvent = corpus.routes.associateBy { it.eventId }

        val notes = buildList {
            addAll(CertaintyNoteResolver.forTimelineQuery(query.point))
            val openEvent = query.openEventId?.let { eid -> corpus.events.firstOrNull { it.id == eid } }
            if (openEvent != null) {
                addAll(
                    CertaintyNoteResolver.forEvent(
                        event = openEvent,
                        placesById = placesById,
                        areasById = areasById,
                        route = routesByEvent[openEvent.id],
                        point = query.point,
                    ),
                )
            }
        }.distinct()

        return ResolvedMapSnapshot(
            time = SnapshotTime.SinglePoint(query.point),
            visiblePlaces = TimeStateResolver.resolveVisiblePlaces(corpus, query),
            visibleAreas = TimeStateResolver.resolveVisibleAreas(corpus, query),
            visibleRoutes = TimeStateResolver.resolveVisibleRoutes(corpus, query),
            visibleEventMarkers = TimeStateResolver.resolveVisibleEventMarkers(corpus, query),
            highlightedTargets = emptyList(),
            certaintyNotes = notes,
            activeStep = null,
        )
    }

    /**
     * Builds the snapshot for one [StoryStep] shot, turning its camera targets, reveals, movement
     * endpoints, and route segments into the exact same resolved exploration visibility structures.
     */
    fun forStoryStep(corpus: ExplorationCorpus, step: StoryStep, sceneEventId: String? = null): ResolvedMapSnapshot {
        val (snapshotTime, effectivePoint) = when (val t = step.time) {
            is StepTime.At -> SnapshotTime.SinglePoint(t.point) to t.point
            is StepTime.TimeLapse -> SnapshotTime.TimeLapse(t.from, t.to) to t.to
        }

        val activeRouteDrawing = step.drawRoute ?: when (val cam = step.camera) {
            is StepCamera.Follow -> StepRouteDrawing(cam.routeId, cam.fromStationIndex, cam.toStationIndex)
            is StepCamera.Frame -> null
        }

        val revealed = buildSet {
            addAll(step.reveal)
            addAll(step.highlight)
            when (val cam = step.camera) {
                is StepCamera.Frame -> addAll(cam.targets)
                is StepCamera.Follow -> add(MapTargetRef.RouteRef(cam.routeId))
            }
            step.drawRoute?.let { add(MapTargetRef.RouteRef(it.routeId)) }
            step.showAreaId?.let { add(MapTargetRef.AreaRef(it)) }
            step.showMovement?.let { move ->
                add(MapTargetRef.PlaceRef(move.fromPlaceId))
                add(MapTargetRef.PlaceRef(move.toPlaceId))
                move.routeId?.let { add(MapTargetRef.RouteRef(it)) }
            }
        }

        val query = TimelineQuery(
            point = effectivePoint,
            openEventId = sceneEventId,
            revealedTargets = revealed,
        )
        val visibleRoutes = TimeStateResolver.resolveVisibleRoutes(corpus, query, activeRouteDrawing)

        val placesById = corpus.places.associateBy { it.id }
        val areasById = corpus.areas.associateBy { it.id }
        val routesByEvent = corpus.routes.associateBy { it.eventId }
        val notes = buildList {
            addAll(CertaintyNoteResolver.forTimelineQuery(effectivePoint))
            val anchored = sceneEventId?.let { eid -> corpus.events.firstOrNull { it.id == eid } }
            if (anchored != null) {
                addAll(
                    CertaintyNoteResolver.forEvent(
                        event = anchored,
                        placesById = placesById,
                        areasById = areasById,
                        route = routesByEvent[anchored.id],
                        point = effectivePoint,
                    ),
                )
            }
            visibleRoutes.forEach { resolvedRoute ->
                addAll(CertaintyNoteResolver.forRoute(resolvedRoute.route))
            }
        }.distinct()

        return ResolvedMapSnapshot(
            time = snapshotTime,
            visiblePlaces = TimeStateResolver.resolveVisiblePlaces(corpus, query),
            visibleAreas = TimeStateResolver.resolveVisibleAreas(corpus, query),
            visibleRoutes = visibleRoutes,
            visibleEventMarkers = TimeStateResolver.resolveVisibleEventMarkers(corpus, query),
            highlightedTargets = step.highlight,
            certaintyNotes = notes,
            activeStep = step,
        )
    }
}
