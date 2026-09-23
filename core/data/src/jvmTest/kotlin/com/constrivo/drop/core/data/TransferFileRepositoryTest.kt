package com.constrivo.drop.core.data

import com.constrivo.drop.core.protocol.Sha256Digest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TransferFileRepository (architecture §12 `transfer_file`; S2, F-D3, F-G2). */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferFileRepositoryTest {
    private val hash = Sha256Digest(ByteArray(32) { it.toByte() })

    private suspend fun DropData.transferWithFiles(count: Int) {
        val peer = peer(1)
        transfers.create(NewTransfer(transferId(1), peer, TransferDirection.RECEIVE, 1_000L * count, count))
    }

    @Test
    fun filesRoundTripInIndexOrder() =
        runTest {
            val data = openTestData()
            data.transferWithFiles(3)
            val id = transferId(1)
            data.transferFiles.add(
                id,
                listOf(
                    NewTransferFile(2, "c.pdf", "application/pdf", 3_000),
                    NewTransferFile(0, "a.jpg", "image/jpeg", 1_000, sha256 = hash, uri = "file:///src/a.jpg"),
                ),
            )
            data.transferFiles.add(id, listOf(NewTransferFile(1, "b", null, 0, status = TransferFileStatus.IN_PROGRESS)))
            assertEquals(
                listOf(
                    TransferFile(id, 0, "a.jpg", "image/jpeg", 1_000, hash, "file:///src/a.jpg", TransferFileStatus.PENDING),
                    TransferFile(id, 1, "b", null, 0, null, null, TransferFileStatus.IN_PROGRESS),
                    TransferFile(id, 2, "c.pdf", "application/pdf", 3_000, null, null, TransferFileStatus.PENDING),
                ),
                data.transferFiles.files(id),
            )
            assertEquals("b", data.transferFiles.file(id, 1)?.name)
            assertNull(data.transferFiles.file(id, 7))
            assertEquals(listOf(1, 2), data.transferFiles.page(id, 1, 5).map { it.index })
            assertEquals(listOf(0), data.transferFiles.page(id, 0, 1).map { it.index })
        }

    @Test
    fun addRejectsUnknownTransfersDuplicatesAndIndicesBeyondTheCount() =
        runTest {
            val data = openTestData()
            assertFailsWith<NoSuchRecordException> { data.transferFiles.add(transferId(1), listOf(NewTransferFile(0, "a", null, 1))) }
            data.transferWithFiles(2)
            val id = transferId(1)
            data.transferFiles.add(id, listOf(NewTransferFile(0, "a", null, 1)))
            assertFailsWith<DuplicateRecordException> { data.transferFiles.add(id, listOf(NewTransferFile(0, "again", null, 1))) }
            assertFailsWith<DuplicateRecordException> {
                data.transferFiles.add(id, listOf(NewTransferFile(1, "b", null, 1), NewTransferFile(1, "b", null, 1)))
            }
            assertFailsWith<IllegalArgumentException> { data.transferFiles.add(id, listOf(NewTransferFile(2, "c", null, 1))) }
            assertEquals(listOf(0), data.transferFiles.files(id).map { it.index }, "failed adds leave nothing behind")
            data.transferFiles.add(id, emptyList())
        }

    @Test
    fun newTransferFileValidatesItsFields() {
        assertFailsWith<IllegalArgumentException> { NewTransferFile(-1, "a", null, 1) }
        assertFailsWith<IllegalArgumentException> { NewTransferFile(0, "", null, 1) }
        assertFailsWith<IllegalArgumentException> { NewTransferFile(0, "a", null, -1) }
    }

    @Test
    fun statusHashAndSavedUriUpdates() =
        runTest {
            val data = openTestData()
            data.transferWithFiles(3)
            val id = transferId(1)
            data.transferFiles.add(id, (0 until 3).map { NewTransferFile(it, "f$it", "image/jpeg", 10) })
            assertTrue(data.transferFiles.updateStatus(id, 0, TransferFileStatus.IN_PROGRESS))
            assertTrue(data.transferFiles.setSha256(id, 0, hash))
            assertTrue(data.transferFiles.complete(id, 0, "content://media/external/images/7"))
            assertTrue(data.transferFiles.updateStatus(id, 1, TransferFileStatus.FAILED))
            assertTrue(data.transferFiles.setSavedUri(id, 2, "file:///tmp/x"))
            assertTrue(data.transferFiles.complete(id, 2))
            assertFalse(data.transferFiles.updateStatus(id, 9, TransferFileStatus.DONE))

            val files = data.transferFiles.files(id)
            assertEquals(TransferFileStatus.DONE, files[0].status)
            assertEquals(hash, files[0].sha256)
            assertEquals("content://media/external/images/7", files[0].savedUri)
            assertEquals(TransferFileStatus.FAILED, files[1].status)
            assertEquals("file:///tmp/x", files[2].savedUri, "complete without a URI keeps the stored one")
            assertEquals(mapOf(TransferFileStatus.DONE to 2, TransferFileStatus.FAILED to 1), data.transferFiles.statusCounts(id))
            assertTrue(data.transferFiles.setSavedUri(id, 2, null))
            assertNull(data.transferFiles.file(id, 2)?.savedUri)
        }

    @Test
    fun observeFilesFollowsChanges() =
        runTest {
            val data = openTestData()
            data.transferWithFiles(2)
            val id = transferId(1)
            val seen = collectInto(data.transferFiles.observeFiles(id))
            runCurrent()
            data.transferFiles.add(id, listOf(NewTransferFile(0, "a", null, 1), NewTransferFile(1, "b", null, 1)))
            runCurrent()
            data.transferFiles.complete(id, 1, "u")
            runCurrent()
            assertEquals(
                listOf(
                    emptyList(),
                    listOf(TransferFileStatus.PENDING, TransferFileStatus.PENDING),
                    listOf(TransferFileStatus.PENDING, TransferFileStatus.DONE),
                ),
                seen.map { list -> list.map { it.status } },
            )
        }

    @Test
    fun theSchemaRefusesValuesTheRepositoryCouldNotReadBack() =
        runTest {
            val data = openTestData()
            data.transferWithFiles(1)
            data.transferFiles.add(transferId(1), listOf(NewTransferFile(0, "a", null, 1)))
            assertFailsWith<Exception> { data.driver.execute(null, "UPDATE transfer_file SET status = 'lost'", 0) }
            assertFailsWith<Exception> { data.driver.execute(null, "UPDATE transfer_file SET sha256 = X'00'", 0) }
            assertEquals(TransferFileStatus.PENDING, data.transferFiles.file(transferId(1), 0)?.status)
            assertNull(data.transferFiles.file(transferId(1), 0)?.sha256)
        }
}
