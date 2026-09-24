package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.DeclineReason
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.transfer.MemorySource
import com.constrivo.drop.core.transfer.PowerPolicy
import com.constrivo.drop.core.transfer.Releasable
import com.constrivo.drop.core.transfer.TestSupport
import com.constrivo.drop.core.transfer.ThermalLevel
import com.constrivo.drop.core.transfer.TransferClock
import com.constrivo.drop.core.transfer.receive.InMemoryResumeStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The timing rules of §7.4, §7.5, §7.7 and §7.8 under virtual time (every engine coroutine on the test scheduler, the
 * clock is its `currentTime`), so heartbeats, windows and rates are exact and the tests are deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualTimeEngineTest {
    private val mib = ProtocolConstants.MIB

    private fun TestScope.pair(
        primaryKind: LinkKind = LinkKind.BLUETOOTH,
        primaryBytesPerSecond: Long? = 100_000,
        resume: InMemoryResumeStore = InMemoryResumeStore(),
        senderPower: PowerPolicy? = null,
    ): EnginePair {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return EnginePair(
            primaryKind = primaryKind,
            primaryBytesPerSecond = primaryBytesPerSecond,
            io = dispatcher,
            dispatcher = dispatcher,
            clock = TransferClock { testScheduler.currentTime },
            resumeStore = resume,
            senderPower = senderPower,
        )
    }

    private class FakePower(
        level: ThermalLevel,
    ) : PowerPolicy {
        override val thermal: StateFlow<ThermalLevel> = MutableStateFlow(level)

        override fun keepAwake(reason: String): Releasable = Releasable {}
    }

    @Test
    fun `F-E5 the first Bluetooth block moves progress within 1 s of Accept at 20 KB per s`() =
        runTest(timeout = 2.minutes) {
            pair(primaryBytesPerSecond = 20_000).use { pair ->
                val files = listOf(MemorySource("photo.jpg", TestSupport.randomBytes(600_000, 91)))
                val (a, b) = pair.connect()
                val sending = pair.senderEngine.send(a, files)
                val incoming = pair.scope.async { pair.receiverEngine.receive(b) }.await()
                val acceptedAt = currentTime
                val receiving = incoming.accept()
                receiving.progress.first { it.bytesDone > 0 }
                val receiverMillis = currentTime - acceptedAt
                sending.progress.first { it.bytesDone > 0 }
                val senderMillis = currentTime - acceptedAt
                assertTrue(receiverMillis < 1_000, "receiver progress after $receiverMillis ms")
                assertTrue(senderMillis < 1_000, "sender progress after $senderMillis ms")
                assertEquals(TransferPhase.STREAMING_BLUETOOTH, receiving.progress.value.phase)
                receiving.cancel()
                sending.await()
            }
        }

    @Test
    fun `S8 heartbeat lost for 6 s interrupts, 2 min of reconnecting parks, the beacon resumes and the transfer completes`() =
        runTest(timeout = 2.minutes) {
            pair().use { pair ->
                val files = listOf(MemorySource("clip.mp4", TestSupport.randomBytes(2 * mib + 5, 92)))
                val (sending, receiving) = pair.start(files)
                receiving.progress.first { it.bytesDone > 100_000 }
                val stalledAt = currentTime
                pair.stallPrimary()

                receiving.progress.first { it.phase == TransferPhase.RECONNECTING }
                val detected = currentTime - stalledAt
                assertTrue(detected in 5_000..7_100, "watchdog fired after $detected ms")
                sending.progress.first { it.phase == TransferPhase.RECONNECTING }

                receiving.progress.first { it.phase == TransferPhase.PARKED }
                val parked = currentTime - stalledAt
                assertTrue(parked in 125_000..127_500, "parked after $parked ms")
                assertTrue(receiving.progress.value.waitingForPeer)
                sending.progress.first { it.phase == TransferPhase.PARKED }

                // The peer's beacon is seen again: both reconnect, the link is back.
                receiving.peerRediscovered()
                sending.peerRediscovered()
                receiving.progress.first { it.phase == TransferPhase.RECONNECTING }
                pair.offerReconnect()
                assertEquals(TransferPhase.DONE, sending.await().phase)
                assertEquals(TransferPhase.DONE, receiving.await().phase)
                assertEquals(TestSupport.sha256(files[0].bytes), TestSupport.sha256(pair.receivedFile("clip.mp4")))
            }
        }

    @Test
    fun `S8 a transfer parked for 24 h is cancelled with timeout and its partials cleared`() =
        runTest(timeout = 2.minutes) {
            val resume = InMemoryResumeStore()
            pair(resume = resume).use { pair ->
                val files = listOf(MemorySource("clip.mp4", TestSupport.randomBytes(mib, 93)))
                val (sending, receiving) = pair.start(files)
                receiving.progress.first { it.bytesDone > 50_000 }
                pair.stallPrimary()
                val stalledAt = currentTime
                val received = receiving.await()
                assertEquals(TransferPhase.CANCELLED, received.phase)
                assertEquals(CancelReason.TIMEOUT, received.cancelReason)
                assertTrue(currentTime - stalledAt >= ProtocolConstants.PARKED_WINDOW_MS)
                assertFalse(resume.contains(receiving.transferId))
                assertFalse(Files.exists(pair.directoryStore.partialPath(receiving.transferId.toHex(), 0).parent))
                assertEquals(TransferPhase.CANCELLED, sending.await().phase)
            }
        }

    @Test
    fun `an offer left unanswered for 30 s is declined with timeout`() =
        runTest(timeout = 2.minutes) {
            pair().use { pair ->
                val (a, b) = pair.connect()
                val sending = pair.senderEngine.send(a, listOf(MemorySource("x", ByteArray(10))))
                val incoming = pair.scope.async { pair.receiverEngine.receive(b) }.await()
                val offeredAt = currentTime
                val received = incoming.transfer.await()
                assertEquals(TransferPhase.CANCELLED, received.phase)
                assertEquals(DeclineReason.TIMEOUT, received.declineReason)
                assertTrue(currentTime - offeredAt >= ProtocolConstants.OFFER_TIMEOUT_MS - 1_000)
                val sent = sending.await()
                assertEquals(TransferPhase.CANCELLED, sent.phase)
            }
        }

    @Test
    fun `a declined offer ends both sides with the reason`() =
        runTest(timeout = 2.minutes) {
            pair().use { pair ->
                val (a, b) = pair.connect()
                val sending = pair.senderEngine.send(a, listOf(MemorySource("x", ByteArray(10))))
                val incoming = pair.scope.async { pair.receiverEngine.receive(b) }.await()
                assertEquals(1, incoming.offer.fileCount)
                assertEquals("Sender", incoming.peerName)
                incoming.decline(DeclineReason.USER)
                val sent = sending.await()
                assertEquals(TransferPhase.CANCELLED, sent.phase)
                assertEquals(DeclineReason.USER, sent.declineReason)
                assertEquals(DeclineReason.USER, incoming.transfer.await().declineReason)
            }
        }

    @Test
    fun `a sender cancelling mid-transfer cancels the receiver, which clears its partials and resume data`() =
        runTest(timeout = 2.minutes) {
            val resume = InMemoryResumeStore()
            pair(resume = resume).use { pair ->
                val files = listOf(MemorySource("clip.mp4", TestSupport.randomBytes(3 * mib, 94)))
                val (sending, receiving) = pair.start(files)
                receiving.progress.first { it.bytesDone > 50_000 }
                sending.cancel()
                val received = receiving.await()
                assertEquals(TransferPhase.CANCELLED, received.phase)
                assertEquals(CancelReason.USER, received.cancelReason)
                assertEquals(CancelReason.USER, sending.await().cancelReason)
                assertFalse(resume.contains(receiving.transferId))
                assertTrue(pair.receivedNames().isEmpty())
            }
        }

    @Test
    fun `F-E8 streams start at 4 and rise to 8 above 40 MB per s`() =
        runTest(timeout = 2.minutes) {
            pair().use { pair ->
                val files = listOf(MemorySource("big.bin", TestSupport.randomBytes(96 * mib, 95)))
                val (sending, receiving) = pair.start(files)
                var most = 0
                var first = 0
                val watch =
                    pair.scope.launch {
                        sending.progress.collect { p ->
                            if (p.streams > 0 && first == 0) first = p.streams
                            most = maxOf(most, p.streams)
                        }
                    }
                // 20 MB/s per stream: four streams move 80 MB/s, above the 40 MB/s threshold.
                pair.attachMemory(sending, receiving, generation = 0, bytesPerSecond = 20_000_000)
                assertEquals(TransferPhase.DONE, sending.await().phase)
                assertEquals(TransferPhase.DONE, receiving.await().phase)
                watch.cancel()
                assertEquals(4, first, "starts with four streams")
                assertEquals(8, most, "rises to eight")
                assertEquals(TestSupport.sha256(files[0].bytes), TestSupport.sha256(pair.receivedFile("big.bin")))
            }
        }

    @Test
    fun `F-F6 a sender at thermal SEVERE uses two streams and both sides show the thermal hint`() =
        runTest(timeout = 2.minutes) {
            pair(senderPower = FakePower(ThermalLevel.SEVERE)).use { pair ->
                val files = listOf(MemorySource("big.bin", TestSupport.randomBytes(24 * mib, 96)))
                val (sending, receiving) = pair.start(files)
                var most = 0
                var senderSawHint = false
                var receiverSawHint = false
                val watch =
                    pair.scope.launch {
                        launch { sending.progress.collect { most = maxOf(most, it.streams) } }
                        launch { sending.progress.first { HintCode.THERMAL in it.hints }.also { senderSawHint = true } }
                        launch { receiving.progress.first { HintCode.THERMAL in it.hints }.also { receiverSawHint = true } }
                    }
                pair.attachMemory(sending, receiving, generation = 0, bytesPerSecond = 20_000_000)
                assertEquals(TransferPhase.DONE, sending.await().phase)
                assertEquals(TransferPhase.DONE, receiving.await().phase)
                watch.cancel()
                assertEquals(2, most, "two streams while hot")
                assertTrue(senderSawHint, "the hot sender shows the thermal hint")
                assertTrue(receiverSawHint, "the receiver shows the peer's thermal hint")
            }
        }
}
