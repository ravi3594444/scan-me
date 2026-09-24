package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.ControlMoved
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.transfer.MemorySource
import com.constrivo.drop.core.transfer.TestSupport
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import com.constrivo.drop.core.transfer.net.TcpListener
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N13: control moves to the first Wi-Fi stream; when that link dies while Bluetooth survives, both devices fall back to
 * the Bluetooth control stream without an interruption, the receiver re-states its missing set, data continues over
 * Bluetooth, and a new link generation takes over again.
 */
class LinkLossIntegrationTest {
    private val mib = ProtocolConstants.MIB

    private class Recorder : TransferLinkListener {
        val moved = CopyOnWriteArrayList<ControlMoved>()
        val lost = CopyOnWriteArrayList<Int>()

        override fun onPeerControlMoved(message: ControlMoved) {
            moved += message
        }

        override fun onLinkLost(
            kind: LinkKind,
            generation: Int,
        ) {
            lost += generation
        }
    }

    @Test
    fun `N13 losing the Wi-Fi link falls back to Bluetooth control, then a new link generation finishes the transfer`() =
        runBlocking<Unit> {
            EnginePair(primaryKind = LinkKind.BLUETOOTH, primaryBytesPerSecond = 200_000).use { pair ->
                val files =
                    listOf(
                        MemorySource("a.bin", TestSupport.randomBytes(30 * mib + 11, 101)),
                        MemorySource("b.txt", TestSupport.randomBytes(70_000, 102)),
                    )
                val senderEvents = Recorder()
                val receiverEvents = Recorder()
                withTimeout(60_000) {
                    val (sending, receiving) =
                        pair.start(
                            files,
                            options = SendOptions(reconnect = pair.senderReconnect, listener = senderEvents),
                            receive = ReceiveOptions(reconnect = pair.receiverReconnect, listener = receiverEvents),
                        )
                    // A slow in-memory Wi-Fi link: 4 streams of 2 MB/s.
                    val channels = pair.attachMemory(sending, receiving, generation = 0, bytesPerSecond = 2_000_000)
                    receiving.progress.first { it.linkKind == LinkKind.P2P && it.bytesDone > 4L * mib }
                    assertTrue(senderEvents.moved.any { it.generation == 0 }, "the receiver moved its control to generation 0")
                    assertTrue(receiverEvents.moved.any { it.generation == 0 }, "the sender moved its control to generation 0")

                    // The group disappears: every stream of generation 0 dies at once; Bluetooth is still there.
                    channels.toList().forEach { it.close() }
                    receiving.progress.first { receiverEvents.lost.contains(0) }
                    assertTrue(receiving.progress.value.phase.isConnected, "no interruption: ${receiving.progress.value.phase}")
                    val atLoss = receiving.progress.value.bytesDone

                    // The ladder brings a new generation up; it carries the rest.
                    pair.attachTcp(sending, receiving, generation = 1)
                    val sent = sending.await()
                    val received = receiving.await()
                    assertEquals(TransferPhase.DONE, sent.phase, "sender: $sent")
                    assertEquals(TransferPhase.DONE, received.phase, "receiver: $received")
                    assertEquals(0, sending.stats.sessionEpoch, "the session survived the link loss")
                    assertTrue(atLoss < files.sumOf { it.size })
                    assertTrue(senderEvents.moved.any { it.generation == 1 })
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
    fun `the receiver can host the link and the sender open the streams`() =
        runBlocking<Unit> {
            EnginePair(primaryKind = LinkKind.BLUETOOTH, primaryBytesPerSecond = 50_000).use { pair ->
                val files = listOf(MemorySource("a.bin", TestSupport.randomBytes(20 * mib + 7, 103)))
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    pair.attachTcp(sending, receiving, generation = 0, senderHosts = false)
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                    assertEquals(files.sumOf { it.size }, sending.stats.fileBytesSent)
                }
                assertEquals(TestSupport.sha256(files[0].bytes), TestSupport.sha256(pair.receivedFile("a.bin")))
            }
        }

    @Test
    fun `idle and garbage connections to the host's port neither block the link nor use up its accept loop`() =
        runBlocking<Unit> {
            EnginePair(primaryKind = LinkKind.BLUETOOTH, primaryBytesPerSecond = 50_000).use { pair ->
                val files = listOf(MemorySource("a.bin", TestSupport.randomBytes(20 * mib + 5, 104)))
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    val listener = TcpListener(kind = LinkKind.LAN)
                    val strangers = ArrayList<SocketChannel>()
                    try {
                        // Before the joiner: two peers that connect and say nothing, and eight port scans that send
                        // garbage or nothing and hang up (more than the accept loop's failure limit).
                        repeat(2) { strangers += SocketChannel.open(InetSocketAddress("127.0.0.1", listener.port)) }
                        repeat(8) { i ->
                            SocketChannel.open(InetSocketAddress("127.0.0.1", listener.port)).use { scan ->
                                if (i % 2 == 0) scan.write(ByteBuffer.wrap(TestSupport.randomBytes(64, 200L + i)))
                            }
                        }
                        val host = DataLink(LinkKind.LAN, 0, DataLinkRole.ACCEPT) { listener.accept() }
                        val join =
                            DataLink(LinkKind.LAN, 0, DataLinkRole.CONNECT) {
                                TcpDataChannel.connect("127.0.0.1", listener.port, LinkKind.LAN)
                            }
                        coroutineScope {
                            launch { sending.connectLink(host) }
                            launch { receiving.connectLink(join) }
                        }
                        // The data streams that follow the control stream get through too.
                        receiving.progress.first { it.streams >= 4 || it.isTerminal }
                        assertEquals(TransferPhase.DONE, sending.await().phase)
                        assertEquals(TransferPhase.DONE, receiving.await().phase)
                        assertEquals(LinkKind.LAN, receiving.progress.value.linkKind)
                    } finally {
                        strangers.forEach { runCatching { it.close() } }
                        listener.close()
                    }
                }
                assertEquals(TestSupport.sha256(files[0].bytes), TestSupport.sha256(pair.receivedFile("a.bin")))
            }
        }
}
