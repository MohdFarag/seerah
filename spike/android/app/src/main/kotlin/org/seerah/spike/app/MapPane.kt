package org.seerah.spike.app

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.seerah.spike.core.ContentPack
import org.seerah.spike.core.StyleBuilder
import org.seerah.spike.core.TimeStates
import java.io.File

private const val EVENTS_SOURCE = "events"
private const val EVENTS_LAYER = "event-markers"

/**
 * MapLibre map driven by app state: the app picks the time state, the engine draws it.
 * Everything is read from the local package directory; no network sources.
 */
@Composable
fun MapPane(
    packDir: File,
    pack: ContentPack,
    year: Int,
    cameraRequest: Pair<Int, org.seerah.spike.core.CameraSpec>?,
    metrics: Metrics,
    onCameraIdle: (lon: Double, lat: Double, zoom: Double) -> Unit,
    onEventClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var loadedDir by remember { mutableStateOf<File?>(null) }
    val timeLayers = remember(packDir) {
        TimeStates.timeLayers(Json.parseToJsonElement(File(packDir, "style.template.json").readText()) as JsonObject)
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        mapView.addOnDidFinishRenderingFrameListener(
            MapView.OnDidFinishRenderingFrameWithStatsListener { _, stats -> metrics.onMapFrame(stats.renderingTime) },
        )
        mapView.getMapAsync { m ->
            map = m
            m.addOnCameraIdleListener {
                val c = m.cameraPosition
                c.target?.let { onCameraIdle(it.longitude, it.latitude, c.zoom) }
            }
            m.addOnMapClickListener { latLng ->
                val pt = m.projection.toScreenLocation(latLng)
                val hit = m.queryRenderedFeatures(pt, EVENTS_LAYER).firstOrNull()?.getStringProperty("id")
                hit?.let(onEventClick)
                hit != null
            }
        }
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    // (Re)load the style when the active package changes (first run or after an update).
    LaunchedEffect(map, packDir) {
        val m = map ?: return@LaunchedEffect
        if (loadedDir == packDir) return@LaunchedEffect
        val t0 = System.nanoTime()
        m.setStyle(Style.Builder().fromJson(StyleBuilder.build(packDir))) { s ->
            s.addSource(GeoJsonSource(EVENTS_SOURCE, TimeStates.eventMarkers(pack).toString()))
            s.addLayer(
                CircleLayer(EVENTS_LAYER, EVENTS_SOURCE).withProperties(
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleColor("#b23a2b"),
                    PropertyFactory.circleStrokeColor("#ffffff"),
                    PropertyFactory.circleStrokeWidth(2f),
                ),
            )
            Log.i(SpikeViewModel.TAG, "style loaded in ${(System.nanoTime() - t0) / 1_000_000} ms from ${packDir.name}")
            style = s
            loadedDir = packDir
        }
    }

    // Content of the same package can change markers after an update.
    LaunchedEffect(style, pack) {
        (style?.getSource(EVENTS_SOURCE) as? GeoJsonSource)?.setGeoJson(TimeStates.eventMarkers(pack).toString())
    }

    // Time: swap the visible state of names, borders and landmarks; markers of this year only.
    LaunchedEffect(style, year) {
        val s = style ?: return@LaunchedEffect
        val t0 = System.nanoTime()
        timeLayers.forEach { (id, base) -> s.getLayer(id)?.setFilterCompat(TimeStates.filter(base, year).toString()) }
        s.getLayer(EVENTS_LAYER)?.setFilterCompat(TimeStates.markerFilter(year).toString())
        Log.i(SpikeViewModel.TAG, "year $year filters applied in ${(System.nanoTime() - t0) / 1000} µs")
    }

    LaunchedEffect(map, cameraRequest?.first) {
        val m = map ?: return@LaunchedEffect
        val c = cameraRequest?.second ?: return@LaunchedEffect
        m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.lat, c.lon), c.zoom), 600)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun Layer.setFilterCompat(json: String) {
    val e = Expression.Converter.convert(json)
    when (this) {
        is FillLayer -> setFilter(e)
        is LineLayer -> setFilter(e)
        is SymbolLayer -> setFilter(e)
        is CircleLayer -> setFilter(e)
    }
}
