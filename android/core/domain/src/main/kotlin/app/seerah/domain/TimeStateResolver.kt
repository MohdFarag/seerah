package app.seerah.domain

import app.seerah.model.Area
import app.seerah.model.AreaState
import app.seerah.model.ChronologicalKey
import app.seerah.model.DatePrecision
import app.seerah.model.EventLocation
import app.seerah.model.ExplorationCorpus
import app.seerah.model.GeoPoint
import app.seerah.model.KnownAccuracy
import app.seerah.model.MapTargetRef
import app.seerah.model.Place
import app.seerah.model.PlaceKind
import app.seerah.model.PlaceLocation
import app.seerah.model.PlaceReference
import app.seerah.model.PlaceState
import app.seerah.model.Route
import app.seerah.model.RouteGeometry
import app.seerah.model.SeerahEvent
import app.seerah.model.StepRouteDrawing
import app.seerah.model.TimelinePoint
import app.seerah.model.Visibility

/**
 * Pure timeline + focus input determining what exists and appears on the exploration map.
 *
 * @property point Active year (and optional sourced month/day) selected by the user or story step.
 * @property openEventId Event currently open in exploration/reader, unlocking its `in-context` places and routes.
 * @property revealedTargets Additional entities explicitly revealed (for example by a story step).
 */
data class TimelineQuery(
    val point: TimelinePoint,
    val openEventId: String? = null,
    val revealedTargets: Set<MapTargetRef> = emptySet(),
)

/**
 * Resolved on-map place at a specific timeline state.
 */
data class ResolvedPlace(
    val place: Place,
    val activeName: String,
    val activeKind: PlaceKind,
    val coordinates: GeoPoint,
    val accuracy: KnownAccuracy,
)

/**
 * Resolved on-map area at a specific timeline state.
 */
data class ResolvedArea(val area: Area, val activeName: String)

/**
 * Resolved event marker plotted on the v1 Arabian Peninsula map.
 */
data class ResolvedEventMarker(val event: SeerahEvent, val coordinates: GeoPoint, val accuracy: KnownAccuracy)

/**
 * Resolved route polyline ready for display (empty coordinates for [RouteGeometry.Undocumented]).
 */
data class ResolvedRoute(val route: Route, val coordinates: List<GeoPoint>)

/**
 * Resolves chronological place/area states, `always` vs `in-context` visibility, non-piling year
 * event markers, and documented route slices cleanly on the JVM.
 */
object TimeStateResolver {
    /** Returns the active [PlaceState] at [point], or null if [point] precedes the place's first state. */
    fun resolvePlaceState(place: Place, point: TimelinePoint): PlaceState? {
        val upper = point.asQueryUpperBoundKey()
        return place.states.lastOrNull { it.start.appliesAt(upper) }
    }

    /** Returns the active [AreaState] at [point], or null if [point] precedes the area's first state. */
    fun resolveAreaState(area: Area, point: TimelinePoint): AreaState? {
        val upper = point.asQueryUpperBoundKey()
        return area.states.lastOrNull { it.start.appliesAt(upper) }
    }

    /**
     * Returns all visible on-map places whose active state has `exists == true` and passes
     * `always` / active `in-context` visibility rules.
     */
    fun resolveVisiblePlaces(corpus: ExplorationCorpus, query: TimelineQuery): List<ResolvedPlace> {
        val routesByEvent = corpus.routes.associateBy { it.eventId }
        val routesById = corpus.routes.associateBy { it.id }
        val context = collectContextScope(corpus, routesByEvent, routesById, query)

        return corpus.places.mapNotNull { place ->
            val onMap = place.location as? PlaceLocation.OnMap ?: return@mapNotNull null
            val state = resolvePlaceState(place, query.point) ?: return@mapNotNull null
            if (!state.exists) return@mapNotNull null

            val visible = place.visibility == Visibility.ALWAYS ||
                place.id in context.placeIds ||
                MapTargetRef.PlaceRef(place.id) in query.revealedTargets
            if (!visible) return@mapNotNull null

            ResolvedPlace(
                place = place,
                activeName = state.name,
                activeKind = state.kindOverride ?: place.kind,
                coordinates = onMap.point,
                accuracy = onMap.accuracy,
            )
        }.sortedWith(compareBy({ it.place.prominence }, { it.place.id }))
    }

    /** Returns all existing on-map areas that carry a label point or closed polygon at [query]. */
    fun resolveVisibleAreas(corpus: ExplorationCorpus, query: TimelineQuery): List<ResolvedArea> =
        corpus.areas.mapNotNull { area ->
            if (area.offMap || (area.labelPoint == null && area.polygonRing.isEmpty())) {
                return@mapNotNull null
            }
            val state = resolveAreaState(area, query.point) ?: return@mapNotNull null
            if (!state.exists) return@mapNotNull null
            ResolvedArea(area = area, activeName = state.name)
        }.sortedBy { it.area.id }

    /**
     * Returns non-piling markers matching the selected year (or sourced month), suppressing
     * `in-context` sub-events unless active/revealed, and suppressing `Unknown` / off-map targets.
     */
    fun resolveVisibleEventMarkers(corpus: ExplorationCorpus, query: TimelineQuery): List<ResolvedEventMarker> {
        val placesById = corpus.places.associateBy { it.id }
        val areasById = corpus.areas.associateBy { it.id }
        val routesByEvent = corpus.routes.associateBy { it.eventId }

        return corpus.events.mapNotNull { event ->
            val eventPoint = event.date.timelinePointOrNull ?: return@mapNotNull null
            if (!matchesSelectedTime(eventPoint, query.point)) return@mapNotNull null

            val visible = event.visibility == Visibility.ALWAYS ||
                event.id == query.openEventId ||
                event.parentId == query.openEventId ||
                MapTargetRef.EventRef(event.id) in query.revealedTargets
            if (!visible) return@mapNotNull null

            val anchor = resolveEventMarkerAnchor(
                event = event,
                point = query.point,
                placesById = placesById,
                areasById = areasById,
                routesByEvent = routesByEvent,
            ) ?: return@mapNotNull null

            ResolvedEventMarker(
                event = event,
                coordinates = anchor.first,
                accuracy = anchor.second,
            )
        }.sortedWith(
            compareBy(
                { it.event.date.timelinePointOrNull?.asStateStartKey() ?: ChronologicalKey(0, 0, 0) },
                { it.event.order },
                { it.event.id },
            ),
        )
    }

    /**
     * Resolves routes visible in the current exploration context or explicitly drawn by a story
     * segment, slicing [RouteGeometry.Documented] polylines across `fromStationIndex..toStationIndex`.
     */
    fun resolveVisibleRoutes(
        corpus: ExplorationCorpus,
        query: TimelineQuery,
        activeRouteDrawing: StepRouteDrawing? = null,
    ): List<ResolvedRoute> {
        val routesByEvent = corpus.routes.associateBy { it.eventId }
        val routesById = corpus.routes.associateBy { it.id }
        val context = collectContextScope(corpus, routesByEvent, routesById, query)

        val candidateIds = buildSet {
            addAll(context.routeIds)
            activeRouteDrawing?.let { add(it.routeId) }
            query.revealedTargets.filterIsInstance<MapTargetRef.RouteRef>().forEach { add(it.id) }
        }

        return candidateIds.mapNotNull { rid ->
            val route = routesById[rid] ?: return@mapNotNull null
            val coords = when (val geom = route.geometry) {
                RouteGeometry.Undocumented -> emptyList()
                is RouteGeometry.Documented -> sliceRouteCoordinates(route, geom, activeRouteDrawing)
            }
            ResolvedRoute(route = route, coordinates = coords)
        }.sortedBy { it.route.id }
    }

    private fun sliceRouteCoordinates(
        route: Route,
        geometry: RouteGeometry.Documented,
        activeRouteDrawing: StepRouteDrawing?,
    ): List<GeoPoint> {
        if (activeRouteDrawing == null || activeRouteDrawing.routeId != route.id) {
            return geometry.coordinates
        }
        val fromIdx = activeRouteDrawing.fromStationIndex.coerceIn(0, geometry.stationWaypointIndices.lastIndex)
        val toIdx = activeRouteDrawing.toStationIndex.coerceIn(fromIdx, geometry.stationWaypointIndices.lastIndex)
        val startCoord = geometry.stationWaypointIndices[fromIdx]
        val endCoord = geometry.stationWaypointIndices[toIdx]
        return geometry.coordinates.subList(startCoord, endCoord + 1)
    }

    private fun matchesSelectedTime(eventPoint: TimelinePoint, queryPoint: TimelinePoint): Boolean {
        if (eventPoint.axis != queryPoint.axis || eventPoint.year != queryPoint.year) return false
        val qMonth = queryPoint.month ?: return true
        // Events dated only to the year remain at year view (never invented into a sourced month).
        if (eventPoint.precision == DatePrecision.YEAR) return false
        if (eventPoint.month != qMonth) return false
        val qDay = queryPoint.day ?: return true
        val eventDay = eventPoint.day ?: return true
        return eventDay <= qDay
    }

    private fun resolveEventMarkerAnchor(
        event: SeerahEvent,
        point: TimelinePoint,
        placesById: Map<String, Place>,
        areasById: Map<String, Area>,
        routesByEvent: Map<String, Route>,
    ): Pair<GeoPoint, KnownAccuracy>? = when (val where = event.where) {
        EventLocation.Unknown -> null

        is EventLocation.SinglePlace -> existingOnMapCoordinate(placesById[where.placeId], point, where.accuracy)

        is EventLocation.MultiPlace -> {
            where.placeIds.firstNotNullOfOrNull { pid ->
                existingOnMapCoordinate(placesById[pid], point, where.accuracy)
            }
        }

        is EventLocation.AreaTarget -> {
            val area = areasById[where.areaId]
            if (area == null || area.offMap) {
                null
            } else {
                val state = resolveAreaState(area, point)
                if (state?.exists != true) {
                    null
                } else {
                    val coord = area.labelPoint ?: polygonCentroid(area.polygonRing)
                    coord?.let { it to KnownAccuracy.GENERAL }
                }
            }
        }

        is EventLocation.RouteTarget -> {
            val route = routesByEvent[event.id]
            route?.stationPlaceIds?.firstNotNullOfOrNull { pid ->
                existingOnMapCoordinate(placesById[pid], point, where.accuracy)
            }
        }
    }

    private fun existingOnMapCoordinate(
        place: Place?,
        point: TimelinePoint,
        accuracy: KnownAccuracy,
    ): Pair<GeoPoint, KnownAccuracy>? {
        val onMap = place?.location as? PlaceLocation.OnMap ?: return null
        val state = resolvePlaceState(place, point) ?: return null
        if (!state.exists) return null
        val effectiveAccuracy = if (onMap.accuracy == KnownAccuracy.GENERAL || accuracy == KnownAccuracy.GENERAL) {
            KnownAccuracy.GENERAL
        } else {
            KnownAccuracy.EXACT
        }
        return onMap.point to effectiveAccuracy
    }

    private fun polygonCentroid(ring: List<GeoPoint>): GeoPoint? {
        if (ring.size < 4) return null
        val vertices = ring.dropLast(1)
        val lat = vertices.sumOf { it.lat } / vertices.size
        val lon = vertices.sumOf { it.lon } / vertices.size
        return GeoPoint(lat = lat, lon = lon)
    }

    private data class ContextScope(val placeIds: Set<String>, val routeIds: Set<String>)

    private fun collectContextScope(
        corpus: ExplorationCorpus,
        routesByEvent: Map<String, Route>,
        routesById: Map<String, Route>,
        query: TimelineQuery,
    ): ContextScope {
        val placeIds = mutableSetOf<String>()
        val routeIds = mutableSetOf<String>()
        val eventsById = corpus.events.associateBy { it.id }
        val childrenByParent = corpus.events.filter { it.parentId != null }.groupBy { it.parentId!! }

        val eventQueue = ArrayDeque<String>()
        val visitedEvents = mutableSetOf<String>()
        query.openEventId?.let { eventQueue.add(it) }
        query.revealedTargets.filterIsInstance<MapTargetRef.EventRef>().forEach { eventQueue.add(it.id) }

        while (eventQueue.isNotEmpty()) {
            val eid = eventQueue.removeFirst()
            if (!visitedEvents.add(eid)) continue
            val event = eventsById[eid]
            if (event != null) {
                when (val where = event.where) {
                    is EventLocation.SinglePlace -> placeIds.add(where.placeId)
                    is EventLocation.MultiPlace -> placeIds.addAll(where.placeIds)
                    is EventLocation.AreaTarget, is EventLocation.RouteTarget, EventLocation.Unknown -> Unit
                }
                event.situations.forEach { sit ->
                    (sit.place as? PlaceReference.Known)?.let { placeIds.add(it.placeId) }
                }
                routesByEvent[eid]?.let { route ->
                    routeIds.add(route.id)
                    placeIds.addAll(route.stationPlaceIds)
                }
            }
            childrenByParent[eid].orEmpty().forEach { child -> eventQueue.add(child.id) }
        }

        query.revealedTargets.forEach { target ->
            when (target) {
                is MapTargetRef.PlaceRef -> placeIds.add(target.id)

                is MapTargetRef.RouteRef -> {
                    routeIds.add(target.id)
                    routesById[target.id]?.let { placeIds.addAll(it.stationPlaceIds) }
                }

                is MapTargetRef.AreaRef, is MapTargetRef.EventRef -> Unit
            }
        }

        return ContextScope(placeIds = placeIds, routeIds = routeIds)
    }
}
