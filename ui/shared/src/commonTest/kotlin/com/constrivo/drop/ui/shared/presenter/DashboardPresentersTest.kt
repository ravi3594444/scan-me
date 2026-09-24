package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.ui.shared.Fixtures
import com.constrivo.drop.ui.shared.VirtualClocks
import com.constrivo.drop.ui.shared.fake.InMemoryDrop
import com.constrivo.drop.ui.shared.model.AppLanguage
import com.constrivo.drop.ui.shared.model.ClearPartialsResult
import com.constrivo.drop.ui.shared.model.DashboardTab
import com.constrivo.drop.ui.shared.model.DayDate
import com.constrivo.drop.ui.shared.model.DayLabel
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.HistoryEntry
import com.constrivo.drop.ui.shared.model.HistoryFile
import com.constrivo.drop.ui.shared.model.HistoryStatus
import com.constrivo.drop.ui.shared.model.ItemSummary
import com.constrivo.drop.ui.shared.model.LastSeen
import com.constrivo.drop.ui.shared.model.StatsSnapshot
import com.constrivo.drop.ui.shared.model.SummaryKind
import com.constrivo.drop.ui.shared.model.TransferStage
import com.constrivo.drop.ui.shared.model.TrustedDeviceEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardPresentersTest {
    private val day = DayCalendar.DAY_MILLIS

    private fun entry(
        id: String,
        startedAt: Long,
    ) = HistoryEntry(
        id = id,
        peerName = "Rohan",
        peerPlatform = DevicePlatform.PHONE,
        direction = Direction.SEND,
        status = HistoryStatus.DONE,
        startedAtMillis = startedAt,
        durationMillis = 12_000,
        bytesTotal = 48_000_000,
        bytesDone = 48_000_000,
        avgBytesPerSecond = 4_000_000,
        summary = ItemSummary(12, SummaryKind.PHOTOS),
        badge = null,
    )

    @Test
    fun fG1_liveTabReflectsTheEngineWithin250Milliseconds() =
        runTest {
            val fake = InMemoryDrop()
            val live = LivePresenter(backgroundScope, fake.transfers, fake)
            runCurrent()
            fake.transfers.value = listOf(Fixtures.transfer("a", "t:x"), Fixtures.transfer("done", "t:y", stage = TransferStage.DONE))
            advanceTimeBy(250)
            assertEquals(listOf("a"), live.state.value.map { it.transferId }, "running transfers only, within 250 ms")
            val row = live.state.value.single()
            assertEquals(0.4f, row.fraction, 1e-6f)
            assertEquals("badge.p2p_5", row.badge?.key?.key)
            live.pause("a")
            live.resume("a")
            live.cancel("a")
            live.addFiles("a")
            assertEquals(listOf("pause:a", "resume:a", "cancel:a", "add:a"), fake.calls)
        }

    @Test
    fun fG2_historyIsGroupedByLocalDayWithTodayAndYesterday() =
        runTest {
            val fake = InMemoryDrop()
            val clocks = VirtualClocks(this)
            // IST, +05:30: the calendar decides the day, not UTC.
            val calendar = DayCalendar.fixedOffset(330)
            val now = clocks.nowMillis()
            fake.historyEntries.value =
                listOf(
                    entry("h1", now - 60_000),
                    entry("h2", now - day),
                    entry("h3", now - 9 * day),
                    entry("h4", now - 9 * day - 3_600_000),
                )
            val history = HistoryPresenter(backgroundScope, fake, calendar, clocks)
            runCurrent()
            val days = history.state.value.days
            assertEquals(listOf(DayLabel.Today, DayLabel.Yesterday), days.take(2).map { it.label })
            assertEquals(listOf("h1"), days[0].rows.map { it.entry.id })
            val older = days.drop(2)
            assertTrue(older.all { it.label is DayLabel.Date })
            assertEquals(4, days.sumOf { it.rows.size }, "every row appears once")
            val today = calendar.epochDayOf(now)
            assertEquals(today, days[0].epochDay)
            val minute = calendar.minuteOfDay(now - 60_000)
            assertEquals(minute / 60, days[0].rows[0].hour)
            assertEquals(minute % 60, days[0].rows[0].minute)
            // After midnight "Today" becomes "Yesterday" without any write.
            val untilMidnight = day - (calendar.minuteOfDay(now) * 60_000L) - (now % 60_000)
            advanceTimeBy(untilMidnight + 60_000)
            runCurrent()
            assertEquals(DayLabel.Yesterday, history.state.value.days.first().label)
        }

    @Test
    fun fG2_detailLoadsFilesAndClearNeedsConfirmation() =
        runTest {
            val fake = InMemoryDrop()
            val clocks = VirtualClocks(this)
            fake.historyEntries.value = listOf(entry("h1", clocks.nowMillis()))
            fake.historyFiles.value = mapOf("h1" to listOf(HistoryFile("f1", "a.jpg", 1, FileKind.IMAGE, openable = true)))
            val history = HistoryPresenter(backgroundScope, fake, DayCalendar.fixedOffset(0), clocks)
            runCurrent()
            history.openDetail("h1")
            runCurrent()
            val detail = assertNotNull(history.state.value.detail)
            assertEquals(1, detail.files?.size)
            history.openFile("h1", "f1")
            history.resend("h1")
            runCurrent()
            assertNull(history.state.value.detail)
            history.askClear()
            runCurrent()
            assertTrue(history.state.value.confirmClear)
            history.dismissClear()
            runCurrent()
            assertFalse(history.state.value.confirmClear)
            assertEquals(1, history.state.value.days.size, "nothing cleared without confirmation")
            history.askClear()
            history.confirmClear()
            runCurrent()
            assertTrue(history.state.value.isEmpty)
            assertEquals(listOf("openFile:h1:f1", "resend:h1", "clearHistory"), fake.calls)
        }

    @Test
    fun fG3_devicesRenameValidatesForgetConfirmsAndLastSeenTicks() =
        runTest {
            val fake = InMemoryDrop()
            val clocks = VirtualClocks(this)
            fake.trustedDevices.value =
                listOf(TrustedDeviceEntry("d1", "Rohan's Pixel", DevicePlatform.PHONE, clocks.nowMillis(), autoAccept = false))
            val devices = DevicesPresenter(backgroundScope, fake, clocks)
            runCurrent()
            assertEquals(LastSeen.JustNow, devices.state.value.devices.single().lastSeen)
            // The label refreshes on each wall-clock minute; the fixed origin is 20 s into a minute.
            advanceTimeBy(5 * 60_000L + 40_000)
            runCurrent()
            assertEquals(LastSeen.MinutesAgo(5), devices.state.value.devices.single().lastSeen)

            devices.startRename("d1")
            runCurrent()
            assertEquals("d1", devices.state.value.renaming?.id)
            assertFalse(devices.saveRename("\u200B  "), "an invisible name is refused")
            assertTrue(devices.saveRename("  Rohan's phone "))
            runCurrent()
            assertEquals("Rohan's phone", devices.state.value.devices.single().name)
            assertNull(devices.state.value.renaming)

            devices.setAutoAccept("d1", true)
            runCurrent()
            assertTrue(devices.state.value.devices.single().autoAccept)

            devices.askForget("d1")
            devices.cancelForget()
            devices.askForget("d1")
            devices.confirmForget()
            runCurrent()
            assertTrue(devices.state.value.devices.isEmpty())
            assertEquals(listOf("rename:d1:Rohan's phone", "autoAccept:d1:true", "forget:d1"), fake.calls)
        }

    @Test
    fun fG4_statsPassThroughAndShare() =
        runTest {
            val fake = InMemoryDrop()
            val stats = StatsPresenter(backgroundScope, fake)
            runCurrent()
            assertEquals(StatsSnapshot.EMPTY, stats.state.value)
            val snapshot = StatsSnapshot(1_000, 2_000, 3, 1.5, List(12) { it })
            fake.statsValues.value = snapshot
            runCurrent()
            assertEquals(snapshot, stats.state.value)
            stats.share()
            assertEquals(listOf("shareStats"), fake.calls)
        }

    @Test
    fun fG5_everySettingAppliesAtOnceAndClearPartialsAsksFirst() =
        runTest {
            val fake = InMemoryDrop()
            fake.clearResult = ClearPartialsResult(3, 12_000_000)
            val settings = SettingsPresenter(backgroundScope, fake, fake.settingsState.value)
            runCurrent()
            settings.setVisibility(Visibility.EVERYONE)
            settings.setPrefer5Ghz(false)
            settings.setKeepScreenAwake(true)
            settings.setBundleSmallFiles(false)
            settings.setLanguage(AppLanguage.HINDI)
            settings.setCrashReports(true)
            settings.setHaptics(false)
            assertFalse(settings.setNickname("   "))
            assertTrue(settings.setNickname(" Asha "))
            runCurrent()
            val v = settings.state.value.values
            assertEquals(Visibility.EVERYONE, v.visibility)
            assertFalse(v.prefer5Ghz)
            assertTrue(v.keepScreenAwake)
            assertFalse(v.bundleSmallFiles)
            assertEquals(AppLanguage.HINDI, v.language)
            assertTrue(v.crashReports)
            assertFalse(v.haptics)
            assertEquals("Asha", v.nickname)
            assertEquals(Visibility.EVERYONE, fake.visibility.value.mode, "visibility reaches the radar too")

            settings.askClearPartials()
            runCurrent()
            assertTrue(settings.confirmClear.value)
            assertFalse(fake.calls.contains("clearPartials"))
            settings.confirmClearPartials()
            runCurrent()
            assertEquals(ClearPartialsResult(3, 12_000_000), settings.state.value.lastClear)
            assertFalse(settings.state.value.clearingPartials)
            assertTrue(fake.calls.contains("clearPartials"))
        }

    @Test
    fun clearPartialsShowsProgressUntilTheAppLayerFinishes() =
        runTest {
            val gate = CompletableDeferred<ClearPartialsResult>()
            val fake = InMemoryDrop()
            val source =
                object : SettingsSource by fake {
                    override suspend fun clearPartialFiles(): ClearPartialsResult = gate.await()
                }
            val settings = SettingsPresenter(backgroundScope, source, fake.settingsState.value)
            settings.confirmClearPartials()
            runCurrent()
            assertTrue(settings.state.value.clearingPartials)
            settings.confirmClearPartials() // a second tap while running does nothing
            gate.complete(ClearPartialsResult(1, 5))
            runCurrent()
            assertFalse(settings.state.value.clearingPartials)
            assertEquals(5, settings.state.value.lastClear?.bytesFreed)
        }

    @Test
    fun dashboardRemembersTheSelectedTab() =
        runTest {
            val fake = InMemoryDrop()
            val clocks = VirtualClocks(this)
            val scope = backgroundScope
            val d =
                DashboardPresenter(
                    LivePresenter(scope, fake.transfers, fake),
                    HistoryPresenter(scope, fake, DayCalendar.fixedOffset(0), clocks),
                    DevicesPresenter(scope, fake, clocks),
                    StatsPresenter(scope, fake),
                    SettingsPresenter(scope, fake, fake.settingsState.value),
                )
            assertEquals(DashboardTab.LIVE, d.tab.value)
            d.selectTab(DashboardTab.STATS)
            assertEquals(DashboardTab.STATS, d.tab.value)
        }

    @Test
    fun dayDateMatchesTheCalendar() {
        assertEquals(DayDate(1970, 1, 1), DayDate.ofEpochDay(0))
        assertEquals(DayDate(2026, 9, 23), DayDate.ofEpochDay(20_719))
        assertEquals(DayDate(2000, 2, 29), DayDate.ofEpochDay(11_016))
        assertEquals(DayDate(1969, 12, 31), DayDate.ofEpochDay(-1))
    }
}
