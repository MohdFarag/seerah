package app.seerah.map

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private const val STYLE_VERSION = 8
private const val SOURCE_ID = "relief"
private const val BACKGROUND_LAYER_ID = "background"
private const val RELIEF_LAYER_ID = "relief"
private const val RASTER_LAYER_TYPE = "raster"
private const val COLOR_RELIEF_LAYER_TYPE = "color-relief"
private const val BACKGROUND_LAYER_TYPE = "background"
private const val RASTER_SOURCE_TYPE = "raster"
private const val RASTER_DEM_SOURCE_TYPE = "raster-dem"
private const val CHANNEL_MASK = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val RELIEF_TILE_SIZE = 256
private const val MAX_CHANNEL = 255.0
private const val ALPHA_DECIMALS = "%.3f"

/**
 * Builds the MapLibre style document for [spec] over the map files in [files].
 *
 * The style has one `relief` source read from the PMTiles file and two layers, a land-coloured
 * `background` and the `relief` layer on top of it. The relief layer fades out over
 * [MapStyleSpec.reliefFade]. A [ReliefSource.Painted] source is drawn as a raster layer; a
 * [ReliefSource.Value] source is a Terrarium raster-dem coloured by its elevation ramp.
 *
 * @throws IllegalArgumentException if a value ramp is empty or its steps are not in strictly
 *   ascending order, which MapLibre needs for an interpolation.
 */
internal fun styleJson(files: MapFiles, spec: MapStyleSpec): String = JSONObject()
    .put("version", STYLE_VERSION)
    .put("sources", JSONObject().put(SOURCE_ID, source(files, spec.relief)))
    .put("layers", JSONArray().put(backgroundLayer(spec.colours)).put(reliefLayer(spec)))
    .toString()

private fun source(files: MapFiles, relief: ReliefSource): JSONObject = JSONObject()
    .put("type", if (relief is ReliefSource.Value) "raster-dem" else "raster")
    .put("url", "pmtiles://file://${files.relief.absolutePath}")
    .put("tileSize", RELIEF_TILE_SIZE)
    .put("maxzoom", relief.maxZoom)
    .apply { if (relief is ReliefSource.Value) put("encoding", "terrarium") }

private fun backgroundLayer(colours: MapColours): JSONObject = JSONObject()
    .put("id", "background")
    .put("type", "background")
    .put("paint", JSONObject().put("background-color", rgba(colours.land)))

private fun reliefLayer(spec: MapStyleSpec): JSONObject {
    val fade = fadeExpression(spec.reliefFade)
    val (type, paint) =
        when (val relief = spec.relief) {
            is ReliefSource.Painted -> RASTER_LAYER_TYPE to JSONObject().put("raster-opacity", fade)

            is ReliefSource.Value ->
                COLOR_RELIEF_LAYER_TYPE to
                    JSONObject()
                        .put("color-relief-color", rampExpression(relief.ramp))
                        .put("color-relief-opacity", fade)
        }
    return JSONObject()
        .put("id", RELIEF_LAYER_ID)
        .put("type", type)
        .put("source", SOURCE_ID)
        .put("paint", paint)
}

private fun fadeExpression(fade: ZoomRange): JSONArray = JSONArray()
    .put("interpolate")
    .put(JSONArray().put("linear"))
    .put(JSONArray().put("zoom"))
    .put(fade.start)
    .put(1)
    .put(fade.end)
    .put(0)

private fun rampExpression(ramp: List<RampStep>): JSONArray {
    require(ramp.isNotEmpty()) { "A value ramp needs at least one step." }
    require(ramp.zipWithNext().all { (low, high) -> low.value < high.value }) {
        "Value ramp steps must be in strictly ascending order: ${ramp.map { it.value }}"
    }
    val expression =
        JSONArray().put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("elevation"))
    ramp.forEach { expression.put(it.value).put(rgba(it.colour)) }
    return expression
}

/** Formats an ARGB [colour] as a CSS `rgba(...)` string, alpha to three decimals without trailing zeros. */
private fun rgba(colour: Int): String {
    val alpha = String.format(Locale.ROOT, ALPHA_DECIMALS, channel(colour, ALPHA_SHIFT) / MAX_CHANNEL)
        .trimEnd('0')
        .trimEnd('.')
    val red = channel(colour, RED_SHIFT)
    val green = channel(colour, GREEN_SHIFT)
    val blue = channel(colour, 0)
    return "rgba($red, $green, $blue, $alpha)"
}

private fun channel(colour: Int, shift: Int): Int = colour ushr shift and CHANNEL_MASK
