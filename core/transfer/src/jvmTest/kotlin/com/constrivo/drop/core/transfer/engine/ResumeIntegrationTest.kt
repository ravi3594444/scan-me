package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferTimeouts
import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.MemorySource
import com.constrivo.drop.core.transfer.PartialFile
import com.constrivo.drop.core.transfer.PublishedFile
import com.constrivo.drop.core.transfer.TestSupport
import com.constrivo.drop.core.transfer.receive.InMemoryResumeStore
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T-05, T-06 and T-07 equivalents: link loss mid-chunk, reconnect with a new handshake (N3), resume (§7.6, N5). */
class ResumeIntegrationTest {
    private val mib = ProtocolConstants.MIB

    private fun files() =
        listOf(
            MemorySource("photo-1.jpg", TestSupport.randomBytes(300_000, 21)),
            MemorySource("photo-2.jpg", TestSupport.randomBytes(200_000, 22)),
            MemorySource("video.mp4", TestSupport.randomBytes(22 * mib + 123, 23)),
            MemorySource("notes.txt", TestSupport.randomBytes(2 * mib + 1, 24)),
        )

    @Test
    fun `T-05 and T-06 a link cut mid-chunk resumes over a new handshake and completes byte-exact`() =
        runBlocking<Unit> {
            val resume = InMemoryResumeStore()
            EnginePair(primaryKind = LinkKind.LAN, resumeStore = resume).use { pair ->
                val files = files()
                var id: com.constrivo.drop.core.protocol.TransferId? = null
                withTimeout(60_000) {
                    // The sender's writes stop after 9 MiB: the link dies in the middle of a 4 MiB chunk frame.
                    val sessions = pair.connect(cutAfterBytes = 9L * mib + 777)
                    val (sending, receiving) = pair.start(files, sessions = sessions)
                    id = sending.transferId
                    sending.progress.first { it.phase == TransferPhase.RECONNECTING }
                    receiving.progress.first { it.phase == TransferPhase.RECONNECTING }
                    val before = receiving.progress.value.bytesDone
                    assertTrue(before > 0, "some data arrived before the cut")
                    // The link comes back (Wi-Fi on again, back in range): a new handshake, then Resume.
                    pair.offerReconnect()
                    val sent = sending.await()
                    val received = receiving.await()
                    assertEquals(TransferPhase.DONE, sent.phase, "sender: $sent")
                    assertEquals(TransferPhase.DONE, received.phase, "receiver: $received")
                    assertEquals(1, sending.stats.sessionEpoch, "exactly one new session")
                    val total = files.sumOf { it.size }
                    val resent = sending.stats.fileBytesSent - total
                    // Only the units in flight at the cut go twice (at most two per stream on one stream).
                    assertTrue(resent in 0..(2L * ProtocolConstants.CHUNK_SIZE), "resent $resent bytes")
                }
                for (file in files) {
                    assertEquals(
                        TestSupport.sha256(file.bytes),
                        TestSupport.sha256(pair.receivedFile(file.name)),
                        file.name,
                    )
                }
                assertTrue(!resume.contains(assertNotNull(id)), "resume data is gone after completion")
            }
        }

    @Test
    fun `N3 a stored transfer resumes only with the peer that started it`() =
        runBlocking<Unit> {
            val resume = InMemoryResumeStore()
            EnginePair(primaryKind = LinkKind.LAN, primaryBytesPerSecond = 8L * mib, resumeStore = resume).use { pair ->
                val files = files()
                val id: com.constrivo.drop.core.protocol.TransferId
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    id = sending.transferId
                    receiving.progress.first { it.bytesDone >= 6L * mib }
                    delay(300)
                    pair.killEngines()
                }
                assertNotNull(resume.load(id))
                withTimeout(30_000) {
                    // Another device that learnt the transfer id offers the same files: it must not continue them.
                    val stranger = TestSupport.sessionConfig("Stranger")
                    val (a, b) = TestSupport.handshake(stranger, pair.receiverConfig, pair.newPrimary())
                    TransferEngine(pair.engineConfig(stranger, pair.senderStore), pair.scope).send(a, files, SendOptions(transferId = id))
                    val incoming = pair.scope.async { pair.receiverEngine.receive(b) }.await()
                    assertTrue(!incoming.isResume, "a record made with another peer is not a resume")
                    // The real sender, on a fresh handshake, still resumes.
                    val (c, d) = pair.connect()
                    pair.senderEngine.send(c, files, SendOptions(transferId = id))
                    val again = pair.scope.async { pair.receiverEngine.receive(d) }.await()
                    assertTrue(again.isResume, "the peer that started it resumes")
                }
            }
        }

    /** A receiver store whose partial reads take [readDelayMillis] each while [slow] is set (a removable card). */
    private class SlowReadStore(
        private val delegate: DirectoryFileStore,
        private val readDelayMillis: Long,
    ) : FileStore by delegate {
        @Volatile
        var slow = true

        override suspend fun openPartial(
            transferId: String,
            fileIndex: Int,
            expectedSize: Long,
        ): PartialFile {
            val inner = delegate.openPartial(transferId, fileIndex, expectedSize)
            return object : PartialFile by inner {
                override suspend fun read(
                    position: Long,
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (slow) delay(readDelayMillis)
                    return inner.read(position, buffer, offset, length)
                }
            }
        }

        override suspend fun publish(
            partial: PartialFile,
            name: String,
            mimeType: String?,
            drop: DropInfo,
        ): PublishedFile = delegate.publish(partial, name, mimeType, drop)
    }

    @Test
    fun `T-07 re-reading gigabytes of partials does not hold the Accept past the offer window`() =
        runBlocking<Unit> {
            val resume = InMemoryResumeStore()
            var slowStore: SlowReadStore? = null
            // A 3 s offer window and 300 ms per 256 KiB read: re-hashing the stored units before the Accept would need
            // well over 10 s (the old behaviour), so the sender would give up with `timeout`.
            EnginePair(
                primaryKind = LinkKind.LAN,
                primaryBytesPerSecond = 8L * mib,
                resumeStore = resume,
                timeouts = TransferTimeouts(offerMillis = 3_000),
                receiverStore = { dir ->
                    SlowReadStore(DirectoryFileStore(dir.resolve("partials"), dir.resolve("received")), 300).also {
                        it.slow = false
                        slowStore = it
                    }
                },
            ).use { pair ->
                val files = files()
                val id: com.constrivo.drop.core.protocol.TransferId
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    id = sending.transferId
                    receiving.progress.first { it.bytesDone >= 10L * mib }
                    delay(400)
                    pair.killEngines()
                }
                val stored = assertNotNull(resume.load(id)).manifests.values.sumOf { it.receivedCount }
                assertTrue(stored >= 2, "units stored before the kill: $stored")
                withTimeout(60_000) {
                    val store = assertNotNull(slowStore)
                    store.slow = true
                    val (a, b) = pair.connect()
                    val sending = pair.senderEngine.send(a, files, SendOptions(transferId = id))
                    val incoming = pair.scope.async { pair.receiverEngine.receive(b) }.await()
                    assertTrue(incoming.isResume)
                    val started = System.nanoTime()
                    incoming.accept()
                    assertNotNull(sending.accept.first { it != null }, "the Accept arrived")
                    val millis = (System.nanoTime() - started) / 1_000_000
                    assertTrue(millis < 3_000, "the Accept took $millis ms")
                    store.slow = false
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                }
                for (file in files) {
                    assertEquals(TestSupport.sha256(file.bytes), TestSupport.sha256(pair.receivedFile(file.name)), file.name)
                }
            }
        }

    @Test
    fun `T-07 an app kill keeps the resume data, a restart re-verifies the partials in the background and completes`() =
        runBlocking<Unit> {
            val resume = InMemoryResumeStore()
            EnginePair(primaryKind = LinkKind.LAN, primaryBytesPerSecond = 8L * mib, resumeStore = resume).use { pair ->
                val files = files()
                val id: com.constrivo.drop.core.protocol.TransferId
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    id = sending.transferId
                    receiving.progress.first { it.bytesDone >= 10L * mib }
                    // Let the write-behind flush (at most 100 ms, N5) persist what is on disk.
                    delay(400)
                    pair.killEngines()
                }
                val record = assertNotNull(resume.load(id), "the resume record survives the kill")
                val durable = record.manifests.values.sumOf { it.receivedCount }
                assertTrue(durable >= 2, "units marked durable before the kill: $durable")
                // Damage the first byte of the first durable chunk of the video; re-verification must catch it (N5).
                val video = 2
                val manifest = assertNotNull(record.manifests[video])
                val bad = (0 until manifest.unitCount).first { manifest.isReceived(it) }
                val part = pair.directoryStore.partialPath(id.toHex(), video)
                FileChannel.open(part, StandardOpenOption.WRITE, StandardOpenOption.READ).use { ch ->
                    val one = java.nio.ByteBuffer.allocate(1)
                    ch.read(one, bad.toLong() * ProtocolConstants.CHUNK_SIZE)
                    one.flip()
                    val flipped = (one.get(0).toInt() xor 0x5A).toByte()
                    ch.write(java.nio.ByteBuffer.wrap(byteArrayOf(flipped)), bad.toLong() * ProtocolConstants.CHUNK_SIZE)
                }

                withTimeout(60_000) {
                    // Both apps start again: a fresh handshake, the sender offers the same transfer.
                    val (a, b) = pair.connect()
                    val sending = pair.senderEngine.send(a, files, SendOptions(transferId = id))
                    val incoming = pair.scope.async { pair.receiverEngine.receive(b) }.await()
                    assertTrue(incoming.isResume, "the receiver recognises the interrupted transfer")
                    val receiving = incoming.accept()
                    val accept = sending.accept.first { it != null }!!
                    val missing = assertNotNull(accept.resume, "a resumed Accept carries the missing set")
                    val layout =
                        com.constrivo.drop.core.protocol.TransferLayout.of(
                            files.map {
                                it.size
                            },
                            ProtocolConstants.CHUNK_SIZE,
                            true,
                        )
                    val requested = layout.expand(missing).map { it.unit }.toSet()
                    // The Accept goes out after a length check only (within the 30 s offer window); the re-hash runs
                    // while the rest streams and asks for the damaged unit with a Retransmit.
                    assertTrue(requested.size < layout.totalUnits, "units that survived are not requested")
                    val sent = sending.await()
                    val received = receiving.await()
                    assertEquals(TransferPhase.DONE, sent.phase, "sender: $sent")
                    assertEquals(TransferPhase.DONE, received.phase, "receiver: $received")
                    assertEquals(1, receiving.stats.resumeDamagedUnits, "the re-hash found the damaged unit")
                    assertEquals(0, receiving.stats.fileHashMismatches, "it was requested before any whole-file check")
                    val total = files.sumOf { it.size }
                    assertTrue(
                        sending.stats.fileBytesSent < total,
                        "the restart sent only what was missing (${sending.stats.fileBytesSent} of $total)",
                    )
                }
                for (file in files) {
                    assertEquals(
                        TestSupport.sha256(file.bytes),
                        TestSupport.sha256(pair.receivedFile(file.name)),
                        file.name,
                    )
                }
                assertTrue(!resume.contains(id), "resume data is gone after completion")
                assertTrue(!Files.exists(pair.directoryStore.partialPath(id.toHex(), 0).parent), "partials are gone after completion")
            }
        }
}
