package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.ProtocolConstants.BUNDLE_FILE_INDEX
import com.constrivo.drop.core.protocol.ProtocolConstants.HEARTBEAT_LOST_MS
import com.constrivo.drop.core.protocol.ProtocolConstants.OFFER_TIMEOUT_MS
import com.constrivo.drop.core.protocol.ProtocolConstants.PARKED_WINDOW_MS
import com.constrivo.drop.core.protocol.ProtocolConstants.RECONNECT_WINDOW_MS
import com.constrivo.drop.core.protocol.TransferEffect.CancelTimer
import com.constrivo.drop.core.protocol.TransferEffect.ClearPartials
import com.constrivo.drop.core.protocol.TransferEffect.FileFailed
import com.constrivo.drop.core.protocol.TransferEffect.NotifyUi
import com.constrivo.drop.core.protocol.TransferEffect.Persist
import com.constrivo.drop.core.protocol.TransferEffect.ReleaseLink
import com.constrivo.drop.core.protocol.TransferEffect.Send
import com.constrivo.drop.core.protocol.TransferEffect.StartReconnect
import com.constrivo.drop.core.protocol.TransferEffect.StartStreaming
import com.constrivo.drop.core.protocol.TransferEffect.StartTimer
import com.constrivo.drop.core.protocol.TransferEffect.StopReconnect
import com.constrivo.drop.core.protocol.TransferEffect.StopWatchingForPeer
import com.constrivo.drop.core.protocol.TransferEffect.WatchForPeer
import com.constrivo.drop.core.protocol.TransferEvent.AcceptReceived
import com.constrivo.drop.core.protocol.TransferEvent.AllChunksAcked
import com.constrivo.drop.core.protocol.TransferEvent.CancelReceived
import com.constrivo.drop.core.protocol.TransferEvent.ChunkHashMismatch
import com.constrivo.drop.core.protocol.TransferEvent.CompleteReceived
import com.constrivo.drop.core.protocol.TransferEvent.DeclineReceived
import com.constrivo.drop.core.protocol.TransferEvent.FileHashMismatch
import com.constrivo.drop.core.protocol.TransferEvent.FileVerified
import com.constrivo.drop.core.protocol.TransferEvent.FirstChunkOverBluetooth
import com.constrivo.drop.core.protocol.TransferEvent.HeartbeatReceived
import com.constrivo.drop.core.protocol.TransferEvent.LinkLost
import com.constrivo.drop.core.protocol.TransferEvent.LocalAccept
import com.constrivo.drop.core.protocol.TransferEvent.LocalCancel
import com.constrivo.drop.core.protocol.TransferEvent.LocalDecline
import com.constrivo.drop.core.protocol.TransferEvent.PeerRediscovered
import com.constrivo.drop.core.protocol.TransferEvent.ProtocolViolation
import com.constrivo.drop.core.protocol.TransferEvent.Resumed
import com.constrivo.drop.core.protocol.TransferEvent.RetransmitRequested
import com.constrivo.drop.core.protocol.TransferEvent.StorageFull
import com.constrivo.drop.core.protocol.TransferEvent.TimerFired
import com.constrivo.drop.core.protocol.TransferEvent.WifiStreamConnected
import com.constrivo.drop.core.protocol.TransferPhase.ACCEPTED
import com.constrivo.drop.core.protocol.TransferPhase.CANCELLED
import com.constrivo.drop.core.protocol.TransferPhase.DONE
import com.constrivo.drop.core.protocol.TransferPhase.FAILED
import com.constrivo.drop.core.protocol.TransferPhase.OFFERED
import com.constrivo.drop.core.protocol.TransferPhase.PARKED
import com.constrivo.drop.core.protocol.TransferPhase.RECONNECTING
import com.constrivo.drop.core.protocol.TransferPhase.STREAMING_BLUETOOTH
import com.constrivo.drop.core.protocol.TransferPhase.STREAMING_WIFI
import com.constrivo.drop.core.protocol.TransferPhase.VERIFYING
import com.constrivo.drop.core.protocol.TransferTimer.HEARTBEAT
import com.constrivo.drop.core.protocol.TransferTimer.OFFER
import com.constrivo.drop.core.protocol.TransferTimer.PARKED_WINDOW
import com.constrivo.drop.core.protocol.TransferTimer.RECONNECT_WINDOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §7.7 and §7.8 with S8, as a pure reducer driven by a fake clock. */
class TransferStateMachineTest {
    private val machine = TransferStateMachine()
    private val t0 = 1_000_000L
    private val accept = Accept(TEST_ID, streamCount = 4)

    /** Drives the machine and remembers the latest transition. */
    private inner class Driver(
        role: TransferRole,
        fileCount: Int = 3,
        totalBytes: Long = 3_000,
    ) {
        var now = t0
        var last: Transition = machine.start(TEST_ID, role, fileCount, totalBytes, now)
        val state: TransferState get() = last.state
        val phase: TransferPhase get() = state.phase
        val effects: List<TransferEffect> get() = last.effects

        fun on(
            event: TransferEvent,
            at: Long = now,
        ): Transition {
            now = at
            last = machine.reduce(state, event, now)
            return last
        }

        fun advance(millis: Long) {
            now += millis
        }

        /** Fires every timer whose deadline has passed, earliest first. */
        fun fireDue() {
            for ((timer, deadline) in state.timers.entries.sortedBy { it.value }) {
                if (deadline <= now && timer in state.timers) on(TimerFired(timer))
            }
        }

        fun sent(): List<ControlMessage> = effects.filterIsInstance<Send>().map { it.message }
    }

    private fun sender() = Driver(TransferRole.SENDER)

    private fun receiver() = Driver(TransferRole.RECEIVER)

    @Test
    fun startArmsTheOfferTimer() {
        val d = sender()
        assertEquals(OFFERED, d.phase)
        assertEquals(listOf(StartTimer(OFFER, t0 + OFFER_TIMEOUT_MS), Persist, NotifyUi), d.effects)
        assertEquals(mapOf(OFFER to t0 + OFFER_TIMEOUT_MS), d.state.timers)
        assertEquals("offered", d.phase.storedStatus)
    }

    @Test
    fun senderHappyPathWithHeadStartAndHop() {
        val d = sender()
        d.on(AcceptReceived(accept), t0 + 800)
        assertEquals(ACCEPTED, d.phase)
        assertEquals(
            listOf(CancelTimer(OFFER), StartTimer(HEARTBEAT, t0 + 800 + HEARTBEAT_LOST_MS), StartStreaming, Persist, NotifyUi),
            d.effects,
        )
        assertEquals(t0 + 800, d.state.acceptedAtMillis)

        d.on(FirstChunkOverBluetooth, t0 + 900)
        assertEquals(STREAMING_BLUETOOTH, d.phase)
        assertEquals(LinkKind.BLUETOOTH, d.state.link)

        d.on(WifiStreamConnected(LinkKind.P2P, 5180), t0 + 2_000)
        assertEquals(STREAMING_WIFI, d.phase)
        assertEquals(LinkKind.P2P, d.state.link)
        assertEquals(5180, d.state.freqMhz)

        d.on(HeartbeatReceived, t0 + 3_000)
        assertEquals(listOf(StartTimer(HEARTBEAT, t0 + 3_000 + HEARTBEAT_LOST_MS)), d.effects)

        d.on(AllChunksAcked, t0 + 10_800)
        assertEquals(VERIFYING, d.phase)
        assertEquals(listOf(Complete(TEST_ID, CompleteStatus.OK, 3_000, 10_000)), d.sent())

        d.on(CompleteReceived(Complete(TEST_ID, CompleteStatus.OK, 3_000, 10_050)), t0 + 11_000)
        assertEquals(DONE, d.phase)
        assertEquals(CompleteStatus.OK, d.state.completeStatus)
        assertEquals(listOf(CancelTimer(HEARTBEAT), ReleaseLink, Persist, NotifyUi), d.effects)
        assertTrue(d.state.timers.isEmpty())
        assertEquals("done", d.phase.storedStatus)
    }

    @Test
    fun receiverHappyPath() {
        val d = receiver()
        d.on(LocalAccept(accept), t0 + 2_000)
        assertEquals(ACCEPTED, d.phase)
        assertEquals(listOf(accept), d.sent())
        assertTrue(StartStreaming in d.effects)

        d.on(FirstChunkOverBluetooth)
        d.on(FileVerified(0, 1_000))
        assertEquals(STREAMING_BLUETOOTH, d.phase, "files verify while others still stream")
        d.on(WifiStreamConnected(LinkKind.LAN))
        d.on(FileVerified(1, 1_000))
        d.on(AllChunksAcked)
        assertEquals(VERIFYING, d.phase)
        assertTrue(d.sent().isEmpty())
        d.on(FileVerified(2, 1_000), t0 + 7_000)
        assertEquals(DONE, d.phase)
        assertEquals(listOf(Complete(TEST_ID, CompleteStatus.OK, 3_000, 5_000)), d.sent())
        assertFalse(ClearPartials in d.effects, "done keeps the files")
        assertTrue(ReleaseLink in d.effects)
    }

    @Test
    fun receiverDoneStraightFromStreamingWhenTheLastFileVerifiesBeforeTheAcks() {
        val d = Driver(TransferRole.RECEIVER, fileCount = 1, totalBytes = 5)
        d.on(LocalAccept(accept))
        d.on(WifiStreamConnected(LinkKind.P2P, 5745))
        assertEquals(STREAMING_WIFI, d.phase, "a Wi-Fi stream before any Bluetooth chunk skips the Bluetooth state")
        d.on(FileVerified(0, 5))
        assertEquals(DONE, d.phase)
    }

    @Test
    fun declineAndTimeouts() {
        val declined = sender()
        declined.on(DeclineReceived(Decline(TEST_ID, DeclineReason.USER)))
        assertEquals(CANCELLED, declined.phase)
        assertEquals(DeclineReason.USER, declined.state.declineReason)
        assertEquals(listOf(CancelTimer(OFFER), Persist, NotifyUi), declined.effects, "no link to release before accept")

        val declining = receiver()
        declining.on(LocalDecline(DeclineReason.STORAGE))
        assertEquals(listOf(Decline(TEST_ID, DeclineReason.STORAGE)), declining.sent())
        assertFalse(ClearPartials in declining.effects)

        val unanswered = sender()
        unanswered.advance(OFFER_TIMEOUT_MS - 1)
        unanswered.on(TimerFired(OFFER))
        assertFalse(unanswered.last.handled, "a timer that fires early is ignored")
        assertEquals(OFFERED, unanswered.phase)
        unanswered.advance(1)
        unanswered.on(TimerFired(OFFER))
        assertEquals(CANCELLED, unanswered.phase)
        assertEquals(CancelReason.TIMEOUT, unanswered.state.cancelReason)
        assertEquals(listOf(Cancel(TEST_ID, CancelReason.TIMEOUT)), unanswered.sent())

        val card = receiver()
        card.advance(OFFER_TIMEOUT_MS)
        card.fireDue()
        assertEquals(CANCELLED, card.phase)
        assertEquals(listOf(Decline(TEST_ID, DeclineReason.TIMEOUT)), card.sent())
    }

    @Test
    fun staleAndForeignEventsAreIgnored() {
        val d = sender()
        d.on(AcceptReceived(Accept(OTHER_ID, streamCount = 1)))
        assertFalse(d.last.handled)
        assertEquals(OFFERED, d.phase)
        d.on(LocalAccept(accept))
        assertFalse(d.last.handled, "a sender does not accept")
        d.on(FirstChunkOverBluetooth)
        assertFalse(d.last.handled)
        d.on(HeartbeatReceived)
        assertFalse(d.last.handled, "no watchdog before accept")
        d.on(TimerFired(HEARTBEAT))
        assertFalse(d.last.handled, "a timer that is not running")
        d.on(AcceptReceived(accept))
        d.on(AcceptReceived(accept))
        assertFalse(d.last.handled, "duplicate accept")
        d.on(CancelReceived(Cancel(OTHER_ID, CancelReason.USER)))
        assertFalse(d.last.handled)
        d.on(FileVerified(0, 1))
        assertFalse(d.last.handled, "the sender does not verify")
        d.on(StorageFull)
        assertFalse(d.last.handled, "storage is the receiver's problem")
        // Heartbeat re-arms replace the deadline: the old one no longer fires.
        d.on(HeartbeatReceived, t0 + 5_000)
        d.on(TimerFired(HEARTBEAT), t0 + HEARTBEAT_LOST_MS + 100)
        assertFalse(d.last.handled)
        assertEquals(ACCEPTED, d.phase)
    }

    @Test
    fun heartbeatLossReconnectsThenParksThenGivesUp() {
        val d = receiver()
        d.on(LocalAccept(accept), t0)
        d.on(FirstChunkOverBluetooth)
        d.advance(HEARTBEAT_LOST_MS)
        d.fireDue()
        assertEquals(RECONNECTING, d.phase)
        assertEquals("interrupted", d.phase.storedStatus)
        val interruptedAt = t0 + HEARTBEAT_LOST_MS
        assertEquals(
            listOf(
                CancelTimer(HEARTBEAT),
                StartTimer(RECONNECT_WINDOW, interruptedAt + RECONNECT_WINDOW_MS),
                StartTimer(PARKED_WINDOW, interruptedAt + PARKED_WINDOW_MS),
                StartReconnect,
                Persist,
                NotifyUi,
            ),
            d.effects,
        )
        d.on(HeartbeatReceived)
        assertFalse(d.last.handled, "no heartbeats while interrupted")

        d.advance(RECONNECT_WINDOW_MS)
        d.fireDue()
        assertEquals(PARKED, d.phase)
        assertEquals(listOf(StopReconnect, WatchForPeer, Persist, NotifyUi), d.effects)

        d.advance(PARKED_WINDOW_MS - RECONNECT_WINDOW_MS - 1)
        d.fireDue()
        assertEquals(PARKED, d.phase)
        d.advance(1)
        d.fireDue()
        assertEquals(CANCELLED, d.phase)
        assertEquals(CancelReason.TIMEOUT, d.state.cancelReason)
        assertEquals(listOf(CancelTimer(PARKED_WINDOW), StopWatchingForPeer, ClearPartials, ReleaseLink, Persist, NotifyUi), d.effects)
    }

    @Test
    fun parkedTransfersResumeWhenThePeerReturns() {
        val d = sender()
        d.on(AcceptReceived(accept))
        d.on(WifiStreamConnected(LinkKind.HOTSPOT, 2437))
        d.on(LinkLost, t0 + 1_000)
        assertEquals(RECONNECTING, d.phase)
        d.advance(RECONNECT_WINDOW_MS)
        d.fireDue()
        assertEquals(PARKED, d.phase)

        d.on(PeerRediscovered, t0 + 3_600_000)
        assertEquals(RECONNECTING, d.phase)
        assertEquals(
            listOf(
                StopWatchingForPeer,
                StartTimer(RECONNECT_WINDOW, t0 + 3_600_000 + RECONNECT_WINDOW_MS),
                StartReconnect,
                Persist,
                NotifyUi,
            ),
            d.effects,
        )
        assertEquals(t0 + 1_000 + PARKED_WINDOW_MS, d.state.timers[PARKED_WINDOW], "the parked window still counts from the interruption")

        d.on(Resumed(LinkKind.P2P, 5200), t0 + 3_601_000)
        assertEquals(STREAMING_WIFI, d.phase)
        assertEquals(LinkKind.P2P, d.state.link)
        assertEquals(
            listOf(
                StopReconnect,
                CancelTimer(RECONNECT_WINDOW),
                CancelTimer(PARKED_WINDOW),
                StartTimer(
                    HEARTBEAT,
                    t0 + 3_601_000 + HEARTBEAT_LOST_MS,
                ),
                Persist,
                NotifyUi,
            ),
            d.effects,
        )
        assertEquals(setOf(HEARTBEAT), d.state.timers.keys)
        assertNull(d.state.interruptedAtMillis)

        // Resumed over Bluetooth while parked.
        d.on(LinkLost)
        d.advance(RECONNECT_WINDOW_MS)
        d.fireDue()
        d.on(Resumed(LinkKind.BLUETOOTH))
        assertEquals(STREAMING_BLUETOOTH, d.phase)
        assertTrue(StopWatchingForPeer in d.effects)
    }

    @Test
    fun reconnectWindowEndingAfterTheParkedDeadlineCancels() {
        val machine = TransferStateMachine(TransferTimeouts(reconnectWindowMillis = 10_000, parkedWindowMillis = 5_000))
        var state = machine.start(TEST_ID, TransferRole.SENDER, 1, 1, 0).state
        state = machine.reduce(state, AcceptReceived(accept), 0).state
        state = machine.reduce(state, LinkLost, 0).state
        val t = machine.reduce(state, TimerFired(RECONNECT_WINDOW), 10_000)
        assertEquals(CANCELLED, t.state.phase)
        assertEquals(CancelReason.TIMEOUT, t.state.cancelReason)
    }

    @Test
    fun resumingAfterEverythingWasAckedGoesBackToVerifying() {
        val d = sender()
        d.on(AcceptReceived(accept))
        d.on(FirstChunkOverBluetooth)
        d.on(AllChunksAcked)
        d.on(LinkLost)
        d.on(Resumed(LinkKind.LAN))
        assertEquals(VERIFYING, d.phase)
        d.on(CompleteReceived(Complete(TEST_ID, CompleteStatus.OK, 3_000, 1)))
        assertEquals(DONE, d.phase)
    }

    @Test
    fun chunkMismatchRetriesTwiceThenFailsTheFile() {
        val d = receiver()
        d.on(LocalAccept(accept))
        d.on(WifiStreamConnected(LinkKind.P2P, 5180))
        val unit = TransferUnit(1, 4)
        d.on(ChunkHashMismatch(unit, listOf(1)))
        assertEquals(listOf(Resume(TEST_ID, MissingUnits(chunks = listOf(MissingChunks(1, listOf(IndexRange(4, 1))))))), d.sent())
        assertEquals(1, d.state.unitStrikes[unit])
        d.on(ChunkHashMismatch(unit, listOf(1)))
        assertEquals(2, d.state.unitStrikes[unit])
        d.on(ChunkHashMismatch(unit, listOf(1)))
        assertTrue(FileFailed(1) in d.effects)
        assertTrue(1 in d.state.failedFiles)
        assertNull(d.state.unitStrikes[unit])
        assertEquals(STREAMING_WIFI, d.phase)
        d.on(FileVerified(0, 1_000))
        d.on(FileVerified(1, 1_000))
        assertFalse(d.last.handled, "a failed file cannot verify")
        d.on(AllChunksAcked)
        d.on(FileVerified(2, 1_000), d.now + 100)
        assertEquals(DONE, d.phase)
        val complete = assertIs<Complete>(d.sent().single())
        assertEquals(CompleteStatus.PARTIAL, complete.status)
        assertEquals(listOf(1), complete.failedFiles)
        assertEquals(2_000, complete.bytes)
    }

    @Test
    fun aBadBundleFailsEveryFileInIt() {
        val d = Driver(TransferRole.RECEIVER, fileCount = 4)
        d.on(LocalAccept(accept))
        d.on(FirstChunkOverBluetooth)
        val bundle = TransferUnit(BUNDLE_FILE_INDEX, 0)
        repeat(3) { d.on(ChunkHashMismatch(bundle, listOf(0, 2))) }
        assertEquals(listOf(FileFailed(0), FileFailed(2)), d.effects.filterIsInstance<FileFailed>())
        assertEquals(listOf(0, 2), d.state.failedFiles.toList())
    }

    @Test
    fun wholeFileMismatchRequestsSuspectsAndReturnsToStreaming() {
        val d = Driver(TransferRole.RECEIVER, fileCount = 2)
        d.on(LocalAccept(accept))
        d.on(WifiStreamConnected(LinkKind.LAN))
        d.on(FileVerified(0, 10))
        d.on(AllChunksAcked)
        assertEquals(VERIFYING, d.phase)
        val suspects = MissingUnits(chunks = listOf(MissingChunks(1, listOf(IndexRange(2, 1)))))
        d.on(FileHashMismatch(1, suspects))
        assertEquals(STREAMING_WIFI, d.phase)
        assertFalse(d.state.allUnitsAcked)
        assertEquals(listOf(Resume(TEST_ID, suspects)), d.sent())
        // Without suspects the whole file is requested again.
        d.on(FileHashMismatch(1, MissingUnits.NONE))
        assertEquals(listOf(Resume(TEST_ID, MissingUnits(files = listOf(IndexRange(1, 1))))), d.sent())
        d.on(FileHashMismatch(1, MissingUnits.NONE))
        assertEquals(DONE, d.phase, "third strike fails the file; everything else is verified")
        assertEquals(CompleteStatus.PARTIAL, d.state.completeStatus)
    }

    @Test
    fun everyFileFailingFailsTheTransfer() {
        val d = Driver(TransferRole.RECEIVER, fileCount = 1)
        d.on(LocalAccept(accept))
        d.on(FirstChunkOverBluetooth)
        repeat(3) { d.on(FileHashMismatch(0, MissingUnits.NONE)) }
        assertEquals(FAILED, d.phase)
        assertEquals(CompleteStatus.FAILED, d.state.completeStatus)
        assertEquals(CancelReason.VERIFICATION, d.state.cancelReason)
        val complete = assertIs<Complete>(d.sent().single())
        assertEquals(CompleteStatus.FAILED, complete.status)
        assertTrue(ClearPartials in d.effects)
    }

    @Test
    fun mismatchWhileInterruptedCountsButDoesNotSend() {
        val d = receiver()
        d.on(LocalAccept(accept))
        d.on(FirstChunkOverBluetooth)
        d.on(LinkLost)
        d.on(ChunkHashMismatch(TransferUnit(0, 0), listOf(0)))
        assertTrue(d.last.handled)
        assertTrue(d.sent().isEmpty())
        assertEquals(RECONNECTING, d.phase)
    }

    @Test
    fun senderRetransmitsFromVerifying() {
        val d = sender()
        d.on(AcceptReceived(accept))
        d.on(WifiStreamConnected(LinkKind.P2P, 5180))
        d.on(AllChunksAcked)
        d.on(RetransmitRequested(Resume(TEST_ID, MissingUnits(files = listOf(IndexRange(0, 1))))))
        assertEquals(STREAMING_WIFI, d.phase)
        d.on(RetransmitRequested(Resume(OTHER_ID, MissingUnits.NONE)))
        assertFalse(d.last.handled)
        d.on(RetransmitRequested(Resume(TEST_ID, MissingUnits.NONE)))
        assertTrue(d.last.handled)
        assertTrue(d.effects.isEmpty())
    }

    @Test
    fun senderLearnsTheOutcomeFromTheReceiversComplete() {
        val partial = sender()
        partial.on(AcceptReceived(accept))
        partial.on(AllChunksAcked)
        partial.on(CompleteReceived(Complete(TEST_ID, CompleteStatus.PARTIAL, 2_000, 1, listOf(2))))
        assertEquals(DONE, partial.phase)
        assertEquals(listOf(2), partial.state.failedFiles.toList())

        val failed = sender()
        failed.on(AcceptReceived(accept))
        failed.on(CompleteReceived(Complete(TEST_ID, CompleteStatus.FAILED, 0, 1, listOf(0, 1, 2))))
        assertEquals(FAILED, failed.phase)

        // The receiver treats the sender's Complete as information unless it reports failure.
        val r = receiver()
        r.on(LocalAccept(accept))
        r.on(CompleteReceived(Complete(TEST_ID, CompleteStatus.OK, 3_000, 1)))
        assertTrue(r.last.handled)
        assertEquals(ACCEPTED, r.phase)
        r.on(CompleteReceived(Complete(TEST_ID, CompleteStatus.FAILED, 0, 1)))
        assertEquals(FAILED, r.phase)
    }

    @Test
    fun cancellationPaths() {
        val local = receiver()
        local.on(LocalAccept(accept))
        local.on(FirstChunkOverBluetooth)
        local.on(LocalCancel())
        assertEquals(CANCELLED, local.phase)
        assertEquals(listOf(Cancel(TEST_ID, CancelReason.USER)), local.sent())
        assertEquals(
            listOf(Send(Cancel(TEST_ID, CancelReason.USER)), CancelTimer(HEARTBEAT), ClearPartials, ReleaseLink, Persist, NotifyUi),
            local.effects,
        )

        val remote = sender()
        remote.on(AcceptReceived(accept))
        remote.on(CancelReceived(Cancel(TEST_ID, CancelReason.STORAGE)))
        assertEquals(CANCELLED, remote.phase)
        assertEquals(CancelReason.STORAGE, remote.state.cancelReason)
        assertTrue(remote.sent().isEmpty())
        assertFalse(ClearPartials in remote.effects, "the sender has no partials")

        val full = receiver()
        full.on(LocalAccept(accept))
        full.on(StorageFull)
        assertEquals(CANCELLED, full.phase)
        assertEquals(listOf(Cancel(TEST_ID, CancelReason.STORAGE)), full.sent())
        assertTrue(ClearPartials in full.effects)

        val offered = sender()
        offered.on(LocalCancel(CancelReason.SOURCE))
        assertEquals(listOf(Cancel(TEST_ID, CancelReason.SOURCE)), offered.sent())
        assertFalse(ReleaseLink in offered.effects)

        val parked = sender()
        parked.on(AcceptReceived(accept))
        parked.on(LinkLost)
        parked.advance(RECONNECT_WINDOW_MS)
        parked.fireDue()
        parked.on(LocalCancel())
        assertEquals(
            listOf(
                Send(Cancel(TEST_ID, CancelReason.USER)),
                CancelTimer(PARKED_WINDOW),
                StopWatchingForPeer,
                ReleaseLink,
                Persist,
                NotifyUi,
            ),
            parked.effects,
        )
    }

    @Test
    fun protocolViolationFails() {
        val d = receiver()
        d.on(LocalAccept(accept))
        d.on(ProtocolViolation("bad frame"))
        assertEquals(FAILED, d.phase)
        assertEquals("bad frame", d.state.failure)
        assertEquals(listOf(Cancel(TEST_ID, CancelReason.PROTOCOL)), d.sent())
        assertEquals("failed", d.phase.storedStatus)
    }

    @Test
    fun terminalStatesIgnoreEverything() {
        val d = sender()
        d.on(LocalCancel())
        val events =
            listOf(
                AcceptReceived(accept),
                FirstChunkOverBluetooth,
                WifiStreamConnected(LinkKind.LAN),
                AllChunksAcked,
                HeartbeatReceived,
                LinkLost,
                Resumed(LinkKind.LAN),
                PeerRediscovered,
                LocalCancel(),
                StorageFull,
                ProtocolViolation("x"),
                TimerFired(OFFER),
                CompleteReceived(Complete(TEST_ID, CompleteStatus.OK, 1, 1)),
            )
        for (event in events) {
            val t = machine.reduce(d.state, event, t0 + 1)
            assertFalse(t.handled, "$event")
            assertEquals(d.state, t.state)
            assertTrue(t.effects.isEmpty())
        }
    }

    @Test
    fun wifiUpdatesKeepTheBadgeCurrent() {
        val d = sender()
        d.on(AcceptReceived(accept))
        d.on(WifiStreamConnected(LinkKind.LAN))
        d.on(WifiStreamConnected(LinkKind.LAN))
        assertTrue(d.last.handled)
        assertTrue(d.effects.isEmpty(), "another stream on the same link changes nothing")
        d.on(WifiStreamConnected(LinkKind.P2P, 5180))
        assertEquals(LinkKind.P2P, d.state.link)
        assertEquals(listOf(Persist, NotifyUi), d.effects)
        assertRejectsArgument { WifiStreamConnected(LinkKind.BLUETOOTH) }
    }

    @Test
    fun fileIndexSetIsACopyOnWriteSet() {
        val a = FileIndexSet.of(3, 70, 3)
        assertEquals(2, a.size)
        assertEquals(listOf(3, 70), a.toList())
        val b = a + 200
        assertEquals(listOf(3, 70), a.toList(), "the original is unchanged")
        assertEquals(listOf(3, 70, 200), b.toList())
        assertTrue(a + 3 === a)
        assertFalse(5 in a)
        assertFalse(-1 in a)
        assertEquals(FileIndexSet.of(70, 3), a)
        assertEquals(FileIndexSet.of(70, 3).hashCode(), a.hashCode())
        assertTrue(FileIndexSet.EMPTY.isEmpty())
        assertRejectsArgument { a + -1 }
    }

    @Test
    fun timeoutsComeFromTheConstants() {
        val defaults = TransferTimeouts()
        assertEquals(OFFER_TIMEOUT_MS, defaults.offerMillis)
        assertEquals(HEARTBEAT_LOST_MS, defaults.heartbeatLostMillis)
        assertEquals(RECONNECT_WINDOW_MS, defaults.reconnectWindowMillis)
        assertEquals(PARKED_WINDOW_MS, defaults.parkedWindowMillis)
        assertEquals(ProtocolConstants.MAX_CHUNK_MISMATCHES, defaults.maxMismatches)
        assertRejectsArgument { TransferTimeouts(offerMillis = 0) }
        assertRejectsArgument { TransferTimeouts(maxMismatches = 0) }
    }
}
