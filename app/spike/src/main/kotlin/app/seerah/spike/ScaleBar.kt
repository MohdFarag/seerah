package app.seerah.spike

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Ground distance, in metres, spanned by one screen pixel at the centre of the map. */
fun metresPerPixel(map: MapLibreMap, width: Int, height: Int): Double {
    val y = height / 2f
    val a = map.projection.fromScreenLocation(android.graphics.PointF(width / 2f - 50f, y))
    val b = map.projection.fromScreenLocation(android.graphics.PointF(width / 2f + 50f, y))
    return a.distanceTo(b) / 100.0
}

private val arabicDigits = "٠١٢٣٤٥٦٧٨٩"

private fun arabic(n: Int) = n.toString().map { arabicDigits[it - '0'] }.joinToString("")

/** A scale bar no wider than [maxWidthDp], snapped to 1, 2 or 5 times a power of ten. */
@Composable
fun ScaleBar(metresPerPixel: Double, modifier: Modifier = Modifier, maxWidthDp: Int = 96) {
    if (metresPerPixel <= 0.0 || metresPerPixel.isNaN()) return
    val density = LocalDensity.current.density
    val maxMetres = metresPerPixel * maxWidthDp * density
    val power = 10.0.pow(floor(log10(maxMetres)))
    val step = listOf(5, 2, 1).first { it * power <= maxMetres }
    val metres = step * power
    val widthDp = (metres / metresPerPixel / density).toFloat()
    val label = if (metres >= 1000) "${arabic((metres / 1000).toInt())} كم" else "${arabic(metres.toInt())} م"
    val ink = Color(0xFF203D32)
    Column(modifier) {
        Canvas(Modifier.width(widthDp.dp).height(10.dp)) {
            val stroke = 2.dp.toPx()
            val y = size.height / 2
            drawLine(ink, Offset(0f, y), Offset(size.width, y), stroke)
            drawLine(ink, Offset(stroke / 2, 0f), Offset(stroke / 2, size.height), stroke)
            drawLine(ink, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), stroke)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = ink)
    }
}
