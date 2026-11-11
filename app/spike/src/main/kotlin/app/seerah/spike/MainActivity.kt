package app.seerah.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    var labelTest by rememberSaveable { mutableStateOf(true) }
    var status by remember { mutableStateOf("جارٍ التحميل") }

    Box(Modifier.fillMaxSize()) {
        SpikeMap(
            styleJson = MapStyle.json(textPath, font, labelTest),
            systemLabelFont = font.takeIf { textPath == TextPath.SystemImages },
            onStatus = { status = it },
        )
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

/** Bounds of the sample data: Makkah up to the last extracted waypoint, with room for the label column. */
private val sampleBounds: LatLngBounds = LatLngBounds.Builder()
    .include(LatLng(21.25, 37.7))
    .include(LatLng(22.95, 39.95))
    .build()

@Composable
private fun SpikeMap(styleJson: String, systemLabelFont: MapFont?, onStatus: (String) -> Unit) {
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
            loaded.uiSettings.isAttributionEnabled = false
            loaded.moveCamera(CameraUpdateFactory.newLatLngBounds(sampleBounds, 48))
            map = loaded
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    DisposableEffect(map, styleJson, systemLabelFont) {
        val builder = Style.Builder().fromJson(styleJson)
        map?.setStyle(builder) { style ->
            systemLabelFont?.let { LabelImages.addTo(style, context, it) }
            onStatus("تم تحميل النمط")
        }
        onDispose { }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}
