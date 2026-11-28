package app.seerah.domain

import app.seerah.model.CalendarAxis
import app.seerah.model.CameraTransition
import app.seerah.model.ContentPackageSummary
import app.seerah.model.EventDepth
import app.seerah.model.EventKind
import app.seerah.model.EventLocation
import app.seerah.model.ExplorationCorpus
import app.seerah.model.GeoPoint
import app.seerah.model.HistoricalDate
import app.seerah.model.KnownAccuracy
import app.seerah.model.MapTargetRef
import app.seerah.model.Place
import app.seerah.model.PlaceKind
import app.seerah.model.PlaceLocation
import app.seerah.model.PlaceState
import app.seerah.model.Route
import app.seerah.model.RouteGeometry
import app.seerah.model.SeerahEvent
import app.seerah.model.ShotSize
import app.seerah.model.StateStart
import app.seerah.model.StepCamera
import app.seerah.model.StepPanel
import app.seerah.model.StepRouteDrawing
import app.seerah.model.StepTime
import app.seerah.model.StoryStep
import app.seerah.model.TimelinePoint
import app.seerah.model.Visibility
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MapSnapshotBuilderTest {
    private val coordA = GeoPoint(20.610, 58.900)
    private val coordB = GeoPoint(20.660, 58.950)

    private val hiddenStation = Place(
        id = "p-hidden",
        kind = PlaceKind.WELL,
        location = PlaceLocation.OnMap(KnownAccuracy.EXACT, coordA),
        visibility = Visibility.IN_CONTEXT,
        prominence = 2,
        states = listOf(PlaceState(StateStart.Baseline, "بئر", true)),
    )

    private val endpoint = Place(
        id = "p-end",
        kind = PlaceKind.PORT,
        location = PlaceLocation.OnMap(KnownAccuracy.EXACT, coordB),
        visibility = Visibility.ALWAYS,
        prominence = 1,
        states = listOf(
            PlaceState(StateStart.Dated(TimelinePoint(CalendarAxis.HIJRI, 1)), "اسم قديم", true),
            PlaceState(StateStart.Dated(TimelinePoint(CalendarAxis.HIJRI, 2)), "اسم جديد", true),
        ),
    )

    private val route = Route(
        id = "r-1",
        eventId = "ev-1",
        stationPlaceIds = listOf("p-hidden", "p-end"),
        geometry = RouteGeometry.Documented(
            coordinates = listOf(coordA, coordB),
            stationWaypointIndices = listOf(0, 1),
            accuracyNote = "مسار موثق",
        ),
    )

    private val event = SeerahEvent(
        id = "ev-1",
        phaseId = "ph-1",
        order = 1,
        kind = EventKind.MIGRATION,
        depth = EventDepth.DEEP,
        title = "حدث مسار",
        date = HistoricalDate.Year(TimelinePoint(CalendarAxis.HIJRI, 2)),
        where = EventLocation.RouteTarget(KnownAccuracy.EXACT),
        visibility = Visibility.ALWAYS,
        prominence = 1,
    )

    private val corpus = ExplorationCorpus(
        summary = ContentPackageSummary(1, "0.1.0", 1, 1),
        phases = emptyList(),
        sources = emptyMap(),
        places = listOf(hiddenStation, endpoint),
        areas = emptyList(),
        routes = listOf(route),
        events = listOf(event),
        storySummaries = emptyList(),
    )

    @Test
    fun explorationAndStoryStep_shareUnifiedVisibilityAndFactResolution() {
        val explore = MapSnapshotBuilder.forExploration(corpus, TimelineQuery(TimelinePoint(CalendarAxis.HIJRI, 1)))
        assertNull(explore.activeStep)
        assertEquals(listOf("p-end"), explore.visiblePlaces.map { it.place.id })
        assertTrue(CertaintyNote.EndOfYearView(TimelinePoint(CalendarAxis.HIJRI, 1)) in explore.certaintyNotes)

        val step = StoryStep(
            id = "step-1",
            order = 1,
            camera = StepCamera.Follow("r-1", 0, 1, ShotSize.MEDIUM, CameraTransition.FLY),
            time = StepTime.TimeLapse(TimelinePoint(CalendarAxis.HIJRI, 1), TimelinePoint(CalendarAxis.HIJRI, 2)),
            highlight = listOf(MapTargetRef.PlaceRef("p-end")),
            reveal = listOf(MapTargetRef.PlaceRef("p-hidden")),
            drawRoute = StepRouteDrawing("r-1", 0, 1),
            panel = StepPanel(titleCard = false, linkingSentence = "جملة رابطة."),
        )

        val shot = MapSnapshotBuilder.forStoryStep(corpus, step, sceneEventId = "ev-1")
        assertEquals(step, shot.activeStep)
        assertIs<SnapshotTime.TimeLapse>(shot.time)
        assertEquals(setOf("p-hidden", "p-end"), shot.visiblePlaces.map { it.place.id }.toSet())
        assertEquals("اسم جديد", shot.visiblePlaces.first { it.place.id == "p-end" }.activeName)
        assertEquals(listOf(coordA, coordB), shot.visibleRoutes.single().coordinates)
        assertEquals(listOf(MapTargetRef.PlaceRef("p-end")), shot.highlightedTargets)
        assertTrue(CertaintyNote.RouteAccuracyNote("مسار موثق") in shot.certaintyNotes)
    }
}
