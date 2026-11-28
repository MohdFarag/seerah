package app.seerah.domain

import app.seerah.model.CalendarAxis
import app.seerah.model.EventDepth
import app.seerah.model.EventKind
import app.seerah.model.EventLocation
import app.seerah.model.HistoricalDate
import app.seerah.model.KnownAccuracy
import app.seerah.model.SeerahEvent
import app.seerah.model.TimelinePoint
import app.seerah.model.Visibility
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class TimelineAxisTest {
    private fun event(id: String, date: HistoricalDate, order: Int = 1) = SeerahEvent(
        id = id,
        phaseId = "ph-1",
        order = order,
        kind = EventKind.CALL,
        depth = EventDepth.BRIEF,
        title = "حدث $id",
        date = date,
        where = EventLocation.SinglePlace("p1", KnownAccuracy.EXACT),
        visibility = Visibility.ALWAYS,
        prominence = 1,
    )

    @Test
    fun buildBuckets_spansPreHijraToHijriWithZeroSkippedAndCollectsOnlySourcedMonths() {
        val events = listOf(
            event("ev-bh2", HistoricalDate.Year(TimelinePoint(CalendarAxis.PRE_HIJRA, 2))),
            event("ev-ah1-m3", HistoricalDate.Month(TimelinePoint(CalendarAxis.HIJRI, 1, 3))),
            event("ev-ah1-d7", HistoricalDate.Day(TimelinePoint(CalendarAxis.HIJRI, 1, 7, 12)), order = 2),
            event("ev-ah1-y", HistoricalDate.Year(TimelinePoint(CalendarAxis.HIJRI, 1)), order = 3),
            event("ev-unknown", HistoricalDate.Unknown(), order = 4),
        )

        val buckets = TimelineAxis.buildBuckets(events)
        assertEquals(
            listOf(
                TimelinePoint(CalendarAxis.PRE_HIJRA, 2),
                TimelinePoint(CalendarAxis.PRE_HIJRA, 1),
                TimelinePoint(CalendarAxis.HIJRI, 1),
            ),
            buckets.map { it.yearPoint },
        )

        val bh2 = buckets[0]
        assertTrue(bh2.hasEvents)
        assertEquals(emptyList(), bh2.sourcedMonths)

        val bh1 = buckets[1]
        assertFalse(bh1.hasEvents)
        assertEquals(emptyList(), bh1.sourcedMonths)

        val ah1 = buckets[2]
        assertTrue(ah1.hasEvents)
        assertEquals(listOf(3, 7), ah1.sourcedMonths)
    }

    @Test
    fun navigationAndOpenTarget_skipEmptyYearsAndKeepUnknownUndated() {
        val bh3 = event("ev-1", HistoricalDate.Year(TimelinePoint(CalendarAxis.PRE_HIJRA, 3)))
        val ah2 = event("ev-2", HistoricalDate.Month(TimelinePoint(CalendarAxis.HIJRI, 2, 5)))
        val unknown = event("ev-unknown", HistoricalDate.Unknown())
        val events = listOf(bh3, ah2, unknown)

        val emptyBh1 = TimelinePoint(CalendarAxis.PRE_HIJRA, 1)
        assertEquals(TimelinePoint(CalendarAxis.PRE_HIJRA, 3), TimelineAxis.previousEventYear(emptyBh1, events))
        assertEquals(TimelinePoint(CalendarAxis.HIJRI, 2), TimelineAxis.nextEventYear(emptyBh1, events))

        assertNull(TimelineAxis.previousEventYear(TimelinePoint(CalendarAxis.PRE_HIJRA, 3), events))
        assertNull(TimelineAxis.nextEventYear(TimelinePoint(CalendarAxis.HIJRI, 2), events))

        val current = TimelinePoint(CalendarAxis.HIJRI, 1)
        assertEquals(TimelinePoint(CalendarAxis.HIJRI, 2, 5), TimelineAxis.eventOpenTarget(ah2, current))
        assertEquals(current, TimelineAxis.eventOpenTarget(unknown, current))
    }
}
