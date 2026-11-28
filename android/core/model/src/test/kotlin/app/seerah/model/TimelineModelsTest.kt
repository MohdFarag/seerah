package app.seerah.model

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class TimelineModelsTest {
    @Test
    fun preHijraAndHijri_bridgeContinousYearWithoutYearZero() {
        val twoBh = TimelinePoint(CalendarAxis.PRE_HIJRA, year = 2)
        val oneBh = TimelinePoint(CalendarAxis.PRE_HIJRA, year = 1)
        val oneAh = TimelinePoint(CalendarAxis.HIJRI, year = 1)
        val twoAh = TimelinePoint(CalendarAxis.HIJRI, year = 2)

        assertEquals(-1, twoBh.continuousYear)
        assertEquals(0, oneBh.continuousYear)
        assertEquals(1, oneAh.continuousYear)
        assertEquals(2, twoAh.continuousYear)

        assertEquals(oneBh, twoBh.nextYear())
        assertEquals(oneAh, oneBh.nextYear())
        assertEquals(twoAh, oneAh.nextYear())

        assertEquals(oneAh, twoAh.previousYear())
        assertEquals(oneBh, oneAh.previousYear())
        assertEquals(twoBh, oneBh.previousYear())

        assertTrue(twoBh < oneBh && oneBh < oneAh && oneAh < twoAh)
    }

    @Test
    fun timelinePoint_rejectsZeroYearAndInvalidParts() {
        assertFailsWith<IllegalArgumentException> { TimelinePoint(CalendarAxis.HIJRI, year = 0) }
        assertFailsWith<IllegalArgumentException> { TimelinePoint(CalendarAxis.PRE_HIJRA, year = -1) }
        assertFailsWith<IllegalArgumentException> { TimelinePoint(CalendarAxis.HIJRI, year = 1, month = 0) }
        assertFailsWith<IllegalArgumentException> { TimelinePoint(CalendarAxis.HIJRI, year = 1, month = 13) }
        assertFailsWith<IllegalArgumentException> { TimelinePoint(CalendarAxis.HIJRI, year = 1, month = 1, day = 0) }
        assertFailsWith<IllegalArgumentException> { TimelinePoint(CalendarAxis.HIJRI, year = 1, month = 1, day = 32) }
        assertFailsWith<IllegalArgumentException> { TimelinePoint(CalendarAxis.HIJRI, year = 1, day = 5) }
    }

    @Test
    fun keysSeparateStateStartFromQueryUpperBound() {
        val yearPoint = TimelinePoint(CalendarAxis.HIJRI, year = 1)
        val monthPoint = TimelinePoint(CalendarAxis.HIJRI, year = 1, month = 3)
        val dayPoint = TimelinePoint(CalendarAxis.HIJRI, year = 1, month = 3, day = 12)

        assertTrue(yearPoint.asStateStartKey() < monthPoint.asStateStartKey())
        assertTrue(monthPoint.asQueryUpperBoundKey() > monthPoint.asStateStartKey())
        assertTrue(yearPoint.asQueryUpperBoundKey() == TimelinePoint(CalendarAxis.HIJRI, 1, 12, 31).asStateStartKey())
        assertTrue(monthPoint.asStateStartKey() <= yearPoint.asQueryUpperBoundKey())
        assertTrue(dayPoint.asStateStartKey() <= monthPoint.asQueryUpperBoundKey())
    }

    @Test
    fun historicalDate_enforcesPrecisionAndProphethoodRules() {
        val preHijraYear = HistoricalDate.Year(
            point = TimelinePoint(CalendarAxis.PRE_HIJRA, 2),
            prophethoodYear = 11,
            approximate = true,
            note = "Disputed season.",
        )
        assertEquals(DatePrecision.YEAR, preHijraYear.precision)
        assertEquals(TimelinePoint(CalendarAxis.PRE_HIJRA, 2), preHijraYear.timelinePointOrNull)

        assertFailsWith<IllegalArgumentException> {
            HistoricalDate.Year(TimelinePoint(CalendarAxis.HIJRI, 1), prophethoodYear = 14)
        }
        assertFailsWith<IllegalArgumentException> {
            HistoricalDate.Year(TimelinePoint(CalendarAxis.HIJRI, 1, month = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            HistoricalDate.Month(TimelinePoint(CalendarAxis.HIJRI, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            HistoricalDate.Day(TimelinePoint(CalendarAxis.HIJRI, 1, month = 2))
        }

        val unknownPlain = HistoricalDate.Unknown()
        assertNull(unknownPlain.calendar)
        assertNull(unknownPlain.timelinePointOrNull)

        val unknownPreHijra = HistoricalDate.Unknown(prophethoodYear = 10)
        assertEquals(CalendarAxis.PRE_HIJRA, unknownPreHijra.calendar)
        assertFailsWith<IllegalArgumentException> { HistoricalDate.Unknown(prophethoodYear = 0) }
        assertFailsWith<IllegalArgumentException> { HistoricalDate.Unknown(note = "   ") }
    }
}
