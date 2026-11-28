package app.seerah.userdata

import kotlinx.coroutines.flow.Flow

/** Reads and updates the locally saved explore session. */
interface UserDataRepository {
    /** Observes committed sessions. A failure ends this collection. Collect again to retry. */
    fun observeSession(): Flow<UserDataResult>

    /**
     * Atomically applies [transform] to the latest session and reports the committed value.
     * Keep the transform pure and quick. Its exceptions and cancellation propagate to the caller.
     */
    suspend fun updateSession(transform: (ExploreSession) -> ExploreSession): UserDataResult
}

/** Outcome of a session read or update. */
sealed interface UserDataResult {
    /** Session read from storage or committed by the update. */
    data class Success(val session: ExploreSession) : UserDataResult

    /** The file could not be read or written due to an I/O error. */
    data object IoFailure : UserDataResult

    /** The file contains invalid data. Its original bytes are preserved. */
    data object UnreadableData : UserDataResult

    /** The file requires a reader that supports [version]. Its original bytes are preserved. */
    data class UnsupportedFormat(val version: Int) : UserDataResult
}
