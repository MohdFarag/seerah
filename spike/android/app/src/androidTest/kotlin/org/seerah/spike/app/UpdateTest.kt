package org.seerah.spike.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.seerah.spike.core.ViewTime

/**
 * Needs `spike/pack/serve.py` reachable at the feed URL (adb reverse tcp:8765 tcp:8765).
 * Args: -e mode corrupt|cut
 */
@RunWith(AndroidJUnit4::class)
class UpdateTest {
    private val mode = InstrumentationRegistry.getArguments().getString("mode") ?: "cut"

    @Test fun updateWithoutNewApp() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            Device.waitStyle("1.0.0-test")
            val vm = Device.vm(s)
            Device.main { vm.checkForUpdate() }
            Device.waitFor("offer") { vm.ui.value.offer != null }
            Device.main { vm.downloadUpdate() }
            Device.waitFor("download finished") { !vm.ui.value.downloading && vm.ui.value.updateStatus?.startsWith("تنزيل") == false }
            val first = vm.ui.value.updateStatus!!
            if (mode == "corrupt") {
                assertTrue(first, first.startsWith("رُفضت الحزمة"))
                assertEquals("working package kept", "1.0.0-test", vm.activeVersion())
                return
            }
            assertTrue("server cut the connection: $first", first.startsWith("انقطع التنزيل"))
            assertEquals("1.0.0-test", vm.activeVersion())
            Device.main { vm.downloadUpdate() } // resume
            Device.waitFor("installed", 120_000) { vm.activeVersion() == "1.1.0-test" }
            Device.waitStyle("1.1.0-test")
            val ui = vm.ui.value
            assertEquals("follow point kept across update", "e2", ui.user?.followEventId)
            assertNotNull("new event from update", ui.pack?.event("e4"))
            Device.main { vm.closeEvent(); vm.setYear(3) }
            Device.waitFor("year 3") { MapProbe.appliedView == ViewTime.YearEnd(3) }
            Device.camera(24.35, 39.75, 7.5)
            assertTrue("corrected landmark name rendered",
                "مَعْلَمٌ تَجْرِيبِيٌّ (مُصَحَّحٌ ٢)" in Device.rendered("name", "place-label"))
            Device.main { vm.openEvent("e2") } // restore for the next restart phase
            Device.screenshot("after-update")
        }
    }
}
