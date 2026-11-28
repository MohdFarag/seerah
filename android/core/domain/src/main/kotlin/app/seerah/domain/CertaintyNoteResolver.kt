package app.seerah.domain

import app.seerah.model.Area
import app.seerah.model.DistanceBasis
import app.seerah.model.EventLocation
import app.seerah.model.HistoricalDate
import app.seerah.model.KnownAccuracy
import app.seerah.model.Place
import app.seerah.model.PlaceLocation
import app.seerah.model.Route
import app.seerah.model.RouteGeometry
import app.seerah.model.SeerahEvent
import app.seerah.model.TimelinePoint

/**
 * Typed historical certainty, precision, or accuracy disclosure derived automatically from verified
 * facts ([ADR 0005](../../../docs/adr/0005-content-model-exploration-and-story.md)).
 *
 * UI layers map these typed notes to Arabic string resources without hardcoding domain logic.
 */
sealed interface CertaintyNote {
    /** Year view summarizes state at the end of [yearPoint]. */
    data class EndOfYearView(val yearPoint: TimelinePoint) : CertaintyNote

    /** Event is dated only to its calendar year ([yearPoint]); month is not documented. */
    data class DatePrecisionYearOnly(val yearPoint: TimelinePoint) : CertaintyNote

    /** Event date is flagged as approximate in sources. */
    data object DateApproximate : CertaintyNote

    /** Event date is unestablished in sources. */
    data object DateUnknown : CertaintyNote

    /** Scholarly dating dispute or historical note authored on the event date. */
    data class DateDisputeNote(val note: String) : CertaintyNote

    /** City or region is known, while the exact spot inside it is not established. */
    data class GeneralPlaceSpotUnknown(val targetNames: List<String>) : CertaintyNote

    /** Location is completely unknown; the item keeps no map marker. */
    data object ExactSpotUnknownNoMarker : CertaintyNote

    /** Location lies outside the Arabian Peninsula map coverage. */
    data object OffMapLocation : CertaintyNote

    /** Route stations are documented without an established path line between them. */
    data class UndocumentedRouteStationsOnly(val routeId: String) : CertaintyNote

    /** Route distance is an editorial estimate rather than directly cited in a primary source. */
    data class EstimatedDistanceKm(val distanceKm: Double) : CertaintyNote

    /** Explicit source accuracy note authored on a documented route line. */
    data class RouteAccuracyNote(val note: String) : CertaintyNote
}

/**
 * Pure fact-to-certainty note resolver shared between exploration readers and story panels.
 */
object CertaintyNoteResolver {
    /** Derives view-level notes for a timeline position (year view communicates end-of-year state). */
    fun forTimelineQuery(point: TimelinePoint): List<CertaintyNote> =
        if (point.month == null) listOf(CertaintyNote.EndOfYearView(point.yearOnly())) else emptyList()

    /** Derives temporal certainty and scholarly dispute notes from [date]. */
    fun forDate(date: HistoricalDate): List<CertaintyNote> = buildList {
        when (date) {
            is HistoricalDate.Unknown -> add(CertaintyNote.DateUnknown)
            is HistoricalDate.Year -> add(CertaintyNote.DatePrecisionYearOnly(date.point))
            is HistoricalDate.Month, is HistoricalDate.Day -> Unit
        }
        if (date.approximate) {
            add(CertaintyNote.DateApproximate)
        }
        date.note?.let { add(CertaintyNote.DateDisputeNote(it)) }
    }

    /** Derives route geometry and distance estimate disclosures from [route]. */
    fun forRoute(route: Route): List<CertaintyNote> = buildList {
        when (val geometry = route.geometry) {
            is RouteGeometry.Documented -> add(CertaintyNote.RouteAccuracyNote(geometry.accuracyNote))
            RouteGeometry.Undocumented -> add(CertaintyNote.UndocumentedRouteStationsOnly(route.id))
        }
        val distanceKm = route.distanceKm
        if (distanceKm != null && route.distanceBasis == DistanceBasis.ESTIMATE) {
            add(CertaintyNote.EstimatedDistanceKm(distanceKm))
        }
    }

    /**
     * Collects all applicable date, spatial certainty, off-map, and route notes for opening [event]
     * at timeline [point].
     */
    fun forEvent(
        event: SeerahEvent,
        placesById: Map<String, Place>,
        areasById: Map<String, Area>,
        route: Route? = null,
        point: TimelinePoint? = null,
    ): List<CertaintyNote> = buildList {
        addAll(forDate(event.date))
        val targetPoint = point ?: event.date.timelinePointOrNull

        when (val where = event.where) {
            EventLocation.Unknown -> add(CertaintyNote.ExactSpotUnknownNoMarker)

            is EventLocation.SinglePlace -> {
                val place = placesById[where.placeId]
                val name = resolvePlaceLabel(place, targetPoint, where.placeId)
                when (val loc = place?.location) {
                    is PlaceLocation.OffMap -> add(CertaintyNote.OffMapLocation)

                    PlaceLocation.Unknown -> add(CertaintyNote.ExactSpotUnknownNoMarker)

                    else -> if (where.accuracy == KnownAccuracy.GENERAL ||
                        (loc is PlaceLocation.OnMap && loc.accuracy == KnownAccuracy.GENERAL)
                    ) {
                        add(CertaintyNote.GeneralPlaceSpotUnknown(listOf(name)))
                    }
                }
            }

            is EventLocation.MultiPlace -> {
                val generalNames = mutableListOf<String>()
                var hasOffMap = false
                for (pid in where.placeIds) {
                    val place = placesById[pid]
                    when (val loc = place?.location) {
                        is PlaceLocation.OffMap -> hasOffMap = true

                        is PlaceLocation.OnMap -> if (where.accuracy == KnownAccuracy.GENERAL ||
                            loc.accuracy == KnownAccuracy.GENERAL
                        ) {
                            generalNames.add(resolvePlaceLabel(place, targetPoint, pid))
                        }

                        PlaceLocation.Unknown, null -> Unit
                    }
                }
                if (hasOffMap) add(CertaintyNote.OffMapLocation)
                if (generalNames.isNotEmpty()) {
                    add(CertaintyNote.GeneralPlaceSpotUnknown(generalNames.distinct()))
                }
            }

            is EventLocation.AreaTarget -> {
                val area = areasById[where.areaId]
                val name = resolveAreaLabel(area, targetPoint, where.areaId)
                if (area?.offMap == true) add(CertaintyNote.OffMapLocation)
                add(CertaintyNote.GeneralPlaceSpotUnknown(listOf(name)))
            }

            is EventLocation.RouteTarget -> {
                if (where.accuracy == KnownAccuracy.GENERAL) {
                    val routeStations = route?.stationPlaceIds
                        ?.map { pid -> resolvePlaceLabel(placesById[pid], targetPoint, pid) }
                        .orEmpty()
                    if (routeStations.isNotEmpty()) {
                        add(CertaintyNote.GeneralPlaceSpotUnknown(routeStations.distinct()))
                    }
                }
            }
        }

        if (route != null) {
            addAll(forRoute(route))
        }
    }

    private fun resolvePlaceLabel(place: Place?, point: TimelinePoint?, fallbackId: String): String {
        if (place == null) return fallbackId
        if (point == null) return place.states.last().name
        val bound = point.asQueryUpperBoundKey()
        return place.states.lastOrNull { it.start.appliesAt(bound) }?.name ?: place.states.last().name
    }

    private fun resolveAreaLabel(area: Area?, point: TimelinePoint?, fallbackId: String): String {
        if (area == null) return fallbackId
        if (point == null) return area.states.last().name
        val bound = point.asQueryUpperBoundKey()
        return area.states.lastOrNull { it.start.appliesAt(bound) }?.name ?: area.states.last().name
    }
}
