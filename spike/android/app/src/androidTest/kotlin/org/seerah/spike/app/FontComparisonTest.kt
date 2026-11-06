package org.seerah.spike.app

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.seerah.spike.core.LabelVariant

/**
 * Same scenes, same names and marks, same zoom levels for every label variant.
 * Produces map-only screenshots in files/fonts/<variant>-<scene>.png for visual review.
 * Rendered-feature counts are logged, but they do NOT prove the glyphs look right.
 */
@RunWith(AndroidJUnit4::class)
class FontComparisonTest {
    // Sample grid (synthetic positions): 3 columns lon 35.6/36.65/37.7, rows lat 28.4/27.85/27.3/26.75.
    // MapLibre zoom is in dp, so these levels are chosen for ~400 dp (phone) to ~1280 dp (tablet) wide maps.
    private val scenes = listOf(
        Scene("row1", 28.4, 36.65, 7.9), // مكة، المدينة، بئر
        Scene("row2", 27.85, 36.65, 7.9), // وادي القرى، الأبواء، بئر أريس
        Scene("row3", 27.3, 36.65, 7.9), // جبل أحد، ماء بدر ١٢، المحطة ٣٤ — 56 كم
        Scene("row4", 26.75, 36.65, 7.9), // النبي ﷺ، حروف منفردة، بلا تشكيل
        Scene("all", 27.57, 36.65, 6.6),
        Scene("crowded", 27.57, 36.65, 5.6),
        Scene("far", 26.0, 40.0, 4.5),
    )

    data class Scene(val name: String, val lat: Double, val lon: Double, val zoom: Double)

    @Test fun compareLabelVariants() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            Device.waitStyle()
            val vm = Device.vm(s)
            Device.main { vm.closeEvent(); vm.setYear(3); vm.setMapOnly(true) }
            val missing = mutableListOf<String>()
            for (v in LabelVariant.entries) {
                Device.main { vm.setLabelFonts(v) }
                Device.waitStyle("${vm.activeVersion()}/$v")
                for (sc in scenes) {
                    Device.camera(sc.lat, sc.lon, sc.zoom)
                    // Wait for glyph ranges to load and labels to be placed (new stacks load lazily).
                    var n = 0
                    val end = android.os.SystemClock.uptimeMillis() + 10_000
                    while (android.os.SystemClock.uptimeMillis() < end) {
                        n = Device.renderedCount("sample-label")
                        if (n > 0) break
                        android.os.SystemClock.sleep(300)
                    }
                    Device.settle(1_200)
                    n = Device.renderedCount("sample-label")
                    Log.i(Device.TAG, "FONTS variant=$v scene=${sc.name} sampleLabelsPlaced=$n")
                    if (sc.name.startsWith("row") && n == 0) missing += "$v/${sc.name}"
                    Device.screenshotMap("${v.name}-${sc.name}")
                }
            }
            Device.main { vm.setLabelFonts(LabelVariant.NASKH); vm.setMapOnly(false) }
            assertTrue("labels not placed: $missing", missing.isEmpty())
        }
    }
}
