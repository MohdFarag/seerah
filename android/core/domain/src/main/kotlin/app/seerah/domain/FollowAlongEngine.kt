package app.seerah.domain

import app.seerah.model.Story
import app.seerah.model.StoryChapter
import app.seerah.model.StoryScene
import app.seerah.model.StoryStep

/**
 * Stable follow-along resume point that survives content updates when step ordering shifts or scenes
 * are edited ([ADR 0003](../../../docs/adr/0003-v1-architecture-and-modules.md)).
 */
data class FollowAlongBookmark(
    val storyId: String,
    val chapterId: String,
    val sceneId: String,
    val stepId: String,
    val fallbackStepIndex: Int,
) {
    init {
        require(
            storyId.isNotBlank() &&
                chapterId.isNotBlank() &&
                sceneId.isNotBlank() &&
                stepId.isNotBlank(),
        ) {
            "FollowAlongBookmark identifiers must not be blank."
        }
        require(fallbackStepIndex >= 0) {
            "FollowAlongBookmark fallbackStepIndex must be >= 0, got $fallbackStepIndex."
        }
    }
}

/**
 * Pure follow-along playback state tracked in the exploration session.
 */
sealed interface FollowAlongState {
    /** User is in free exploration with no active or suspended follow-along session. */
    data object Inactive : FollowAlongState

    /** Follow-along mode is actively showing [bookmark] at position [flatIndex] of [totalSteps]. */
    data class Active(val bookmark: FollowAlongBookmark, val flatIndex: Int, val totalSteps: Int) : FollowAlongState {
        init {
            require(totalSteps >= 1) { "Active FollowAlongState requires totalSteps >= 1." }
            require(flatIndex in 0 until totalSteps) {
                "Active flatIndex $flatIndex out of 0 until $totalSteps."
            }
        }

        /** True when stepping backward to an earlier shot is available. */
        val canGoBack: Boolean get() = flatIndex > 0

        /** True when stepping forward to a later shot is available. */
        val canGoNext: Boolean get() = flatIndex < totalSteps - 1
    }

    /**
     * Follow-along mode is paused, preserving [bookmark] with an option to resume manually without
     * auto-playing.
     */
    data class Suspended(val bookmark: FollowAlongBookmark, val offerResume: Boolean = true) : FollowAlongState
}

/**
 * Pure immutable state machine governing guided follow-along navigation rules:
 * - Manual next/back stepping only (never timer-based auto-advance).
 * - User exploration time changes suspend the session without losing its bookmark.
 * - Opening another event in exploration suspends without moving the saved bookmark unless
 *   [moveBookmarkToEvent] is explicitly invoked.
 * - App relaunch restores as [FollowAlongState.Suspended] with `offerResume = true` and never auto-starts.
 */
object FollowAlongEngine {
    private data class FlattenedStep(
        val chapter: StoryChapter,
        val scene: StoryScene,
        val step: StoryStep,
        val flatIndex: Int,
    )

    /** Starts [story] cleanly at [atStepId] (or step index `0` when null/unmatched). */
    fun start(story: Story, atStepId: String? = null): FollowAlongState.Active {
        val flattened = flatten(story)
        val index = atStepId?.let { target -> flattened.indexOfFirst { it.step.id == target } } ?: 0
        val safeIndex = if (index >= 0) index else 0
        return toActive(story, flattened, safeIndex)
    }

    /**
     * Starts follow-along at the first scene anchored to [eventId], breaking ties deterministically
     * by `(story.id, chapter.order, scene.order)`. Returns null if no scene anchors [eventId].
     */
    fun startForEvent(eventId: String, stories: List<Story>): FollowAlongState.Active? {
        require(eventId.isNotBlank()) { "startForEvent requires a non-blank eventId." }
        for (story in stories.sortedBy { it.id }) {
            val flattened = flatten(story)
            val matchIndex = flattened.indexOfFirst { it.scene.eventId == eventId }
            if (matchIndex >= 0) {
                return toActive(story, flattened, matchIndex)
            }
        }
        return null
    }

    /** Advances manually one step forward when [state] is [FollowAlongState.Active]. */
    fun nextStep(state: FollowAlongState, story: Story): FollowAlongState {
        val active = state as? FollowAlongState.Active ?: return state
        val flattened = flatten(story)
        val currentIdx = resolveIndex(flattened, active.bookmark) ?: return FollowAlongState.Inactive
        val targetIdx = (currentIdx + 1).coerceAtMost(flattened.lastIndex)
        return toActive(story, flattened, targetIdx)
    }

    /** Steps manually one step backward when [state] is [FollowAlongState.Active]. */
    fun previousStep(state: FollowAlongState, story: Story): FollowAlongState {
        val active = state as? FollowAlongState.Active ?: return state
        val flattened = flatten(story)
        val currentIdx = resolveIndex(flattened, active.bookmark) ?: return FollowAlongState.Inactive
        val targetIdx = (currentIdx - 1).coerceAtLeast(0)
        return toActive(story, flattened, targetIdx)
    }

    /**
     * Suspends active follow-along when the user manually scrubs the timeline bar in exploration.
     * Note: callers must not invoke this when the story step itself applies its own `StepTime`.
     */
    fun onUserExploreTimeChanged(state: FollowAlongState): FollowAlongState = when (state) {
        is FollowAlongState.Active -> FollowAlongState.Suspended(state.bookmark, offerResume = true)
        FollowAlongState.Inactive, is FollowAlongState.Suspended -> state
    }

    /**
     * Opens [eventId] in exploration while keeping any active follow-along bookmark untouched
     * (moving the bookmark requires calling [moveBookmarkToEvent] explicitly).
     */
    fun onUserOpenedEventInExploration(state: FollowAlongState, eventId: String): FollowAlongState {
        require(eventId.isNotBlank()) { "onUserOpenedEventInExploration requires non-blank eventId." }
        return when (state) {
            is FollowAlongState.Active -> FollowAlongState.Suspended(state.bookmark, offerResume = true)
            FollowAlongState.Inactive, is FollowAlongState.Suspended -> state
        }
    }

    /** Explicitly moves the follow-along position to the scene anchored at [eventId]. */
    fun moveBookmarkToEvent(stories: List<Story>, eventId: String): FollowAlongState =
        startForEvent(eventId, stories) ?: FollowAlongState.Inactive

    /** Resumes a [FollowAlongState.Suspended] session, adapting cleanly if content shifted. */
    fun resume(state: FollowAlongState, stories: List<Story>): FollowAlongState = when (state) {
        FollowAlongState.Inactive -> FollowAlongState.Inactive

        is FollowAlongState.Active -> state

        is FollowAlongState.Suspended -> {
            val story = stories.firstOrNull { it.id == state.bookmark.storyId }
                ?: return FollowAlongState.Inactive
            val flattened = flatten(story)
            val resolved = resolveIndex(flattened, state.bookmark) ?: return FollowAlongState.Inactive
            toActive(story, flattened, resolved)
        }
    }

    /**
     * Restores a persisted bookmark upon relaunch as [FollowAlongState.Suspended] (`offerResume = true`),
     * never auto-starting playback, and falling back to [FollowAlongState.Inactive] if removed.
     */
    fun restoreOnRelaunch(savedBookmark: FollowAlongBookmark?, stories: List<Story>): FollowAlongState {
        if (savedBookmark == null) return FollowAlongState.Inactive
        val story = stories.firstOrNull { it.id == savedBookmark.storyId } ?: return FollowAlongState.Inactive
        val flattened = flatten(story)
        val resolvedIdx = resolveIndex(flattened, savedBookmark) ?: return FollowAlongState.Inactive
        val hit = flattened[resolvedIdx]
        return FollowAlongState.Suspended(
            bookmark = FollowAlongBookmark(
                storyId = story.id,
                chapterId = hit.chapter.id,
                sceneId = hit.scene.id,
                stepId = hit.step.id,
                fallbackStepIndex = hit.flatIndex,
            ),
            offerResume = true,
        )
    }

    /** Exits follow-along cleanly back to free exploration. */
    fun exit(): FollowAlongState.Inactive = FollowAlongState.Inactive

    /** Resolves the live [StoryStep] referenced by [state] inside [story], or null if unavailable. */
    fun resolveCurrentStep(state: FollowAlongState, story: Story): StoryStep? {
        val bookmark = when (state) {
            FollowAlongState.Inactive -> return null
            is FollowAlongState.Active -> state.bookmark
            is FollowAlongState.Suspended -> state.bookmark
        }
        if (bookmark.storyId != story.id) return null
        val flattened = flatten(story)
        val idx = resolveIndex(flattened, bookmark) ?: return null
        return flattened[idx].step
    }

    private fun flatten(story: Story): List<FlattenedStep> {
        var index = 0
        return buildList {
            for (chapter in story.chapters.sortedWith(compareBy({ it.order }, { it.id }))) {
                for (scene in chapter.scenes.sortedWith(compareBy({ it.order }, { it.id }))) {
                    for (step in scene.steps.sortedWith(compareBy({ it.order }, { it.id }))) {
                        add(FlattenedStep(chapter = chapter, scene = scene, step = step, flatIndex = index++))
                    }
                }
            }
        }
    }

    private fun resolveIndex(flattened: List<FlattenedStep>, bookmark: FollowAlongBookmark): Int? {
        if (flattened.isEmpty()) return null
        val byStepId = flattened.indexOfFirst { it.step.id == bookmark.stepId }
        if (byStepId >= 0) return byStepId

        val sceneSteps = flattened.filter { it.scene.id == bookmark.sceneId }
        if (sceneSteps.isNotEmpty()) {
            return sceneSteps.last().flatIndex.coerceAtMost(
                bookmark.fallbackStepIndex.coerceAtLeast(sceneSteps.first().flatIndex),
            )
        }

        return bookmark.fallbackStepIndex.coerceIn(0, flattened.lastIndex)
    }

    private fun toActive(story: Story, flattened: List<FlattenedStep>, flatIndex: Int): FollowAlongState.Active {
        val hit = flattened[flatIndex]
        return FollowAlongState.Active(
            bookmark = FollowAlongBookmark(
                storyId = story.id,
                chapterId = hit.chapter.id,
                sceneId = hit.scene.id,
                stepId = hit.step.id,
                fallbackStepIndex = flatIndex,
            ),
            flatIndex = flatIndex,
            totalSteps = flattened.size,
        )
    }
}
