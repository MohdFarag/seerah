package app.seerah.map

import app.seerah.model.MapMetadata
import app.seerah.model.ReliefEncoding
import java.io.File

private const val RGB_MASK = 0x00FFFFFF
private const val ALPHA_SHIFT = 24
private const val ALPHA_NONE = 0x00
private const val ALPHA_LIGHT = 0x33
private const val ALPHA_MEDIUM = 0x66
private const val ALPHA_STRONG = 0x99

private const val BAND_EDGE_1_LOW = 42.0
private const val BAND_EDGE_1_HIGH = 43.0
private const val BAND_EDGE_2_LOW = 127.0
private const val BAND_EDGE_2_HIGH = 128.0
private const val BAND_EDGE_3_LOW = 212.0
private const val BAND_EDGE_3_HIGH = 213.0

private val STYLE_C_BAND_EDGES = listOf(
    BAND_EDGE_1_LOW to BAND_EDGE_1_HIGH,
    BAND_EDGE_2_LOW to BAND_EDGE_2_HIGH,
    BAND_EDGE_3_LOW to BAND_EDGE_3_HIGH,
)

private val STYLE_C_ALPHAS = listOf(ALPHA_NONE, ALPHA_LIGHT, ALPHA_MEDIUM, ALPHA_STRONG)

/** Maps [MapMetadata] camera bounds and zoom range into [CameraLimits]. */
fun MapMetadata.toCameraLimits(): CameraLimits = CameraLimits(
    west = cameraBounds.west,
    south = cameraBounds.south,
    east = cameraBounds.east,
    north = cameraBounds.north,
    minZoom = minZoom,
    maxZoom = maxZoom,
)

/**
 * Resolves `sources.relief.file` and `sources.base.file` inside [dataDir], enforcing canonical
 * path containment so relative traversal or absolute paths cannot escape [dataDir].
 */
fun resolveMapFiles(dataDir: File, metadata: MapMetadata): MapFiles {
    val reliefFile = resolveContainedFile(dataDir, metadata.sources.relief.file)
    val baseFile = resolveContainedFile(dataDir, metadata.sources.base.file)
    return MapFiles(relief = reliefFile, base = baseFile)
}

/**
 * Builds the 6-stop Style C elevation ramp for Terrarium value tiles using only the RGB channels
 * of [inkArgb], preserving each band's stepped alpha (`0x00`, `0x33`, `0x66`, `0x99`).
 */
fun styleCValueRamp(inkArgb: Int): List<RampStep> {
    val rgb = inkArgb and RGB_MASK
    return STYLE_C_ALPHAS
        .zipWithNext()
        .zip(STYLE_C_BAND_EDGES) { (belowAlpha, aboveAlpha), (belowValue, aboveValue) ->
            listOf(
                RampStep(value = belowValue, colour = (belowAlpha shl ALPHA_SHIFT) or rgb),
                RampStep(value = aboveValue, colour = (aboveAlpha shl ALPHA_SHIFT) or rgb),
            )
        }.flatten()
}

/** Converts [MapMetadata] plus caller-supplied theme [colours] and [reliefFade] into [MapStyleSpec]. */
fun MapMetadata.toStyleSpec(
    colours: MapColours,
    reliefFade: ZoomRange,
    valueRamp: List<RampStep> = styleCValueRamp(colours.ink),
): MapStyleSpec {
    val relief = when (sources.relief.encoding) {
        ReliefEncoding.TERRARIUM_VALUE -> ReliefSource.Value(
            maxZoom = sources.relief.maxZoom,
            ramp = valueRamp,
        )

        ReliefEncoding.PAINTED_RGBA -> ReliefSource.Painted(
            maxZoom = sources.relief.maxZoom,
        )
    }
    return MapStyleSpec(relief = relief, reliefFade = reliefFade, colours = colours)
}

private fun resolveContainedFile(dataDir: File, relativeName: String): File {
    require(relativeName.isNotBlank()) { "Archive file name must not be blank." }
    require(!File(relativeName).isAbsolute) { "Archive file path must be relative: $relativeName" }
    val canonicalRoot = dataDir.canonicalFile.toPath()
    val candidate = File(dataDir, relativeName)
    val canonicalTarget = candidate.canonicalFile.toPath()
    require(canonicalTarget.startsWith(canonicalRoot) && canonicalTarget != canonicalRoot) {
        "Resolved archive path '$canonicalTarget' escapes root '$canonicalRoot'."
    }
    return canonicalTarget.toFile()
}
