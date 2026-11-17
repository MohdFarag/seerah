package app.seerah.trial

import app.seerah.map.MapCamera
import java.util.Locale

/** How many times the camera path is run: the first run is cold, the others are warm. */
const val RUNS = 3

/** What a missing rate is shown as. */
private const val NO_READING = "no reading"

/**
 * Flies [camera] through every stop of [stops], in order, waiting for each flight to end.
 *
 * Ends with a `CancellationException` if a gesture cancels a flight.
 */
suspend fun animateThrough(camera: MapCamera, stops: List<CameraStop>) {
    stops.forEach { camera.animateTo(it.lat, it.lon, it.zoom, it.durationMs) }
}

/**
 * The frame-rate readings of one run of the camera path.
 *
 * @property readings the published rates, in frames per second.
 */
data class RunResult(val readings: List<Double>) {
    /** The median reading (the mean of the middle two for an even count), or null without readings. */
    val typicalFps: Double? = median(readings)

    /** The smallest reading, or null without readings. */
    val lowestFps: Double? = readings.minOrNull()
}

/**
 * The runs of one trial.
 *
 * @property runs the runs in order; the first is the cold one.
 */
data class PathResults(val runs: List<RunResult>) {
    /** The first run, before anything is cached. */
    val cold: RunResult = runs.first()

    /** The median of the runs' typical rates, or null when no run has a reading. */
    val typicalFps: Double? = median(runs.mapNotNull { it.typicalFps })

    /** The smallest reading over all runs, or null when there is none. */
    val lowestFps: Double? = runs.mapNotNull { it.lowestFps }.minOrNull()

    /** Renders the results under [header] as the English text the overlay shows. */
    fun asText(header: TrialHeader): String = buildList {
        add("Map trial | ${header.device} | ${header.view} | ${header.fingerprint8}")
        add("Open time: ${header.openTimeMs.orNoReading("ms")} | Memory (PSS): ${header.pssMb.orNoReading("MB")}")
        runs.forEachIndexed { index, run ->
            val label = if (index == 0) "Run 1 (cold)" else "Run ${index + 1}"
            add("$label: ${rates(run.typicalFps, run.lowestFps)}")
        }
        add("All runs: ${rates(typicalFps, lowestFps)}")
    }.joinToString("\n")
}

/**
 * What the overlay's first two lines say about the device and the view.
 *
 * @property device the device model.
 * @property view the trial view id.
 * @property fingerprint8 the first eight characters of the variant's fingerprint.
 * @property openTimeMs the time to open the map, or null if not measured.
 * @property pssMb the proportional set size after the run, or null if not measured.
 */
data class TrialHeader(
    val device: String,
    val view: String,
    val fingerprint8: String,
    val openTimeMs: Long?,
    val pssMb: Int?,
)

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

private fun rates(typical: Double?, lowest: Double?): String = if (typical == null || lowest == null) {
    NO_READING
} else {
    "typical ${typical.oneDecimal()} fps, lowest ${lowest.oneDecimal()} fps"
}

private fun Double.oneDecimal(): String = String.format(Locale.ROOT, "%.1f", this)

private fun Number?.orNoReading(unit: String): String = if (this == null) NO_READING else "$this $unit"
