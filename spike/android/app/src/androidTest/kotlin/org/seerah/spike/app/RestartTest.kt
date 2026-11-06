package org.seerah.spike.app

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run in a NEW process after `am force-stop`, typically with networking off.
 * Args: -e expectVersion <v>  -e expectOffline true|false
 */
@RunWith(AndroidJUnit4::class)
class RestartTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test fun stateAndMapRestoredFromDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        if (args.getString("expectOffline") == "true") {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            assertNull("device must be offline for this phase", cm.activeNetwork)
        }
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            val expect = args.getString("expectVersion") ?: "1.0.0-test"
            Device.waitStyle(expect)
            val ui = Device.main { Device.vm(s).ui.value }
            assertEquals("follow point survives restart", "e2", ui.user?.followEventId)
            assertEquals("opened event restored", "e2", ui.user?.openEventId)
            Device.settle(4_000)
            assertTrue("base map from local PMTiles", Device.renderedCount("land") > 0)
            assertTrue("labels from local fonts", Device.rendered("name", "place-label", "region-label").isNotEmpty())
            Device.screenshot("restart-${args.getString("phase") ?: "x"}")
        }
    }
}
