package app.seerah.spike

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.gt
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.lte
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Time states. Years are Hijri; pre-Hijra years are negative (13 ق.هـ is -13).
 * A feature shows when `from <= year < to`. The year means the state at the end
 * of that year; nothing is interpolated between states.
 */
object Era {
    const val REGION_FILL = "era-region-fill"
    const val REGION_LINE = "era-region-line"
    const val REGION_LABEL = "era-region-label"
    const val PLACE = "era-place"
    const val LANDMARK = "era-landmark"
    const val LANDMARK_LABEL = "era-landmark-label"

    /** Years on the bar: 13 ق.هـ to 11 هـ. There is no year 0. */
    val years: List<Int> = (-13..-1) + (1..11)

    private val digits = "٠١٢٣٤٥٦٧٨٩"
    private fun arabic(n: Int) = n.toString().map { digits[it - '0'] }.joinToString("")

    fun label(year: Int): String = if (year < 0) "${arabic(-year)} ق.هـ" else "${arabic(year)} هـ"

    private fun filter(kind: String, year: Int): Expression = all(
        eq(get("kind"), literal(kind)),
        lte(get("from"), literal(year)),
        gt(get("to"), literal(year)),
    )

    fun apply(style: Style, year: Int) {
        (style.getLayer(REGION_FILL) as? FillLayer)?.setFilter(filter("region", year))
        (style.getLayer(REGION_LINE) as? LineLayer)?.setFilter(filter("region", year))
        (style.getLayer(REGION_LABEL) as? SymbolLayer)?.setFilter(filter("region-label", year))
        (style.getLayer(PLACE) as? SymbolLayer)?.setFilter(filter("place", year))
        (style.getLayer(LANDMARK) as? CircleLayer)?.setFilter(filter("landmark", year))
        (style.getLayer(LANDMARK_LABEL) as? SymbolLayer)?.setFilter(filter("landmark", year))
    }
}
