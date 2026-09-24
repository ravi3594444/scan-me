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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
