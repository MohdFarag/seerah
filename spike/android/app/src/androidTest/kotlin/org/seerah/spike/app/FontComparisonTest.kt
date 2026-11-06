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
    // Sample grid (synthetic positions) spans lon 35.6..37.7, lat 26.75..28.4.
    private val scenes = listOf(
        Scene("closeup-top", 28.12, 36.65, 9.1),
        Scene("closeup-bottom", 27.0, 36.65, 9.1),
        Scene("close", 27.57, 36.65, 8.2),
        Scene("mid", 27.57, 36.9, 7.0),
        Scene("far", 26.5, 38.5, 5.6),
    )

    data class Scene(val name: String, val lat: Double, val lon: Double, val zoom: Double)

    @Test fun compareLabelVariants() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            Device.waitStyle()
            val vm = Device.vm(s)
            Device.main { vm.closeEvent(); vm.setYear(3) }
            for (v in LabelVariant.entries) {
                Device.main { vm.setLabelFonts(v) }
                Device.waitStyle("${vm.activeVersion()}/$v")
                for (sc in scenes) {
                    Device.camera(sc.lat, sc.lon, sc.zoom)
                    Device.settle(1_500)
                    val n = Device.renderedCount("sample-label")
                    Log.i(Device.TAG, "FONTS variant=$v scene=${sc.name} sampleLabelsPlaced=$n")
                    if (sc.name == "close") assertTrue("$v: sample labels must be placed", n > 0)
                    Device.screenshotMap("${v.name}-${sc.name}")
                }
            }
            Device.main { vm.setLabelFonts(LabelVariant.NASKH) }
        }
    }
}
