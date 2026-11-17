package app.seerah.map

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * The map: a MapLibre view that draws [files] with [style], keeps the camera inside [limits] and
 * follows the lifecycle of the screen that hosts it.
 *
 * A change of [files] or [style] reloads the style; a change of [limits] re-applies them.
 *
 * @param onCamera receives the map's [MapCamera] after every style load (the first and each
 *   reload). It is the same instance every time, so a caller may treat the call as idempotent.
 * @param onEvent receives the map's rendering events: [MapEvent.FullyRendered] when a render
 *   finishes with everything drawn, and [MapEvent.FrameRendered] after each frame.
 */
@Composable
fun SeerahMap(
    files: MapFiles,
    style: MapStyleSpec,
    limits: CameraLimits,
    modifier: Modifier = Modifier,
    onCamera: (MapCamera) -> Unit = {},
    onEvent: (MapEvent) -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnCamera by rememberUpdatedState(onCamera)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val mapView = remember { createMapView(context) { currentOnEvent(it) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    MapLifecycle(mapView)
    DisposableEffect(mapView) {
        mapView.getMapAsync { map = it }
        onDispose { map = null }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
    map?.let { MapContent(it, files, style, limits) { camera -> currentOnCamera(camera) } }
}

/** Applies [limits] to [map] and loads its style, handing out the camera after every load. */
@Composable
private fun MapContent(
    map: MapLibreMap,
    files: MapFiles,
    style: MapStyleSpec,
    limits: CameraLimits,
    onCamera: (MapCamera) -> Unit,
) {
    val camera = remember(map) { MapLibreCamera(map) }
    val currentOnCamera by rememberUpdatedState(onCamera)
    LaunchedEffect(map, limits) { map.applyLimits(limits) }
    LaunchedEffect(map, files, style) {
        map.setStyle(Style.Builder().fromJson(styleJson(files, style))) { currentOnCamera(camera) }
    }
}

/** Creates the map view once, with its rendering listeners reporting to [onEvent]. */
private fun createMapView(context: Context, onEvent: (MapEvent) -> Unit): MapView {
    MapLibre.getInstance(context)
    return MapView(context).apply {
        onCreate(null)
        addOnDidFinishRenderingMapListener { fully -> if (fully) onEvent(MapEvent.FullyRendered) }
        addOnDidFinishRenderingFrameListener { _, _, _ ->
            onEvent(MapEvent.FrameRendered(SystemClock.elapsedRealtime()))
        }
    }
}

/**
 * Forwards the host's lifecycle to [mapView], and on leaving the composition takes the view down
 * through the steps it has not yet passed, ending with `onDestroy`.
 */
@Composable
private fun MapLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var state = Lifecycle.State.CREATED
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
            if (event != Lifecycle.Event.ON_DESTROY && event != Lifecycle.Event.ON_ANY) {
                state = event.targetState
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
}

private fun MapLibreMap.applyLimits(limits: CameraLimits) {
    setLatLngBoundsForCameraTarget(LatLngBounds.from(limits.north, limits.east, limits.south, limits.west))
    setMinZoomPreference(limits.minZoom)
    setMaxZoomPreference(limits.maxZoom)
}

/** [MapCamera] over a MapLibre map: [animateTo] suspends until MapLibre's animation ends. */
private class MapLibreCamera(private val map: MapLibreMap) : MapCamera {
    override suspend fun animateTo(lat: Double, lon: Double, zoom: Double, durationMs: Long) {
        val target = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build()
        suspendCancellableCoroutine { continuation ->
            val callback = object : MapLibreMap.CancelableCallback {
                override fun onFinish() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onCancel() {
                    continuation.cancel(CancellationException("MapLibre cancelled the camera animation."))
                }
            }
            continuation.invokeOnCancellation { map.cancelTransitions() }
            map.animateCamera(CameraUpdateFactory.newCameraPosition(target), durationMs.toInt(), callback)
        }
    }
}
