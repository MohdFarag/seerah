package app.seerah.map

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
    val mapView = remember { createMapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    MapViewOwnership(mapView, onMap = { map = it }, onEvent = { currentOnEvent(it) })
    MapLifecycle(mapView)

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
    DisposableEffect(map, files, style) {
        var active = true
        map.setStyle(Style.Builder().fromJson(styleJson(files, style))) { if (active) currentOnCamera(camera) }
        onDispose { active = false }
    }
}

/** Creates the map view once, after making sure MapLibre is initialised. */
private fun createMapView(context: Context): MapView {
    MapLibre.getInstance(context)
    return MapView(context).apply { onCreate(null) }
}

/**
 * Owns [mapView] for as long as it is composed: hands its map to [onMap], reports its rendering
 * events to [onEvent] and forwards low-memory warnings to it. On leaving the composition it removes
 * its listeners and destroys the view; no callback reaches the caller after that.
 *
 * Declared before [MapLifecycle] so that its disposal runs after the lifecycle has stepped the view
 * down (effects are disposed in reverse order).
 */
@Composable
private fun MapViewOwnership(mapView: MapView, onMap: (MapLibreMap?) -> Unit, onEvent: (MapEvent) -> Unit) {
    val context = LocalContext.current
    val currentOnMap by rememberUpdatedState(onMap)
    val currentOnEvent by rememberUpdatedState(onEvent)
    DisposableEffect(mapView) {
        var disposed = false
        val mapRendered = MapView.OnDidFinishRenderingMapListener { fully ->
            if (fully && !disposed) currentOnEvent(MapEvent.FullyRendered)
        }
        val frameRendered = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
            if (!disposed) currentOnEvent(MapEvent.FrameRendered(SystemClock.elapsedRealtime()))
        }
        val memory = LowMemoryCallbacks(mapView)
        mapView.addOnDidFinishRenderingMapListener(mapRendered)
        mapView.addOnDidFinishRenderingFrameListener(frameRendered)
        context.registerComponentCallbacks(memory)
        mapView.getMapAsync { if (!disposed) currentOnMap(it) }
        onDispose {
            disposed = true
            currentOnMap(null)
            context.unregisterComponentCallbacks(memory)
            mapView.removeOnDidFinishRenderingMapListener(mapRendered)
            mapView.removeOnDidFinishRenderingFrameListener(frameRendered)
            mapView.onDestroy()
        }
    }
}

/** Forwards the system's low-memory warning to [mapView]. */
private class LowMemoryCallbacks(private val mapView: MapView) : ComponentCallbacks2 {
    // Deprecated in ComponentCallbacks since API 34, but still called on older devices, which
    // are the ones that run out of memory first; MapView offers no trim-level equivalent.
    @Deprecated("Deprecated in ComponentCallbacks; kept for older devices.")
    override fun onLowMemory() = mapView.onLowMemory()

    override fun onTrimMemory(level: Int) = Unit

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}

/**
 * Forwards the host's lifecycle to [mapView]. When the host's lifecycle changes or the map leaves
 * the composition, it stops observing and steps the view down to created; a new lifecycle then
 * brings it back up to its own state. The view is destroyed by [MapViewOwnership], not here.
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
        }
    }
}

private fun MapLibreMap.applyLimits(limits: CameraLimits) {
    setLatLngBoundsForCameraTarget(LatLngBounds.from(limits.north, limits.east, limits.south, limits.west))
    setMinZoomPreference(limits.minZoom)
    setMaxZoomPreference(limits.maxZoom)
}

/**
 * [MapCamera] over a MapLibre map. [animateTo] runs on the main thread, whatever the caller's
 * dispatcher, and suspends until MapLibre's animation ends. A cancelled coroutine stops the
 * animation (posted to the main thread); an animation that MapLibre cancels, for example by a
 * gesture, ends the call with [CancellationException].
 */
private class MapLibreCamera(private val map: MapLibreMap) : MapCamera {
    private val mainThread = Handler(Looper.getMainLooper())

    override suspend fun animateTo(lat: Double, lon: Double, zoom: Double, durationMs: Long) {
        require(durationMs in 0..Int.MAX_VALUE) { "durationMs must be between 0 and ${Int.MAX_VALUE}: $durationMs" }
        val target = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build()
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : MapLibreMap.CancelableCallback {
                    override fun onFinish() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onCancel() {
                        continuation.cancel(CancellationException("MapLibre cancelled the camera animation."))
                    }
                }
                continuation.invokeOnCancellation { mainThread.post { map.cancelTransitions() } }
                map.animateCamera(CameraUpdateFactory.newCameraPosition(target), durationMs.toInt(), callback)
            }
        }
    }
}
