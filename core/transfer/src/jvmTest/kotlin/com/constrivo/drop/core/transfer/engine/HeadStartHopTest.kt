package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.transfer.MemorySource
import com.constrivo.drop.core.transfer.TestSupport
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
}
