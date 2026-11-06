package org.seerah.spike.app

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

/** Scripted pan/zoom for ~20 s, then logs a METRICS line. Emulator numbers are not device numbers. */
@RunWith(AndroidJUnit4::class)
class PerfTest {
    @Test fun scriptedPanZoom() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Device.waitStyle()
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val stops = listOf(Triple(24.5, 39.6, 7.0), Triple(30.0, 35.0, 5.0), Triple(15.0, 45.0, 6.0), Triple(20.0, 40.0, 2.0), Triple(24.0, 39.0, 8.0))
            val end = SystemClock.uptimeMillis() + 20_000
            var i = 0
            while (SystemClock.uptimeMillis() < end) {
                val (lat, lon, z) = stops[i++ % stops.size]
                Device.main { MapProbe.map!!.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), z), 1_500) }
                SystemClock.sleep(1_700)
            }
            val line = Metrics.shared.snapshot(ctx, Device.main { Device.vm(scenario).ui.value.packDir }).line()
            Log.i(Device.TAG, "METRICS emulator $line")
        }
    }
}
