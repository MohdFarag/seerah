package app.seerah.spike

import android.animation.ValueAnimator
import android.content.Context
import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import androidx.core.animation.doOnEnd
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/** A feature that exists only for some years. `from <= year < to`, Hijri, pre-Hijra negative. */
data class EraFeature(val id: String, val kind: String, val from: Int, val to: Int) {
    fun visibleIn(year: Int) = year in from until to

    /** Every style layer that draws this feature. */
    val layers: List<String>
        get() = when (kind) {
            "region" -> listOf("era-$id-fill", "era-$id-line")
            "route" -> listOf("era-$id")
            else -> listOf("era-$id")
        }
}

/**
 * Time states. The year means the state at the end of that year; nothing is
 * interpolated between shapes. Each change cross-fades over [FADE_MS] so the
 * eye can follow what appeared and what went.
 */
object Era {
    private const val FADE_MS = 700L

    /** Years on the bar: 13 ق.هـ to 11 هـ. There is no year 0. */
    val years: List<Int> = (-13..-1) + (1..11)

    private val digits = "٠١٢٣٤٥٦٧٨٩"
    private fun arabic(n: Int) = n.toString().map { digits[it - '0'] }.joinToString("")

    fun label(year: Int): String = if (year < 0) "${arabic(-year)} ق.هـ" else "${arabic(year)} هـ"

    fun load(context: Context): List<EraFeature> = buildList {
        for (file in listOf("time-states.geojson", "hijra-route.geojson")) {
            val json = context.assets.open(file).bufferedReader().use { it.readText() }
            val features = JSONObject(json).getJSONArray("features")
            for (i in 0 until features.length()) {
                val f = features.getJSONObject(i)
                val p = f.getJSONObject("properties")
                if (!p.has("from")) continue
                add(EraFeature(f.getString("id"), p.optString("kind", "route"), p.getInt("from"), p.getInt("to")))
            }
        }
    }

    private val running = mutableListOf<ValueAnimator>()

    fun apply(style: Style, features: List<EraFeature>, year: Int, animate: Boolean) {
        running.forEach { it.cancel() }
        running.clear()
        for (feature in features) {
            val target = if (feature.visibleIn(year)) 1f else 0f
            for (layerId in feature.layers) {
                val layer = style.getLayer(layerId) ?: continue
                val from = current(layer)
                // A hidden layer still takes part in label collisions even at zero
                // opacity, so it is switched off once faded out (يثرب sat under an
                // invisible المدينة at the same point and never showed).
                if (target > 0f) layer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
                if (!animate || from == target) {
                    set(layer, target)
                    if (target == 0f) layer.setProperties(PropertyFactory.visibility(Property.NONE))
                    continue
                }
                running += ValueAnimator.ofFloat(from, target).apply {
                    duration = FADE_MS
                    addUpdateListener { set(layer, it.animatedValue as Float) }
                    doOnEnd { if (target == 0f) layer.setProperties(PropertyFactory.visibility(Property.NONE)) }
                    start()
                }
            }
        }
    }

    private fun current(layer: org.maplibre.android.style.layers.Layer): Float = when (layer) {
        is FillLayer -> layer.fillOpacity.value?.div(0.10f)
        is LineLayer -> layer.lineOpacity.value
        is SymbolLayer -> layer.textOpacity.value
        else -> null
    } ?: 0f

    private fun set(layer: org.maplibre.android.style.layers.Layer, v: Float) {
        when (layer) {
            is FillLayer -> layer.setProperties(PropertyFactory.fillOpacity(v * 0.10f))
            is LineLayer -> layer.setProperties(PropertyFactory.lineOpacity(v))
            is SymbolLayer -> layer.setProperties(PropertyFactory.textOpacity(v), PropertyFactory.iconOpacity(v))
        }
    }
}
