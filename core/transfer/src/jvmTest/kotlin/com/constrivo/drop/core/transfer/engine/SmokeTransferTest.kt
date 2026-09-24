package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.transfer.MemorySource
import com.constrivo.drop.core.transfer.TestSupport
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeTransferTest {
    @Test
    fun threeFilesOverAnInMemoryLanPrimary() =
        runBlocking<Unit> {
            val dir = TestSupport.tempDir("smoke")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val senderConfig = TestSupport.sessionConfig("Sender")
                val receiverConfig = TestSupport.sessionConfig("Receiver")
                val (a, b) = TestSupport.handshake(senderConfig, receiverConfig, TestSupport.memoryPair(LinkKind.LAN))
                val store = DirectoryFileStore(dir.resolve("partials"), dir.resolve("received"))
                val sendEngine = TransferEngine(EngineConfig(senderConfig, store, lingerMillis = 200), scope)
                val receiveEngine = TransferEngine(EngineConfig(receiverConfig, store, lingerMillis = 200), scope)
                val files =
                    listOf(
                        MemorySource("small.txt", TestSupport.randomBytes(1000, 1)),
                        MemorySource("big.bin", TestSupport.randomBytes(5 * 1024 * 1024 + 17, 2)),
                        MemorySource("empty.dat", ByteArray(0)),
                    )
                withTimeout(30_000) {
                    val sending = sendEngine.send(a, files)
                    val incoming = async { receiveEngine.receive(b) }.await()
                    val receiving = incoming.accept()
                    val sent = sending.await()
                    val received = receiving.await()
                    assertEquals(TransferPhase.DONE, sent.phase, "sender: $sent")
                    assertEquals(TransferPhase.DONE, received.phase, "receiver: $received")
                }
                for (file in files) {
                    val out = dir.resolve("received").resolve(file.name)
                    assertEquals(TestSupport.sha256(file.bytes), TestSupport.sha256(out), file.name)
                }
                assertEquals(3, Files.list(dir.resolve("received")).count())
            } finally {
                scope.cancel()
                TestSupport.deleteTree(dir)
            }
        }

    @Test
    fun `each disk-backed source is closed once its file is sent and acknowledged`() =
        runBlocking<Unit> {
            EnginePair(primaryKind = LinkKind.LAN, primaryBytesPerSecond = 4_000_000).use { pair ->
                val sources = pair.dir.resolve("sources")
                Files.createDirectories(sources)
                val small =
                    (0 until 400).map { i ->
                        val path = sources.resolve("note-%03d.txt".format(i))
                        Files.write(path, TestSupport.randomBytes(3_000, 300L + i))
                        path
                    }
                val big = sources.resolve("zz-video.mp4").also { Files.write(it, TestSupport.randomBytes(12 * 1024 * 1024, 299)) }
                val files = (small + listOf(big)).map { pair.senderStore.source(it) }
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    // Bundles go first; once they are acked and their FileDones are out, their files are closed, so
                    // while the big file streams at most it (and a hash pass) holds a handle.
                    receiving.progress.first { p -> (p.files.lastOrNull()?.bytesDone ?: 0) > 0 }
                    val bigSize = Files.size(big)
                    while (pair.senderStore.openSources > 2 &&
                        (receiving.progress.value.files.lastOrNull()?.bytesDone ?: 0) < bigSize
                    ) {
                        delay(20)
                    }
                    assertTrue(
                        pair.senderStore.openSources <= 2,
                        "open sources while the last file streams: ${pair.senderStore.openSources}",
                    )
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                }
                assertEquals(0, pair.senderStore.openSources, "no source is left open")
            }
        }
}
