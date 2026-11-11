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
import org.maplibre.android.maps.Style
import kotlin.math.ceil

/**
 * Draws each label with Android's text engine and registers it as a style image.
 *
 * The map then places the images as icons, so collision handling still works,
 * while joining, diacritics and direction are handled by the system.
 */
object LabelImages {
    const val PREFIX = "label:"
    const val WAYPOINT_SP = 15f
    const val TEST_SP = 20f

    private val sources = listOf("hijra-waypoints.geojson", "label-test.geojson")

    fun addTo(style: Style, context: Context, font: MapFont) {
        val metrics = context.resources.displayMetrics
        val typeface = Typeface.createFromAsset(context.assets, "fonts/${font.ttf}")
        // Rendered at the larger of the two sizes so the test labels stay sharp.
        val textPx = TEST_SP * metrics.scaledDensity
        val haloPx = 1.5f * metrics.density
        names(context).forEach { name ->
            val bitmap = render(name, typeface, textPx, haloPx)
            // Declares the bitmap as drawn at TEST_SP, so icon-size 1 shows WAYPOINT_SP.
            bitmap.density = (metrics.densityDpi * TEST_SP / WAYPOINT_SP).toInt()
            style.addImage(PREFIX + name, bitmap)
        }
    }

    private fun names(context: Context): Set<String> = buildSet {
        sources.forEach { file ->
            val json = context.assets.open(file).bufferedReader().use { it.readText() }
            val features = JSONObject(json).getJSONArray("features")
            for (i in 0 until features.length()) {
                val name = features.getJSONObject(i).getJSONObject("properties").optString("name")
                if (name.isNotEmpty()) add(name)
            }
        }
    }

    private fun render(text: String, typeface: Typeface, textPx: Float, haloPx: Float): Bitmap {
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
        paint.color = Color.parseColor(MapStyle.LABEL)
        layout.draw(canvas)
        return bitmap
    }
}
