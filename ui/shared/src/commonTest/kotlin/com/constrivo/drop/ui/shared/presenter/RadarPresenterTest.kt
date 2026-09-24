package com.constrivo.drop.ui.shared.presenter

import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.ladder.LadderHint
import com.constrivo.drop.ui.shared.Fixtures
import com.constrivo.drop.ui.shared.VirtualClocks
import com.constrivo.drop.ui.shared.fake.InMemoryDrop
import com.constrivo.drop.ui.shared.model.AttachedFiles
import com.constrivo.drop.ui.shared.model.BubbleActivity
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.RadarNotice
import com.constrivo.drop.ui.shared.model.RadioState
import com.constrivo.drop.ui.shared.model.ReceivedFile
import com.constrivo.drop.ui.shared.model.SelfProfile
import com.constrivo.drop.ui.shared.model.SummaryKind
import com.constrivo.drop.ui.shared.model.TransferStage
import com.constrivo.drop.ui.shared.model.VisibilityState
import com.constrivo.drop.ui.shared.theme.DropMotion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RadarPresenterTest {
    private class Setup(
        scope: TestScope,
    ) {
        val fake = InMemoryDrop(SelfProfile("Asha Verma", "self"))
        val clocks = VirtualClocks(scope)
        val presenter =
            RadarPresenter(
                scope = scope.backgroundScope,
                devices = fake.devices,
                transfers = fake.transfers,
                radio = fake.radio,
                visibility = fake.visibility,
                self = fake.profile,
                initialSelf = fake.profile.value,
                actions = fake,
                monotonicClock = clocks,
                wallClock = clocks,
                received = fake.receivedFiles,
            )
        val state get() = presenter.state.value
    }

    private val photos = AttachedFiles(List(3) { PickedItem("p$it", "IMG_$it.jpg", 4_000_000, FileKind.IMAGE) })

    @Test
    fun fA2_mapsNearbyDevicesToBubblesWithRingTrustAndInitials() =
        runTest {
            val s = Setup(this)
            s.fake.devices.value =
                listOf(
                    Fixtures.device("t:rohan", "Rohan's Pixel", Ring.INNER, trusted = true),
                    Fixtures.device("e:0a", null, Ring.OUTER),
                    Fixtures.device("e:0b", "Meera", Ring.MIDDLE, lanOnly = true),
                )
            runCurrent()
            val bubbles = s.state.bubbles
            assertEquals(listOf("e:0a", "e:0b", "t:rohan"), bubbles.map { it.key }, "sorted by key, so the order is stable")
            val rohan = bubbles.single { it.key == "t:rohan" }
            assertEquals(Ring.INNER, rohan.ring)
            assertTrue(rohan.trusted)
            assertEquals("RP", rohan.initials)
            assertNull(bubbles.single { it.key == "e:0a" }.initials, "a device without a nickname shows its glyph")
            assertTrue(bubbles.single { it.key == "e:0b" }.lanOnly)
            assertNull(s.state.notice)
            assertEquals("AV", s.state.selfInitials)
        }

    @Test
    fun designSection81_noticesInPriorityOrder() =
        runTest {
            val s = Setup(this)
            runCurrent()
            assertEquals(RadarNotice.NO_DEVICES, s.state.notice)
            s.fake.visibility.value = VisibilityState(Visibility.HIDDEN)
            runCurrent()
            assertEquals(RadarNotice.HIDDEN, s.state.notice)
            s.fake.radio.value = RadioState(wifiOn = false)
            runCurrent()
            assertEquals(RadarNotice.WIFI_OFF, s.state.notice)
            s.fake.radio.value = RadioState(wifiOn = false, bluetoothOn = false)
            runCurrent()
            assertEquals(RadarNotice.BLUETOOTH_OFF, s.state.notice)
            assertTrue(s.state.ringsDimmed)
            s.fake.radio.value = RadioState(bluetoothOn = false, nearbyPermission = false)
            runCurrent()
            assertEquals(RadarNotice.PERMISSION_MISSING, s.state.notice)
            // A desktop without Bluetooth has no "Turn on Bluetooth" state.
            s.fake.radio.value = RadioState(bluetoothAvailable = false, bluetoothOn = false)
            s.fake.visibility.value = VisibilityState(Visibility.EVERYONE)
            runCurrent()
            assertEquals(RadarNotice.NO_DEVICES, s.state.notice)
        }

    @Test
    fun fC4_sendingStateCarriesProgressBadgeHintAndAnnouncementSteps() =
        runTest {
            val s = Setup(this)
            s.fake.devices.value = listOf(Fixtures.device("t:rohan", "Rohan", Ring.INNER, trusted = true))
            s.fake.transfers.value = listOf(Fixtures.transfer("tx", "t:rohan", bytesDone = 49_000_000, hint = LadderHint.band24()))
            runCurrent()
            val active = assertIs<BubbleActivity.Active>(s.state.bubbles.single().activity)
            assertEquals(0.49f, active.fraction, 1e-6f)
            assertEquals(25, active.announcedPercent, "49% is announced as the 25% step")
            assertEquals(44_000_000, active.bytesPerSecond)
            assertEquals("badge.p2p_5", active.badge?.key?.key)
            assertEquals(LadderHint.band24(), active.hint)
            assertEquals("tx", active.dropToken, "a new send plays the drop animation")
            assertEquals(DropMotion.MAX_FLYERS, active.flyers.size, "at most 8 thumbnails fly")
            advanceTimeBy(RadarPresenter.DROP_TOKEN_WINDOW_MILLIS + 1)
            runCurrent()
            assertNull(assertIs<BubbleActivity.Active>(s.state.bubbles.single().activity).dropToken, "no replay after the window")
        }

    @Test
    fun designSection42_completionPopsForOneAndAHalfSecondsThenIdles() =
        runTest {
            val s = Setup(this)
            s.fake.devices.value = listOf(Fixtures.device("t:rohan", "Rohan", Ring.INNER))
            s.fake.transfers.value = listOf(Fixtures.transfer("tx", "t:rohan"))
            runCurrent()
            s.fake.transfers.value = listOf(Fixtures.transfer("tx", "t:rohan", stage = TransferStage.DONE, bytesDone = 100_000_000))
            runCurrent()
            assertIs<BubbleActivity.Completed>(s.state.bubbles.single().activity)
            advanceTimeBy(DropMotion.COMPLETION_HOLD_MILLIS - 1)
            runCurrent()
            assertIs<BubbleActivity.Completed>(s.state.bubbles.single().activity)
            advanceTimeBy(2)
            runCurrent()
            assertNull(s.state.bubbles.single().activity, "the bubble returns to idle after 1.5 s")
        }

    @Test
    fun designSection51_declinedAndNoAnswerShowOnTheSendersBubble() =
        runTest {
            val s = Setup(this)
            s.fake.devices.value = listOf(Fixtures.device("e:a", "Dev"))
            s.fake.transfers.value = listOf(Fixtures.transfer("tx", "e:a", stage = TransferStage.AWAITING_ACCEPT, bytesDone = 0))
            runCurrent()
            s.fake.transfers.value = listOf(Fixtures.transfer("tx", "e:a", stage = TransferStage.NO_ANSWER, bytesDone = 0))
            runCurrent()
            val ended = assertIs<BubbleActivity.Ended>(s.state.bubbles.single().activity)
            assertEquals(TransferStage.NO_ANSWER, ended.stage)
            advanceTimeBy(RadarPresenter.ENDED_HOLD_MILLIS + 1)
            runCurrent()
            assertNull(s.state.bubbles.single().activity)
        }

    @Test
    fun anAlreadyFinishedTransferNeverPops() =
        runTest {
            val s = Setup(this)
            s.fake.devices.value = listOf(Fixtures.device("e:a", "Dev"))
            s.fake.transfers.value = listOf(Fixtures.transfer("old", "e:a", stage = TransferStage.DONE))
            runCurrent()
            assertNull(s.state.bubbles.single().activity)
        }

    @Test
    fun designSection42_cancelConfirmsOnlyPastOneHundredMegabytes() =
        runTest {
            val s = Setup(this)
            s.fake.devices.value = listOf(Fixtures.device("e:a", "Dev"))
            s.fake.transfers.value = listOf(Fixtures.transfer("small", "e:a", bytesDone = 99_000_000))
            runCurrent()
            s.presenter.onCancelTapped("small")
            assertEquals(listOf("cancel:small"), s.fake.calls)
            assertNull(s.state.cancelConfirm)

            s.fake.transfers.value = listOf(Fixtures.transfer("big", "e:a", bytesDone = 100_000_001, bytesTotal = 900_000_000))
            runCurrent()
            s.presenter.onCancelTapped("big")
            runCurrent()
            assertEquals("big", s.state.cancelConfirm?.transferId)
            assertEquals(listOf("cancel:small"), s.fake.calls, "nothing cancelled before the confirmation")
            s.presenter.dismissCancel()
            runCurrent()
            assertNull(s.state.cancelConfirm)
            s.presenter.onCancelTapped("big")
            s.presenter.confirmCancel()
            runCurrent()
            assertEquals(listOf("cancel:small", "cancel:big"), s.fake.calls)
            assertNull(s.state.cancelConfirm)
        }

    @Test
    fun fC2_tapWithSharedFilesSendsAtOnceOtherwiseOpensThePicker() =
        runTest {
            val s = Setup(this)
            s.fake.devices.value = listOf(Fixtures.device("e:a", "Dev"), Fixtures.device("e:b", "Meera"))
            runCurrent()
            assertEquals(BubbleTapResult.OPEN_PICKER, s.presenter.onBubbleTapped("e:a"))
            runCurrent()
            assertEquals("e:a", s.state.selectedKey, "the bubble is raised while its picker is open")
            s.presenter.select(null)

            s.presenter.attach(photos)
            runCurrent()
            assertEquals(3, s.state.attachment?.summary?.count)
            assertEquals(SummaryKind.PHOTOS, s.state.attachment?.summary?.kind)
            assertEquals(12_000_000, s.state.attachment?.totalBytes)
            assertEquals(BubbleTapResult.SENT, s.presenter.onBubbleTapped("e:b"))
            runCurrent()
            assertEquals(listOf("send:e:b:3"), s.fake.calls)
            assertNull(s.state.attachment, "the banner goes once the files are sent")

            s.fake.transfers.value = listOf(Fixtures.transfer("tx", "e:b"))
            runCurrent()
            assertEquals(BubbleTapResult.BUSY, s.presenter.onBubbleTapped("e:b"))
        }

    @Test
    fun fC3_directShareTargetReceivesTheFilesWhenItAppears() =
        runTest {
            val s = Setup(this)
            s.presenter.attach(photos, directTarget = "t:rohan")
            runCurrent()
            assertTrue(s.fake.calls.isEmpty())
            s.fake.devices.value = listOf(Fixtures.device("t:rohan", "Rohan", Ring.INNER, trusted = true))
            runCurrent()
            assertEquals(listOf("send:t:rohan:3"), s.fake.calls)
            assertNull(s.state.attachment)
        }

    @Test
    fun fA5_tenMinuteChipCountsDownAndReverts() =
        runTest {
            val s = Setup(this)
            val clocks = s.clocks
            s.fake.visibility.value =
                VisibilityState(
                    Visibility.EVERYONE_TEN_MINUTES,
                    expiresAtMillis = clocks.nowMillis() + 10 * 60_000,
                    revertTo = Visibility.TRUSTED_ONLY,
                )
            runCurrent()
            assertEquals(Visibility.EVERYONE_TEN_MINUTES, s.state.visibility.mode)
            assertEquals(10, s.state.visibility.minutesLeft)
            advanceTimeBy(60_001)
            runCurrent()
            assertEquals(9, s.state.visibility.minutesLeft)
            advanceTimeBy(9 * 60_000L)
            runCurrent()
            assertEquals(Visibility.TRUSTED_ONLY, s.state.visibility.mode)
            assertNull(s.state.visibility.minutesLeft)
        }

    @Test
    fun fD3_trayCollectsReceivedFilesNewestLastAndClearsOnLeave() =
        runTest {
            val s = Setup(this)
            runCurrent()
            s.fake.receivedFiles.emit(ReceivedFile("f1", "tx", "a.jpg", FileKind.IMAGE))
            s.fake.receivedFiles.emit(ReceivedFile("f2", "tx", "b.pdf", FileKind.DOCUMENT))
            runCurrent()
            assertEquals(listOf("f1", "f2"), s.state.tray.map { it.id })
            repeat(RadarPresenter.MAX_TRAY_ITEMS + 5) { s.fake.receivedFiles.emit(ReceivedFile("x$it", "tx", "x", FileKind.OTHER)) }
            runCurrent()
            assertEquals(RadarPresenter.MAX_TRAY_ITEMS, s.state.tray.size)
            assertEquals("x${RadarPresenter.MAX_TRAY_ITEMS + 4}", s.state.tray.last().id)
            s.presenter.clearTray()
            runCurrent()
            assertTrue(s.state.tray.isEmpty())
        }

    @Test
    fun fB3_senderSeesTheCodeUntilConfirmed() =
        runTest {
            val s = Setup(this)
            s.fake.devices.value = listOf(Fixtures.device("e:a", "Dev"))
            s.fake.transfers.value = listOf(Fixtures.transfer("tx", "e:a", stage = TransferStage.AWAITING_ACCEPT, pairingCode = "042917"))
            runCurrent()
            assertEquals("042917", s.state.senderPairing?.code)
            s.presenter.confirmPairing("tx")
            runCurrent()
            assertNull(s.state.senderPairing)
            assertEquals(listOf("pairSend:tx"), s.fake.calls)
        }

    @Test
    fun selfProfileChangesReachTheAvatar() =
        runTest {
            val s = Setup(this)
            s.fake.profile.value = SelfProfile("Kabir", "self")
            runCurrent()
            assertEquals("K", s.state.selfInitials)
            assertFalse(s.state.bubbles.any())
        }
}
