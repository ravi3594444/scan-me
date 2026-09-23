package com.constrivo.drop.core.data

import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.ProtocolConstants
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ChunkManifestRepository (architecture §7.6 `chunk_manifest`; S1, N5) and the 24 h purge. */
class ChunkManifestRepositoryTest {
    private fun hash(n: Int) = ChunkHash(Random(n).nextBytes(16))

    private suspend fun DropData.receiving(
        n: Int,
        at: Long = T0,
    ) {
        transfers.create(NewTransfer(transferId(n), peer(1), TransferDirection.RECEIVE, 100_000_000, 3), at)
    }

    @Test
    fun putAndGetRoundTripBitmapHashesAndPartialPrefix() =
        runTest {
            val data = openTestData()
            data.receiving(1)
            val id = transferId(1)
            val manifest =
                ChunkManifest
                    .empty(id, 0, 23, T0)
                    .withReceived(0, hash(0), T0 + 1)
                    .withReceived(9, hash(9), T0 + 2)
                    .withReceived(22, hash(22), T0 + 3)
                    .withPartial(1, 3 * ProtocolConstants.BLUETOOTH_BLOCK_SIZE, T0 + 4)
            data.manifests.put(manifest)
            val bundles = ChunkManifest.empty(id, ProtocolConstants.BUNDLE_FILE_INDEX, 2, T0).withReceived(1, hash(100), T0 + 5)
            data.manifests.put(bundles)

            val stored = data.manifests.get(id, 0)
            assertEquals(manifest, stored)
            assertEquals(hash(9), stored!!.hashOf(9))
            assertNull(stored.hashOf(1))
            assertEquals(3 * ProtocolConstants.BLUETOOTH_BLOCK_SIZE, stored.presentPrefix(1))
            assertEquals(T0 + 4, stored.updatedAtMillis)
            assertEquals(listOf(bundles, manifest), data.manifests.forTransfer(id), "bundle key -1 first")
            assertNull(data.manifests.get(id, 1))
        }

    @Test
    fun putReplacesTheStoredManifest() =
        runTest {
            val data = openTestData()
            data.receiving(1)
            val first = ChunkManifest.empty(transferId(1), 2, 4, T0).withReceived(0, hash(0), T0)
            data.manifests.put(first)
            val second = first.withReceived(1, hash(1), T0 + 50)
            data.manifests.put(second)
            assertEquals(second, data.manifests.get(transferId(1), 2))
            assertEquals(listOf(1L), data.driver.longs("SELECT count(*) FROM chunk_manifest"))
        }

    @Test
    fun putAllIsOneTransactionAndRefusesUnknownTransfers() =
        runTest {
            val data = openTestData()
            data.receiving(1)
            val good = ChunkManifest.empty(transferId(1), 0, 4, T0)
            val orphan = ChunkManifest.empty(transferId(2), 0, 4, T0)
            assertFailsWith<NoSuchRecordException> { data.manifests.putAll(listOf(good, orphan)) }
            assertEquals(emptyList(), data.manifests.forTransfer(transferId(1)), "nothing from the failed batch is kept")
            data.manifests.putAll(listOf(good, ChunkManifest.empty(transferId(1), 1, 2, T0)))
            assertEquals(2, data.manifests.forTransfer(transferId(1)).size)
            data.manifests.putAll(emptyList())
        }

    @Test
    fun aFinishedTransferTakesNoManifest() =
        runTest {
            val data = openTestData()
            data.receiving(1)
            data.receiving(2)
            assertTrue(data.manifests.put(ChunkManifest.empty(transferId(1), 0, 4, T0)))
            data.transfers.finish(transferId(1), TransferOutcome(TransferStatus.CANCELLED, 0), T0 + 1)
            // A late write-behind flush: its bits would claim bytes the clean-up may already have deleted.
            val late = ChunkManifest.empty(transferId(1), 0, 4, T0 + 2).withReceived(0, hash(0), T0 + 2)
            assertFalse(data.manifests.put(late))
            assertEquals(ChunkManifest.empty(transferId(1), 0, 4, T0), data.manifests.get(transferId(1), 0), "the stored one is unchanged")
            val mixed = listOf(late, ChunkManifest.empty(transferId(2), 0, 4, T0), ChunkManifest.empty(transferId(1), 1, 4, T0))
            assertEquals(1, data.manifests.putAll(mixed), "only the unfinished transfer's manifest is stored")
            assertEquals(1, data.manifests.forTransfer(transferId(2)).size)
            assertEquals(1, data.manifests.forTransfer(transferId(1)).size)
        }

    @Test
    fun deleteOneAndDeleteForTransfer() =
        runTest {
            val data = openTestData()
            data.receiving(1)
            data.manifests.putAll((0 until 3).map { ChunkManifest.empty(transferId(1), it, 1, T0) })
            assertTrue(data.manifests.delete(transferId(1), 1))
            assertFalse(data.manifests.delete(transferId(1), 1))
            assertEquals(2, data.manifests.deleteForTransfer(transferId(1)))
            assertEquals(emptyList(), data.manifests.forTransfer(transferId(1)))
        }

    @Test
    fun manifestsWithBitsBeyondTheUnitCountAreReportedAsCorrupt() =
        runTest {
            val data = openTestData()
            data.receiving(1)
            data.manifests.put(ChunkManifest.empty(transferId(1), 0, 3, T0))
            // Unit count 3 leaves five spare bits in the single bitmap byte; bit 7 passes the length CHECK but not decoding.
            data.driver.execute(null, "UPDATE chunk_manifest SET received_bitmap = X'80'", 0)
            assertFailsWith<DataCorruptionException> { data.manifests.get(transferId(1), 0) }
            // A partial unit that is also marked received.
            data.driver.execute(null, "UPDATE chunk_manifest SET received_bitmap = X'01', partial_unit = 0, partial_bytes = 5", 0)
            assertFailsWith<DataCorruptionException> { data.manifests.forTransfer(transferId(1)) }
        }

    @Test
    fun purgeRemovesManifestsIdleForMoreThan24Hours() =
        runTest {
            val clock = FakeClock(T0)
            val data = openTestData(clock)
            data.receiving(1, T0) // idle since T0
            data.receiving(2, T0) // one manifest touched recently
            data.receiving(3, T0) // manifests idle, but the transfer row saw activity
            data.receiving(4, T0) // idle for exactly 24 h: kept (the rule is "older than")
            data.manifests.putAll(
                listOf(
                    ChunkManifest.empty(transferId(1), 0, 1, T0),
                    ChunkManifest.empty(transferId(1), 1, 1, T0 + 1),
                    ChunkManifest.empty(transferId(2), 0, 1, T0),
                    ChunkManifest.empty(transferId(2), 1, 1, T0 + 20 * HOUR),
                    ChunkManifest.empty(transferId(3), 0, 1, T0),
                    ChunkManifest.empty(transferId(4), 0, 1, T0 + 2 * HOUR),
                ),
            )
            data.transfers.updateProgress(transferId(3), 0, T0 + 10 * HOUR)

            val now = T0 + 26 * HOUR
            assertEquals(listOf(transferId(1)), data.manifests.inactiveTransfers(now - DAY))
            assertEquals(listOf(transferId(1)), data.manifests.purgeInactive(now))
            assertEquals(emptyList(), data.manifests.forTransfer(transferId(1)))
            assertEquals(2, data.manifests.forTransfer(transferId(2)).size)
            assertEquals(1, data.manifests.forTransfer(transferId(3)).size)
            assertEquals(1, data.manifests.forTransfer(transferId(4)).size)

            clock.now = T0 + 26 * HOUR + 1
            assertEquals(listOf(transferId(4)), data.manifests.purgeInactive(), "one millisecond past 24 h")
            clock.now = T0 + 44 * HOUR + 1
            assertEquals(
                listOf(transferId(2), transferId(3)).sortedBy {
                    it.toHex()
                },
                data.manifests.purgeInactive().sortedBy { it.toHex() },
            )
            assertEquals(listOf(0L), data.driver.longs("SELECT count(*) FROM chunk_manifest"))
            assertFailsWith<IllegalArgumentException> { data.manifests.purgeInactive(now, -1) }
        }

    @Test
    fun deleteIfInactiveSparesAManifestWrittenAfterTheScan() =
        runTest {
            val data = openTestData()
            data.receiving(1)
            data.manifests.put(ChunkManifest.empty(transferId(1), 0, 1, T0))
            val cutoff = T0 + DAY
            assertEquals(listOf(transferId(1)), data.manifests.inactiveTransfers(cutoff))
            // The engine resumes the transfer between the scan and the delete.
            data.manifests.put(ChunkManifest.empty(transferId(1), 1, 1, cutoff + 5))
            assertEquals(0, data.manifests.deleteIfInactive(transferId(1), cutoff))
            assertEquals(2, data.manifests.forTransfer(transferId(1)).size)
        }
}
