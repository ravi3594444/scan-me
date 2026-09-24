package com.constrivo.drop.platform.desktop.data

import com.constrivo.drop.core.crypto.InMemorySecretStorage
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.NewTransfer
import com.constrivo.drop.core.data.TransferDirection
import com.constrivo.drop.core.data.TransferOutcome
import com.constrivo.drop.core.data.TransferStatus
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.Sha256Digest
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.receive.FileResumeState
import com.constrivo.drop.core.transfer.receive.FileResumeStatus
import com.constrivo.drop.core.transfer.receive.ResumeSummary
import com.constrivo.drop.core.transfer.receive.UnitManifest
import com.constrivo.drop.platform.desktop.data.DataResumeStore.Companion.toChunkManifest
import com.constrivo.drop.platform.desktop.data.DataResumeStore.Companion.toUnitManifest
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataResumeStoreTest {
    private val crypto = JcaCryptoProvider()
    private val dir: Path = Files.createTempDirectory("drop-resume-")
    private val data: DropData = DesktopDatabase.inMemory(InMemorySecretStorage(), crypto)
    private val errors = ArrayList<String>()
    private val plans = FileResumePlanStore({ id -> dir.resolve(id.toHex()) })
    private val store = DataResumeStore(data, plans) { m, e -> errors += "$m: $e" }
    private val peer = crypto.generateEd25519().publicKey
    private val id = TransferId(ByteArray(16) { (it + 1).toByte() })
    private val summary =
        ResumeSummary(fileCount = 3, totalBytes = 10_000_123, chunkSize = 4 * 1024 * 1024, bundleSmall = true, bundleCount = 1)
    private val files =
        listOf(
            FileEntry(0, "Holiday/a.jpg", 100, "image/jpeg"),
            FileEntry(1, "b.txt", 23, null),
            FileEntry(2, "movie.mp4", 10_000_000, "video/mp4"),
        )

    @AfterTest
    fun cleanUp() {
        data.close()
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private suspend fun receiveRow() {
        val device = data.devices.recordPeer(peer, "Alice", DevicePlatform.LAPTOP)
        data.transfers.create(NewTransfer(id, device.id, TransferDirection.RECEIVE, summary.totalBytes, summary.fileCount))
    }

    @Test
    fun `a record is created, loaded back field for field, and deleted`() =
        runBlocking<Unit> {
            receiveRow()
            assertNull(store.load(id), "no record before create")
            store.create(id, summary, files, 1_000, peer)
            val record = assertNotNull(store.load(id))
            assertEquals(summary, record.summary)
            assertContentEquals(peer, record.peerIdentityKey)
            assertEquals(files.map { it.copy(modifiedMillis = null) }, record.files)
            assertTrue(record.manifests.isEmpty())
            assertTrue(record.fileStates.isEmpty())

            val hash = ChunkHash(ByteArray(16) { 7 })
            val manifest = UnitManifest.empty(id, 2, 3, 2_000)
            val bitmap = byteArrayOf(0b001)
            val hashes = ByteArray(48).also { hash.toByteArray().copyInto(it, 0) }
            val updated = UnitManifest(id, 2, 3, bitmap, hashes, partialUnit = 1, partialBytes = 16_384, updatedAtMillis = 2_500)
            store.putManifests(listOf(manifest))
            store.putManifests(listOf(updated))
            val sha = Sha256Digest(ByteArray(32) { 3 })
            store.putFileState(id, 0, FileResumeState(FileResumeStatus.DONE, sha, "file:///r/a.jpg"), 3_000)
            store.putFileState(id, 1, FileResumeState(FileResumeStatus.FAILED), 3_000)
            store.putFileState(id, 2, FileResumeState(FileResumeStatus.PENDING, sha), 3_000)

            val loaded = assertNotNull(store.load(id))
            assertEquals(mapOf(2 to updated), loaded.manifests)
            assertEquals(FileResumeState(FileResumeStatus.DONE, sha, "file:///r/a.jpg"), loaded.fileStates[0])
            assertEquals(FileResumeStatus.FAILED, loaded.fileStates[1]?.status)
            assertEquals(FileResumeState(FileResumeStatus.PENDING, sha), loaded.fileStates[2])

            store.delete(id)
            assertNull(store.load(id))
            assertTrue(data.manifests.forTransfer(id).isEmpty())
            assertEquals(3, data.transferFiles.files(id).size, "History keeps the files")
            assertTrue(errors.isEmpty(), errors.toString())
        }

    @Test
    fun `creating again replaces the manifests and starts the listed files over`() =
        runBlocking<Unit> {
            receiveRow()
            store.create(id, summary, files, 1_000, peer)
            store.putManifests(listOf(UnitManifest.empty(id, 2, 3, 2_000)))
            val sha = Sha256Digest(ByteArray(32) { 3 })
            store.putFileState(id, 0, FileResumeState(FileResumeStatus.DONE, sha, "file:///r/a.jpg"), 3_000)
            store.putFileState(id, 1, FileResumeState(FileResumeStatus.FAILED), 3_000)
            store.create(id, summary, files, 4_000, peer)
            val record = assertNotNull(store.load(id))
            assertTrue(record.manifests.isEmpty())
            assertEquals(3, data.transferFiles.files(id).size)
            // A done file of the previous attempt is not done in the new one (its layout may differ).
            assertNotEquals(FileResumeStatus.DONE, record.fileStates[0]?.status)
            assertNotEquals(FileResumeStatus.FAILED, record.fileStates[1]?.status)
            assertNull(data.transferFiles.file(id, 0)?.savedUri, "the old saved URI goes with the old attempt")
            assertTrue(errors.isEmpty(), errors.toString())
        }

    @Test
    fun `N3 a record is only made for the peer that started the transfer`() =
        runBlocking<Unit> {
            receiveRow()
            val stranger = crypto.generateEd25519().publicKey
            store.create(id, summary, files, 1_000, stranger)
            assertNull(store.load(id), "no record for another identity with the same transfer id")
            assertTrue(errors.single().contains("belongs to another device"), errors.toString())
            store.create(id, summary, files, 1_000, peer)
            assertContentEquals(peer, assertNotNull(store.load(id)).peerIdentityKey)
        }

    @Test
    fun `nothing resumes without its transfer row, after it finished, or with a damaged plan`() =
        runBlocking<Unit> {
            store.create(id, summary, files, 1_000, peer)
            assertTrue(errors.single().contains("no unfinished transfer"))
            assertNull(store.load(id))

            receiveRow()
            store.create(id, summary, files, 1_000, peer)
            Files.write(dir.resolve(id.toHex()).resolve(FileResumePlanStore.FILE_NAME), "garbage".toByteArray())
            assertNull(store.load(id), "a damaged plan loads as no record")
            assertTrue(errors.last().contains("loading"))

            store.create(id, summary, files, 1_000, peer)
            assertNotNull(store.load(id))
            data.transfers.finish(id, TransferOutcome(TransferStatus.CANCELLED, 0))
            assertNull(store.load(id), "a finished transfer never resumes")
            // Writes for a finished transfer are dropped quietly (the store may lag, never lead).
            store.putManifests(listOf(UnitManifest.empty(id, 2, 3, 5_000)))
            assertTrue(data.manifests.forTransfer(id).isEmpty())
        }

    @Test
    fun `a record with fewer stored files than its plan is not resumed`() =
        runBlocking<Unit> {
            receiveRow()
            store.create(id, summary, files.take(2), 1_000, peer)
            assertNull(store.load(id))
        }

    @Test
    fun `manifests convert field for field in both directions`() {
        val unit = UnitManifest(id, -1, 9, byteArrayOf(0x55, 0x01), ByteArray(144) { it.toByte() }, 3, 100, 42)
        assertEquals(unit, unit.toChunkManifest().toUnitManifest())
    }

    @Test
    fun `resume plans round-trip and malformed ones are refused`() =
        runBlocking<Unit> {
            assertNull(plans.read(id))
            plans.write(id, summary)
            assertEquals(summary, plans.read(id))
            plans.delete(id)
            plans.delete(id)
            assertNull(plans.read(id))
            assertEquals(summary, FileResumePlanStore.parse(FileResumePlanStore.format(summary)))
            val good = FileResumePlanStore.format(summary)
            val bad =
                listOf(
                    "",
                    "drop-resume-plan 2\n",
                    good.replace("files=3", "files=x"),
                    good.replace("files=3", "files=0"),
                    good.replace("chunk=4194304", "chunk=1024"),
                    good.replace("bundle=true", "bundle=maybe"),
                    good.replace("bundle=true", "bundle=false"),
                    good.replace("bundles=1", "bundles=4"),
                    good + "files=3\n",
                    good.replace("bytes=", "bytes"),
                    good + "x".repeat(600),
                    good.replace("bytes=10000123", "bytes=-5"),
                )
            for (text in bad) assertFailsWith<ResumePlanFormatException>(text) { FileResumePlanStore.parse(text) }
        }
}
