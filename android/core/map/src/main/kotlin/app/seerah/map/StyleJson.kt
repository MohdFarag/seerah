package app.seerah.map

import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private const val STYLE_VERSION = 8
private const val BASE_SOURCE_ID = "base"
private const val RELIEF_SOURCE_ID = "relief"
private const val BACKGROUND_LAYER_ID = "background"
private const val LAND_LAYER_ID = "land"
private const val COAST_LAYER_ID = "coast"
private const val RELIEF_LAYER_ID = "relief"
private const val VECTOR_SOURCE_TYPE = "vector"
private const val RASTER_SOURCE_TYPE = "raster"
private const val RASTER_DEM_SOURCE_TYPE = "raster-dem"
private const val BACKGROUND_LAYER_TYPE = "background"
private const val LAND_LAYER_TYPE = "fill"
private const val COAST_LAYER_TYPE = "line"
private const val RASTER_LAYER_TYPE = "raster"
private const val COLOR_RELIEF_LAYER_TYPE = "color-relief"
private const val LAND_SOURCE_LAYER = "land"
private const val COAST_SOURCE_LAYER = "coast"
private const val BACKGROUND_COLOR = "background-color"
private const val FILL_COLOR = "fill-color"
private const val FILL_ANTIALIAS = "fill-antialias"
private const val LINE_COLOR = "line-color"
private const val LINE_WIDTH = "line-width"
private const val RASTER_OPACITY = "raster-opacity"
private const val COLOR_RELIEF_COLOR = "color-relief-color"
private const val COLOR_RELIEF_OPACITY = "color-relief-opacity"
private const val PMTILES_URL_PREFIX = "pmtiles://file://"
private const val TERRARIUM_ENCODING = "terrarium"
private const val COAST_MIN_ZOOM = 3
private const val COAST_MIN_WIDTH = 0.4
private const val COAST_MAX_ZOOM = 10
private const val COAST_MAX_WIDTH = 1.2
private const val RELIEF_TILE_SIZE = 256
private const val CHANNEL_MASK = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BLUE_SHIFT = 0
private const val MAX_CHANNEL = 255.0
private const val ALPHA_DECIMALS = "%.3f"

/**
 * Builds the MapLibre style document for [spec] over the map files in [files].
 *
 * The style reads two PMTiles sources — the vector `base` archive and the `relief` archive — and
 * draws four layers in order: a sea-coloured `background`, the `land` fill and `coast` line
 * layers from `base`, and the `relief` layer on top. The relief layer fades out over
 * [MapStyleSpec.reliefFade]. A [ReliefSource.Painted] source is drawn as a raster layer; a
 * [ReliefSource.Value] source is a Terrarium raster-dem coloured by its elevation ramp.
 *
 * @throws IllegalArgumentException if a value ramp is empty or its steps are not in strictly
 *   ascending order, which MapLibre needs for an interpolation.
 */
internal fun styleJson(files: MapFiles, spec: MapStyleSpec): String = JSONObject()
    .put("version", STYLE_VERSION)
    .put(
        "sources",
        JSONObject()
            .put(BASE_SOURCE_ID, baseSource(files.base))
            .put(RELIEF_SOURCE_ID, reliefSource(files.relief, spec.relief)),
    )
    .put(
        "layers",
        JSONArray()
            .put(backgroundLayer(spec.colours))
            .put(landLayer(spec.colours))
            .put(coastLayer(spec.colours))
            .put(reliefLayer(spec)),
    )
    .toString()

private fun baseSource(base: File): JSONObject = JSONObject()
    .put("type", VECTOR_SOURCE_TYPE)
    .put("url", pmtilesUrl(base))

private fun reliefSource(relief: File, spec: ReliefSource): JSONObject = JSONObject()
    .put("type", if (spec is ReliefSource.Value) RASTER_DEM_SOURCE_TYPE else RASTER_SOURCE_TYPE)
    .put("url", pmtilesUrl(relief))
    .put("tileSize", RELIEF_TILE_SIZE)
    .put("maxzoom", spec.maxZoom)
    .apply { if (spec is ReliefSource.Value) put("encoding", TERRARIUM_ENCODING) }

private fun pmtilesUrl(file: File): String = "$PMTILES_URL_PREFIX${file.absolutePath}"

private fun backgroundLayer(colours: MapColours): JSONObject = JSONObject()
    .put("id", BACKGROUND_LAYER_ID)
    .put("type", BACKGROUND_LAYER_TYPE)
    .put("paint", JSONObject().put(BACKGROUND_COLOR, rgba(colours.sea)))

private fun landLayer(colours: MapColours): JSONObject = baseVectorLayer(
    id = LAND_LAYER_ID,
    type = LAND_LAYER_TYPE,
    sourceLayer = LAND_SOURCE_LAYER,
    paint = JSONObject().put(FILL_COLOR, rgba(colours.land)).put(FILL_ANTIALIAS, false),
)

private fun coastLayer(colours: MapColours): JSONObject = baseVectorLayer(
    id = COAST_LAYER_ID,
    type = COAST_LAYER_TYPE,
    sourceLayer = COAST_SOURCE_LAYER,
    paint = JSONObject().put(LINE_COLOR, rgba(colours.coast)).put(LINE_WIDTH, coastWidthExpression()),
)

private fun baseVectorLayer(id: String, type: String, sourceLayer: String, paint: JSONObject): JSONObject = JSONObject()
    .put("id", id)
    .put("type", type)
    .put("source", BASE_SOURCE_ID)
    .put("source-layer", sourceLayer)
    .put("paint", paint)

private fun reliefLayer(spec: MapStyleSpec): JSONObject {
    val fade = fadeExpression(spec.reliefFade)
    val (type, paint) =
        when (val relief = spec.relief) {
            is ReliefSource.Painted -> RASTER_LAYER_TYPE to JSONObject().put(RASTER_OPACITY, fade)

            is ReliefSource.Value ->
                COLOR_RELIEF_LAYER_TYPE to
                    JSONObject()
                        .put(COLOR_RELIEF_COLOR, rampExpression(relief.ramp))
                        .put(COLOR_RELIEF_OPACITY, fade)
        }
    return JSONObject()
        .put("id", RELIEF_LAYER_ID)
        .put("type", type)
        .put("source", RELIEF_SOURCE_ID)
        .put("paint", paint)
}

private fun linearInterpolation(input: String): JSONArray = JSONArray()
    .put("interpolate")
    .put(JSONArray().put("linear"))
    .put(JSONArray().put(input))

private fun fadeExpression(fade: ZoomRange): JSONArray = linearInterpolation("zoom")
    .put(fade.start)
    .put(1)
    .put(fade.end)
    .put(0)

private fun coastWidthExpression(): JSONArray = linearInterpolation("zoom")
    .put(COAST_MIN_ZOOM)
    .put(COAST_MIN_WIDTH)
    .put(COAST_MAX_ZOOM)
    .put(COAST_MAX_WIDTH)

private fun rampExpression(ramp: List<RampStep>): JSONArray {
    require(ramp.isNotEmpty()) { "A value ramp needs at least one step." }
    require(ramp.zipWithNext().all { (low, high) -> low.value < high.value }) {
        "Value ramp steps must be in strictly ascending order: ${ramp.map { it.value }}"
    }
    val expression = linearInterpolation("elevation")
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
    val blue = channel(colour, BLUE_SHIFT)
    return "rgba($red, $green, $blue, $alpha)"
}

private fun channel(colour: Int, shift: Int): Int = colour ushr shift and CHANNEL_MASK
