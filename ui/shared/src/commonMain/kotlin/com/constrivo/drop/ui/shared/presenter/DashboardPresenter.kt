package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.core.discovery.Nicknames
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.ui.shared.model.AppLanguage
import com.constrivo.drop.ui.shared.model.Avatars
import com.constrivo.drop.ui.shared.model.ClearPartialsResult
import com.constrivo.drop.ui.shared.model.DashboardTab
import com.constrivo.drop.ui.shared.model.DayDate
import com.constrivo.drop.ui.shared.model.DayLabel
import com.constrivo.drop.ui.shared.model.DeviceCardUi
import com.constrivo.drop.ui.shared.model.HistoryDayUi
import com.constrivo.drop.ui.shared.model.HistoryDetailUi
import com.constrivo.drop.ui.shared.model.HistoryEntry
import com.constrivo.drop.ui.shared.model.HistoryFile
import com.constrivo.drop.ui.shared.model.HistoryRowUi
import com.constrivo.drop.ui.shared.model.LastSeen
import com.constrivo.drop.ui.shared.model.LiveRowUi
import com.constrivo.drop.ui.shared.model.SettingsUi
import com.constrivo.drop.ui.shared.model.SettingsValues
import com.constrivo.drop.ui.shared.model.StatsSnapshot
import com.constrivo.drop.ui.shared.model.TransferSnapshot
import com.constrivo.drop.ui.shared.model.TrustedDeviceEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Live tab actions (F‑G1: pause, cancel, add). */
interface LiveActions {
    fun pause(transferId: String)

    fun resume(transferId: String)

    fun cancel(transferId: String)

    /** Opens the picker to queue more files into a running transfer (F‑C5). */
    fun addFiles(transferId: String)
}

/** History (F‑G2), backed by core/data's `TransferRepository` in the app layer. */
interface HistorySource {
    /** Every transfer, newest first; survives restarts. */
    val history: Flow<List<HistoryEntry>>

    /** The files of one transfer, for the detail sheet. */
    suspend fun files(transferId: String): List<HistoryFile>

    fun openFile(
        transferId: String,
        fileId: String,
    )

    /** Offers the same files to the same device again. */
    fun resend(transferId: String)

    /** Clears History (the list only; received files stay). */
    fun clear()
}

/** Trusted devices (F‑B4, F‑G3), backed by core/data's `DeviceRepository`. */
interface DeviceSource {
    val trusted: Flow<List<TrustedDeviceEntry>>

    fun rename(
        deviceId: String,
        name: String,
    )

    fun setAutoAccept(
        deviceId: String,
        enabled: Boolean,
    )

    /** Removes the recognition secret; the device shows as untrusted next time (F‑G3). */
    fun forget(deviceId: String)
}

/** Stats (F‑G4), backed by core/data's `StatsRepository`, so the figures reconcile with History. */
interface StatsSource {
    val stats: Flow<StatsSnapshot>

    fun shareStatsCard()
}

/**
 * Settings (F‑G5, design §6). Every setter takes effect at once, without a restart; the app layer writes core/data's
 * `SettingsRepository` and tells the engine.
 */
interface SettingsSource {
    val settings: Flow<SettingsValues>

    fun setVisibility(mode: Visibility)

    fun setPrefer5Ghz(enabled: Boolean)

    fun setKeepScreenAwake(enabled: Boolean)

    fun setBundleSmallFiles(enabled: Boolean)

    /** Opens the platform folder chooser (SAF tree on Android). */
    fun pickSaveLocation()

    /**
     * "Clear partial files": the app layer stops parked and reconnecting transfers first (they would otherwise keep
     * writing), then deletes the partials and manifests (core/data `ResumeDataCleaner.clearPartials`).
     */
    suspend fun clearPartialFiles(): ClearPartialsResult

    fun setNickname(nickname: String)

    fun pickAvatar()

    fun removeAvatar()

    fun setLanguage(language: AppLanguage)

    fun setCrashReports(enabled: Boolean)

    fun setHaptics(enabled: Boolean)
}

/** The user's local calendar, for History day groups (the app passes its time zone's rules). */
interface DayCalendar {
    /** Days since 1970-01-01 of [unixMillis] in local time. */
    fun epochDayOf(unixMillis: Long): Long

    /** Minutes since local midnight of [unixMillis], `0..1439`. */
    fun minuteOfDay(unixMillis: Long): Int

    companion object {
        /** A fixed UTC offset (tests and previews). */
        fun fixedOffset(offsetMinutes: Int): DayCalendar =
            object : DayCalendar {
                private val offsetMillis = offsetMinutes * 60_000L

                override fun epochDayOf(unixMillis: Long): Long = (unixMillis + offsetMillis).floorDiv(DAY_MILLIS)

                override fun minuteOfDay(unixMillis: Long): Int = ((unixMillis + offsetMillis).mod(DAY_MILLIS) / 60_000L).toInt()
            }

        /**
         * The device's time zone, read on every call so a zone change (travel, daylight saving) regroups History at
         * once. Both apps run on the JVM, so `java.time` supplies the zone rules.
         */
        val System: DayCalendar =
            object : DayCalendar {
                private fun local(unixMillis: Long) = java.time.Instant.ofEpochMilli(unixMillis).atZone(java.time.ZoneId.systemDefault())

                override fun epochDayOf(unixMillis: Long): Long = local(unixMillis).toLocalDate().toEpochDay()

                override fun minuteOfDay(unixMillis: Long): Int = local(unixMillis).let { it.hour * 60 + it.minute }
            }

        const val DAY_MILLIS: Long = 86_400_000L
    }
}

/** Live tab (F‑G1): running transfers from the engine's snapshots, with no added latency (≤ 250 ms). */
class LivePresenter(
    scope: CoroutineScope,
    transfers: Flow<List<TransferSnapshot>>,
    private val actions: LiveActions,
) {
    val state: StateFlow<List<LiveRowUi>> =
        transfers
            .map { list -> list.filter { !it.stage.isFinal }.map(::row) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun pause(id: String) = actions.pause(id)

    fun resume(id: String) = actions.resume(id)

    fun cancel(id: String) = actions.cancel(id)

    fun addFiles(id: String) = actions.addFiles(id)

    private fun row(t: TransferSnapshot) =
        LiveRowUi(
            transferId = t.id,
            peerName = t.peerName,
            peerPlatform = t.peerPlatform,
            direction = t.direction,
            stage = t.stage,
            summary = t.summary,
            bytesDone = t.bytesDone,
            bytesTotal = t.bytesTotal,
            fraction = t.fraction,
            bytesPerSecond = t.bytesPerSecond,
            etaMillis = t.etaMillis,
            badge = t.badge,
            hint = t.hint,
            paused = t.paused,
            canAddFiles = t.canAddFiles,
        )
}

/** History tab state: day groups plus the open detail sheet and the clear confirmation. */
data class HistoryUi(
    val days: List<HistoryDayUi> = emptyList(),
    val detail: HistoryDetailUi? = null,
    val confirmClear: Boolean = false,
) {
    val isEmpty: Boolean get() = days.isEmpty()
}

/**
 * History tab (F‑G2, design §6): transfers newest first, grouped by local day with "Today" and "Yesterday", a detail
 * sheet with per-file open and "Send again", and "Clear history" behind a confirmation. Day labels are re-evaluated
 * every minute, so "Today" becomes "Yesterday" after midnight.
 */
class HistoryPresenter(
    private val scope: CoroutineScope,
    private val source: HistorySource,
    private val calendar: DayCalendar,
    private val wallClock: WallClock,
) {
    private val detail = MutableStateFlow<HistoryDetailUi?>(null)
    private val confirmClear = MutableStateFlow(false)
    private var days: List<HistoryDayUi> = emptyList()

    private val minutes: Flow<Long> =
        flow {
            while (true) {
                val now = wallClock.nowMillis()
                emit(now)
                delay(MINUTE_MILLIS - now.mod(MINUTE_MILLIS))
            }
        }

    val state: StateFlow<HistoryUi> =
        combine(source.history, minutes, detail, confirmClear) { entries, now, d, c ->
            days = group(entries, calendar.epochDayOf(now))
            HistoryUi(days, d, c)
        }.stateIn(scope, SharingStarted.Eagerly, HistoryUi())

    /** Opens the detail sheet of [transferId] and loads its files. */
    fun openDetail(transferId: String) {
        val (day, row) = findRow(transferId) ?: return
        detail.value = HistoryDetailUi(row, day.label, files = null)
        scope.launch {
            val files =
                try {
                    source.files(transferId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            detail.update { d -> if (d?.row?.entry?.id == transferId) d.copy(files = files) else d }
        }
    }

    fun closeDetail() {
        detail.value = null
    }

    fun openFile(
        transferId: String,
        fileId: String,
    ) = source.openFile(transferId, fileId)

    fun resend(transferId: String) {
        detail.value = null
        source.resend(transferId)
    }

    fun askClear() {
        confirmClear.value = true
    }

    fun dismissClear() {
        confirmClear.value = false
    }

    fun confirmClear() {
        confirmClear.value = false
        detail.value = null
        source.clear()
    }

    private fun findRow(id: String): Pair<HistoryDayUi, HistoryRowUi>? {
        for (day in days) {
            val row = day.rows.firstOrNull { it.entry.id == id } ?: continue
            return day to row
        }
        return null
    }

    private fun group(
        entries: List<HistoryEntry>,
        today: Long,
    ): List<HistoryDayUi> {
        val out = ArrayList<HistoryDayUi>()
        var openDay: Long? = null
        var rows = ArrayList<HistoryRowUi>()
        for (e in entries) {
            val day = calendar.epochDayOf(e.startedAtMillis)
            if (day != openDay) {
                openDay?.let { out += HistoryDayUi(it, labelOf(it, today), rows) }
                openDay = day
                rows = ArrayList()
            }
            val minute = calendar.minuteOfDay(e.startedAtMillis)
            rows += HistoryRowUi(e, minute / 60, minute % 60)
        }
        openDay?.let { out += HistoryDayUi(it, labelOf(it, today), rows) }
        return out
    }

    private fun labelOf(
        day: Long,
        today: Long,
    ): DayLabel =
        when (day) {
            today -> DayLabel.Today
            today - 1 -> DayLabel.Yesterday
            else -> DayLabel.Date(DayDate.ofEpochDay(day))
        }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
    }
}

/** Devices tab state: the cards plus the rename and forget sheets. */
data class DevicesUi(
    val devices: List<DeviceCardUi> = emptyList(),
    val renaming: DeviceCardUi? = null,
    val forgetting: DeviceCardUi? = null,
)

/**
 * Devices tab (F‑G3, design §6): trusted device cards with rename, the auto-accept switch and Forget (after a
 * confirmation). "Seen 5 min ago" is refreshed every minute.
 */
class DevicesPresenter(
    scope: CoroutineScope,
    private val source: DeviceSource,
    private val wallClock: WallClock,
) {
    private val renaming = MutableStateFlow<String?>(null)
    private val forgetting = MutableStateFlow<String?>(null)

    private val minutes: Flow<Long> =
        flow {
            while (true) {
                val now = wallClock.nowMillis()
                emit(now)
                delay(60_000L - now.mod(60_000L))
            }
        }

    val state: StateFlow<DevicesUi> =
        combine(source.trusted, minutes, renaming, forgetting) { list, now, r, f ->
            val cards =
                list.map {
                    DeviceCardUi(
                        id = it.id,
                        name = it.name,
                        initials = Avatars.initials(it.name),
                        avatarHash = Avatars.hash(it.id),
                        platform = it.platform,
                        lastSeen = LastSeen.of(it.lastSeenMillis, now),
                        autoAccept = it.autoAccept,
                    )
                }
            DevicesUi(cards, cards.firstOrNull { it.id == r }, cards.firstOrNull { it.id == f })
        }.stateIn(scope, SharingStarted.Eagerly, DevicesUi())

    fun setAutoAccept(
        id: String,
        enabled: Boolean,
    ) = source.setAutoAccept(id, enabled)

    fun startRename(id: String) {
        renaming.value = id
    }

    /** Saves a new name if it has a visible character (core/discovery `Nicknames`); returns false otherwise. */
    fun saveRename(name: String): Boolean {
        val id = renaming.value ?: return false
        val clean = Nicknames.normalize(name) ?: return false
        renaming.value = null
        source.rename(id, clean.text)
        return true
    }

    fun cancelRename() {
        renaming.value = null
    }

    fun askForget(id: String) {
        forgetting.value = id
    }

    fun confirmForget() {
        val id = forgetting.value ?: return
        forgetting.value = null
        source.forget(id)
    }

    fun cancelForget() {
        forgetting.value = null
    }
}

/** Stats tab (F‑G4): the figures as the data layer computes them. */
class StatsPresenter(
    scope: CoroutineScope,
    private val source: StatsSource,
) {
    val state: StateFlow<StatsSnapshot> = source.stats.stateIn(scope, SharingStarted.Eagerly, StatsSnapshot.EMPTY)

    fun share() = source.shareStatsCard()
}

/**
 * Settings tab (F‑G5): each change goes straight to [SettingsSource] (no Save button, no restart). "Clear partial
 * files" asks first, then shows progress and the space freed.
 */
class SettingsPresenter(
    private val scope: CoroutineScope,
    private val source: SettingsSource,
    initial: SettingsValues,
) {
    private val clearing = MutableStateFlow(false)
    private val lastClear = MutableStateFlow<ClearPartialsResult?>(null)
    private val confirmingClear = MutableStateFlow(false)

    val state: StateFlow<SettingsUi> =
        combine(source.settings, clearing, lastClear) { v, c, l -> SettingsUi(v, c, l) }
            .stateIn(scope, SharingStarted.Eagerly, SettingsUi(initial))

    /** Whether the "Clear partial files?" confirmation is up. */
    val confirmClear: StateFlow<Boolean> = confirmingClear.asStateFlow()

    fun setVisibility(mode: Visibility) = source.setVisibility(mode)

    fun setPrefer5Ghz(enabled: Boolean) = source.setPrefer5Ghz(enabled)

    fun setKeepScreenAwake(enabled: Boolean) = source.setKeepScreenAwake(enabled)

    fun setBundleSmallFiles(enabled: Boolean) = source.setBundleSmallFiles(enabled)

    fun pickSaveLocation() = source.pickSaveLocation()

    fun pickAvatar() = source.pickAvatar()

    fun removeAvatar() = source.removeAvatar()

    fun setLanguage(language: AppLanguage) = source.setLanguage(language)

    fun setCrashReports(enabled: Boolean) = source.setCrashReports(enabled)

    fun setHaptics(enabled: Boolean) = source.setHaptics(enabled)

    /** Saves a nickname with a visible character (sanitised like every nickname); returns false otherwise. */
    fun setNickname(nickname: String): Boolean {
        val clean = Nicknames.normalize(nickname) ?: return false
        source.setNickname(clean.text)
        return true
    }

    fun askClearPartials() {
        confirmingClear.value = true
    }

    fun dismissClearPartials() {
        confirmingClear.value = false
    }

    fun confirmClearPartials() {
        confirmingClear.value = false
        if (clearing.value) return
        clearing.value = true
        lastClear.value = null
        scope.launch {
            try {
                lastClear.value = source.clearPartialFiles()
            } finally {
                clearing.value = false
            }
        }
    }
}

/**
 * The dashboard (design §6): five tabs over the presenters above. The selected tab is kept here so returning to the
 * dashboard shows the tab the user left.
 */
class DashboardPresenter(
    val live: LivePresenter,
    val history: HistoryPresenter,
    val devices: DevicesPresenter,
    val stats: StatsPresenter,
    val settings: SettingsPresenter,
) {
    private val tabState = MutableStateFlow(DashboardTab.LIVE)
    val tab: StateFlow<DashboardTab> = tabState.asStateFlow()

    fun selectTab(tab: DashboardTab) {
        tabState.value = tab
    }
}
