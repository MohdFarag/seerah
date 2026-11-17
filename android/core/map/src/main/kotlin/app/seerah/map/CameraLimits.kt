package app.seerah.map

/**
 * Where the camera may go: a box in degrees and a zoom range.
 *
 * @property west the western edge, in degrees of longitude.
 * @property south the southern edge, in degrees of latitude.
 * @property east the eastern edge, in degrees of longitude.
 * @property north the northern edge, in degrees of latitude.
 * @property minZoom the lowest MapLibre zoom.
 * @property maxZoom the highest MapLibre zoom.
 */
data class CameraLimits(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val minZoom: Double,
    val maxZoom: Double,
)
