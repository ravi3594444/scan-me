package com.constrivo.drop.core.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** [LocalCalendar] in the time zone [zone], with its daylight-saving rules (java.time). */
class ZoneCalendar(
    val zone: ZoneId,
) : LocalCalendar {
    override fun epochDayOf(epochMillis: Long): Long = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay()

    /** The first instant of the day; where a transition skips local midnight, the first instant that exists. */
    override fun startOfDayMillis(epochDay: Long): Long = LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()

    override fun toString(): String = "ZoneCalendar($zone)"
}

/** [LocalCalendar] in the system's current time zone, read at every call, so it follows the user across zones. */
object SystemZoneCalendar : LocalCalendar {
    override fun epochDayOf(epochMillis: Long): Long = ZoneCalendar(ZoneId.systemDefault()).epochDayOf(epochMillis)

    override fun startOfDayMillis(epochDay: Long): Long = ZoneCalendar(ZoneId.systemDefault()).startOfDayMillis(epochDay)
}
