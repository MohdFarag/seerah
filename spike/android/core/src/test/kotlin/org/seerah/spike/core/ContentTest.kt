package org.seerah.spike.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentTest {
    private val v1 = Fixtures.content("content.v1.json")

    @Test fun `spike content is valid and marked test`() {
        assertEquals(emptyList(), v1.validate())
        assertTrue(v1.test && v1.events.all { it.test })
    }

    @Test fun `date label states precision and never invents parts`() {
        assertEquals("سنة اختبار ٢", v1.event("e1")!!.date.label("سنة اختبار"))
        assertEquals("الشهر ٧ من سنة اختبار ٣ (تقريبي)", v1.event("e2")!!.date.label("سنة اختبار"))
        val bad = EventDate(year = 3, month = 7, precision = EventDate.Precision.YEAR)
        assertTrue(bad.validate().isNotEmpty(), "a month must not hide behind precision=year")
    }

    @Test fun `place accuracy controls marker and camera`() {
        val exact = v1.event("e1")!!.place
        val general = v1.event("e2")!!.place
        val unknown = v1.event("e3")!!.place
        assertTrue(exact.showsMarker)
        assertTrue(!general.showsMarker && general.cameraTarget() != null && general.note() != null)
        assertTrue(!unknown.showsMarker)
        assertNull(unknown.cameraTarget(), "unknown place must not move the camera to a guess")
        assertTrue(EventPlace(EventPlace.Accuracy.UNKNOWN, lon = 1.0, lat = 1.0).validate().isNotEmpty())
    }

    @Test fun `empty year offers previous and next events`() {
        assertTrue(v1.eventsInYear(1).isEmpty())
        assertNull(v1.previousEvent(1))
        assertEquals("e1", v1.nextEvent(1)!!.id)
        assertTrue(v1.eventsInYear(6).isEmpty())
        assertEquals("e3", v1.previousEvent(6)!!.id)
    }
}
