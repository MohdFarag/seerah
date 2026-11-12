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

    private var last = 0L
    private val intervals = ArrayDeque<Long>()
    private var fps = 0
    private var minFps: Int? = null
    private var firstMapMs: Long? = null
    private var lastPublish = 0L

    init {
        mapView.addOnDidFinishRenderingFrameListener { _, _, _ -> frame() }
        mapView.addOnDidFinishRenderingMapListener { fully ->
            if (fully && firstMapMs == null) {
                firstMapMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                publish()
            }
        }
    }

    /**
     * Rate from the gaps between consecutive frames while the map keeps drawing.
     * A gap over [IDLE_MS] means the map stopped moving, so it is not counted;
     * the earlier version counted those idle gaps and its "lowest" read 1.
     */
    private fun frame() {
        val now = SystemClock.elapsedRealtime()
        val gap = now - last
        last = now
        if (gap in 1..IDLE_MS) {
            intervals.addLast(gap)
            while (intervals.size > WINDOW) intervals.removeFirst()
        }
        if (intervals.size >= WINDOW && now - lastPublish >= 1000) {
            fps = (1000 * intervals.size / intervals.sum()).toInt()
            minFps = minOf(minFps ?: fps, fps)
            lastPublish = now
            publish()
        }
    }

    fun reset() {
        minFps = null
        publish()
    }

    private fun publish() = onChange(Snapshot(fps, minFps, Debug.getPss().toInt() / 1024, firstMapMs))

    companion object {
        private const val IDLE_MS = 250L
        private const val WINDOW = 30

        val device: String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
    }
}
