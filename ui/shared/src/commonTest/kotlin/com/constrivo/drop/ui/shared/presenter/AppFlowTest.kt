package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.ui.shared.Fixtures
import com.constrivo.drop.ui.shared.VirtualClocks
import com.constrivo.drop.ui.shared.fake.InMemoryDrop
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.DashboardTab
import com.constrivo.drop.ui.shared.model.DropPermission
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.OemBrand
import com.constrivo.drop.ui.shared.model.OnboardingStep
import com.constrivo.drop.ui.shared.model.PermissionMoment
import com.constrivo.drop.ui.shared.model.PermissionStatus
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.RadarNotice
import com.constrivo.drop.ui.shared.model.ScanStatus
import com.constrivo.drop.ui.shared.model.SelfProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    ) {
        val fake = InMemoryDrop(SelfProfile("Asha", "self"))
        val perms = FakePermissions()
        val clocks = VirtualClocks(scope)
        val controller =
            DropAppController(
                scope.backgroundScope,
                DropDependencies.inMemory(
                    fake = fake,
                    wallClock = clocks,
                    monotonicClock = clocks,
                    permissions = perms,
                    onboarding = if (onboarding) OnboardingConfig("Asha's phone", null, fake) else null,
                ),
            )
    }

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
            assertEquals(Screen.RADAR, s.controller.screen.value)
            assertEquals(RadarSheet.PICKER, s.controller.radarSheet.value)
            assertEquals("e:meera", s.controller.radar.state.value.selectedKey)
            assertTrue(s.controller.back())
            assertNull(s.controller.radarSheet.value)
            runCurrent()
            assertNull(s.controller.radar.state.value.selectedKey)
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
