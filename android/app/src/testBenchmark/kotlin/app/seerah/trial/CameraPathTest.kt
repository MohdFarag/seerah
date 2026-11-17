package app.seerah.trial

import app.seerah.map.MapCamera
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CameraPathTest {
    private val header = TrialHeader(
        device = "Pixel 7",
        view = "zoom12",
        fingerprint8 = "ab12cd34",
        openTimeMs = 850,
        pssMb = 310,
    )

    private fun results(vararg runs: List<Double>) = PathResults(runs.map { RunResult(it) })

    @Test
    fun animateThrough_visitsEveryStopInOrder() {
        val calls = mutableListOf<CameraStop>()
        val camera = object : MapCamera {
            override suspend fun animateTo(lat: Double, lon: Double, zoom: Double, durationMs: Long) {
                calls += CameraStop(lat, lon, zoom, durationMs)
            }
        }
        val stops = listOf(CameraStop(1.0, 2.0, 3.0, 400), CameraStop(4.0, 5.0, 6.0, 700))

        runBlocking { animateThrough(camera, stops) }

        assertEquals(stops, calls)
    }

    @Test
    fun results_reportColdRunSeparately_andMinimumOverAllRuns() {
        val results = results(listOf(50.0, 60.0, 58.0), listOf(59.0, 60.0, 60.0), listOf(57.0, 60.0, 61.0))

        assertEquals(58.0, results.cold.typicalFps)
        assertEquals(60.0, results.typicalFps)
        assertEquals(50.0, results.lowestFps)
        assertTrue("Run 1 (cold): typical 58.0 fps, lowest 50.0 fps" in results.asText(header))
        assertTrue("All runs: typical 60.0 fps, lowest 50.0 fps" in results.asText(header))
    }

    @Test
    fun median_ofEvenCount_isMeanOfMiddleTwo() {
        assertEquals(59.0, RunResult(listOf(50.0, 60.0, 58.0, 62.0)).typicalFps)
    }

    @Test
    fun runWithoutReadings_showsNoReading() {
        val empty = RunResult(emptyList())
        assertNull(empty.typicalFps)
        assertNull(empty.lowestFps)

        val text = results(listOf(60.0), emptyList(), listOf(60.0)).asText(header)

        assertTrue("Run 2: no reading" in text)
    }

    @Test
    fun text_includesDeviceViewFingerprintAndOpenTime() {
        val lines = results(listOf(60.0), listOf(60.0), listOf(60.0)).asText(header).lines()

        assertEquals("Map trial | Pixel 7 | zoom12 | ab12cd34", lines[0])
        assertEquals("Open time: 850 ms | Memory (PSS): 310 MB", lines[1])
    }

    @Test
    fun text_showsNoReadingForMissingOpenTimeAndMemory() {
        val unmeasured = header.copy(openTimeMs = null, pssMb = null)

        val lines = results(listOf(60.0), listOf(60.0), listOf(60.0)).asText(unmeasured).lines()

        assertEquals("Open time: no reading | Memory (PSS): no reading", lines[1])
    }

    @Test
    fun animateThrough_stopsAndPropagatesWhenAFlightIsCancelled() {
        val visited = mutableListOf<CameraStop>()
        val camera = object : MapCamera {
            override suspend fun animateTo(lat: Double, lon: Double, zoom: Double, durationMs: Long) {
                visited += CameraStop(lat, lon, zoom, durationMs)
                if (visited.size == 2) throw CancellationException("gesture")
            }
        }
        val stops = List(3) { CameraStop(it.toDouble(), 0.0, 1.0, 100) }

        assertFailsWith<CancellationException> { runBlocking { animateThrough(camera, stops) } }

        assertEquals(stops.take(2), visited)
    }

    @Test
    fun animateThrough_withNoStops_doesNothing() {
        val camera = object : MapCamera {
            override suspend fun animateTo(lat: Double, lon: Double, zoom: Double, durationMs: Long) =
                error("no stop to fly to")
        }

        runBlocking { animateThrough(camera, emptyList()) }
    }

    @Test
    fun results_withoutRuns_areRejected() {
        assertFailsWith<IllegalArgumentException> { PathResults(emptyList()) }
    }
}
