package org.seerah.spike.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import java.io.File

/** Helpers that read MapLibre Native's real rendered state. */
object Device {
    const val TAG = "SeerahDeviceTest"
    private val instr get() = InstrumentationRegistry.getInstrumentation()

    fun <T> main(block: () -> T): T {
        var r: Result<T>? = null
        instr.runOnMainSync { r = runCatching(block) }
        return r!!.getOrThrow()
    }

    fun waitFor(what: String, timeoutMs: Long = 60_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (main { cond() }) return
            SystemClock.sleep(200)
        }
        error("timeout waiting for $what")
    }

    fun vm(s: ActivityScenario<MainActivity>): SpikeViewModel {
        var vm: SpikeViewModel? = null
        s.onActivity { vm = ViewModelProvider(it)[SpikeViewModel::class.java] }
        return vm!!
    }

    fun waitStyle(prefix: String? = null) = waitFor("style ${prefix ?: ""}") {
        MapProbe.map != null && MapProbe.styleReady?.let { prefix == null || it.startsWith(prefix) } == true
    }

    /** Let the renderer finish tiles and label placement. */
    fun settle(ms: Long = 2_500) { instr.waitForIdleSync(); SystemClock.sleep(ms) }

    fun camera(lat: Double, lon: Double, zoom: Double) {
        main { MapProbe.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom)) }
        settle()
    }

    fun target(): Triple<Double, Double, Double> = main {
        val c = MapProbe.map!!.cameraPosition
        Triple(c.target!!.latitude, c.target!!.longitude, c.zoom)
    }

    /** Property values of features MapLibre actually rendered in the given layers (whole viewport). */
    fun rendered(prop: String, vararg layers: String): Set<String> = main {
        val m = MapProbe.map!!
        m.queryRenderedFeatures(RectF(0f, 0f, m.width.toFloat(), m.height.toFloat()), *layers).mapNotNull {
            if (it.hasProperty(prop)) it.getStringProperty(prop) else null
        }.toSet().also { Log.i(TAG, "rendered $prop in ${layers.toList()}: $it") }
    }

    /** States of one entity rendered in a layer (e.g. which border shape is on screen). */
    fun renderedStates(entity: String, layer: String): Set<String> = main {
        val m = MapProbe.map!!
        m.queryRenderedFeatures(RectF(0f, 0f, m.width.toFloat(), m.height.toFloat()), layer)
            .filter { it.hasProperty("entity") && it.getStringProperty("entity") == entity }
            .map { it.getStringProperty("state") }.toSet()
    }

    fun renderedCount(vararg layers: String): Int = main {
        val m = MapProbe.map!!
        m.queryRenderedFeatures(RectF(0f, 0f, m.width.toFloat(), m.height.toFloat()), *layers).size
    }

    fun screenshot(name: String) {
        settle(1_000)
        val bmp: Bitmap = instr.uiAutomation.takeScreenshot()
        val dir = File(instr.targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name")
    }
}
