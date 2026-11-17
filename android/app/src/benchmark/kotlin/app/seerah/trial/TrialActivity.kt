package app.seerah.trial

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import app.seerah.map.MapCamera
import app.seerah.map.MapEvent
import app.seerah.map.MapFiles
import app.seerah.map.SeerahMap
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TRIAL_FOLDER = "trial"
private const val PREFERENCES = "map_trial"
private const val LAST_VIEW = "last_view"
private const val KB_PER_MB = 1_024
private val SPACING = 8.dp

/**
 * The map trial's screen, in the benchmark build type only: opens a view of an imported variant,
 * shows the measurement overlay and runs the camera path [RUNS] times.
 *
 * Launch it with [EXTRA_VIEW] set to `<folder>/<view id>` to open that view directly.
 */
class TrialActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = getExternalFilesDir(null)?.let { File(it, TRIAL_FOLDER) }
        val requested = intent.getStringExtra(EXTRA_VIEW)
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    TrialScreen(root, requested, preferences, Modifier.safeDrawingPadding())
                }
            }
        }
    }

    companion object {
        /** The intent extra naming the view to open, as `<folder>/<view id>`. */
        const val EXTRA_VIEW = "app.seerah.trial.VIEW"
    }
}

/** Open time, measured once per process at the first fully rendered map, as the spec defines it. */
private object OpenTime {
    var millis: Long? = null
        private set

    fun recordOnce() {
        if (millis == null) millis = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
    }
}

@Composable
private fun TrialScreen(
    root: File?,
    requested: String?,
    preferences: SharedPreferences,
    modifier: Modifier = Modifier,
) {
    var loads by remember { mutableStateOf<List<VariantLoad>?>(null) }
    var selection by remember { mutableStateOf<TrialSelection?>(null) }
    LaunchedEffect(root) {
        val (loaded, lastUsed) = withContext(Dispatchers.IO) {
            root?.let(::loadVariants).orEmpty() to preferences.getString(LAST_VIEW, null)
        }
        selection = startSelection(requested, lastUsed, loaded.variants())
        loads = loaded
    }
    val current = selection
    when {
        loads == null -> Text("Loading the trial files…", modifier.padding(SPACING))

        loads.orEmpty().isEmpty() -> NoFiles(root, modifier)

        current == null -> Picker(
            loads = loads.orEmpty(),
            onPick = { picked ->
                preferences.edit { putString(LAST_VIEW, picked.key) }
                selection = picked
            },
            modifier = modifier,
        )

        else -> TrialMap(current, onViews = { selection = null }, modifier = modifier)
    }
}

private fun List<VariantLoad>.variants(): List<TrialVariant> = filterIsInstance<VariantLoad.Loaded>().map { it.variant }

@Composable
private fun NoFiles(root: File?, modifier: Modifier = Modifier) {
    val message = if (root == null) {
        "External storage is not available, so the trial files cannot be read."
    } else {
        "No trial files. Push a variant folder with:\nadb push <folder> ${root.absolutePath}/"
    }
    Text(message, modifier.padding(SPACING))
}

@Composable
private fun Picker(loads: List<VariantLoad>, onPick: (TrialSelection) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(SPACING),
        verticalArrangement = Arrangement.spacedBy(SPACING),
    ) {
        loads.forEach { load ->
            when (load) {
                is VariantLoad.Loaded -> viewsFor(load.variant.variant).forEach { view ->
                    val choice = TrialSelection(load.variant, view)
                    Button(onClick = { onPick(choice) }) { Text(choice.key) }
                }

                is VariantLoad.Skipped -> Text("Skipped ${load.folder.name}: ${load.reason}")
            }
        }
    }
}

/** The recording of the camera path's current run; `null` readings means no run is recording. */
private class RunRecorder {
    var readings: MutableList<Double>? = null
}

/**
 * The map with the overlay and the camera path's controls.
 *
 * Measurement cost: the overlay's state is read only inside [OverlayPanel], so a published reading
 * recomposes the overlay alone, and the memory (PSS) is read off the main thread, so neither sits on
 * the frame path being measured. The screen does not lock its orientation: rotating it during a run
 * recreates the activity and loses that run's results.
 */
@Composable
private fun TrialMap(selection: TrialSelection, onViews: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats = remember(selection) { FrameStats() }
    val recorder = remember(selection) { RunRecorder() }
    var camera by remember(selection) { mutableStateOf<MapCamera?>(null) }
    var running by remember(selection) { mutableStateOf(false) }
    var stopped by remember(selection) { mutableStateOf(false) }
    var hasRuns by remember(selection) { mutableStateOf(false) }
    val pssReader = remember(selection) { PssReader() }
    val overlay = remember(selection) {
        mutableStateOf(
            OverlayState(
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                view = selection.key,
                fingerprint8 = selection.variant.fingerprint8,
                fps = null,
                lowestFps = null,
                pssMb = null,
                openTimeMs = OpenTime.millis,
                runs = emptyList(),
            ),
        )
    }

    fun update(change: (OverlayState) -> OverlayState) {
        overlay.value = change(overlay.value)
    }

    fun runPath(path: MapCamera) = scope.launch {
        running = true
        stopped = false
        update { it.copy(runs = emptyList()) }
        hasRuns = false
        try {
            repeat(RUNS) {
                stats.reset()
                val readings = mutableListOf<Double>()
                recorder.readings = readings
                animateThrough(path, selection.variant.cameraPath.stops)
                update { it.copy(runs = it.runs + RunResult(readings.toList())) }
                hasRuns = true
            }
        } catch (cancelled: CancellationException) {
            // A touch on the map stops the path; only a cancelled screen ends this coroutine.
            ensureActive()
            stopped = true
        } finally {
            recorder.readings = null
            running = false
        }
    }

    Box(modifier.fillMaxSize()) {
        SeerahMap(
            files = MapFiles(selection.variant.relief),
            style = selection.view.styleSpec(),
            limits = selection.variant.cameraPath.limits,
            modifier = Modifier.fillMaxSize(),
            onCamera = { camera = it },
            onEvent = { event ->
                when (event) {
                    MapEvent.FullyRendered -> if (overlay.value.openTimeMs == null) {
                        OpenTime.recordOnce()
                        update { it.copy(openTimeMs = OpenTime.millis) }
                    }

                    is MapEvent.FrameRendered -> stats.onFrame(event.atMillis)?.let { reading ->
                        recorder.readings?.add(reading.fps)
                        update { it.copy(fps = reading.fps, lowestFps = reading.lowestFps) }
                        pssReader.read(scope) { mb -> update { it.copy(pssMb = mb) } }
                    }
                }
            },
        )
        OverlayPanel({ overlay.value }, stopped, Modifier.align(Alignment.TopStart).padding(SPACING))
        Row(
            Modifier.align(Alignment.BottomCenter).padding(SPACING),
            horizontalArrangement = Arrangement.spacedBy(SPACING),
        ) {
            Button(onClick = onViews) { Text("Views") }
            Button(onClick = { camera?.let(::runPath) }, enabled = camera != null && !running) { Text("Run path") }
            Button(onClick = { copyResults(context, overlay.value) }, enabled = hasRuns) {
                Text("Copy results")
            }
        }
    }
}

/** Reads only the overlay's state, so a published reading recomposes nothing else. */
@Composable
private fun OverlayPanel(state: () -> OverlayState, stopped: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Overlay(state())
        if (stopped) Text("Path stopped", color = MaterialTheme.colorScheme.error)
    }
}

/**
 * Reads the memory (PSS) off the main thread: `Debug.getPss()` walks `/proc/self/smaps`, which can
 * take tens of milliseconds, so on the main thread it would drop the frames being measured. A read
 * is skipped while the previous one is still running.
 */
private class PssReader {
    private var reading = false

    fun read(scope: CoroutineScope, onMb: (Int) -> Unit) {
        if (reading) return
        reading = true
        scope.launch {
            try {
                val kb = withContext(Dispatchers.Default) { Debug.getPss() }
                onMb((kb / KB_PER_MB).toInt())
            } finally {
                reading = false
            }
        }
    }
}

private fun copyResults(context: Context, overlay: OverlayState) {
    val text = PathResults(overlay.runs).asText(overlay.header)
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Map trial", text))
}
