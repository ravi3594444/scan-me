package com.constrivo.drop.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Calendar arithmetic behind History's day groups and the Stats weeks (design §6). */
class CalendarTest {
    @Test
    fun knownDates() {
        assertEquals(CalendarDate(1970, 1, 1), CalendarDate.ofEpochDay(0))
        assertEquals(CalendarDate(1969, 12, 31), CalendarDate.ofEpochDay(-1))
        assertEquals(CalendarDate(2000, 2, 29), CalendarDate.ofEpochDay(11_016))
        assertEquals(CalendarDate(2026, 9, 23), CalendarDate.ofEpochDay(20_719))
        assertEquals(CalendarDate(1600, 3, 1), CalendarDate.ofEpochDay(-135_080))
        assertEquals("2026-09-23", CalendarDate(2026, 9, 23).toString())
        assertEquals(Weekday.WEDNESDAY, CalendarDate(2026, 9, 23).weekday)
        assertEquals(Weekday.THURSDAY, Weekday.of(0))
        assertEquals(Weekday.WEDNESDAY, Weekday.of(-1))
        assertEquals(Weekday.MONDAY, Weekday.of(4))
    }

    @Test
    fun epochDaysRoundTripAcrossFourHundredYears() {
        var previous: CalendarDate? = null
        for (day in -146_097L..146_097L step 1) {
            val date = CalendarDate.ofEpochDay(day)
            assertEquals(day, date.epochDay)
            previous?.let { p ->
                val next =
                    if (p.day <
                        daysIn(p.year, p.month)
                    ) {
                        CalendarDate(p.year, p.month, p.day + 1)
                    } else if (p.month <
                        12
                    ) {
                        CalendarDate(p.year, p.month + 1, 1)
                    } else {
                        CalendarDate(p.year + 1, 1, 1)
                    }
                assertEquals(next, date)
            }
            previous = date
        }
    }

    private fun daysIn(
        year: Int,
        month: Int,
    ): Int =
        when (month) {
            2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

    @Test
    fun fixedOffsetDaysStartAtLocalMidnight() {
        val ist = FixedOffsetCalendar(330 * 60_000L)
        val t0 = 1_790_164_800_000L // 2026-09-23T12:00Z = 17:30 IST
        assertEquals(20_719, ist.epochDayOf(t0))
        assertEquals(t0 - (17 * 60 + 30) * 60_000L, ist.startOfDayMillis(20_719))
        assertEquals(20_720, ist.epochDayOf(t0 + 6 * 3_600_000L + 30 * 60_000L), "midnight IST starts the next day")
        assertEquals(20_719, ist.epochDayOf(t0 + 6 * 3_600_000L + 30 * 60_000L - 1))
        val newYork = FixedOffsetCalendar(-4 * 3_600_000L)
        assertEquals(-1, newYork.epochDayOf(0), "1970-01-01T00:00Z was still Dec 31 in UTC-4")
        assertEquals(20_719, LocalCalendar.UTC.epochDayOf(t0))
        assertFailsWith<IllegalArgumentException> { FixedOffsetCalendar(19 * 3_600_000L) }
        assertFailsWith<IllegalArgumentException> { CalendarDate(2026, 13, 1) }
        assertFailsWith<IllegalArgumentException> { CalendarDate(2026, 2, 29) }
        assertFailsWith<IllegalArgumentException> { CalendarDate(1900, 2, 29) }
        assertEquals(29, CalendarDate(2000, 2, 29).day)
        assertEquals(30, CalendarDate.lengthOfMonth(2026, 9))
    }
}
