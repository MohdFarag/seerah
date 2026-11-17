package app.seerah.trial

import app.seerah.map.CameraLimits
import java.io.File
import org.json.JSONException
import org.json.JSONObject

/** The tile zoom every trial view assumes the variant was built for. */
private const val TRIAL_MAX_ZOOM = 12

/** How many leading characters of the fingerprint name a variant on screen. */
private const val FINGERPRINT_LENGTH = 8

private const val METADATA_FILE = "metadata.json"
private const val CAMERA_PATH_FILE = "camera-path.json"
private const val RELIEF_FILE = "relief.pmtiles"

/** How a trial build stores the relief; the `variant` field of `metadata.json`. */
enum class Variant(val metadataName: String) {
    /** Tiles that hold the finished shading as an image. */
    PAINTED("painted"),

    /** Tiles that hold a stored value per pixel, coloured by the map. */
    VALUE("value"),
}

/**
 * One stop of the camera path.
 *
 * @property lat the latitude, in degrees.
 * @property lon the longitude, in degrees.
 * @property zoom the MapLibre zoom.
 * @property durationMs how long the camera takes to get there, in milliseconds.
 */
data class CameraStop(val lat: Double, val lon: Double, val zoom: Double, val durationMs: Long)

/**
 * The camera path of a variant: where to fly and how far the camera may go.
 *
 * @property stops the stops, in order.
 * @property limits the box and zoom range the camera is kept inside.
 */
data class CameraPathSpec(val stops: List<CameraStop>, val limits: CameraLimits)

/**
 * A trial variant folder that passed every check.
 *
 * @property folder the variant folder on the device.
 * @property variant how the relief is stored.
 * @property fingerprint8 the first eight characters of the build fingerprint.
 * @property cameraPath the camera path to run over this variant.
 */
data class TrialVariant(
    val folder: File,
    val variant: Variant,
    val fingerprint8: String,
    val cameraPath: CameraPathSpec,
) {
    /** The relief tiles of the variant. */
    val relief: File get() = File(folder, RELIEF_FILE)
}

/** The outcome of reading one sub-folder of the trial folder. */
sealed interface VariantLoad {
    /** The folder is a usable variant. */
    data class Loaded(val variant: TrialVariant) : VariantLoad

    /** The folder is not usable; [reason] says why, for the picker to show. */
    data class Skipped(val folder: File, val reason: String) : VariantLoad
}

/**
 * Reads every sub-folder of [root] as a trial variant, sorted by name.
 *
 * Never throws for a bad folder: whatever is wrong with it (a missing file, malformed JSON, a build
 * that is not a trial build) becomes a [VariantLoad.Skipped] with the reason. A missing [root]
 * gives an empty list.
 */
fun loadVariants(root: File): List<VariantLoad> = root
    .listFiles(File::isDirectory)
    .orEmpty()
    .sortedBy(File::getName)
    .map(::loadVariant)

private fun loadVariant(folder: File): VariantLoad = try {
    readVariant(folder)
} catch (error: JSONException) {
    VariantLoad.Skipped(folder, "unreadable JSON in ${error.message}")
}

private fun readVariant(folder: File): VariantLoad {
    val metadataFile = File(folder, METADATA_FILE)
    if (!metadataFile.isFile) return skipped(folder, "$METADATA_FILE is missing")
    val metadata = parsing(metadataFile, ::JSONObject)

    if (!metadata.optBoolean("trial", false)) return skipped(folder, "$METADATA_FILE does not say trial: true")
    val variant =
        Variant.entries.firstOrNull { it.metadataName == metadata.optString("variant") }
            ?: return skipped(folder, "unknown variant \"${metadata.optString("variant")}\"")
    if (metadata.optInt("maxzoom", -1) != TRIAL_MAX_ZOOM) {
        return skipped(folder, "maxzoom is not $TRIAL_MAX_ZOOM, and every view assumes a zoom-$TRIAL_MAX_ZOOM build")
    }
    val fingerprint = metadata.optString("fingerprint")
    if (fingerprint.length < FINGERPRINT_LENGTH) return skipped(folder, "fingerprint is missing or too short")
    if (!File(folder, RELIEF_FILE).isFile) return skipped(folder, "$RELIEF_FILE is missing")

    val cameraPathFile = File(folder, CAMERA_PATH_FILE)
    if (!cameraPathFile.isFile) return skipped(folder, "$CAMERA_PATH_FILE is missing")

    return VariantLoad.Loaded(
        TrialVariant(
            folder = folder,
            variant = variant,
            fingerprint8 = fingerprint.take(FINGERPRINT_LENGTH),
            cameraPath = parsing(cameraPathFile) { parseCameraPath(JSONObject(it)) },
        ),
    )
}

/** Parses [file]'s text with [parse], naming the file in the message of any JSON error. */
private fun <T> parsing(file: File, parse: (String) -> T): T = try {
    parse(file.readText())
} catch (error: JSONException) {
    throw JSONException("${file.name}: ${error.message}")
}

private fun skipped(folder: File, reason: String) = VariantLoad.Skipped(folder, reason)

private fun parseCameraPath(json: JSONObject): CameraPathSpec {
    val stops = json.getJSONArray("stops")
    val limits = json.getJSONObject("limits")
    return CameraPathSpec(
        stops =
            (0 until stops.length()).map { index ->
                val stop = stops.getJSONObject(index)
                CameraStop(
                    lat = stop.getDouble("lat"),
                    lon = stop.getDouble("lon"),
                    zoom = stop.getDouble("zoom"),
                    durationMs = stop.getLong("duration_ms"),
                )
            },
        limits =
            CameraLimits(
                west = limits.getDouble("west"),
                south = limits.getDouble("south"),
                east = limits.getDouble("east"),
                north = limits.getDouble("north"),
                minZoom = limits.getDouble("min_zoom"),
                maxZoom = limits.getDouble("max_zoom"),
            ),
    )
}
