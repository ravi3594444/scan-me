package com.constrivo.drop.ui.shared.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.ladder.LadderHint
import com.constrivo.drop.core.ladder.TransportBadge

/** The five dashboard tabs (design §6), in their order across the bottom. */
enum class DashboardTab { LIVE, HISTORY, DEVICES, STATS, SETTINGS }

/** A Live tab row (F‑G1, design §6). */
@Immutable
data class LiveRowUi(
    val transferId: String,
    val peerName: String,
    val peerPlatform: DevicePlatform,
    val direction: Direction,
    val stage: TransferStage,
    val summary: ItemSummary,
    val bytesDone: Long,
    val bytesTotal: Long,
    val fraction: Float,
    val bytesPerSecond: Long?,
    val etaMillis: Long?,
    val badge: TransportBadge?,
    val hint: LadderHint?,
    val paused: Boolean,
    val canAddFiles: Boolean,
)

/** How a History row ended (F‑G2 status chip). */
enum class HistoryStatus { DONE, PARTIAL, FAILED, CANCELLED, INTERRUPTED }

/** One transfer in History (F‑G2), as the data layer's `TransferRecord` provides it. */
@Immutable
data class HistoryEntry(
    val id: String,
    val peerName: String,
    val peerPlatform: DevicePlatform,
    val direction: Direction,
    val status: HistoryStatus,
    val startedAtMillis: Long,
    val durationMillis: Long?,
    val bytesTotal: Long,
    val bytesDone: Long,
    val avgBytesPerSecond: Long?,
    val summary: ItemSummary,
    val badge: TransportBadge?,
)

/** A file of a History transfer, for the detail sheet (design §6: "per-file open"). */
@Immutable
data class HistoryFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val kind: FileKind,
    /** False when the file failed or is gone from disk; the row is shown without the open action. */
    val openable: Boolean,
)

/** A calendar date for day headers, from the app's local calendar. */
@Immutable
data class DayDate(
    val year: Int,
    /** 1–12. */
    val month: Int,
    val day: Int,
) {
    init {
        require(month in MONTHS && day in 1..31) { "not a date: $year-$month-$day" }
    }

    companion object {
        /** The valid [month] numbers. */
        val MONTHS: IntRange = 1..12

        /** The developer message for a month outside [MONTHS]; never shown to users. */
        const val MONTH_RANGE: String = "month must be 1..12"

        /** The proleptic Gregorian date of [epochDay] (days since 1970-01-01), by Howard Hinnant's civil_from_days. */
        fun ofEpochDay(epochDay: Long): DayDate {
            val z = epochDay + 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return DayDate(y, m, d)
        }
    }
}

/** A History day header: "Today", "Yesterday" or a date (design §6: grouped by day). */
@Immutable
sealed interface DayLabel {
    data object Today : DayLabel

    data object Yesterday : DayLabel

    data class Date(
        val date: DayDate,
    ) : DayLabel
}

/** A History row with its local time of day. */
@Immutable
data class HistoryRowUi(
    val entry: HistoryEntry,
    val hour: Int,
    val minute: Int,
)

@Immutable
data class HistoryDayUi(
    val epochDay: Long,
    val label: DayLabel,
    val rows: List<HistoryRowUi>,
)

/** The History detail sheet. */
@Immutable
data class HistoryDetailUi(
    val row: HistoryRowUi,
    val label: DayLabel,
    val files: List<HistoryFile>?,
)

/** A trusted device card (F‑G3, design §6). */
@Immutable
data class TrustedDeviceEntry(
    val id: String,
    val name: String,
    val platform: DevicePlatform,
    val lastSeenMillis: Long,
    val autoAccept: Boolean,
)

/** "Seen just now" / "Seen 5 min ago" / "Seen 3 h ago" / "Seen 2 days ago". */
@Immutable
sealed interface LastSeen {
    data object JustNow : LastSeen

    data class MinutesAgo(
        val minutes: Int,
    ) : LastSeen

    data class HoursAgo(
        val hours: Int,
    ) : LastSeen

    data class DaysAgo(
        val days: Int,
    ) : LastSeen

    companion object {
        fun of(
            lastSeenMillis: Long,
            nowMillis: Long,
        ): LastSeen {
            val ago = (nowMillis - lastSeenMillis).coerceAtLeast(0)
            val minutes = ago / 60_000
            return when {
                minutes < 1 -> JustNow
                minutes < 60 -> MinutesAgo(minutes.toInt())
                minutes < 24 * 60 -> HoursAgo((minutes / 60).toInt())
                else -> DaysAgo((minutes / (24 * 60)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        }
    }
}

@Immutable
data class DeviceCardUi(
    val id: String,
    val name: String,
    val initials: String?,
    val avatarHash: Int,
    val platform: DevicePlatform,
    val lastSeen: LastSeen,
    val autoAccept: Boolean,
)

/** Stats tab figures (F‑G4), from the data layer's `TransferStats`. */
@Immutable
data class StatsSnapshot(
    val totalBytes: Long,
    val averageBytesPerSecond: Long?,
    val transfersThisWeek: Int,
    val hoursSaved: Double,
    /** Transfers per week for the last 12 weeks, oldest first; the last is this week. */
    val weeks: List<Int>,
) {
    init {
        require(totalBytes >= 0 && transfersThisWeek >= 0) { "counts must not be negative" }
        require(weeks.all { it >= 0 }) { "week counts must not be negative" }
    }

    companion object {
        const val WEEKS: Int = 12
        val EMPTY = StatsSnapshot(0, null, 0, 0.0, List(WEEKS) { 0 })
    }
}

/** App languages (decision 9); [tag] is the BCP 47 tag stored in `profile.language`, null for the system default. */
enum class AppLanguage(
    val tag: String?,
) {
    SYSTEM(null),
    ENGLISH("en"),
    HINDI("hi"),
    ;

    companion object {
        fun ofTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag != null && it.tag.equals(tag, ignoreCase = true) } ?: SYSTEM
    }
}

/** Every setting of design §6 / F‑G5 at one moment. */
@Immutable
data class SettingsValues(
    val visibility: Visibility,
    val prefer5Ghz: Boolean,
    val keepScreenAwake: Boolean,
    val bundleSmallFiles: Boolean,
    /** A display label for the save location (a folder name); null for the platform default. */
    val saveLocationLabel: String?,
    val nickname: String,
    val avatar: ImageBitmap?,
    val language: AppLanguage,
    val crashReports: Boolean,
    val haptics: Boolean,
    val appVersion: String,
)

/** Result of "Clear partial files" (the app stops parked transfers first, then deletes). */
@Immutable
data class ClearPartialsResult(
    val filesRemoved: Int,
    val bytesFreed: Long,
)

/** The Settings tab: the values plus transient UI state. */
@Immutable
data class SettingsUi(
    val values: SettingsValues,
    val clearingPartials: Boolean = false,
    val lastClear: ClearPartialsResult? = null,
)
