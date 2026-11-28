package app.seerah.domain

import app.seerah.model.Area
import app.seerah.model.AreaState
import app.seerah.model.CalendarAxis
import app.seerah.model.ContentPackageSummary
import app.seerah.model.EventDepth
import app.seerah.model.EventKind
import app.seerah.model.EventLocation
import app.seerah.model.ExplorationCorpus
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
import app.seerah.model.StepRouteDrawing
import app.seerah.model.TimelinePoint
import app.seerah.model.Visibility
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class TimeStateResolverTest {
    private val p1Coord = GeoPoint(20.610, 58.900)
    private val p2Coord = GeoPoint(20.640, 58.930)
    private val p3Coord = GeoPoint(20.680, 58.980)

    private val shiftingSettlement = Place(
        id = "p-town",
        kind = PlaceKind.SETTLEMENT,
        location = PlaceLocation.OnMap(KnownAccuracy.EXACT, p1Coord),
        visibility = Visibility.ALWAYS,
        prominence = 1,
        states = listOf(
            PlaceState(StateStart.Baseline, "اسم تأسيسي", exists = true),
            PlaceState(StateStart.Dated(TimelinePoint(CalendarAxis.HIJRI, 1)), "اسم أول السنة", exists = true),
            PlaceState(StateStart.Dated(TimelinePoint(CalendarAxis.HIJRI, 1, 6)), "اسم شهر ٦", exists = true),
        ),
    )

    private val contextStation = Place(
        id = "p-station",
        kind = PlaceKind.WELL,
        location = PlaceLocation.OnMap(KnownAccuracy.EXACT, p2Coord),
        visibility = Visibility.IN_CONTEXT,
        prominence = 2,
        states = listOf(PlaceState(StateStart.Dated(TimelinePoint(CalendarAxis.HIJRI, 1, 2)), "محطة طريق", true)),
    )

    private val offMapPlace = Place(
        id = "p-offmap",
        kind = PlaceKind.PORT,
        location = PlaceLocation.OffMap(KnownAccuracy.EXACT, p3Coord),
        visibility = Visibility.ALWAYS,
        prominence = 1,
        states = listOf(PlaceState(StateStart.Baseline, "ميناء بعيد", true)),
    )

    private val unknownPlace = Place(
        id = "p-unknown",
        kind = PlaceKind.OTHER,
        location = PlaceLocation.Unknown,
        visibility = Visibility.ALWAYS,
        prominence = 1,
        states = listOf(PlaceState(StateStart.Baseline, "موضع غير محدد", true)),
    )

    private val area = Area(
        id = "a-main",
        labelPoint = p2Coord,
        states = listOf(
            AreaState(StateStart.Dated(TimelinePoint(CalendarAxis.HIJRI, 1)), "إقليم نشط", true),
            AreaState(StateStart.Dated(TimelinePoint(CalendarAxis.HIJRI, 2)), "إقليم مندثِر", false),
        ),
    )

    private val documentedRoute = Route(
        id = "route-1",
        eventId = "ev-parent",
        stationPlaceIds = listOf("p-town", "p-station", "p-town"),
        geometry = RouteGeometry.Documented(
            coordinates = listOf(p1Coord, p2Coord, p3Coord, p1Coord),
            stationWaypointIndices = listOf(0, 1, 3),
            accuracyNote = "خط مسار تجريبي",
        ),
    )

    private val parentEventYearOnly = SeerahEvent(
        id = "ev-parent",
        phaseId = "ph-1",
        order = 1,
        kind = EventKind.MIGRATION,
        depth = EventDepth.DEEP,
        title = "رحلة عامة",
        date = HistoricalDate.Year(TimelinePoint(CalendarAxis.HIJRI, 1)),
        where = EventLocation.RouteTarget(KnownAccuracy.GENERAL),
        visibility = Visibility.ALWAYS,
        prominence = 1,
    )

    private val childEventInContext = SeerahEvent(
        id = "ev-child",
        phaseId = "ph-1",
        parentId = "ev-parent",
        order = 2,
        kind = EventKind.MIGRATION,
        depth = EventDepth.BRIEF,
        title = "محطة فرعية",
        date = HistoricalDate.Month(TimelinePoint(CalendarAxis.HIJRI, 1, 4)),
        where = EventLocation.SinglePlace("p-station", KnownAccuracy.EXACT),
        visibility = Visibility.IN_CONTEXT,
        prominence = 2,
    )

    private val unknownWhereEvent = SeerahEvent(
        id = "ev-no-coord",
        phaseId = "ph-1",
        order = 3,
        kind = EventKind.CALL,
        depth = EventDepth.BRIEF,
        title = "حدث بلا موضع",
        date = HistoricalDate.Month(TimelinePoint(CalendarAxis.HIJRI, 1, 4)),
        where = EventLocation.Unknown,
        visibility = Visibility.ALWAYS,
        prominence = 2,
    )

    private val corpus = ExplorationCorpus(
        summary = ContentPackageSummary(
            contentSchema = 1,
            contentVersion = "0.1.0",
            packageVersion = 1,
            generation = 1,
            minTimelinePoint = TimelinePoint(CalendarAxis.PRE_HIJRA, 1),
            maxTimelinePoint = TimelinePoint(CalendarAxis.HIJRI, 2),
        ),
        phases = emptyList(),
        sources = emptyMap(),
        places = listOf(shiftingSettlement, contextStation, offMapPlace, unknownPlace),
        areas = listOf(area),
        routes = listOf(documentedRoute),
        events = listOf(parentEventYearOnly, childEventInContext, unknownWhereEvent),
        storySummaries = emptyList(),
    )

    @Test
    fun placeAndAreaState_followYearEndVsSourcedMonthAndNullBeforeAppearance() {
        assertNull(TimeStateResolver.resolvePlaceState(contextStation, TimelinePoint(CalendarAxis.PRE_HIJRA, 1)))
        assertEquals(
            "اسم شهر ٦",
            TimeStateResolver.resolvePlaceState(shiftingSettlement, TimelinePoint(CalendarAxis.HIJRI, 1))?.name,
        )
        assertEquals(
            "اسم أول السنة",
            TimeStateResolver.resolvePlaceState(shiftingSettlement, TimelinePoint(CalendarAxis.HIJRI, 1, 3))?.name,
        )

        val areasAh1 = TimeStateResolver.resolveVisibleAreas(
            corpus,
            TimelineQuery(TimelinePoint(CalendarAxis.HIJRI, 1)),
        )
        val areasAh2 = TimeStateResolver.resolveVisibleAreas(
            corpus,
            TimelineQuery(TimelinePoint(CalendarAxis.HIJRI, 2)),
        )
        assertEquals(listOf("a-main"), areasAh1.map { it.area.id })
        assertEquals(emptyList(), areasAh2)
    }

    @Test
    fun overviewVsOpenEvent_respectsVisibilityUnknownOffMapMonthViewAndRouteSlice() {
        val yearOverview = TimelineQuery(TimelinePoint(CalendarAxis.HIJRI, 1))
        assertEquals(listOf("p-town"), TimeStateResolver.resolveVisiblePlaces(corpus, yearOverview).map { it.place.id })
        assertEquals(
            listOf("ev-parent"),
            TimeStateResolver.resolveVisibleEventMarkers(corpus, yearOverview).map { it.event.id },
        )

        val monthView = TimelineQuery(TimelinePoint(CalendarAxis.HIJRI, 1, 4))
        assertEquals(emptyList(), TimeStateResolver.resolveVisibleEventMarkers(corpus, monthView))

        val openedParent = TimelineQuery(TimelinePoint(CalendarAxis.HIJRI, 1, 4), openEventId = "ev-parent")
        assertEquals(
            setOf("p-town", "p-station"),
            TimeStateResolver.resolveVisiblePlaces(corpus, openedParent).map { it.place.id }.toSet(),
        )
        assertEquals(
            listOf("ev-child"),
            TimeStateResolver.resolveVisibleEventMarkers(corpus, openedParent).map { it.event.id },
        )

        val partialRoute = TimeStateResolver.resolveVisibleRoutes(
            corpus = corpus,
            query = openedParent,
            activeRouteDrawing = StepRouteDrawing("route-1", fromStationIndex = 0, toStationIndex = 1),
        ).single()
        assertEquals(listOf(p1Coord, p2Coord), partialRoute.coordinates)
    }
}
