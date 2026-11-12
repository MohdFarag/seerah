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
    const val TEXT_SP = 15f
    const val CITY_SP = 19f
    const val TEST_SP = 20f
    private const val DOT_DP = 6f

    private val stationColor = Color.parseColor("#B7963A")
    private val landmarkColor = Color.parseColor(MapStyle.LABEL)

    fun build(context: Context, font: MapFont, systemText: Boolean): Map<String, Bitmap> {
        val d = context.resources.displayMetrics.density
        val images = mutableMapOf(
            DOT_STATION to dot(stationColor, d),
            DOT_LANDMARK to dot(landmarkColor, d),
        )
        if (!systemText) return images
        val typeface = Typeface.createFromAsset(context.assets, "fonts/${font.ttf}")
        val sp = context.resources.displayMetrics.scaledDensity
        val halo = 1.5f * d
        names(context, "hijra-waypoints.geojson").forEach {
            images[STATION + it] = withDot(render(it, typeface, TEXT_SP * sp, halo), stationColor, d)
        }
        names(context, "time-states.geojson", "landmark").forEach {
            images[LANDMARK + it] = withDot(render(it, typeface, TEXT_SP * sp, halo), landmarkColor, d)
        }
        names(context, "time-states.geojson", "place").forEach {
            images[LABEL + it] = render(it, typeface, CITY_SP * sp, halo * 1.4f)
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

    private fun dot(color: Int, d: Float): Bitmap {
        val r = DOT_DP * d
        val size = ceil(2 * r).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor(MapStyle.HALO)
        c.drawCircle(size / 2f, size / 2f, r, p)
        p.color = color
        c.drawCircle(size / 2f, size / 2f, r - 2 * d, p)
        return b
    }

    /** The dot centred above the label; the image's top edge is the dot's top. */
    private fun withDot(label: Bitmap, color: Int, d: Float): Bitmap {
        val dot = dot(color, d)
        val gap = (2 * d).toInt()
        val w = maxOf(label.width, dot.width)
        val b = Bitmap.createBitmap(w, dot.height + gap + label.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawBitmap(dot, (w - dot.width) / 2f, 0f, null)
        c.drawBitmap(label, (w - label.width) / 2f, (dot.height + gap).toFloat(), null)
        return b
    }

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
        val pad = ceil(haloPx * 2).toInt()
        val width = ceil(paint.measureText(text)).toInt() + 2 * pad
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
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
