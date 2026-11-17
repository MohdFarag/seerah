package app.seerah.trial

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class FrameStatsTest {
    private class Published(val atMillis: Long, val reading: FrameReading)

    /** Feeds frames whose gaps repeat [gaps] until [durationMillis] has passed, and returns what was published. */
    private fun run(
        stats: FrameStats,
        gaps: List<Long>,
        durationMillis: Long,
        startMillis: Long = 0,
    ): List<Published> {
        val published = mutableListOf<Published>()
        var now = startMillis
        stats.onFrame(now)
        var index = 0
        while (now - startMillis < durationMillis) {
            now += gaps[index % gaps.size]
            index++
            stats.onFrame(now)?.let { published += Published(now, it) }
        }
        return published
    }

    @Test
    fun steady60fps_reads60() {
        val published = run(FrameStats(), listOf(17, 17, 16), durationMillis = 3_000)

        assertTrue(published.size >= 2, "expected at least two readings, got ${published.size}")
        published.forEach { assertEquals(60.0, it.reading.fps, 0.01) }
    }

    @Test
    fun pauseOver250ms_isIgnored() {
        val stats = FrameStats()
        val before = run(stats, listOf(20), durationMillis = 2_000)
        // The first frame of the next run arrives 300 ms after the last of the first.
        val after = run(stats, listOf(20), durationMillis = 2_000, startMillis = 2_300)

        val published = before + after
        assertTrue(before.isNotEmpty() && after.isNotEmpty(), "expected readings before and after the pause")
        published.forEach {
            assertEquals(50.0, it.reading.fps, 0.01)
            assertEquals(50.0, it.reading.lowestFps, 0.01)
        }
    }

    @Test
    fun slowStretch_lowersLowest() {
        val stats = FrameStats()
        val fast = run(stats, listOf(20), durationMillis = 2_000)
        val slow = run(stats, listOf(50), durationMillis = 3_000, startMillis = 2_000)

        val published = fast + slow
        assertTrue(published.size >= 3, "expected at least three readings, got ${published.size}")
        assertEquals(20.0, published.last().reading.lowestFps, 0.01)
        assertEquals(published.minOf { it.reading.fps }, published.last().reading.lowestFps, 0.0)
    }

    @Test
    fun readings_publishedAtMostOncePerSecond() {
        val published = run(FrameStats(), listOf(16), durationMillis = 5_000)

        assertTrue(published.size >= 4, "expected at least four readings, got ${published.size}")
        published.zipWithNext().forEach { (earlier, later) ->
            assertTrue(later.atMillis - earlier.atMillis >= 1_000)
        }
    }

    @Test
    fun reset_clearsLowest() {
        val stats = FrameStats()
        run(stats, listOf(50), durationMillis = 2_000)

        stats.reset()
        val published = run(stats, listOf(20), durationMillis = 2_000)

        val first = assertNotNull(published.firstOrNull(), "expected a reading after reset")
        assertEquals(50.0, first.reading.lowestFps, 0.01)
        published.forEach { assertEquals(50.0, it.reading.lowestFps, 0.01) }
    }
}
