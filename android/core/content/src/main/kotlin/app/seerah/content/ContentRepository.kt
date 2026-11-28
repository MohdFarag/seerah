package app.seerah.content

import app.seerah.model.EventDetail
import app.seerah.model.ExplorationCorpus
import app.seerah.model.Story
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Availability state of the offline Seerah content database exposed to the app. */
sealed interface ContentState {
    /** Cold-start disk hydration or database preparation is in progress off the main thread. */
    data object Loading : ContentState

    /** No valid signed content package is currently installed. */
    data object NotInstalled : ContentState

    /** A generation-consistent [ExplorationCorpus] is ready for exploration and domain resolution. */
    data class Ready(val corpus: ExplorationCorpus) : ContentState

    /** Active package verification or disposable Room rebuild failed with typed [failure]. */
    data class Error(val failure: PackageFailure) : ContentState
}

/** Lazy lookup state for heavy content records fetched on demand from Room 3. */
sealed interface ContentLookupResult<out T> {
    /** Content database is still preparing or querying the requested record. */
    data object Loading : ContentLookupResult<Nothing>

    /** Requested record [value] was loaded cleanly from the active content generation. */
    data class Found<out T>(val value: T) : ContentLookupResult<T>

    /** No record with that ID exists in the active package (or no content package is installed). */
    data object NotFound : ContentLookupResult<Nothing>

    /** Content database preparation or lookup failed with typed [failure]. */
    data class Error(val failure: PackageFailure) : ContentLookupResult<Nothing>
}

/** Reactive repository exposing offline exploration data, lazy event details, and story scripts. */
interface ContentRepository {
    /** Observes the live, tear-free [ContentState] of the active offline content generation. */
    fun observeContentState(): StateFlow<ContentState>

    /** Lazily observes the complete [EventDetail] bundle for [eventId] across package updates. */
    fun observeEventDetail(eventId: String): Flow<ContentLookupResult<EventDetail>>

    /** Lazily observes the complete directed [Story] script for [storyId] across package updates. */
    fun observeStory(storyId: String): Flow<ContentLookupResult<Story>>
}
