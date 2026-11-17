package app.seerah.map

/** Something the map reports about its own rendering. */
sealed interface MapEvent {
    /** The map finished drawing everything it was asked to draw. */
    data object FullyRendered : MapEvent

    /**
     * One frame finished.
     *
     * @property atMillis `SystemClock.elapsedRealtime()` when the frame finished.
     */
    data class FrameRendered(val atMillis: Long) : MapEvent
}
