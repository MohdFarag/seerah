package app.seerah.domain

import app.seerah.model.CalendarAxis
import app.seerah.model.CameraTransition
import app.seerah.model.MapTargetRef
import app.seerah.model.ShotSize
import app.seerah.model.StepCamera
import app.seerah.model.StepPanel
import app.seerah.model.StepTime
import app.seerah.model.Story
import app.seerah.model.StoryChapter
import app.seerah.model.StoryScene
import app.seerah.model.StoryStep
import app.seerah.model.TimelinePoint
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class FollowAlongEngineTest {
    private fun step(id: String, order: Int) = StoryStep(
        id = id,
        order = order,
        camera = StepCamera.Frame(listOf(MapTargetRef.PlaceRef("p-1")), ShotSize.MEDIUM, CameraTransition.FLY),
        time = StepTime.At(TimelinePoint(CalendarAxis.HIJRI, 1)),
        panel = StepPanel(titleCard = order == 1),
    )

    private val story = Story(
        id = "story-1",
        title = "قصة مثالية",
        chapters = listOf(
            StoryChapter(
                id = "ch-1",
                order = 1,
                title = "فصل ١",
                scenes = listOf(
                    StoryScene(
                        "sc-1",
                        eventId = "ev-1",
                        order = 1,
                        title = "مشهد ١",
                        steps = listOf(step("s1", 1), step("s2", 2)),
                    ),
                    StoryScene("sc-2", eventId = "ev-2", order = 2, title = "مشهد ٢", steps = listOf(step("s3", 1))),
                ),
            ),
        ),
    )

    @Test
    fun stepping_clampsAtEndsAndMovesManuallyAcrossScenes() {
        val started = FollowAlongEngine.start(story)
        assertEquals("s1", started.bookmark.stepId)
        assertFalse(started.canGoBack)
        assertTrue(started.canGoNext)

        val step2 = FollowAlongEngine.nextStep(started, story) as FollowAlongState.Active
        assertEquals("s2", step2.bookmark.stepId)

        val step3 = FollowAlongEngine.nextStep(step2, story) as FollowAlongState.Active
        assertEquals("s3", step3.bookmark.stepId)
        assertEquals("sc-2", step3.bookmark.sceneId)
        assertFalse(step3.canGoNext)

        val clampedEnd = FollowAlongEngine.nextStep(step3, story) as FollowAlongState.Active
        assertEquals("s3", clampedEnd.bookmark.stepId)

        val back = FollowAlongEngine.previousStep(clampedEnd, story) as FollowAlongState.Active
        assertEquals("s2", back.bookmark.stepId)
    }

    @Test
    fun explorationInterruptionAndRelaunch_preserveBookmarkWithoutMovingOrAutoStarting() {
        val s2 = FollowAlongEngine.start(story, atStepId = "s2")
        val suspendedOnScrub = FollowAlongEngine.onUserExploreTimeChanged(s2) as FollowAlongState.Suspended
        assertEquals("s2", suspendedOnScrub.bookmark.stepId)
        assertTrue(suspendedOnScrub.offerResume)

        val suspendedOnOtherEvent = FollowAlongEngine.onUserOpenedEventInExploration(
            s2,
            "ev-2",
        ) as FollowAlongState.Suspended
        assertEquals("s2", suspendedOnOtherEvent.bookmark.stepId)

        val resumed = FollowAlongEngine.resume(suspendedOnOtherEvent, listOf(story)) as FollowAlongState.Active
        assertEquals("s2", resumed.bookmark.stepId)

        val moved = FollowAlongEngine.moveBookmarkToEvent(listOf(story), "ev-2") as FollowAlongState.Active
        assertEquals("s3", moved.bookmark.stepId)

        val relaunched = FollowAlongEngine.restoreOnRelaunch(s2.bookmark, listOf(story))
        assertIs<FollowAlongState.Suspended>(relaunched)
        assertTrue(relaunched.offerResume)
        assertEquals("s2", relaunched.bookmark.stepId)
    }

    @Test
    fun restoreOnRelaunch_survivesUpdatedStoryContentGracefully() {
        val savedBookmark =
            FollowAlongBookmark("story-1", "ch-1", "sc-1", stepId = "deleted-step", fallbackStepIndex = 5)
        val restored = FollowAlongEngine.restoreOnRelaunch(savedBookmark, listOf(story)) as FollowAlongState.Suspended
        assertEquals("s2", restored.bookmark.stepId)

        val missingStory = FollowAlongEngine.restoreOnRelaunch(savedBookmark, emptyList())
        assertEquals(FollowAlongState.Inactive, missingStory)
    }
}
