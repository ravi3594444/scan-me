package com.constrivo.drop.ui.shared

import com.constrivo.drop.ui.shared.presenter.DayCalendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** [DayCalendar.System] follows the device's time zone, including a change while the app runs (History day groups). */
class SystemCalendarTest {
    private fun <T> inZone(
        id: String,
        block: () -> T,
    ): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        return try {
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun matchesAFixedOffsetAndFollowsZoneChanges() {
        // 2026-09-23T20:00:00Z: 01:30 on 24 September in India (UTC+5:30), still 23 September in UTC.
        val millis = 1_790_193_600_000L
        val utc = DayCalendar.fixedOffset(0)
        val india = DayCalendar.fixedOffset(330)
        inZone("UTC") {
            assertEquals(utc.epochDayOf(millis), DayCalendar.System.epochDayOf(millis))
            assertEquals(utc.minuteOfDay(millis), DayCalendar.System.minuteOfDay(millis))
        }
        inZone("Asia/Kolkata") {
            assertEquals(india.epochDayOf(millis), DayCalendar.System.epochDayOf(millis))
            assertEquals(90, DayCalendar.System.minuteOfDay(millis))
        }
        assertEquals(utc.epochDayOf(millis) + 1, india.epochDayOf(millis))
    }
}
