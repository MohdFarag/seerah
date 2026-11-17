package app.seerah.trial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale

private const val SCRIM_ALPHA = 0.7f
private val OVERLAY_PADDING = 8.dp

/**
 * What the measurement overlay shows. The screen replaces it only when [FrameStats] publishes a
 * reading (and when that reading's memory value arrives), when a run of the camera path starts or
 * ends, and at the first open time; never per frame, so the overlay does not lower the rate it
 * measures.
 *
 * @property device the manufacturer and model.
 * @property view the selection's `<folder>/<view id>` value.
 * @property fingerprint8 the variant's eight-character fingerprint.
 * @property fps the latest published frame rate, or null before the first.
 * @property lowestFps the lowest published rate since the last reset, or null before the first.
 * @property pssMb the memory (PSS) read with the latest reading, in MB.
 * @property openTimeMs the process's open time, or null until the first fully rendered map.
 * @property openTimeFromWarmProcess true when [openTimeMs] was measured before this screen opened.
 * @property runs the camera path's finished runs, cold run first.
 */
data class OverlayState(
    val device: String,
    val view: String,
    val fingerprint8: String,
    val fps: Double?,
    val lowestFps: Double?,
    val pssMb: Int?,
    val openTimeMs: Long?,
    val openTimeFromWarmProcess: Boolean,
    val runs: List<RunResult>,
) {
    /** The header [PathResults.asText] starts its report with. */
    val header: TrialHeader get() = TrialHeader(device, view, fingerprint8, openTimeMs, openTimeFromWarmProcess, pssMb)
}

/** The measurement overlay: the live readings, then the camera path's results once a run ends. */
@Composable
fun Overlay(state: OverlayState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .padding(OVERLAY_PADDING),
    ) {
        val style = MaterialTheme.typography.bodySmall
        Text("Now: ${state.fps.fpsText()} | lowest ${state.lowestFps.fpsText()}", color = Color.White, style = style)
        val report = if (state.runs.isEmpty()) {
            state.header.let { "${it.device} | ${it.view} | ${it.fingerprint8}" }
        } else {
            PathResults(state.runs).asText(state.header)
        }
        Text(report, color = Color.White, style = style)
        if (state.runs.isEmpty()) {
            Text(
                "Open time: ${state.openTimeMs.msText()}${state.header.warmSuffix} | Memory (PSS): ${state.pssMb.mbText()}",
                color = Color.White,
                style = style,
            )
        }
    }
}

private fun Double?.fpsText(): String = if (this == null) "-" else String.format(Locale.ROOT, "%.1f fps", this)

private fun Long?.msText(): String = if (this == null) "-" else "$this ms"

private fun Int?.mbText(): String = if (this == null) "-" else "$this MB"
