package com.constrivo.drop.core.data

/**
 * The user's local calendar, for History's day groups and the Stats weeks (design §6). An "epoch day" counts local
 * calendar days from 1970-01-01.
 *
 * The JVM implementation follows the system time zone (`SystemZoneCalendar`, with daylight saving); tests use
 * [FixedOffsetCalendar].
 */
interface LocalCalendar {
    /** The local calendar day containing the instant [epochMillis]. */
    fun epochDayOf(epochMillis: Long): Long

    /** The first instant (unix ms) of local day [epochDay]. Must be increasing in [epochDay]. */
    fun startOfDayMillis(epochDay: Long): Long

    companion object {
        val UTC: LocalCalendar = FixedOffsetCalendar(0)
    }
}

/** A calendar at a fixed offset from UTC ([offsetMillis], east positive), without daylight saving. */
class FixedOffsetCalendar(
    val offsetMillis: Long,
) : LocalCalendar {
    init {
        require(offsetMillis in -MAX_OFFSET..MAX_OFFSET) { "offset beyond ±18 h" }
    }

    override fun epochDayOf(epochMillis: Long): Long = (epochMillis + offsetMillis).floorDiv(DAY_MILLIS)

    override fun startOfDayMillis(epochDay: Long): Long = epochDay * DAY_MILLIS - offsetMillis

    override fun toString(): String = "FixedOffsetCalendar($offsetMillis)"

    private companion object {
        const val MAX_OFFSET = 18L * 60 * 60 * 1000
    }
}

internal const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

/** ISO days of the week; [isoNumber] 1 is Monday. */
enum class Weekday(
    val isoNumber: Int,
) {
    MONDAY(1),
    TUESDAY(2),
    WEDNESDAY(3),
    THURSDAY(4),
    FRIDAY(5),
    SATURDAY(6),
    SUNDAY(7),
    ;

    companion object {
        /** The weekday of [epochDay]; 1970-01-01 was a Thursday. */
        fun of(epochDay: Long): Weekday = entries[(epochDay + 3).mod(7L).toInt()]
    }
}

/** A proleptic Gregorian date, for day headers. */
data class CalendarDate(
    val year: Int,
    val month: Int,
    val day: Int,
) {
    init {
        require(month in 1..12 && day in 1..lengthOfMonth(year, month)) { "invalid date $year-$month-$day" }
    }

    val epochDay: Long get() = daysFromCivil(year.toLong(), month, day)

    val weekday: Weekday get() = Weekday.of(epochDay)

    /** ISO 8601 form, `2026-09-23`. */
    override fun toString(): String =
        "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    companion object {
        /** The date of [epochDay] (H. Hinnant's civil-from-days algorithm). */
        fun ofEpochDay(epochDay: Long): CalendarDate {
            val z = epochDay + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val year = yoe + era * 400 + if (month <= 2) 1 else 0
            return CalendarDate(year.toInt(), month, day)
        }

        /** Days in [month] of [year] (proleptic Gregorian). */
        fun lengthOfMonth(
            year: Int,
            month: Int,
        ): Int =
            when (month) {
                2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }

        private fun daysFromCivil(
            y0: Long,
            month: Int,
            day: Int,
        ): Long {
            val y = if (month <= 2) y0 - 1 else y0
            val era = y.floorDiv(400L)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }
    }
}
