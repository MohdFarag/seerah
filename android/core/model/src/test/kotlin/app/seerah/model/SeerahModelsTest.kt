package app.seerah.model

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class SeerahModelsTest {
    private val portCoord = GeoPoint(lat = 20.620, lon = 58.910)
    private val capeCoord = GeoPoint(lat = 20.650, lon = 58.940)
    private val hillCoord = GeoPoint(lat = 20.680, lon = 58.970)

    @Test
    fun place_validatesStatesOrderingLocationAndProminence() {
        val stateYear = PlaceState(StateStart.Dated(TimelinePoint(CalendarAxis.HIJRI, 1)), "اسم سنة ١", true)
        val stateMonth = PlaceState(StateStart.Dated(TimelinePoint(CalendarAxis.HIJRI, 1, 3)), "اسم شهر ٣", true)

        val place = Place(
            id = "example-port",
            kind = PlaceKind.PORT,
            location = PlaceLocation.OnMap(KnownAccuracy.EXACT, portCoord),
            visibility = Visibility.ALWAYS,
            prominence = 1,
            states = listOf(PlaceState(StateStart.Baseline, "اسم قديم", true), stateYear, stateMonth),
        )
        assertEquals(3, place.states.size)

        assertFailsWith<IllegalArgumentException> {
            place.copy(states = listOf(stateMonth, stateYear))
        }
        assertFailsWith<IllegalArgumentException> {
            place.copy(prominence = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            place.copy(states = emptyList())
        }
    }

    @Test
    fun area_requiresClosedRingWhenNonEmpty() {
        val closed = listOf(portCoord, capeCoord, hillCoord, portCoord)
        val validArea = Area(
            id = "example-region",
            labelPoint = capeCoord,
            polygonRing = closed,
            states = listOf(AreaState(StateStart.Baseline, "إقليم مثالي", true)),
        )
        assertEquals(4, validArea.polygonRing.size)

        assertFailsWith<IllegalArgumentException> {
            validArea.copy(polygonRing = listOf(portCoord, capeCoord, hillCoord))
        }
        assertFailsWith<IllegalArgumentException> {
            validArea.copy(polygonRing = listOf(portCoord, capeCoord, hillCoord, capeCoord))
        }
    }

    @Test
    fun route_enforcesWaypointsAndDistanceInvariants() {
        val documented = Route(
            id = "example-route",
            eventId = "example-event",
            stationPlaceIds = listOf("p1", "p2", "p3"),
            geometry = RouteGeometry.Documented(
                coordinates = listOf(portCoord, capeCoord, hillCoord),
                stationWaypointIndices = listOf(0, 1, 2),
                accuracyNote = "مسار تجريبي",
            ),
            distanceKm = 12.5,
            distanceBasis = DistanceBasis.ESTIMATE,
            days = 2,
        )
        assertEquals(3, documented.stationPlaceIds.size)

        assertFailsWith<IllegalArgumentException> {
            documented.copy(stationPlaceIds = listOf("p1"))
        }
        assertFailsWith<IllegalArgumentException> {
            documented.copy(
                geometry = RouteGeometry.Documented(
                    coordinates = listOf(portCoord, capeCoord, hillCoord),
                    stationWaypointIndices = listOf(0, 2, 1),
                    accuracyNote = "مسار تجريبي",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            documented.copy(distanceBasis = null)
        }
    }

    @Test
    fun text_enforcesEditorialAndExcerptRules() {
        val citation = SourceCitation(sourceWorkId = "book-1", locator = "ص ١")
        val editorial = ContentText(id = "ed-1", kind = TextKind.EDITORIAL, body = "تمهيد")
        assertEquals(TextKind.EDITORIAL, editorial.kind)

        assertFailsWith<IllegalArgumentException> {
            ContentText(id = "ed-bad", kind = TextKind.EDITORIAL, body = "x", source = citation)
        }
        assertFailsWith<IllegalArgumentException> {
            ContentText(id = "ed-excerpt", kind = TextKind.EDITORIAL, body = "x", excerptOfTextId = "t1")
        }
        assertFailsWith<IllegalArgumentException> {
            ContentText(id = "narration-no-source", kind = TextKind.SEERAH_NARRATION, body = "x", source = null)
        }
        assertFailsWith<IllegalArgumentException> {
            ContentText(
                id = "bad-exp",
                kind = TextKind.HADITH,
                body = "x",
                source = citation,
                explanationSource = citation,
            )
        }
    }

    @Test
    fun eventStoryAndSummary_enforceFieldInvariants() {
        val event = SeerahEvent(
            id = "ev-1",
            phaseId = "ph-1",
            order = 1,
            kind = EventKind.EXPEDITION,
            depth = EventDepth.DEEP,
            expeditionRole = ExpeditionRole.PROPHET_LED,
            title = "حدث مثالي",
            date = HistoricalDate.Year(TimelinePoint(CalendarAxis.HIJRI, 2)),
            where = EventLocation.RouteTarget(KnownAccuracy.GENERAL),
            visibility = Visibility.ALWAYS,
            prominence = 1,
        )
        assertEquals(ExpeditionRole.PROPHET_LED, event.expeditionRole)

        assertFailsWith<IllegalArgumentException> {
            event.copy(kind = EventKind.FOUNDING)
        }
        assertFailsWith<IllegalArgumentException> {
            event.copy(parentId = event.id)
        }

        assertFailsWith<IllegalArgumentException> {
            StepTime.TimeLapse(
                from = TimelinePoint(CalendarAxis.HIJRI, 2),
                to = TimelinePoint(CalendarAxis.PRE_HIJRA, 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ContentPackageSummary(
                contentSchema = 1,
                contentVersion = "0.1.0",
                packageVersion = 1,
                generation = 1,
                minTimelinePoint = TimelinePoint(CalendarAxis.HIJRI, 2),
                maxTimelinePoint = TimelinePoint(CalendarAxis.HIJRI, 1),
            )
        }
    }
}
