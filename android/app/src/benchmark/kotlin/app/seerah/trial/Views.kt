package app.seerah.trial

import app.seerah.map.MapColours
import app.seerah.map.MapStyleSpec
import app.seerah.map.RampStep
import app.seerah.map.ReliefSource
import app.seerah.map.ZoomRange

// The spike's colours, until :core:designsystem exists.
private const val LAND = 0xFFF1E6C8.toInt()
private const val SEA = 0xFF809B94.toInt()
private const val COAST = 0xFF6F8F88.toInt()
private const val INK_RGB = 0x967648

private const val ALPHA_SHIFT = 24
private const val ALPHA_OPAQUE = 0xFF
private const val ALPHA_NONE = 0x00
private const val ALPHA_LIGHT = 0x33
private const val ALPHA_MEDIUM = 0x66
private const val ALPHA_STRONG = 0x99

private const val BUILD_ZOOM = 12
private const val COARSE_BUILD_ZOOM = 11

private const val SELECTION_SEPARATOR = "/"
private const val SELECTION_PARTS = 2

// The relief fades out where the style's zoom (the build zoom minus one) passes these values.
private val EARLY_FADE = ZoomRange(start = 11.5, end = 13.5)
private val LATE_FADE = ZoomRange(start = 13.5, end = 15.5)

/**
 * The stored values where option (c)'s ramp steps from one alpha to the next. Each pair is one value
 * apart, so the band edge is a hard step and not a blend.
 */
private val BAND_EDGES = listOf(42.0 to 43.0, 127.0 to 128.0, 212.0 to 213.0)

private fun ink(alpha: Int) = (alpha shl ALPHA_SHIFT) or INK_RGB

private val OPTION_C_RAMP: List<RampStep> =
    listOf(ALPHA_NONE, ALPHA_LIGHT, ALPHA_MEDIUM, ALPHA_STRONG)
        .zipWithNext()
        .zip(BAND_EDGES) { (below, above), (belowValue, aboveValue) ->
            listOf(RampStep(belowValue, ink(below)), RampStep(aboveValue, ink(above)))
        }.flatten()

private val TRIAL_COLOURS = MapColours(sea = SEA, land = LAND, coast = COAST, ink = ink(ALPHA_OPAQUE))

/**
 * A way of drawing the relief that the trial compares.
 *
 * Covers the five trial views: zooms 12 and 11 on the pyramid build, options (a) and (c) whose
 * fade range moves later to style zoom 13.5–15.5, and the per-zoom build (`PER_ZOOM`).
 *
 * @property id the stable lowercase name of the view, used in the `<folder>/<view>` selection value.
 */
enum class TrialView(val id: String) {
    /** The painted tiles built to zoom 12, fading at style zoom 11.5 to 13.5. */
    ZOOM_12("zoom12"),

    /** The painted tiles built to zoom 11, with the same fade. */
    ZOOM_11("zoom11"),

    /** The painted zoom-12 tiles, fading later: style zoom 13.5 to 15.5 (a trial variable). */
    OPTION_A("option-a"),

    /** The value tiles with a stepped colour ramp, fading style zoom 13.5 to 15.5 (a trial variable). */
    OPTION_C("option-c"),

    /** The painted tiles built per zoom to zoom 12, fading at style zoom 11.5 to 13.5. */
    PER_ZOOM("per-zoom"),
}

/** The views that can be drawn from a variant built as [variant] in [mode]. */
fun viewsFor(variant: Variant, mode: BuildMode = BuildMode.PYRAMID): List<TrialView> = when (mode) {
    BuildMode.PYRAMID -> when (variant) {
        Variant.PAINTED -> listOf(TrialView.ZOOM_12, TrialView.ZOOM_11, TrialView.OPTION_A)
        Variant.VALUE -> listOf(TrialView.OPTION_C)
    }

    BuildMode.PER_ZOOM -> when (variant) {
        Variant.PAINTED -> listOf(TrialView.PER_ZOOM)
        Variant.VALUE -> emptyList()
    }
}

/** The style this view asks the map to draw. */
fun TrialView.styleSpec(): MapStyleSpec = when (this) {
    TrialView.ZOOM_12 -> MapStyleSpec(ReliefSource.Painted(BUILD_ZOOM), EARLY_FADE, TRIAL_COLOURS)
    TrialView.ZOOM_11 -> MapStyleSpec(ReliefSource.Painted(COARSE_BUILD_ZOOM), EARLY_FADE, TRIAL_COLOURS)
    TrialView.OPTION_A -> MapStyleSpec(ReliefSource.Painted(BUILD_ZOOM), LATE_FADE, TRIAL_COLOURS)
    TrialView.OPTION_C -> MapStyleSpec(ReliefSource.Value(BUILD_ZOOM, OPTION_C_RAMP), LATE_FADE, TRIAL_COLOURS)
    TrialView.PER_ZOOM -> MapStyleSpec(ReliefSource.Painted(BUILD_ZOOM), EARLY_FADE, TRIAL_COLOURS)
}

/**
 * A variant together with the view chosen to draw it.
 *
 * @property variant the loaded variant.
 * @property view one of the views [viewsFor] gives for it.
 */
data class TrialSelection(val variant: TrialVariant, val view: TrialView) {
    /** The `<folder>/<view id>` value that [resolveView] turns back into this selection. */
    val key: String get() = variant.folder.name + SELECTION_SEPARATOR + view.id
}

/**
 * Turns a `<folder>/<view id>` value into a selection, or null when it names nothing the app offers.
 *
 * The value comes from an exported activity's extra and from saved preferences, so it is checked
 * against what was loaded: the folder must be the name of a loaded variant's folder, the view one
 * that variant offers, and nothing else (`../x`, extra segments, null, blank) is accepted.
 */
fun resolveView(requested: String?, loaded: List<TrialVariant>): TrialSelection? {
    val parts = requested?.split(SELECTION_SEPARATOR) ?: return null
    if (parts.size != SELECTION_PARTS) return null
    val (folderName, viewId) = parts
    val variant = loaded.firstOrNull { it.folder.name == folderName } ?: return null
    val view = viewsFor(variant.variant, variant.mode).firstOrNull { it.id == viewId } ?: return null
    return TrialSelection(variant, view)
}

/**
 * The view the trial screen opens with: the launch intent's value, else the last used one, each
 * checked with [resolveView]. Null means the screen shows the picker.
 */
fun startSelection(requested: String?, lastUsed: String?, loaded: List<TrialVariant>): TrialSelection? =
    resolveView(requested, loaded) ?: resolveView(lastUsed, loaded)
