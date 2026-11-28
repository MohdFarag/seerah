package app.seerah.model

/**
 * Saved follow-along resume point. Content IDs identify the original step, and
 * [fallbackStepIndex] lets the engine find a nearby step if the original is removed.
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
