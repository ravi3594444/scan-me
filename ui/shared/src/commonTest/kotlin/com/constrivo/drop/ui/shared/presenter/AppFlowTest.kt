package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.ui.shared.Fixtures
import com.constrivo.drop.ui.shared.VirtualClocks
import com.constrivo.drop.ui.shared.fake.InMemoryDrop
import com.constrivo.drop.ui.shared.model.AppLanguage
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.DashboardTab
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.DropPermission
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.HistoryEntry
import com.constrivo.drop.ui.shared.model.HistoryFile
import com.constrivo.drop.ui.shared.model.HistoryStatus
import com.constrivo.drop.ui.shared.model.InstallerWarningUi
import com.constrivo.drop.ui.shared.model.ItemSummary
import com.constrivo.drop.ui.shared.model.OemBrand
import com.constrivo.drop.ui.shared.model.OnboardingStep
import com.constrivo.drop.ui.shared.model.PermissionMoment
import com.constrivo.drop.ui.shared.model.PermissionStatus
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.PickerTarget
import com.constrivo.drop.ui.shared.model.RadarNotice
import com.constrivo.drop.ui.shared.model.ReceivedFile
import com.constrivo.drop.ui.shared.model.ScanStatus
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.model.SummaryKind
import com.constrivo.drop.ui.shared.model.TransferStage
import com.constrivo.drop.ui.shared.model.VisibilityUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scriptable [PermissionController]: statuses per permission and what the system dialog answers. */
private class FakePermissions : PermissionController {
    val status = HashMap<DropPermission, PermissionStatus>()
    val answers = HashMap<DropPermission, PermissionStatus>()
    val requested = ArrayList<DropPermission>()
    var settingsOpened = 0

    override fun status(permission: DropPermission) = status[permission] ?: PermissionStatus.GRANTED

    override suspend fun request(permission: DropPermission): PermissionStatus {
        requested += permission
        return (answers[permission] ?: PermissionStatus.GRANTED).also { status[permission] = it }
    }

    override fun openAppSettings() {
        settingsOpened++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingPresenterTest {
    private class Recorder : OnboardingActions {
        val calls = ArrayList<String>()

        override fun finish(nickname: String) {
            calls += "finish:$nickname"
        }

        override fun pickAvatar() {
            calls += "pickAvatar"
        }

        override fun openBrandSettings(brand: OemBrand) {
            calls += "brand:$brand"
        }
    }

    @Test
    fun fI3_nicknameIsPrefilledValidatedAndSanitised() {
        val r = Recorder()
        val p = OnboardingPresenter("Asha's Galaxy S24", brand = null, actions = r)
        assertTrue(p.state.value.nicknameValid)
        p.onNicknameChanged("\u200B \u2066 ")
        assertFalse(p.state.value.nicknameValid, "invisible text is not a name")
        p.onStart()
        assertTrue(r.calls.isEmpty())
        p.onNicknameChanged("  Asha  ")
        p.pickAvatar()
        p.onStart()
        assertEquals(listOf("pickAvatar", "finish:Asha"), r.calls)
        assertTrue(p.finished.value)
        p.onStart()
        assertEquals(2, r.calls.size, "finishes once")
    }

    @Test
    fun fI2_brandStepOnlyOnListedOemsAndSkippable() {
        val r = Recorder()
        val p = OnboardingPresenter("Redmi Note", OemBrand.of("Xiaomi", "Redmi"), r)
        p.onStart()
        assertEquals(OnboardingStep.BRAND, p.state.value.step)
        assertTrue(r.calls.isEmpty())
        p.onSkipBrand()
        assertEquals(listOf("finish:Redmi Note"), r.calls)

        val r2 = Recorder()
        val q = OnboardingPresenter("Galaxy", OemBrand.SAMSUNG, r2)
        q.onStart()
        q.onOpenBrandSettings()
        assertEquals(listOf("brand:SAMSUNG", "finish:Galaxy"), r2.calls)
        assertNull(OemBrand.of("Google", "google"))
        assertEquals(OemBrand.OPPO, OemBrand.of(null, "OnePlus"))
        assertEquals(OemBrand.VIVO, OemBrand.of("iQOO"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionGateTest {
    @Test
    fun fI1_grantedNeedsNoPrompt() =
        runTest {
            val gate = PermissionGate(FakePermissions())
            assertTrue(gate.ensure(DropPermission.NEARBY))
            assertNull(gate.state.value)
        }

    @Test
    fun fI1_explainerThenSystemDialog() =
        runTest {
            val perms = FakePermissions()
            perms.status[DropPermission.CAMERA] = PermissionStatus.DENIED
            val gate = PermissionGate(perms)
            val result = async { gate.ensure(DropPermission.CAMERA) }
            runCurrent()
            assertEquals(DropPermission.CAMERA, gate.state.value?.permission)
            assertFalse(gate.state.value!!.blocked)
            assertTrue(perms.requested.isEmpty(), "nothing is asked before the explainer is answered")
            gate.onContinue()
            assertTrue(result.await())
            assertEquals(listOf(DropPermission.CAMERA), perms.requested)
            assertNull(gate.state.value)
        }

    @Test
    fun fI1_notNowGivesUpWithoutTheSystemDialog() =
        runTest {
            val perms = FakePermissions()
            perms.status[DropPermission.MEDIA] = PermissionStatus.DENIED
            val gate = PermissionGate(perms)
            val result = async { gate.ensure(DropPermission.MEDIA) }
            runCurrent()
            gate.onDismiss()
            assertFalse(result.await())
            assertTrue(perms.requested.isEmpty())
        }

    @Test
    fun fI1_permanentDenialShowsTheSettingsRecovery() =
        runTest {
            val perms = FakePermissions()
            perms.status[DropPermission.NEARBY] = PermissionStatus.DENIED
            perms.answers[DropPermission.NEARBY] = PermissionStatus.BLOCKED
            val gate = PermissionGate(perms)
            val result = async { gate.ensure(DropPermission.NEARBY) }
            runCurrent()
            gate.onContinue()
            runCurrent()
            assertTrue(gate.state.value!!.blocked, "after 'don't ask again' the sheet offers Settings")
            gate.onContinue()
            assertFalse(result.await())
            assertEquals(1, perms.settingsOpened)

            // Already blocked: straight to the recovery wording, no system dialog.
            val again = async { gate.ensure(DropPermission.NEARBY) }
            runCurrent()
            assertTrue(gate.state.value!!.blocked)
            gate.onDismiss()
            assertFalse(again.await())
            assertEquals(1, perms.requested.size)
        }

    @Test
    fun fI1_theStatusIsReadAgainOnceTheExplainerIsAnswered() =
        runTest {
            // Android can tell "don't ask again" only with an activity showing: first DENIED, BLOCKED once it can tell.
            val perms = FakePermissions()
            perms.status[DropPermission.NEARBY] = PermissionStatus.DENIED
            val gate = PermissionGate(perms)
            val result = async { gate.ensure(DropPermission.NEARBY) }
            runCurrent()
            assertFalse(gate.state.value!!.blocked)
            perms.status[DropPermission.NEARBY] = PermissionStatus.BLOCKED
            gate.onContinue()
            runCurrent()
            assertTrue(gate.state.value!!.blocked, "the recovery wording, not a system dialog that would not show")
            gate.onContinue()
            assertFalse(result.await())
            assertTrue(perms.requested.isEmpty())
            assertEquals(1, perms.settingsOpened)

            // Granted in Settings meanwhile: no dialog at all.
            perms.status[DropPermission.CAMERA] = PermissionStatus.DENIED
            val camera = async { gate.ensure(DropPermission.CAMERA) }
            runCurrent()
            perms.status[DropPermission.CAMERA] = PermissionStatus.GRANTED
            gate.onContinue()
            assertTrue(camera.await())
            assertTrue(perms.requested.isEmpty())
        }

    @Test
    fun architectureSection11_momentsMatchTheMatrix() {
        assertEquals(PermissionMoment.RADAR_OPEN, DropPermission.NEARBY.askedAt)
        assertEquals(PermissionMoment.FIRST_SEND, DropPermission.MEDIA.askedAt)
        assertEquals(PermissionMoment.FIRST_SEND, DropPermission.LOCATION_FOR_WIFI_DIRECT.askedAt)
        assertEquals(PermissionMoment.FIRST_TRANSFER, DropPermission.NOTIFICATIONS.askedAt)
        assertEquals(PermissionMoment.FIRST_SCAN, DropPermission.CAMERA.askedAt)
        assertEquals(PermissionMoment.ONBOARDING, DropPermission.BATTERY.askedAt)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DropAppControllerTest {
    private class Setup(
        scope: TestScope,
        onboarding: Boolean = false,
        brand: OemBrand? = null,
        configure: (FakePermissions) -> Unit = {},
    ) {
        val clocks = VirtualClocks(scope)
        val fake = InMemoryDrop(SelfProfile("Asha", "self"), wallClock = clocks)
        val perms = FakePermissions().also(configure)
        val controller =
            DropAppController(
                scope.backgroundScope,
                DropDependencies.inMemory(
                    fake = fake,
                    wallClock = clocks,
                    monotonicClock = clocks,
                    permissions = perms,
                    onboarding = if (onboarding) OnboardingConfig("Asha's phone", brand, fake) else null,
                    computeContext = EmptyCoroutineContext,
                ),
            )
    }

    private val photos = AttachedFiles(List(3) { PickedItem("u$it", "IMG_$it.jpg", 4_000_000, FileKind.IMAGE) })

    @Test
    fun designSection2_bubbleTapOpensThePickerAndSendGoesToTheEngine() =
        runTest {
            val s = Setup(this)
            s.fake.devices.value = listOf(Fixtures.device("t:rohan", "Rohan", Ring.INNER, trusted = true))
            s.fake.mediaItems.value = listOf(PickedItem("p1", "a.jpg", 1_000, FileKind.IMAGE))
            runCurrent()
            s.controller.onBubbleTap("t:rohan")
            runCurrent()
            assertEquals(RadarSheet.PICKER, s.controller.radarSheet.value)
            assertEquals("Rohan", s.controller.picker.state.value?.targetName)
            assertTrue(s.controller.canGoBack.value)
            s.perms.status[DropPermission.NOTIFICATIONS] = PermissionStatus.DENIED
            s.controller.picker.toggle("p1")
            s.controller.sendPicked()
            runCurrent()
            assertNull(s.controller.radarSheet.value)
            assertEquals(listOf("send:t:rohan:1"), s.fake.calls)
            assertEquals(
                DropPermission.NOTIFICATIONS,
                s.controller.permissions.state.value?.permission,
                "notifications on the first transfer",
            )
        }

    @Test
    fun fI1_permissionsAreAskedJustInTime() =
        runTest {
            val s = Setup(this)
            DropPermission.entries.forEach { s.perms.status[it] = PermissionStatus.DENIED }
            val c = s.controller
            runCurrent()
            // Radar open → Nearby.
            assertEquals(DropPermission.NEARBY, c.permissions.state.value?.permission)
            c.permissions.onContinue()
            runCurrent()
            assertEquals(listOf(DropPermission.NEARBY), s.perms.requested)
            // Scanner → camera.
            c.openScanner()
            runCurrent()
            assertEquals(DropPermission.CAMERA, c.permissions.state.value?.permission)
            c.permissions.onDismiss()
            runCurrent()
            assertEquals(ScanStatus.NO_CAMERA_PERMISSION, c.scanStatus.value)
            assertEquals(Screen.SCAN, c.screen.value)
            assertTrue(c.back())
            assertEquals(Screen.RADAR, c.screen.value)
        }

    @Test
    fun designSection43_shareIntentAttachesAndDashboardClearsTheTray() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            c.openDashboard(DashboardTab.HISTORY)
            assertEquals(Screen.DASHBOARD, c.screen.value)
            assertEquals(DashboardTab.HISTORY, c.dashboard.tab.value)
            c.onShareIntent(AttachedFiles(listOf(PickedItem("u", "a.jpg", 10, FileKind.IMAGE))))
            runCurrent()
            assertEquals(Screen.RADAR, c.screen.value, "sharing into the app opens the radar")
            assertNotNull(c.radar.state.value.attachment)
            assertFalse(c.back(), "nothing to go back from on the radar")
        }

    @Test
    fun designSection44_scannedCodeReturnsToTheRadarWithTheDeviceSelected() =
        runTest {
            val s = Setup(this)
            s.fake.devices.value = listOf(Fixtures.device("e:meera", "Meera"))
            runCurrent()
            s.controller.openScanner()
            runCurrent()
            s.controller.onCodeScanned("e:meera")
            runCurrent()
            // The scanner stays up in its success state for a moment: the frame turns green and the haptic plays.
            assertEquals(Screen.SCAN, s.controller.screen.value)
            assertEquals(ScanStatus.SUCCESS, s.controller.scanStatus.value)
            s.controller.onCodeScanned("e:meera") // the camera keeps seeing the code: once is enough
            advanceTimeBy(DropAppController.SCAN_SUCCESS_HOLD_MILLIS + 1)
            runCurrent()
            assertEquals(Screen.RADAR, s.controller.screen.value)
            assertEquals(RadarSheet.PICKER, s.controller.radarSheet.value)
            assertEquals("e:meera", s.controller.radar.state.value.selectedKey)
            assertTrue(s.controller.back())
            assertNull(s.controller.radarSheet.value)
            runCurrent()
            assertNull(s.controller.radar.state.value.selectedKey)
        }

    @Test
    fun designSection44_backDuringTheSuccessStateCancelsTheReturn() =
        runTest {
            val s = Setup(this)
            s.fake.devices.value = listOf(Fixtures.device("e:meera", "Meera"))
            s.controller.openScanner()
            runCurrent()
            s.controller.onCodeScanned("e:meera")
            assertTrue(s.controller.back())
            advanceTimeBy(DropAppController.SCAN_SUCCESS_HOLD_MILLIS + 1)
            runCurrent()
            assertNull(s.controller.radarSheet.value, "the picker does not open after the user left the scanner")
        }

    @Test
    fun designSection43_aShareClosesTheOpenSheetProperly() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            s.fake.devices.value = listOf(Fixtures.device("e:meera", "Meera"))
            runCurrent()
            c.openSheet(RadarSheet.SHOW_QR)
            runCurrent()
            assertTrue(c.showQr.isOpen)
            c.onShareIntent(photos)
            runCurrent()
            assertFalse(c.showQr.isOpen, "\"Show my code\" stops refreshing")
            assertFalse(c.showQr.isRefreshing)
            assertNull(c.showQr.state.value)

            c.radar.clearAttachment()
            c.onBubbleTap("e:meera")
            runCurrent()
            assertNotNull(c.picker.state.value)
            assertEquals("e:meera", c.radar.state.value.selectedKey)
            c.onShareIntent(photos)
            runCurrent()
            assertNull(c.radarSheet.value)
            assertNull(c.picker.state.value, "the picker closes, so the gallery is no longer read")
            assertNull(c.radar.state.value.selectedKey, "the bubble is lowered")
        }

    @Test
    fun designSection43_openingASheetClosesTheOneBefore() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            c.openSheet(RadarSheet.SHOW_QR)
            c.openSheet(RadarSheet.VISIBILITY)
            runCurrent()
            assertFalse(c.showQr.isOpen)
            assertEquals(RadarSheet.VISIBILITY, c.radarSheet.value)
        }

    @Test
    fun fD5_receivedInstallersAskBeforeOpening() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            runCurrent()
            s.fake.receivedFiles.emit(ReceivedFile("apk", "tx", "WhatsApp.apk", FileKind.OTHER, senderName = "Dev"))
            s.fake.receivedFiles.emit(ReceivedFile("jpg", "tx", "IMG_1.jpg", FileKind.IMAGE))
            advanceTimeBy(RadarPresenter.TRAY_BATCH_MILLIS + 1)
            runCurrent()
            c.platformOpenReceived("jpg")
            assertEquals(listOf("openReceived:jpg"), s.fake.calls, "an ordinary file opens at once")
            c.platformOpenReceived("apk")
            runCurrent()
            assertEquals(InstallerWarningUi("WhatsApp.apk", "Dev"), c.installerWarning.value)
            assertEquals(1, s.fake.calls.size, "nothing opens before the user confirms")
            assertTrue(c.canGoBack.value)
            assertTrue(c.back())
            runCurrent()
            assertNull(c.installerWarning.value)
            c.platformOpenReceived("apk")
            c.confirmOpenInstaller()
            runCurrent()
            assertEquals(listOf("openReceived:jpg", "openReceived:apk"), s.fake.calls)
        }

    @Test
    fun fD5_historyInstallersAskBeforeOpening() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            val entry =
                HistoryEntry(
                    "h1",
                    "Dev",
                    DevicePlatform.PHONE,
                    Direction.RECEIVE,
                    HistoryStatus.DONE,
                    s.clocks.nowMillis(),
                    1_000,
                    10,
                    10,
                    null,
                    ItemSummary(2, SummaryKind.FILES),
                    null,
                )
            s.fake.historyEntries.value = listOf(entry)
            s.fake.historyFiles.value =
                mapOf(
                    "h1" to
                        listOf(
                            HistoryFile("f1", "setup.exe", 5, FileKind.OTHER, openable = true),
                            HistoryFile("f2", "notes.pdf", 5, FileKind.DOCUMENT, openable = true),
                        ),
                )
            c.openDashboard(DashboardTab.HISTORY)
            runCurrent()
            c.dashboard.history.openDetail("h1")
            runCurrent()
            c.openHistoryFile("h1", "f2")
            c.openHistoryFile("h1", "f1")
            runCurrent()
            assertEquals(listOf("openFile:h1:f2"), s.fake.calls)
            assertEquals(InstallerWarningUi("setup.exe", "Dev"), c.installerWarning.value)
            c.confirmOpenInstaller()
            assertEquals(listOf("openFile:h1:f2", "openFile:h1:f1"), s.fake.calls)
        }

    @Test
    fun fC5_addFilesOpensThePickerOverTheDashboardForThatTransfer() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            s.fake.transfers.value = listOf(Fixtures.transfer("tx", "t:rohan").copy(canAddFiles = true))
            s.fake.mediaItems.value =
                listOf(PickedItem("p1", "a.jpg", 1_000, FileKind.IMAGE), PickedItem("p2", "b.jpg", 1_000, FileKind.IMAGE))
            c.openDashboard(DashboardTab.LIVE)
            runCurrent()
            c.openAddFiles("tx")
            runCurrent()
            assertEquals(RadarSheet.PICKER, c.radarSheet.value)
            assertEquals(PickerTarget.Transfer("tx", "Rohan's Pixel"), c.picker.state.value?.target)
            assertEquals(Screen.DASHBOARD, c.screen.value, "over the dashboard")
            c.picker.toggle("p1")
            c.picker.toggle("p2")
            c.sendPicked()
            runCurrent()
            assertEquals(listOf("add:tx:2"), s.fake.calls, "the files go into the running transfer, not a new send")
            assertNull(c.radarSheet.value)
            assertEquals(Screen.DASHBOARD, c.screen.value)
        }

    @Test
    fun designSection42_liveCancelAsksFirstPastOneHundredMegabytes() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            s.fake.transfers.value = listOf(Fixtures.transfer("big", null, bytesDone = 1_900_000_000, bytesTotal = 2_000_000_000))
            c.openDashboard(DashboardTab.LIVE)
            runCurrent()
            c.cancelTransfer("big")
            runCurrent()
            assertEquals("big", c.radar.state.value.cancelConfirm?.transferId, "95% of 2 GB is not lost to one tap")
            assertTrue(s.fake.calls.isEmpty())
            c.radar.confirmCancel()
            assertEquals(listOf("cancel:big"), s.fake.calls)
        }

    @Test
    fun architectureSection11_everySendPathAsksForLocationOnAndroid12() =
        runTest {
            val s = Setup(this) { it.status[DropPermission.LOCATION_FOR_WIFI_DIRECT] = PermissionStatus.DENIED }
            val c = s.controller
            s.fake.devices.value = listOf(Fixtures.device("e:meera", "Meera"))
            runCurrent()
            // Share sheet, then a bubble tap (F‑C2).
            c.onShareIntent(photos)
            c.onBubbleTap("e:meera")
            runCurrent()
            assertEquals(DropPermission.LOCATION_FOR_WIFI_DIRECT, c.permissions.state.value?.permission)
            assertTrue(s.fake.calls.isEmpty(), "the send waits for the answer")
            c.permissions.onContinue()
            runCurrent()
            assertEquals(listOf("send:e:meera:3"), s.fake.calls)
            assertEquals(listOf(DropPermission.LOCATION_FOR_WIFI_DIRECT), s.perms.requested)

            // Direct share (F‑C3): asked again, since the first answer was a denial in this fake.
            s.perms.status[DropPermission.LOCATION_FOR_WIFI_DIRECT] = PermissionStatus.DENIED
            c.onShareIntent(photos, directTarget = "t:rohan", directTargetName = "Rohan")
            s.fake.devices.value = listOf(Fixtures.device("e:meera", "Meera"), Fixtures.device("t:rohan", "Rohan", trusted = true))
            runCurrent()
            assertEquals(DropPermission.LOCATION_FOR_WIFI_DIRECT, c.permissions.state.value?.permission)
            c.permissions.onDismiss()
            runCurrent()
            assertEquals(listOf("send:e:meera:3", "send:t:rohan:3"), s.fake.calls, "a refusal does not stop the send")
        }

    @Test
    fun architectureSection11_batteryIsAskedOnceAfterOnboarding() =
        runTest {
            val s = Setup(this, onboarding = true, brand = OemBrand.XIAOMI) { it.status[DropPermission.BATTERY] = PermissionStatus.DENIED }
            val c = s.controller
            val onboarding = c.onboarding!!
            onboarding.onStart()
            onboarding.onOpenBrandSettings()
            runCurrent()
            assertEquals(Screen.RADAR, c.screen.value)
            assertEquals(DropPermission.BATTERY, c.permissions.state.value?.permission, "after the brand screen")
            c.permissions.onDismiss() // skippable
            runCurrent()
            assertNull(c.permissions.state.value)
            c.reofferBackgroundRunning()
            runCurrent()
            assertEquals(DropPermission.BATTERY, c.permissions.state.value?.permission, "re-offered after a background kill")
            c.permissions.onDismiss()
            c.reofferBackgroundRunning()
            runCurrent()
            assertNull(c.permissions.state.value, "only once")
        }

    @Test
    fun designSection6_keepScreenAwakeHoldsTheScreenOnlyWhileATransferMoves() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            runCurrent()
            assertFalse(c.keepScreenOn.value)
            s.fake.transfers.value = listOf(Fixtures.transfer("tx", "t:rohan"))
            runCurrent()
            assertFalse(c.keepScreenOn.value, "the setting is off by default")
            c.dashboard.settings.setKeepScreenAwake(true)
            runCurrent()
            assertTrue(c.keepScreenOn.value)
            s.fake.transfers.value = listOf(Fixtures.transfer("tx", "t:rohan", stage = TransferStage.WAITING_FOR_PEER))
            runCurrent()
            assertFalse(c.keepScreenOn.value, "not for hours while waiting for the peer")
            s.fake.transfers.value = emptyList()
            runCurrent()
            assertFalse(c.keepScreenOn.value)
        }

    @Test
    fun decision9_theLanguageSettingReachesTheHost() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            runCurrent()
            assertEquals(AppLanguage.SYSTEM, c.language.value)
            c.dashboard.settings.setLanguage(AppLanguage.HINDI)
            runCurrent()
            assertEquals(AppLanguage.HINDI, c.language.value)
        }

    @Test
    fun fA5_tenMinutesFromSettingsCountsDownOnTheChip() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            c.dashboard.settings.setVisibility(Visibility.EVERYONE_TEN_MINUTES)
            runCurrent()
            assertEquals(VisibilityUi(Visibility.EVERYONE_TEN_MINUTES, 10), c.radar.state.value.visibility)
            assertEquals(Visibility.EVERYONE_TEN_MINUTES, c.dashboard.settings.state.value.values.visibility, "Settings and the chip agree")
        }

    @Test
    fun n15_theBrowserPageServesTheSharedOrPickedFiles() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            // With files from the share sheet: they are served at once.
            c.onShareIntent(photos)
            c.openSheet(RadarSheet.SHOW_QR)
            c.startBrowserShare()
            runCurrent()
            assertEquals(listOf("startBrowserShare:3"), s.fake.calls)
            assertNull(c.radar.state.value.attachment, "the files are the page's now")
            assertTrue(c.showQr.state.value!!.browserStarting)
            c.closeSheet()
            assertEquals(listOf("startBrowserShare:3", "stopBrowserShare"), s.fake.calls)

            // Without: the picker opens for the page, and its button starts it.
            s.fake.mediaItems.value = listOf(PickedItem("p1", "a.jpg", 1_000, FileKind.IMAGE))
            c.openSheet(RadarSheet.SHOW_QR)
            c.startBrowserShare()
            runCurrent()
            assertEquals(RadarSheet.PICKER, c.radarSheet.value)
            assertEquals(PickerTarget.Browser, c.picker.state.value?.target)
            c.picker.toggle("p1")
            c.sendPicked()
            runCurrent()
            assertEquals(RadarSheet.SHOW_QR, c.radarSheet.value, "back on the code sheet, now with the page starting")
            assertEquals("startBrowserShare:1", s.fake.calls.last())
        }

    @Test
    fun fB3_backPutsTheSendersCodeAsideWithoutTrusting() =
        runTest {
            val s = Setup(this)
            val c = s.controller
            s.fake.devices.value = listOf(Fixtures.device("e:a", "Dev"))
            s.fake.transfers.value = listOf(Fixtures.transfer("tx", "e:a", stage = TransferStage.AWAITING_ACCEPT, pairingCode = "042917"))
            runCurrent()
            assertTrue(c.canGoBack.value)
            assertTrue(c.back())
            runCurrent()
            assertNull(c.radar.state.value.senderPairing)
            assertFalse(s.fake.calls.any { it.startsWith("pairSend") }, "dismissing never confirms the pairing")
        }

    @Test
    fun designSection7_onboardingFinishesIntoTheRadar() =
        runTest {
            val s = Setup(this, onboarding = true)
            val c = s.controller
            assertEquals(Screen.ONBOARDING, c.screen.value)
            val onboarding = assertNotNull(c.onboarding)
            assertEquals("Asha's phone", onboarding.state.value.nickname)
            onboarding.onStart()
            runCurrent()
            assertEquals(Screen.RADAR, c.screen.value)
            assertTrue(s.fake.calls.contains("onboarded:Asha's phone"))
        }

    @Test
    fun designSection81_noticeActionsReachThePlatform() =
        runTest {
            val s = Setup(this)
            s.controller.onNoticeAction(RadarNotice.BLUETOOTH_OFF)
            s.controller.onNoticeAction(RadarNotice.WIFI_OFF)
            s.controller.onNoticeAction(RadarNotice.HIDDEN)
            runCurrent()
            assertEquals(listOf("turnOnBluetooth", "openWifiPanel"), s.fake.calls)
            assertEquals(RadarSheet.VISIBILITY, s.controller.radarSheet.value)
        }
}
