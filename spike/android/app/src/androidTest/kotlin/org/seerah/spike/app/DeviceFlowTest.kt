package org.seerah.spike.app

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.seerah.spike.core.LabelVariant
import org.seerah.spike.core.TimePoint
import org.seerah.spike.core.ViewTime
import kotlin.math.abs

private const val TOWN_BEFORE = "قَرْيَةُ الاخْتِبَارِ"
private const val TOWN_AFTER = "مَدِينَةُ الاخْتِبَارِ"

/** Phase 1 (online, fresh install). Leaves follow point = e2 for the offline restart phase. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceFlowTest {

    @Test fun a_localPmtilesAndArabicLabelsRender() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            Device.waitStyle("1.0.0-test")
            val vm = Device.vm(s)
            Device.main { vm.setYear(3) }
            Device.camera(24.0, 39.4, 6.3)
            assertTrue("land from the local PMTiles must render", Device.renderedCount("land") > 0)
            for (f in listOf(LabelVariant.NASKH, LabelVariant.FONT_FACES)) {
                Device.main { vm.setLabelFonts(f) }
                Device.waitStyle("1.0.0-test/$f")
                Device.settle()
                val names = Device.rendered("name", "place-label", "region-label")
                Log.i(Device.TAG, "labels ($f): $names")
                assertTrue("labels must render with $f: $names", names.isNotEmpty())
                Device.screenshot("arabic-labels-$f")
                Device.camera(24.4, 39.0, 7.6)
                Device.screenshot("arabic-labels-zoom-$f")
                Device.camera(24.0, 39.4, 6.3)
            }
            Device.main { vm.setLabelFonts(LabelVariant.NASKH) }
            Device.waitStyle("1.0.0-test/NASKH")
            Device.camera(20.0, 40.0, 1.3)
            Device.screenshot("world-overview")
        }
    }

    @Test fun b_timeChangesNameBorderLandmark() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            Device.waitStyle()
            val vm = Device.vm(s)
            Device.camera(24.5, 39.8, 6.0)

            Device.main { vm.closeEvent(); vm.setYear(2) }
            Device.waitFor("year 2") { MapProbe.appliedView == ViewTime.YearEnd(2) }
            Device.settle()
            assertEquals(setOf(TOWN_BEFORE), Device.rendered("name", "place-label") intersect setOf(TOWN_BEFORE, TOWN_AFTER))
            assertEquals("rectangle border", setOf("a"), Device.renderedStates("test-region", "region-fill"))
            assertEquals(0, Device.renderedCount("landmark-dot"))
            Device.screenshot("time-year2")

            Device.main { vm.setYear(3) }
            Device.waitFor("year 3") { MapProbe.appliedView == ViewTime.YearEnd(3) }
            Device.settle()
            assertEquals(setOf(TOWN_AFTER), Device.rendered("name", "place-label") intersect setOf(TOWN_BEFORE, TOWN_AFTER))
            assertEquals("hexagon border replaces the rectangle", setOf("b"), Device.renderedStates("test-region", "region-fill"))
            assertEquals("landmark appears by end of year 3", 1, Device.renderedCount("landmark-dot"))
            Device.screenshot("time-year3")
        }
    }

    @Test fun c_openEventSetsItsOwnTimeAndPlace() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            Device.waitStyle()
            val vm = Device.vm(s)
            // e2: month 7 of test year 3, region known, exact spot unknown.
            Device.main { vm.openEvent("e2") }
            Device.waitFor("event view") { MapProbe.appliedView == ViewTime.EventSpan(TimePoint(3, 7)) }
            Device.settle()
            val (lat, lon, zoom) = Device.target()
            assertTrue("camera on the event's region ($lat,$lon,$zoom)", abs(lat - 24.5) < 0.05 && abs(lon - 40.0) < 0.05 && abs(zoom - 5.5) < 0.05)
            assertEquals("landmark built in month 9 is not there in month 7", 0, Device.renderedCount("landmark-dot"))
            assertEquals("rename dated only 'year 3': order unknown -> earlier name", setOf(TOWN_BEFORE),
                Device.rendered("name", "place-label") intersect setOf(TOWN_BEFORE, TOWN_AFTER))
            assertEquals("no point marker for a general place", 0, Device.renderedCount(EVENTS_LAYER))
            assertTrue(Device.main { vm.ui.value.undetermined }.contains(TOWN_AFTER))
            Device.screenshot("event-e2-month7")

            // Same year, end-of-year view: landmark and new name are there.
            Device.main { vm.closeEvent() }
            Device.waitFor("year end") { MapProbe.appliedView == ViewTime.YearEnd(3) }
            Device.settle()
            assertEquals(1, Device.renderedCount("landmark-dot"))

            // e1: exact place, year 2 -> marker of year 2 visible.
            Device.main { vm.openEvent("e1") }
            Device.waitFor("e1") { MapProbe.appliedView == ViewTime.EventSpan(TimePoint(2)) }
            Device.settle()
            assertEquals(1, Device.renderedCount(EVENTS_LAYER))
            Device.screenshot("event-e1")

            // e3: place unknown -> camera must not move to a guess.
            val before = Device.target()
            Device.main { vm.openEvent("e3") }
            Device.settle()
            assertEquals(before, Device.target())

            // Leave a follow point and an opened event for the restart phase.
            Device.main { vm.openEvent("e2"); vm.setFollowPoint("e2") }
            Device.settle()
        }
    }
}
