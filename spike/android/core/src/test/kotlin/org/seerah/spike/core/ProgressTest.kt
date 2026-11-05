package org.seerah.spike.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgressTest {
    private val v1 = Fixtures.content("content.v1.json")
    private val v2 = Fixtures.content("content.v2.json")

    @Test fun `first run starts at package initial view, follow-along not started`() {
        val r = ProgressRules.reconcile(null, v1)
        assertEquals(1, r.state.year)
        assertNull(r.state.followEventId)
    }

    @Test fun `progress survives a content update`() {
        val saved = UserState(year = 3, lon = 39.6, lat = 24.5, zoom = 7.0, followEventId = "e2", openEventId = "e1")
        val r = ProgressRules.reconcile(saved, v2)
        assertEquals(saved, r.state)
        assertNull(r.notice)
    }

    @Test fun `removed followed event is cleared with a notice, never silently replaced`() {
        val saved = UserState(year = 5, lon = 0.0, lat = 0.0, zoom = 3.0, followEventId = "e4")
        val r = ProgressRules.reconcile(saved, v1) // e4 does not exist in v1
        assertNull(r.state.followEventId)
        assertEquals(true, r.notice != null)
    }
}
