package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.Ack
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.transfer.MemorySource
import com.constrivo.drop.core.transfer.TestSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-E5 and spec change S1: the Bluetooth head start moves progress within a second of `Accept` at 20 KB/s, then the
 * transfer hops to a Wi-Fi (here loopback TCP) link without sending any byte twice.
 */
class HeadStartHopTest {
    @Test
    fun `F-E5 progress within 1 s of Accept over 20 KB per s Bluetooth, then a hop to TCP with no byte sent twice`() =
        runBlocking<Unit> {
            EnginePair(primaryKind = LinkKind.BLUETOOTH, primaryBytesPerSecond = 20_000).use { pair ->
                val files =
                    listOf(
                        MemorySource("a.jpg", TestSupport.randomBytes(40_000, 11)),
                        MemorySource("b.jpg", TestSupport.randomBytes(30_000, 12)),
                        MemorySource("movie.mp4", TestSupport.randomBytes(9 * 1024 * 1024 + 5, 13)),
                    )
                withTimeout(60_000) {
                    val (a, b) = pair.connect()
                    val sending = pair.senderEngine.send(a, files, SendOptions(reconnect = pair.senderReconnect))
                    val incoming = pair.scope.async { pair.receiverEngine.receive(b) }.await()
                    val accepted = System.nanoTime()
                    val receiving = incoming.accept()
                    receiving.progress.first { it.bytesDone > 0 }
                    val receiverMillis = (System.nanoTime() - accepted) / 1_000_000
                    sending.progress.first { it.bytesDone > 0 }
                    val senderMillis = (System.nanoTime() - accepted) / 1_000_000
                    assertTrue(receiverMillis < 1_000, "receiver's first progress after $receiverMillis ms")
                    assertTrue(senderMillis < 1_100, "sender's first acked progress after $senderMillis ms")
                    assertEquals(LinkKind.BLUETOOTH, receiving.progress.value.linkKind)
                    val beforeHop = sending.progress.value.bytesDone
                    assertTrue(beforeHop in 1 until 9 * 1024 * 1024, "head start moved $beforeHop bytes")

                    pair.attachTcp(sending, receiving, generation = 0)
                    val sent = sending.await()
                    val received = receiving.await()
                    assertEquals(TransferPhase.DONE, sent.phase, "sender: $sent")
                    assertEquals(TransferPhase.DONE, received.phase, "receiver: $received")
                    assertEquals(LinkKind.LAN, received.linkKind)
                    val total = files.sumOf { it.size }
                    assertEquals(total, sending.stats.fileBytesSent, "every file byte is sent exactly once across the hop")
                }
                for (file in files) {
                    assertEquals(
                        TestSupport.sha256(file.bytes),
                        TestSupport.sha256(pair.receivedFile(file.name)),
                        file.name,
                    )
                }
            }
        }

    @Test
    fun `no progress counts bytes before the streaming phase has begun`() =
        runBlocking<Unit> {
            // The first Bluetooth block both starts Streaming_BT and brings the first bytes; every publication must show
            // them in that order, on both sides (the phase change used to be queued while the bytes were published at once).
            repeat(3) { round ->
                EnginePair(primaryKind = LinkKind.BLUETOOTH, primaryBytesPerSecond = 200_000).use { pair ->
                    val files = listOf(MemorySource("photo-$round.jpg", TestSupport.randomBytes(120_000, 20L + round)))
                    withTimeout(60_000) {
                        val (a, b) = pair.connect()
                        val sending = pair.senderEngine.send(a, files, SendOptions(reconnect = pair.senderReconnect))
                        val incoming = pair.scope.async { pair.receiverEngine.receive(b) }.await()
                        val seen = CopyOnWriteArrayList<TransferProgress>()
                        val watchers =
                            listOf(sending.progress, incoming.transfer.progress).map { flow ->
                                pair.scope.launch(Dispatchers.Unconfined) { flow.collect { seen += it } }
                            }
                        val receiving = incoming.accept()
                        assertEquals(TransferPhase.DONE, sending.await().phase)
                        assertEquals(TransferPhase.DONE, receiving.await().phase)
                        watchers.forEach { it.cancel() }
                        val early =
                            seen.filter {
                                it.bytesDone > 0 &&
                                    (it.phase == TransferPhase.OFFERED || it.phase == TransferPhase.ACCEPTED)
                            }
                        assertTrue(early.isEmpty(), "bytes counted before streaming began: $early")
                        assertTrue(seen.any { it.bytesDone > 0 && it.phase == TransferPhase.STREAMING_BLUETOOTH }, "head start seen")
                    }
                }
            }
        }

    @Test
    fun `repeated hops between Bluetooth and Wi-Fi never lose the rest of a unit`() =
        runBlocking<Unit> {
            EnginePair(primaryKind = LinkKind.BLUETOOTH, primaryBytesPerSecond = 400_000).use { pair ->
                val files = listOf(MemorySource("movie.mp4", TestSupport.randomBytes(40 * 1024 * 1024 + 17, 14)))
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    // The link is up but data stays on Bluetooth until the test moves it.
                    pair.attachMemory(sending, receiving, generation = 0, use = false, bytesPerSecond = 3_000_000)
                    val random = kotlin.random.Random(7)
                    repeat(24) {
                        // On Bluetooth long enough for a block or two, then hop; the producer is idle in take() when the
                        // Bluetooth stream puts the rest of its unit back.
                        sending.useLink(null)
                        delay(60L + random.nextLong(60))
                        sending.useLink(0)
                        delay(20L + random.nextLong(40))
                    }
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                    assertEquals(files.sumOf { it.size }, sending.stats.fileBytesSent, "no byte twice across 24 hops")
                }
                assertEquals(TestSupport.sha256(files[0].bytes), TestSupport.sha256(pair.receivedFile("movie.mp4")))
            }
        }

    @Test
    fun `a Bluetooth block written after the rest of its unit still makes the unit durable`() =
        runBlocking<Unit> {
            // The Bluetooth reader is slow to queue its blocks' writes; the hop happens meanwhile, and the Wi-Fi stream
            // brings the rest of the unit. Every unit must still reach the manifest and the file its SHA-256 check.
            val hooks =
                DebugHooks(beforeWritesQueued = { unit, offset ->
                    if (!unit.isBundle && offset > 0 && offset < ProtocolConstants.CHUNK_SIZE) Thread.sleep(150)
                })
            EnginePair(primaryKind = LinkKind.BLUETOOTH, primaryBytesPerSecond = 200_000, debug = hooks).use { pair ->
                val files = listOf(MemorySource("clip.mp4", TestSupport.randomBytes(6 * 1024 * 1024 + 3, 15)))
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    receiving.progress.first { it.bytesDone > 3 * ProtocolConstants.BLUETOOTH_BLOCK_SIZE }
                    pair.attachTcp(sending, receiving, generation = 0)
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                }
                assertEquals(TestSupport.sha256(files[0].bytes), TestSupport.sha256(pair.receivedFile("clip.mp4")))
            }
        }

    @Test
    fun `a Bluetooth block ack lost with the receiver's Wi-Fi control route is recovered by the Resume that follows`() =
        runBlocking<Unit> {
            val block = ProtocolConstants.BLUETOOTH_BLOCK_SIZE
            val dropped = CompletableDeferred<Unit>()
            val hooks =
                DebugHooks(dropControl = { message ->
                    val lose =
                        message is Ack && !dropped.isCompleted &&
                            message.chunks.any { it.fileIndex == 0 && (it.blockOffset ?: 0) >= 8 * block }
                    if (lose) dropped.complete(Unit)
                    lose
                })
            EnginePair(primaryKind = LinkKind.BLUETOOTH, primaryBytesPerSecond = 300_000, debug = hooks).use { pair ->
                val files = listOf(MemorySource("clip.mp4", TestSupport.randomBytes(1024 * 1024 + 11, 16)))
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    // The receiver's control moves to a Wi-Fi stream; the sender's data stays on Bluetooth.
                    val channels = pair.attachMemory(sending, receiving, generation = 0, use = false)
                    receiving.moveControl(0)
                    dropped.await()
                    // The sender now waits for an ack that is gone. The Wi-Fi link dies; the receiver's route falls back
                    // to Bluetooth and it re-states what it misses, which settles the block.
                    delay(200)
                    channels.toList().forEach { it.close() }
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                    assertEquals(0, sending.stats.sessionEpoch, "no reconnect was needed")
                }
                assertEquals(TestSupport.sha256(files[0].bytes), TestSupport.sha256(pair.receivedFile("clip.mp4")))
            }
        }
}
