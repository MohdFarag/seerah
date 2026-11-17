package app.seerah.trial

/** Milliseconds in a second: the numerator of the rate. */
private const val MILLIS_PER_SECOND = 1_000L

/** The least time between two published readings. */
private const val PUBLISH_INTERVAL_MILLIS = 1_000L

/** The shortest frame gap that counts. */
private const val MIN_GAP_MILLIS = 1L

/** The longest frame gap that counts; a longer one is an idle pause, not a slow frame. */
private const val MAX_GAP_MILLIS = 250L

/** How many kept gaps one reading averages over. */
private const val WINDOW_GAPS = 30

/**
 * One published frame-rate reading.
 *
 * @property fps the rate over the last [WINDOW_GAPS] kept frame gaps.
 * @property lowestFps the smallest [fps] published since the last [FrameStats.reset]. Over a run it
 * equals the minimum of that run's readings, so a run's lowest rate can be read from either.
 */
data class FrameReading(val fps: Double, val lowestFps: Double)

/**
 * Turns frame-finished times into frame-rate readings, as the spike's `Stats.kt` did, minus its flaw
 * (see `docs/spike/findings.md`): a gap counts only if it is 1-250 ms, so idle pauses between camera
 * moves do not drag the lowest rate down.
 *
 * The rate is the last 30 kept gaps' count over their sum. The first reading is published as soon as
 * 30 gaps exist, then at most once a second, so the overlay is not redrawn per frame. A gap outside
 * 1-250 ms is dropped, but its frame still becomes the last frame. Not thread-safe: call it from one
 * thread.
 */
class FrameStats {
    private val gaps = ArrayDeque<Long>()
    private var lastFrameMillis: Long? = null
    private var lastPublishedMillis: Long? = null
    private var lowestFps = Double.POSITIVE_INFINITY

    /** Records a finished frame at [atMillis]; returns a reading only when one is published. */
    fun onFrame(atMillis: Long): FrameReading? {
        val previous = lastFrameMillis
        lastFrameMillis = atMillis
        if (previous == null) return null

        val gap = atMillis - previous
        if (gap in MIN_GAP_MILLIS..MAX_GAP_MILLIS) {
            gaps.addLast(gap)
            if (gaps.size > WINDOW_GAPS) gaps.removeFirst()
        }
        return publishIfDue(atMillis)
    }

    /** Clears the gaps, the last frame and the lowest rate, ready for a new run. */
    fun reset() {
        gaps.clear()
        lastFrameMillis = null
        lastPublishedMillis = null
        lowestFps = Double.POSITIVE_INFINITY
    }

    private fun publishIfDue(atMillis: Long): FrameReading? {
        if (gaps.size < WINDOW_GAPS) return null
        val published = lastPublishedMillis
        if (published != null && atMillis - published < PUBLISH_INTERVAL_MILLIS) return null

        lastPublishedMillis = atMillis
        val fps = MILLIS_PER_SECOND * WINDOW_GAPS.toDouble() / gaps.sum()
        lowestFps = minOf(lowestFps, fps)
        return FrameReading(fps, lowestFps)
    }
}
