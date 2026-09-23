package com.constrivo.drop.core.data

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TransferRepository (architecture §12 `transfer`; F-G1, F-G2). */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferRepositoryTest {
    private fun newTransfer(
        n: Int,
        peer: String,
        direction: TransferDirection = TransferDirection.RECEIVE,
        bytes: Long = 48_000_000,
        files: Int = 12,
        histogram: Map<String, Int> = mapOf("image/*" to files),
    ) = NewTransfer(transferId(n), peer, direction, bytes, files, histogram)

    @Test
    fun createAndReadBack() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1, nickname = "Dev", platform = DevicePlatform.LAPTOP)
            val created = data.transfers.create(newTransfer(1, peer, histogram = mapOf("image/jpeg" to 10, "application/pdf" to 2)), T0)
            val expected =
                TransferRecord(
                    id = transferId(1),
                    peerDeviceId = peer,
                    peerName = "Dev",
                    peerPlatform = DevicePlatform.LAPTOP,
                    direction = TransferDirection.RECEIVE,
                    status = TransferStatus.OFFERED,
                    transport = null,
                    band = null,
                    startedAtMillis = T0,
                    finishedAtMillis = null,
                    updatedAtMillis = T0,
                    bytesTotal = 48_000_000,
                    bytesDone = 0,
                    avgSpeedBytesPerSecond = null,
                    hints = emptyList(),
                    fileCount = 12,
                    mimeHistogram = mapOf("image/jpeg" to 10, "application/pdf" to 2),
                    failedFiles = 0,
                )
            assertEquals(expected, created)
            assertEquals(expected, data.transfers.get(transferId(1)))
            assertNull(data.transfers.get(transferId(2)))
            assertTrue(created.isActive)
            assertNull(created.durationMillis)
        }

    @Test
    fun createRefusesAnUnknownPeerAndADuplicateId() =
        runTest {
            val data = openTestData()
            assertFailsWith<NoSuchRecordException> { data.transfers.create(newTransfer(1, "a".repeat(32))) }
            val peer = data.peer(1)
            data.transfers.create(newTransfer(1, peer))
            assertFailsWith<DuplicateRecordException> { data.transfers.create(newTransfer(1, peer)) }
        }

    @Test
    fun newTransferValidatesItsFields() {
        val peer = "a".repeat(32)
        assertFailsWith<IllegalArgumentException> { NewTransfer(transferId(1), "bad", TransferDirection.SEND, 1, 1) }
        assertFailsWith<IllegalArgumentException> { NewTransfer(transferId(1), peer, TransferDirection.SEND, -1, 1) }
        assertFailsWith<IllegalArgumentException> { NewTransfer(transferId(1), peer, TransferDirection.SEND, 1, 0) }
        assertFailsWith<IllegalArgumentException> { NewTransfer(transferId(1), peer, TransferDirection.SEND, 1, 1, mapOf("image/*" to 2)) }
        assertFailsWith<IllegalArgumentException> { NewTransfer(transferId(1), peer, TransferDirection.SEND, 1, 1, mapOf("" to 1)) }
        assertFailsWith<IllegalArgumentException> {
            NewTransfer(transferId(1), peer, TransferDirection.SEND, 1, 1, status = TransferStatus.DONE)
        }
        assertFailsWith<IllegalArgumentException> { TransferOutcome(TransferStatus.STREAMING, 0) }
        assertFailsWith<IllegalArgumentException> { TransferOutcome(TransferStatus.DONE, -1) }
    }

    @Test
    fun lifecycleUpdatesMoveLastActivity() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1)
            val id = transferId(1)
            data.transfers.create(newTransfer(1, peer, bytes = 1_000), T0)
            assertTrue(data.transfers.updateStatus(id, TransferStatus.ACCEPTED, T0 + 1))
            assertTrue(data.transfers.recordLink(id, LinkKind.BLUETOOTH, null, T0 + 2))
            assertTrue(data.transfers.updateStatus(id, TransferStatus.STREAMING, T0 + 3))
            assertTrue(data.transfers.updateProgress(id, 400, T0 + 4))
            assertTrue(data.transfers.recordLink(id, LinkKind.P2P, WifiBand.GHZ_5, T0 + 5))
            assertTrue(data.transfers.addHint(id, HintCode.BUNDLING, T0 + 6))
            assertTrue(data.transfers.addHint(id, HintCode.THERMAL, T0 + 7))
            assertTrue(data.transfers.addHint(id, HintCode.BUNDLING, T0 + 8), "already present")
            val record = assertNotNull(data.transfers.get(id))
            assertEquals(TransferStatus.STREAMING, record.status)
            assertEquals(400, record.bytesDone)
            assertEquals(LinkKind.P2P, record.transport)
            assertEquals(WifiBand.GHZ_5, record.band)
            assertEquals(listOf(HintCode.BUNDLING, HintCode.THERMAL), record.hints)
            assertEquals(T0 + 7, record.updatedAtMillis)
            assertFailsWith<IllegalArgumentException> { data.transfers.updateStatus(id, TransferStatus.DONE) }
        }

    @Test
    fun progressIsClampedToTheTotal() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1)
            data.transfers.create(newTransfer(1, peer, bytes = 1_000))
            assertTrue(data.transfers.updateProgress(transferId(1), 5_000))
            assertEquals(1_000, data.transfers.get(transferId(1))?.bytesDone)
            assertTrue(data.transfers.updateProgress(transferId(1), -5))
            assertEquals(0, data.transfers.get(transferId(1))?.bytesDone)
            assertFalse(data.transfers.updateProgress(transferId(9), 1))
        }

    @Test
    fun finishRecordsTheOutcomeOnceAndFreezesTheTransfer() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1)
            val id = transferId(1)
            data.transfers.create(newTransfer(1, peer, bytes = 1_000, files = 3), T0)
            data.transferFiles.add(
                id,
                listOf(
                    NewTransferFile(0, "a.jpg", "image/jpeg", 400),
                    NewTransferFile(1, "b.jpg", "image/jpeg", 300),
                    NewTransferFile(2, "c.jpg", "image/jpeg", 300),
                ),
            )
            data.transferFiles.complete(id, 0, "content://media/1")
            data.transferFiles.updateStatus(id, 1, TransferFileStatus.IN_PROGRESS)
            data.transfers.addHint(id, HintCode.SDCARD, T0 + 1)
            data.transfers.recordLink(id, LinkKind.HOTSPOT, WifiBand.GHZ_2_4, T0 + 2)

            val outcome =
                TransferOutcome(
                    status = TransferStatus.DONE,
                    bytesDone = 5_000,
                    avgSpeedBytesPerSecond = 25_000_000,
                    hints = listOf(HintCode.BAND24, HintCode.SDCARD),
                    failedFiles = 1,
                )
            assertTrue(data.transfers.finish(id, outcome, T0 + 60_000))
            val done = assertNotNull(data.transfers.get(id))
            assertEquals(TransferStatus.DONE, done.status)
            assertEquals(T0 + 60_000, done.finishedAtMillis)
            assertEquals(60_000, done.durationMillis)
            assertEquals(1_000, done.bytesDone, "clamped to the total")
            assertEquals(LinkKind.HOTSPOT, done.transport, "null keeps the recorded link")
            assertEquals(WifiBand.GHZ_2_4, done.band)
            assertEquals(25_000_000, done.avgSpeedBytesPerSecond)
            assertEquals(listOf(HintCode.SDCARD, HintCode.BAND24), done.hints)
            assertEquals(1, done.failedFiles)
            assertTrue(done.isPartial)
            assertFalse(done.isActive)
            assertEquals(
                listOf(TransferFileStatus.DONE, TransferFileStatus.CANCELLED, TransferFileStatus.CANCELLED),
                data.transferFiles.files(id).map { it.status },
            )

            assertFalse(data.transfers.finish(id, TransferOutcome(TransferStatus.CANCELLED, 0), T0 + 70_000))
            assertFalse(data.transfers.updateStatus(id, TransferStatus.STREAMING))
            assertFalse(data.transfers.updateProgress(id, 1))
            assertFalse(data.transfers.recordLink(id, LinkKind.LAN, null))
            assertFalse(data.transfers.addHint(id, HintCode.LAN_SLOW))
            assertEquals(done, data.transfers.get(id), "a finished transfer never changes")
            assertFalse(data.transfers.finish(transferId(9), outcome))
        }

    @Test
    fun finishOverridesTheLinkWhenGivenAndRejectsTooManyFailedFiles() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1)
            data.transfers.create(newTransfer(1, peer, files = 2))
            data.transfers.recordLink(transferId(1), LinkKind.BLUETOOTH, null)
            assertFailsWith<IllegalArgumentException> {
                data.transfers.finish(transferId(1), TransferOutcome(TransferStatus.FAILED, 0, failedFiles = 3))
            }
            assertTrue(
                data.transfers.finish(
                    transferId(1),
                    TransferOutcome(TransferStatus.FAILED, 0, LinkKind.LAN, WifiBand.GHZ_6, failedFiles = 2),
                ),
            )
            val failed = data.transfers.get(transferId(1))!!
            assertEquals(LinkKind.LAN, failed.transport)
            assertEquals(WifiBand.GHZ_6, failed.band)
            assertFalse(failed.isPartial)
        }

    @Test
    fun historyIsNewestFirstAndPagesCoverEveryRowOnce() =
        runTest {
            val data = openTestData()
            val peers = (1..3).map { data.peer(it) }
            val random = Random(42)
            // Many equal start times, so the id tie-break matters.
            val starts = (0 until 257).associate { n -> transferId(n) to T0 + random.nextInt(40) * 1_000L }
            for ((n, entry) in starts.entries.withIndex()) {
                data.transfers.create(NewTransfer(entry.key, peers[n % 3], TransferDirection.SEND, 10, 1), entry.value)
            }
            val expected =
                starts.entries.sortedWith(
                    compareByDescending<Map.Entry<TransferId, Long>> {
                        it.value
                    }.thenByDescending { it.key.toHex() },
                ).map { it.key }

            for (size in listOf(1, 10, 50, 256, 257, 1000)) {
                val seen = ArrayList<TransferId>()
                var page = data.transfers.historyPage(null, size)
                while (true) {
                    assertTrue(page.transfers.size <= size)
                    seen += page.transfers.map { it.id }
                    val next = page.next ?: break
                    assertEquals(page.transfers.last().cursor, next)
                    page = data.transfers.historyPage(next, size)
                }
                assertEquals(expected, seen, "page size $size")
            }
            assertFailsWith<IllegalArgumentException> { data.transfers.historyPage(null, 0) }
            assertFailsWith<IllegalArgumentException> { data.transfers.historyPage(null, TransferRepository.MAX_PAGE_SIZE + 1) }
        }

    @Test
    fun anExactlyFullLastPageHasNoNextCursor() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1)
            repeat(4) { data.transfers.create(newTransfer(it, peer), T0 + it) }
            val first = data.transfers.historyPage(null, 2)
            val second = data.transfers.historyPage(first.next, 2)
            assertEquals(2, second.transfers.size)
            assertNull(second.next)
            assertEquals(HistoryPage(emptyList(), null), data.transfers.historyPage(HistoryCursor(0, transferId(99)), 2))
        }

    @Test
    fun historySurvivesAPeerRenameAndShowsTheCurrentName() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1, nickname = "Dev")
            data.transfers.create(newTransfer(1, peer))
            data.devices.rename(peer, "Work phone")
            assertEquals("Work phone", data.transfers.historyPage().transfers.single().peerName)
            assertEquals(listOf(transferId(1)), data.transfers.historyWith(peer).map { it.id })
            assertEquals(emptyList(), data.transfers.historyWith(data.peer(2)))
        }

    @Test
    fun observeHistoryAndActiveFollowChanges() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1)
            val history = collectInto(data.transfers.observeHistory(2))
            val active = collectInto(data.transfers.observeActive())
            val one = collectInto(data.transfers.observe(transferId(1)))
            runCurrent()
            data.transfers.create(newTransfer(1, peer), T0)
            runCurrent()
            data.transfers.create(newTransfer(2, peer), T0 + 1)
            runCurrent()
            data.transfers.finish(transferId(1), TransferOutcome(TransferStatus.DONE, 10), T0 + 2)
            runCurrent()
            data.transfers.create(newTransfer(3, peer), T0 + 3)
            runCurrent()

            assertEquals(
                listOf(emptyList(), listOf(1), listOf(2, 1), listOf(2, 1), listOf(3, 2)),
                history.map { list -> list.map { r -> (0..3).first { transferId(it) == r.id } } },
            )
            assertEquals(
                listOf(emptyList(), listOf(1), listOf(2, 1), listOf(2), listOf(3, 2)),
                active.map { list ->
                    list.map { r ->
                        (0..3).first {
                            transferId(it) ==
                                r.id
                        }
                    }
                },
            )
            assertEquals(listOf(null, TransferStatus.OFFERED, TransferStatus.DONE), one.map { it?.status })
            assertEquals(listOf(transferId(3), transferId(2)), data.transfers.active().map { it.id })
        }

    @Test
    fun deleteRemovesOnlyFinishedTransfersWithTheirChildrenAndPartials() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1)
            data.transfers.create(newTransfer(1, peer, files = 1))
            data.transferFiles.add(transferId(1), listOf(NewTransferFile(0, "a", null, 1)))
            data.manifests.put(ChunkManifest.empty(transferId(1), 0, 4, T0))
            val deleted = mutableListOf<TransferId>()
            val rowsWhenDeleting = mutableListOf<Int>()
            val deletePartials: suspend (TransferId) -> Unit = { id ->
                rowsWhenDeleting += data.manifests.forTransfer(id).size
                deleted += id
            }
            assertFalse(data.transfers.delete(transferId(1), deletePartials), "running transfers belong to the engine")
            assertEquals(emptyList(), deleted, "nor are their partial files touched")
            data.transfers.finish(transferId(1), TransferOutcome(TransferStatus.CANCELLED, 0))
            assertTrue(data.transfers.delete(transferId(1), deletePartials))
            assertEquals(listOf(transferId(1)), deleted, "a cancelled receive may have left partial files")
            assertEquals(listOf(1), rowsWhenDeleting, "partial files go first, while the rows still point at them")
            assertNull(data.transfers.get(transferId(1)))
            assertEquals(emptyList(), data.transferFiles.files(transferId(1)))
            assertEquals(emptyList(), data.manifests.forTransfer(transferId(1)))
            assertFalse(data.transfers.delete(transferId(1), deletePartials))

            // A sent transfer has no partial files.
            data.transfers.create(newTransfer(2, peer, direction = TransferDirection.SEND))
            data.transfers.finish(transferId(2), TransferOutcome(TransferStatus.DONE, 0))
            assertTrue(data.transfers.delete(transferId(2), deletePartials))
            assertEquals(listOf(transferId(1)), deleted)
        }

    @Test
    fun aFailedPartialDeletionKeepsTheTransfer() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1)
            data.transfers.create(newTransfer(1, peer, files = 1))
            data.manifests.put(ChunkManifest.empty(transferId(1), 0, 4, T0))
            data.transfers.finish(transferId(1), TransferOutcome(TransferStatus.FAILED, 0))
            assertFailsWith<java.io.IOException> { data.transfers.delete(transferId(1)) { throw java.io.IOException("busy") } }
            assertNotNull(data.transfers.get(transferId(1)), "the row still points at the partial files")
            assertEquals(1, data.manifests.forTransfer(transferId(1)).size)
            assertFailsWith<java.io.IOException> { data.transfers.clearHistory { throw java.io.IOException("busy") } }
            assertNotNull(data.transfers.get(transferId(1)))
            assertTrue(data.transfers.delete(transferId(1)) {})
        }

    @Test
    fun clearHistoryKeepsRunningTransfersAndDeletesPartialsFirst() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1)
            for (n in 1..6) {
                val direction = if (n == 6) TransferDirection.SEND else TransferDirection.RECEIVE
                data.transfers.create(newTransfer(n, peer, direction = direction, files = 1), T0 + n)
                data.transferFiles.add(transferId(n), listOf(NewTransferFile(0, "f$n", null, 1)))
            }
            data.manifests.put(ChunkManifest.empty(transferId(2), 0, 4, T0))
            data.transfers.finish(transferId(1), TransferOutcome(TransferStatus.DONE, 1))
            data.transfers.finish(transferId(2), TransferOutcome(TransferStatus.FAILED, 0))
            data.transfers.finish(transferId(3), TransferOutcome(TransferStatus.CANCELLED, 0))
            data.transfers.finish(transferId(6), TransferOutcome(TransferStatus.DONE, 1))
            data.transfers.updateStatus(transferId(4), TransferStatus.INTERRUPTED)
            val deleted = mutableListOf<TransferId>()
            val cleared =
                data.transfers.clearHistory { id ->
                    assertNotNull(data.transfers.get(id), "partial files go before the rows")
                    deleted += id
                    // Transfer 5 finishes while the clear runs: its partials were not deleted, so it must stay.
                    data.transfers.finish(transferId(5), TransferOutcome(TransferStatus.CANCELLED, 0))
                }
            assertEquals(4, cleared)
            assertEquals(setOf(transferId(1), transferId(2), transferId(3)), deleted.toSet(), "received ones only")
            assertEquals(listOf(transferId(5), transferId(4)), data.transfers.historyPage().transfers.map { it.id })
            assertEquals(listOf(2L), data.driver.longs("SELECT count(*) FROM transfer_file"))
            assertEquals(listOf(0L), data.driver.longs("SELECT count(*) FROM chunk_manifest"))
            assertEquals(1, data.transfers.clearHistory {}, "the next clear takes transfer 5")
            assertEquals(0, data.transfers.clearHistory {})
        }

    @Test
    fun expireInactiveCancelsOnlyTransfersIdleOnRowAndManifests() =
        runTest {
            val clock = FakeClock()
            val data = openTestData(clock)
            val peer = data.peer(1)
            data.transfers.create(newTransfer(1, peer, files = 1), T0) // idle
            data.transfers.create(newTransfer(2, peer, files = 1), T0) // row idle, manifest recent
            data.transfers.create(newTransfer(3, peer, files = 1), T0) // row recent
            data.transfers.create(newTransfer(4, peer, files = 1), T0) // finished
            data.transferFiles.add(transferId(1), listOf(NewTransferFile(0, "a", null, 1)))
            data.manifests.put(ChunkManifest.empty(transferId(2), 0, 1, T0 + 30 * HOUR))
            data.transfers.updateProgress(transferId(3), 0, T0 + 30 * HOUR)
            data.transfers.finish(transferId(4), TransferOutcome(TransferStatus.DONE, 1), T0)

            val cutoff = T0 + 25 * HOUR - DAY
            assertEquals(listOf(transferId(1)), data.transfers.expireInactive(T0 + 1, T0 + 25 * HOUR))
            assertEquals(emptyList(), data.transfers.expireInactive(cutoff, T0 + 25 * HOUR))
            val expired = data.transfers.get(transferId(1))!!
            assertEquals(TransferStatus.CANCELLED, expired.status)
            assertEquals(T0 + 25 * HOUR, expired.finishedAtMillis)
            assertEquals(TransferFileStatus.CANCELLED, data.transferFiles.file(transferId(1), 0)?.status)
            assertEquals(TransferStatus.OFFERED, data.transfers.get(transferId(2))?.status)
            assertEquals(TransferStatus.OFFERED, data.transfers.get(transferId(3))?.status)
        }

    @Test
    fun corruptRowsRaiseDataCorruption() =
        runTest {
            val data = openTestData()
            val peer = data.peer(1)
            data.transfers.create(newTransfer(1, peer))
            data.driver.execute(null, "UPDATE transfer SET hint_codes = 'band24,warp'", 0)
            assertFailsWith<DataCorruptionException> { data.transfers.get(transferId(1)) }
            data.driver.execute(null, "UPDATE transfer SET hint_codes = NULL, mime_histogram = 'image/*=x'", 0)
            assertFailsWith<DataCorruptionException> { data.transfers.historyPage() }
        }

    @Test
    fun statusesMapFromEveryStateMachinePhase() {
        for (phase in TransferPhase.entries) {
            assertEquals(phase.storedStatus, TransferStatus.of(phase).dbValue, "$phase")
            assertEquals(phase.isTerminal, TransferStatus.of(phase).isTerminal, "$phase")
        }
        assertEquals(TransferStatus.INTERRUPTED, TransferStatus.of(TransferPhase.PARKED))
        assertEquals(TransferDirection.SEND, TransferDirection.of(TransferRole.SENDER))
        assertEquals(TransferDirection.RECEIVE, TransferDirection.of(TransferRole.RECEIVER))
    }
}
