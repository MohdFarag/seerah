package app.seerah.spike

import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import org.maplibre.android.maps.MapView

/**
 * Spike measurements, shown on screen so the owner can compare devices:
 * frames per second while the map moves, the slowest second seen, memory in
 * use, and the time from process start to the first fully drawn map.
 */
class Stats(mapView: MapView, private val onChange: (Snapshot) -> Unit) {
    data class Snapshot(val fps: Int, val minFps: Int?, val memoryMb: Int, val firstMapMs: Long?)

    private var frames = 0
    private var windowStart = SystemClock.elapsedRealtime()
    private var fps = 0
    private var minFps: Int? = null
    private var firstMapMs: Long? = null

    init {
        mapView.addOnDidFinishRenderingFrameListener { _, _, _ -> frame() }
        mapView.addOnDidFinishRenderingMapListener { fully ->
            if (fully && firstMapMs == null) {
                firstMapMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                publish()
            }
        }
    }

    private fun frame() {
        frames++
        val now = SystemClock.elapsedRealtime()
        val span = now - windowStart
        if (span >= 1000) {
            fps = (frames * 1000 / span).toInt()
            // Only seconds in which the map kept drawing count towards the minimum;
            // an idle map draws nothing and would read as 0.
            if (frames >= 5) minFps = minOf(minFps ?: fps, fps)
            frames = 0
            windowStart = now
            publish()
        }
    }

    fun reset() {
        minFps = null
        publish()
    }

    private fun publish() = onChange(Snapshot(fps, minFps, Debug.getPss().toInt() / 1024, firstMapMs))

    companion object {
        val device: String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
    }
}
