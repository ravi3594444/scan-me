package com.constrivo.drop.core.data

/** The transfers of one local calendar day, newest first (History tab, design §6: "grouped by day"). */
data class HistoryDay(
    val epochDay: Long,
    val date: CalendarDate,
    val transfers: List<TransferRecord>,
)

/**
 * Groups History pages by the local day each transfer started (design §6). Input is in History order (newest
 * first, as [TransferRepository.historyPage] returns it); groups and the records inside them keep that order.
 */
object HistoryGrouping {
    /** [transfers] grouped by local start day. */
    fun byDay(
        transfers: List<TransferRecord>,
        calendar: LocalCalendar,
    ): List<HistoryDay> = append(emptyList(), transfers, calendar)

    /**
     * [days] with the next History [page] added: records of the day [days] ends with join that day's group, the rest
     * form new groups, so paging never shows one day twice.
     */
    fun append(
        days: List<HistoryDay>,
        page: List<TransferRecord>,
        calendar: LocalCalendar,
    ): List<HistoryDay> {
        if (page.isEmpty()) return days
        val out = days.toMutableList()
        // The open group: the day [days] ends with, reopened so that the page can extend it.
        val last = out.removeLastOrNull()
        var openDay = last?.epochDay ?: calendar.epochDayOf(page.first().startedAtMillis)
        var open = last?.transfers?.toMutableList() ?: mutableListOf()
        for (record in page) {
            val day = calendar.epochDayOf(record.startedAtMillis)
            if (day != openDay) {
                if (open.isNotEmpty()) out += group(openDay, open)
                openDay = day
                open = mutableListOf()
            }
            open += record
        }
        out += group(openDay, open)
        return out
    }

    private fun group(
        epochDay: Long,
        transfers: List<TransferRecord>,
    ) = HistoryDay(epochDay, CalendarDate.ofEpochDay(epochDay), transfers.toList())
}
