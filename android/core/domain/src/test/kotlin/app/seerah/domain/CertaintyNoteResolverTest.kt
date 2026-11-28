package app.seerah.domain

import app.seerah.model.Area
import app.seerah.model.AreaState
import app.seerah.model.CalendarAxis
import app.seerah.model.DistanceBasis
import app.seerah.model.EventDepth
import app.seerah.model.EventKind
import app.seerah.model.EventLocation
import app.seerah.model.GeoPoint
import app.seerah.model.HistoricalDate
import app.seerah.model.KnownAccuracy
import app.seerah.model.Place
import app.seerah.model.PlaceKind
import app.seerah.model.PlaceLocation
import app.seerah.model.PlaceState
import app.seerah.model.Route
import app.seerah.model.RouteGeometry
import app.seerah.model.SeerahEvent
import app.seerah.model.StateStart
import app.seerah.model.TimelinePoint
import app.seerah.model.Visibility
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class CertaintyNoteResolverTest {
    private val coordA = GeoPoint(20.610, 58.900)
    private val coordB = GeoPoint(20.660, 58.950)

    @Test
    fun timelineQuery_marksYearViewAsEndOfYearAndMonthViewClean() {
        val yearPoint = TimelinePoint(CalendarAxis.HIJRI, 1)
        assertEquals(listOf(CertaintyNote.EndOfYearView(yearPoint)), CertaintyNoteResolver.forTimelineQuery(yearPoint))
        assertEquals(emptyList(), CertaintyNoteResolver.forTimelineQuery(TimelinePoint(CalendarAxis.HIJRI, 1, 4)))
    }

    @Test
    fun forEvent_surfacesDatePlaceOffMapAndRouteNotesAutomatically() {
        val generalPlace = Place(
            id = "p-gen",
            kind = PlaceKind.SETTLEMENT,
            location = PlaceLocation.OnMap(KnownAccuracy.GENERAL, coordA),
            visibility = Visibility.ALWAYS,
            prominence = 1,
            states = listOf(PlaceState(StateStart.Baseline, "بلدة افتراضية", true)),
        )
        val offMapPlace = Place(
            id = "p-off",
            kind = PlaceKind.SETTLEMENT,
            location = PlaceLocation.OffMap(KnownAccuracy.GENERAL, coordB),
            visibility = Visibility.ALWAYS,
            prominence = 1,
            states = listOf(PlaceState(StateStart.Baseline, "مكان خارج الخريطة", true)),
        )
        val area = Area(
            id = "a-1",
            labelPoint = coordA,
            states = listOf(AreaState(StateStart.Baseline, "إقليم مثالي", true)),
        )
        val route = Route(
            id = "r-1",
            eventId = "ev-1",
            stationPlaceIds = listOf("p-gen", "p-off"),
            geometry = RouteGeometry.Undocumented,
            distanceKm = 40.0,
            distanceBasis = DistanceBasis.ESTIMATE,
        )
        val event = SeerahEvent(
            id = "ev-1",
            phaseId = "ph-1",
            order = 1,
            kind = EventKind.MIGRATION,
            depth = EventDepth.MEDIUM,
            title = "رحلة مثالية",
            date = HistoricalDate.Year(
                point = TimelinePoint(CalendarAxis.PRE_HIJRA, 2),
                prophethoodYear = 11,
                approximate = true,
                note = "في تحديد الموسم خلاف.",
            ),
            where = EventLocation.MultiPlace(listOf("p-gen", "p-off"), KnownAccuracy.GENERAL),
            visibility = Visibility.ALWAYS,
            prominence = 1,
        )

        val notes = CertaintyNoteResolver.forEvent(
            event = event,
            placesById = mapOf("p-gen" to generalPlace, "p-off" to offMapPlace),
            areasById = mapOf("a-1" to area),
            route = route,
        )

        assertTrue(CertaintyNote.DatePrecisionYearOnly(TimelinePoint(CalendarAxis.PRE_HIJRA, 2)) in notes)
        assertTrue(CertaintyNote.DateApproximate in notes)
        assertTrue(CertaintyNote.DateDisputeNote("في تحديد الموسم خلاف.") in notes)
        assertTrue(CertaintyNote.OffMapLocation in notes)
        assertTrue(CertaintyNote.GeneralPlaceSpotUnknown(listOf("بلدة افتراضية")) in notes)
        assertTrue(CertaintyNote.UndocumentedRouteStationsOnly("r-1") in notes)
        assertTrue(CertaintyNote.EstimatedDistanceKm(40.0) in notes)

        val unknownEvent = event.copy(
            id = "ev-unknown",
            date = HistoricalDate.Unknown(),
            where = EventLocation.Unknown,
        )
        val unknownNotes = CertaintyNoteResolver.forEvent(unknownEvent, emptyMap(), emptyMap())
        assertTrue(CertaintyNote.DateUnknown in unknownNotes)
        assertTrue(CertaintyNote.ExactSpotUnknownNoMarker in unknownNotes)
    }
}
