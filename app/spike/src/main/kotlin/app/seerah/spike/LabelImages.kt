package app.seerah.spike

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import kotlin.math.ceil

/**
 * Images the style needs: the marker dots, and, in the system-text mode, every
 * label drawn by Android's text engine (joining, diacritics and direction come
 * from the system). A marker and its name are one image there, so they always
 * appear and hide together.
 */
object LabelImages {
    const val LABEL = "label:"
    const val STATION = "station:"
    const val LANDMARK = "landmark:"
    const val DOT_STATION = "dot-station"
    const val DOT_LANDMARK = "dot-landmark"
    const val CITY = "city:"
    const val DOT_CITY = "dot-city"
    const val TEXT_SP = 13f
    const val CITY_SP = 17f
    const val TEST_SP = 20f
    private const val DOT_DP = 3.5f
    private const val CITY_DOT_DP = 4.5f

    private val stationColor = Color.parseColor("#B7963A")
    private val landmarkColor = Color.parseColor(MapStyle.LABEL)

    fun build(context: Context, font: MapFont, systemText: Boolean): Map<String, Bitmap> {
        val d = context.resources.displayMetrics.density
        val images = mutableMapOf(
            DOT_STATION to dot(stationColor, d),
            DOT_LANDMARK to dot(landmarkColor, d),
            DOT_CITY to dot(landmarkColor, d, CITY_DOT_DP),
        )
        if (!systemText) return images
        val typeface = Typeface.createFromAsset(context.assets, "fonts/${font.ttf}")
        val sp = context.resources.displayMetrics.scaledDensity
        val halo = 1.5f * d
        names(context, "hijra-waypoints.geojson").forEach {
            images[STATION + it] = withDot(render(it, typeface, TEXT_SP * sp, halo), dot(stationColor, d), d)
        }
        names(context, "time-states.geojson", "landmark").forEach {
            images[LANDMARK + it] = withDot(render(it, typeface, TEXT_SP * sp, halo), dot(landmarkColor, d), d)
        }
        names(context, "time-states.geojson", "place").forEach {
            images[CITY + it] = withDot(render(it, typeface, CITY_SP * sp, halo * 1.4f), dot(landmarkColor, d, CITY_DOT_DP), d)
        }
        names(context, "time-states.geojson", "region-label").forEach {
            images[LABEL + it] = render(it, typeface, TEXT_SP * sp, halo, Color.parseColor(MapStyle.ROUTE))
        }
        names(context, "label-test.geojson").forEach {
            images[LABEL + it] = render(it, typeface, TEST_SP * sp, halo)
        }
        return images
    }

    private fun names(context: Context, file: String, kind: String? = null): Set<String> = buildSet {
        val json = context.assets.open(file).bufferedReader().use { it.readText() }
        val features = JSONObject(json).getJSONArray("features")
        for (i in 0 until features.length()) {
            val p = features.getJSONObject(i).getJSONObject("properties")
            if (kind != null && p.optString("kind") != kind) continue
            p.optString("name").takeIf { it.isNotEmpty() }?.let(::add)
        }
    }

    private fun dot(color: Int, d: Float, radiusDp: Float = DOT_DP): Bitmap {
        val r = radiusDp * d
        val size = ceil(2 * r).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor(MapStyle.HALO)
        c.drawCircle(size / 2f, size / 2f, r, p)
        p.color = color
        c.drawCircle(size / 2f, size / 2f, r - 1.2f * d, p)
        return b
    }

    /**
     * The dot on the left with the name just to its right, as on Google Maps. The
     * image's left edge is the dot's left edge, so the style anchors it there and
     * shifts it by the dot's radius to put the dot's centre on the point.
     */
    private fun withDot(label: Bitmap, dot: Bitmap, d: Float): Bitmap {
        val gap = (2 * d).toInt()
        val h = maxOf(label.height, dot.height)
        val b = Bitmap.createBitmap(dot.width + gap + label.width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawBitmap(dot, 0f, (h - dot.height) / 2f, null)
        c.drawBitmap(label, (dot.width + gap).toFloat(), (h - label.height) / 2f, null)
        return b
    }

    fun dotRadiusDp(prefix: String) = if (prefix == CITY) CITY_DOT_DP else DOT_DP

    private fun render(
        text: String,
        typeface: Typeface,
        textPx: Float,
        haloPx: Float,
        color: Int = Color.parseColor(MapStyle.LABEL),
    ): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = textPx
        }
        val pad = ceil(haloPx * 1.5f).toInt()
        val width = ceil(paint.measureText(text)).toInt() + 2 * pad
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        val bitmap = Bitmap.createBitmap(width, layout.height + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(0f, pad.toFloat())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = haloPx * 2
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.parseColor(MapStyle.HALO)
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = color
        layout.draw(canvas)
        return bitmap
    }
}
