package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.crypto.frame.FrameLimitException
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.CompleteStatus
import com.constrivo.drop.core.protocol.DeclineReason
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferUnit
import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.MemorySource
import com.constrivo.drop.core.transfer.PartialFile
import com.constrivo.drop.core.transfer.PublishedFile
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.StorageFullException
import com.constrivo.drop.core.transfer.TestSupport
import com.constrivo.drop.core.transfer.receive.InMemoryResumeStore
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T-27, T-28 and N3 equivalents: a full disk, corrupted plaintext caught by the per-frame hash, a key at its limit. */
class FaultInjectionTest {
    private val mib = ProtocolConstants.MIB

    /** A store with a fixed free-space answer that fails writes once [budget] bytes were written (a full disk). */
    private class LimitedStore(
        private val delegate: DirectoryFileStore,
        private val free: Long,
        private val budget: Long,
    ) : FileStore by delegate {
        val written = AtomicLong()

        override suspend fun freeBytes(): Long = free

        override suspend fun openPartial(
            transferId: String,
            fileIndex: Int,
            expectedSize: Long,
        ): PartialFile {
            val inner = delegate.openPartial(transferId, fileIndex, expectedSize)
            return object : PartialFile by inner {
                override suspend fun write(
                    position: Long,
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    if (written.addAndGet(length.toLong()) > budget) throw StorageFullException("No space left on device")
                    inner.write(position, buffer, offset, length)
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

    private fun files(): List<SourceFile> =
        listOf(
            MemorySource("a.jpg", TestSupport.randomBytes(120_000, 31)),
            MemorySource("b.jpg", TestSupport.randomBytes(80_000, 32)),
            MemorySource("clip.mp4", TestSupport.randomBytes(13 * mib + 99, 33)),
        )

    @Test
    fun `T-28 a chunk corrupted before sealing passes the AEAD, fails its hash and is sent again`() =
        runBlocking<Unit> {
            val corrupted = AtomicInteger()
            val hooks =
                DebugHooks(corruptPlaintext = { unit, _, payload, offset, length ->
                    if (unit == TransferUnit(2, 1) && length > 0 && corrupted.getAndIncrement() == 0) {
                        payload[offset + length / 2] = (payload[offset + length / 2].toInt() xor 0x01).toByte()
                        true
                    } else {
                        false
                    }
                })
            EnginePair(primaryKind = LinkKind.LAN, debug = hooks).use { pair ->
                val files = files()
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    val received = receiving.await()
                    assertEquals(TransferPhase.DONE, received.phase, "receiver: $received")
                    assertEquals(CompleteStatus.OK, received.completeStatus)
                }
                assertTrue(corrupted.get() >= 2, "the unit went out corrupted once and again intact")
                for (file in files) {
                    val source = file as MemorySource
                    assertEquals(TestSupport.sha256(source.bytes), TestSupport.sha256(pair.receivedFile(file.name)), file.name)
                }
            }
        }

    @Test
    fun `three corrupted copies of a bundle fail its files and the rest of the transfer still completes`() =
        runBlocking<Unit> {
            val hooks =
                DebugHooks(corruptPlaintext = { unit, _, payload, offset, length ->
                    if (unit.isBundle && length > 0) {
                        payload[offset + length - 1] = (payload[offset + length - 1].toInt() xor 0x80).toByte()
                        true
                    } else {
                        false
                    }
                })
            EnginePair(primaryKind = LinkKind.LAN, debug = hooks).use { pair ->
                val files = files()
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    val received = receiving.await()
                    val sent = sending.await()
                    assertEquals(TransferPhase.DONE, received.phase, "receiver: $received")
                    assertEquals(CompleteStatus.PARTIAL, received.completeStatus)
                    assertEquals(CompleteStatus.PARTIAL, sent.completeStatus)
                    assertEquals(listOf(0, 1), sending.state.value.failedFiles.toList(), "both bundled files failed")
                    assertEquals(FileStatus.FAILED, received.files[0].status)
                    assertEquals(FileStatus.DONE, received.files[2].status)
                }
                assertEquals(listOf("clip.mp4"), pair.receivedNames())
                assertEquals(TestSupport.sha256((files[2] as MemorySource).bytes), TestSupport.sha256(pair.receivedFile("clip.mp4")))
            }
        }

    @Test
    fun `T-27 an offer that does not fit is declined with storage and leaves nothing behind`() =
        runBlocking<Unit> {
            val resume = InMemoryResumeStore()
            EnginePair(primaryKind = LinkKind.LAN, resumeStore = resume, receiverStore = { dir ->
                LimitedStore(DirectoryFileStore(dir.resolve("partials"), dir.resolve("received")), free = 5L * mib, budget = Long.MAX_VALUE)
            }).use { pair ->
                withTimeout(30_000) {
                    val (sending, receiving) = pair.start(files())
                    val sent = sending.await()
                    val received = receiving.await()
                    assertEquals(TransferPhase.CANCELLED, sent.phase)
                    assertEquals(DeclineReason.STORAGE, sent.declineReason)
                    assertEquals(DeclineReason.STORAGE, received.declineReason)
                    assertFalse(resume.contains(sending.transferId))
                }
                assertTrue(pair.receivedNames().isEmpty())
            }
        }

    @Test
    fun `T-27 the disk filling up mid-transfer cancels with storage on both sides and clears the partials`() =
        runBlocking<Unit> {
            val resume = InMemoryResumeStore()
            var partialsDir: java.nio.file.Path? = null
            EnginePair(primaryKind = LinkKind.LAN, resumeStore = resume, receiverStore = { dir ->
                partialsDir = dir.resolve("partials")
                LimitedStore(DirectoryFileStore(dir.resolve("partials"), dir.resolve("received")), free = 1L shl 40, budget = 6L * mib)
            }).use { pair ->
                withTimeout(30_000) {
                    val (sending, receiving) = pair.start(files())
                    val received = receiving.await()
                    val sent = sending.await()
                    assertEquals(TransferPhase.CANCELLED, received.phase, "receiver: $received")
                    assertEquals(CancelReason.STORAGE, received.cancelReason)
                    assertEquals(TransferPhase.CANCELLED, sent.phase, "sender: $sent")
                    assertEquals(CancelReason.STORAGE, sent.cancelReason)
                    assertFalse(resume.contains(sending.transferId), "resume data cleared")
                    val left = Files.list(partialsDir!!).use { it.count() }
                    assertEquals(0L, left, "partials cleared")
                }
            }
        }

    @Test
    fun `N3 a key reaching its frame limit forces a new handshake and the transfer resumes under fresh keys`() =
        runBlocking<Unit> {
            val seals = AtomicInteger()
            val hooks =
                DebugHooks(beforeChunkSealed = { _ ->
                    if (seals.incrementAndGet() == 2) throw FrameLimitException("stream reached its limit (test)")
                })
            EnginePair(primaryKind = LinkKind.LAN, debug = hooks).use { pair ->
                val files = files()
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    // The sender drops the session on its own; the link itself is fine and comes back at once.
                    pair.offerReconnect()
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                    assertEquals(1, sending.stats.sessionEpoch, "one new session with fresh keys")
                }
                for (file in files) {
                    assertEquals(
                        TestSupport.sha256((file as MemorySource).bytes),
                        TestSupport.sha256(pair.receivedFile(file.name)),
                        file.name,
                    )
                }
            }
        }
}
