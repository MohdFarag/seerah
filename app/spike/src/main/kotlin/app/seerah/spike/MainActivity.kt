package app.seerah.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapLibre.getInstance(this)
        // Everything is bundled. Tell the engine never to try the network.
        MapLibre.setConnected(false)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SpikeScreen()
                }
            }
        }
    }
}

@Composable
private fun SpikeScreen() {
    var textPath by rememberSaveable { mutableStateOf(TextPath.GlyphPbf) }
    var font by rememberSaveable { mutableStateOf(MapFont.Plex) }
    var labelTest by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("جارٍ التحميل") }
    val context = LocalContext.current
    // null while the base map is being copied to storage on first launch.
    val base by produceState<BaseState?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { BaseState(BaseMap.prepare(context)) }
    }

    Box(Modifier.fillMaxSize()) {
        var metresPerPixel by remember { mutableStateOf(0.0) }
        val eras = remember { Era.load(context) }
        var map by remember { mutableStateOf<MapLibreMap?>(null) }
        var yearIndex by rememberSaveable { mutableStateOf(Era.years.indexOf(1)) }
        val year = Era.years[yearIndex]
        base?.let { ready ->
            SpikeMap(
                styleJson = MapStyle.json(textPath, font, labelTest, ready.dir, eras),
                images = remember(font, textPath) {
                    LabelImages.build(context, font, textPath == TextPath.SystemImages)
                },
                eras = eras,
                onStatus = { status = if (ready.dir == null) "$it · بلا أساس" else it },
                onScale = { metresPerPixel = it },
                onMap = { map = it },
                year = year,
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth(),
        ) {
            ScaleBar(
                metresPerPixel = metresPerPixel,
                modifier = Modifier
                    .align(AbsoluteAlignment.Left)
                    .padding(start = 8.dp, bottom = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                SpikeEvent.entries.forEach { event ->
                    AssistChip(
                        onClick = {
                            yearIndex = Era.years.indexOf(event.year)
                            map?.animateCamera(event.camera(), 1800)
                        },
                        label = { Text(event.title) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            }
            YearBar(yearIndex = yearIndex, onYearIndex = { yearIndex = it })
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(12.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = "${BuildConfig.FLAVOR} · MapLibre ${BuildConfig.MAPLIBRE_VERSION} · $status",
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextPath.entries.forEach { option ->
                        FilterChip(
                            selected = textPath == option,
                            onClick = { textPath = option },
                            label = { Text(option.label) },
                        )
                    }
                    MapFont.entries.forEach { option ->
                        FilterChip(
                            selected = font == option,
                            onClick = { font = option },
                            label = { Text(option.label) },
                        )
                    }
                    FilterChip(
                        selected = labelTest,
                        onClick = { labelTest = !labelTest },
                        label = { Text("جمل الاختبار") },
                    )
                }
            }
        }
    }
}

/** Result of preparing the base map. [dir] is null when it was not bundled. */
private class BaseState(val dir: String?)

/** Bounds of the sample data: Makkah to Quba, with room for the label column. */
private val sampleBounds: LatLngBounds = LatLngBounds.Builder()
    .include(LatLng(21.25, 37.7))
    .include(LatLng(24.55, 39.95))
    .build()

@Composable
private fun SpikeMap(
    styleJson: String,
    images: Map<String, android.graphics.Bitmap>,
    eras: List<EraFeature>,
    onStatus: (String) -> Unit,
    onScale: (Double) -> Unit,
    onMap: (MapLibreMap) -> Unit,
    year: Int,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.addOnDidFailLoadingMapListener { message -> onStatus("خطأ: $message") }
        mapView.getMapAsync { loaded ->
            loaded.uiSettings.isLogoEnabled = false
            // The relief fades out from zoom 12.5; past that the map is paper, names and route.
            loaded.setMaxZoomPreference(16.0)
            loaded.uiSettings.isAttributionEnabled = false
            loaded.moveCamera(CameraUpdateFactory.newLatLngBounds(sampleBounds, 48))
            val report = { onScale(metresPerPixel(loaded, mapView.width, mapView.height)) }
            loaded.addOnCameraMoveListener { report() }
            loaded.addOnCameraIdleListener { report() }
            map = loaded
            onMap(loaded)
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    DisposableEffect(map, styleJson, images) {
        val builder = Style.Builder().fromJson(styleJson)
        images.forEach { (id, bitmap) -> builder.withImage(id, bitmap) }
        map?.setStyle(builder) { style ->
            Era.apply(style, eras, year, animate = false)
            onStatus("تم تحميل النمط")
        }
        onDispose { }
    }

    // Changing the year never reloads the style; the layers that change cross-fade.
    LaunchedEffect(year) { map?.style?.takeIf { it.isFullyLoaded }?.let { Era.apply(it, eras, year, animate = true) } }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun YearBar(yearIndex: Int, onYearIndex: (Int) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("نهاية ${Era.label(Era.years[yearIndex])}", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = yearIndex.toFloat(),
                onValueChange = { onYearIndex(it.roundToInt()) },
                valueRange = 0f..(Era.years.size - 1).toFloat(),
                steps = Era.years.size - 2,
            )
        }
    }
}
