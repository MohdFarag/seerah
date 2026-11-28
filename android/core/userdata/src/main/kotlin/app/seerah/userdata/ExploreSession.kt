package app.seerah.userdata

import app.seerah.model.FollowAlongBookmark
import app.seerah.model.TimelinePoint

/**
 * Saved exploration and follow-along state. The caller resolves these references against active content.
 *
 * @property time Last selected timeline point at its saved precision.
 * @property focus Last place or event selected in exploration.
 * @property bookmark Resume point for follow-along, independent of the exploration selection.
 */
data class ExploreSession(
    val time: TimelinePoint? = null,
    val focus: ExploreFocus? = null,
    val bookmark: FollowAlongBookmark? = null,
)

/** A stable content reference for the saved exploration focus. */
sealed interface ExploreFocus {
    /** The stable ID of the place or event. */
    val id: String

    /** Focus on a place. */
    data class Place(override val id: String) : ExploreFocus {
        init {
            require(id.isNotBlank()) { "Place focus ID must not be blank." }
        }
    }

    /** Focus on an event. */
    data class Event(override val id: String) : ExploreFocus {
        init {
            require(id.isNotBlank()) { "Event focus ID must not be blank." }
        }
    }
}
