package org.seerah.spike.app

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import java.io.File

/** Map render-frame timings from MapLibre plus process memory. Spike instrumentation only. */
class Metrics {
    companion object { val shared = Metrics() }

    private val renderMs = ArrayDeque<Double>()
    private val frameTimes = ArrayDeque<Long>()

    @Synchronized
    fun onMapFrame(renderingTimeMs: Double) {
        val now = System.nanoTime()
        frameTimes.addLast(now)
        while (frameTimes.isNotEmpty() && now - frameTimes.first() > 1_000_000_000L) frameTimes.removeFirst()
        renderMs.addLast(renderingTimeMs)
        while (renderMs.size > 300) renderMs.removeFirst()
    }

    @Synchronized
    fun snapshot(context: Context, packDir: File?): Snapshot {
        val sorted = renderMs.sorted()
        fun pct(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()]
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pssKb = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss
        val apk = File(context.applicationInfo.sourceDir).length()
        val packBytes = packDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
        return Snapshot(
            mapFps = frameTimes.size,
            renderP50 = pct(0.5), renderP95 = pct(0.95), frames = sorted.size,
            pssMb = pssKb / 1024.0, nativeHeapMb = Debug.getNativeHeapAllocatedSize() / 1_048_576.0,
            apkMb = apk / 1_048_576.0, packMb = packBytes / 1_048_576.0,
        )
    }

    data class Snapshot(
        val mapFps: Int, val renderP50: Double, val renderP95: Double, val frames: Int,
        val pssMb: Double, val nativeHeapMb: Double, val apkMb: Double, val packMb: Double,
    ) {
        fun line() = "fps=%d render p50=%.1fms p95=%.1fms (n=%d) pss=%.0fMB native=%.0fMB apk=%.1fMB pack=%.2fMB"
            .format(mapFps, renderP50, renderP95, frames, pssMb, nativeHeapMb, apkMb, packMb)
    }
}
