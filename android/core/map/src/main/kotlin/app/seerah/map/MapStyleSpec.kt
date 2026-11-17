package app.seerah.map

/**
 * What the map style needs besides the files: how the relief is stored and drawn, and the colours.
 *
 * @property relief how the relief tiles store the terrain.
 * @property reliefFade the zooms over which the relief fades in.
 * @property colours the colours of the map.
 */
data class MapStyleSpec(val relief: ReliefSource, val reliefFade: ZoomRange, val colours: MapColours)

/** How the relief tiles store the terrain. */
sealed interface ReliefSource {
    /** The highest zoom the tiles are built for; the map stretches them beyond it. */
    val maxZoom: Int

    /** Tiles that already hold the finished shading as an image. */
    data class Painted(override val maxZoom: Int) : ReliefSource

    /**
     * Tiles that hold a stored value (0 to 255) per pixel, which the map colours with [ramp].
     *
     * @property ramp the colours by stored value, in ascending order of value.
     */
    data class Value(override val maxZoom: Int, val ramp: List<RampStep>) : ReliefSource
}

/**
 * A range of MapLibre zoom levels.
 *
 * @property start the zoom where the range begins.
 * @property end the zoom where the range ends.
 */
data class ZoomRange(val start: Double, val end: Double)

/**
 * The colours of the map, as ARGB integers.
 *
 * [sea] and [coast] are drawn from phase 3, when the base map arrives; they are part of the
 * interface now so that it does not change then.
 *
 * @property sea the sea.
 * @property land the land.
 * @property coast the coastline.
 * @property ink the shading ink of the relief.
 */
data class MapColours(val sea: Int, val land: Int, val coast: Int, val ink: Int)

/**
 * One stop of a relief colour ramp.
 *
 * @property value the stored value, from 0 to 255.
 * @property colour the ARGB colour at that value.
 */
data class RampStep(val value: Double, val colour: Int)
