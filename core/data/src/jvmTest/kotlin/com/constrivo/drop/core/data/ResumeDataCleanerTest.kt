package com.constrivo.drop.core.data

import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.TransferId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The 24 h clean-up of partials and manifests (architecture §7.6, §7.7 with S8). */
@OptIn(ExperimentalCoroutinesApi::class)
class ResumeDataCleanerTest {
    private suspend fun DropData.receiving(
        n: Int,
        at: Long,
    ) {
        transfers.create(NewTransfer(transferId(n), peer(1), TransferDirection.RECEIVE, 1_000, 1), at)
    }

    @Test
    fun aPassExpiresIdleTransfersAndDeletesPartialsBeforeManifests() =
        runTest {
            val clock = FakeClock(T0)
            val data = openTestData(clock)
            data.receiving(1, T0) // parked, then the app was killed: no engine holds it
            data.receiving(2, T0) // finished, but its manifest was left behind
            data.receiving(3, T0) // still receiving
            data.manifests.put(ChunkManifest.empty(transferId(1), 0, 2, T0))
            data.manifests.put(ChunkManifest.empty(transferId(2), 0, 2, T0))
            data.transfers.finish(transferId(2), TransferOutcome(TransferStatus.DONE, 1_000), T0 + HOUR)
            data.manifests.put(ChunkManifest.empty(transferId(3), 0, 2, T0 + 20 * HOUR))

            val deleted = mutableListOf<TransferId>()
            val manifestsWhenDeleting = mutableListOf<Int>()
            val cleaner =
                data.resumeDataCleaner(clock) { id ->
                    manifestsWhenDeleting += data.manifests.forTransfer(id).size
                    deleted += id
                }
            clock.now = T0 + 26 * HOUR
            val report = cleaner.runOnce()

            assertEquals(listOf(transferId(1)), report.expired)
            assertEquals(setOf(transferId(1), transferId(2)), report.purged.toSet())
            assertEquals(emptyMap(), report.failed)
            assertEquals(setOf(transferId(1), transferId(2)), deleted.toSet())
            assertEquals(listOf(1, 1), manifestsWhenDeleting, "partial files go first, while the manifest still points at them")
            assertEquals(TransferStatus.CANCELLED, data.transfers.get(transferId(1))?.status)
            assertEquals(emptyList(), data.manifests.forTransfer(transferId(1)))
            assertEquals(emptyList(), data.manifests.forTransfer(transferId(2)))
            assertEquals(1, data.manifests.forTransfer(transferId(3)).size)
            assertEquals(TransferStatus.OFFERED, data.transfers.get(transferId(3))?.status)

            assertEquals(
                ResumeDataCleaner.Report(emptyList(), emptyList(), emptyMap()),
                cleaner.runOnce(),
                "a second pass has nothing to do",
            )
        }

    @Test
    fun aLateFlushCannotLeaveAManifestWhoseBytesWereDeleted() =
        runTest {
            val clock = FakeClock(T0)
            val data = openTestData(clock)
            data.receiving(1, T0)
            data.manifests.put(ChunkManifest.empty(transferId(1), 0, 2, T0))
            clock.now = T0 + 2 * DAY
            val flushes = mutableListOf<Boolean>()
            val cleaner =
                data.resumeDataCleaner(clock) { id ->
                    // An engine that still held the transfer flushes its write-behind manifest right after the partials went.
                    val late = ChunkManifest.empty(id, 0, 2, clock.now).withReceived(0, ChunkHash(ByteArray(16) { 1 }), clock.now)
                    flushes += data.manifests.put(late)
                }
            val report = cleaner.runOnce()
            assertEquals(listOf(transferId(1)), report.expired)
            assertEquals(listOf(transferId(1)), report.purged)
            assertEquals(listOf(false), flushes, "a cancelled transfer takes no manifest")
            assertEquals(emptyList(), data.manifests.forTransfer(transferId(1)), "no manifest points at the deleted bytes")
        }

    @Test
    fun anIdleManifestOfARunningTransferIsLeftAlone() =
        runTest {
            val clock = FakeClock(T0)
            val data = openTestData(clock)
            data.receiving(1, T0)
            data.manifests.put(ChunkManifest.empty(transferId(1), 0, 2, T0))
            clock.now = T0 + 2 * DAY
            // The transfer row saw activity (so it is not expired), but its manifest did not.
            data.transfers.updateProgress(transferId(1), 10, clock.now)
            val deleted = mutableListOf<TransferId>()
            val report = data.resumeDataCleaner(clock) { deleted += it }.runOnce()
            assertEquals(ResumeDataCleaner.Report(emptyList(), emptyList(), emptyMap()), report)
            assertEquals(emptyList(), deleted)
            assertEquals(1, data.manifests.forTransfer(transferId(1)).size)
        }

    @Test
    fun clearPartialsNeverTouchesATransferAnEngineHolds() =
        runTest {
            val clock = FakeClock(T0)
            val data = openTestData(clock)
            data.receiving(1, T0) // streaming right now
            data.receiving(2, T0) // parked, released by the caller
            data.receiving(3, T0) // failed a minute ago, partials left
            data.receiving(4, T0) // done, no manifest
            data.transfers.create(NewTransfer(transferId(5), data.peer(1), TransferDirection.SEND, 10, 1), T0) // sent: no partials
            for (n in 1..3) data.manifests.put(ChunkManifest.empty(transferId(n), 0, 2, T0))
            data.transfers.updateStatus(transferId(1), TransferStatus.STREAMING, T0)
            data.transfers.finish(transferId(3), TransferOutcome(TransferStatus.FAILED, 0), T0)
            data.transfers.finish(transferId(4), TransferOutcome(TransferStatus.DONE, 1_000), T0)
            data.transfers.finish(transferId(5), TransferOutcome(TransferStatus.DONE, 10), T0)

            val deleted = mutableListOf<TransferId>()
            val cleaner = data.resumeDataCleaner(clock) { deleted += it }
            clock.now = T0 + 60_000
            val report = cleaner.clearPartials(released = listOf(transferId(2), transferId(3)))

            assertEquals(listOf(transferId(2)), report.expired, "a released transfer cannot resume without its partials")
            assertEquals(setOf(transferId(2), transferId(3), transferId(4)), deleted.toSet())
            assertEquals(deleted.toSet(), report.purged.toSet())
            assertEquals(TransferStatus.STREAMING, data.transfers.get(transferId(1))?.status)
            assertEquals(1, data.manifests.forTransfer(transferId(1)).size, "the running transfer keeps its resume state")
            assertEquals(TransferStatus.CANCELLED, data.transfers.get(transferId(2))?.status)
            assertEquals(emptyList(), data.manifests.forTransfer(transferId(2)))
            assertEquals(emptyList(), data.manifests.forTransfer(transferId(3)))
            assertEquals(TransferStatus.DONE, data.transfers.get(transferId(4))?.status, "History is untouched")
        }

    @Test
    fun aFailedPartialDeletionKeepsTheManifestForTheNextPass() =
        runTest {
            val clock = FakeClock(T0)
            val data = openTestData(clock)
            data.receiving(1, T0)
            data.manifests.put(ChunkManifest.empty(transferId(1), 0, 2, T0))
            var fail = true
            val cleaner = data.resumeDataCleaner(clock) { if (fail) throw IOException("disk busy") }
            clock.now = T0 + 2 * DAY
            val first = cleaner.runOnce()
            assertEquals(listOf(transferId(1)), first.failed.keys.toList())
            assertTrue(first.failed.getValue(transferId(1)) is IOException)
            assertEquals(1, data.manifests.forTransfer(transferId(1)).size)

            fail = false
            assertEquals(listOf(transferId(1)), cleaner.runOnce().purged)
            assertEquals(emptyList(), data.manifests.forTransfer(transferId(1)))
        }

    @Test
    fun aCustomRetentionClearsSooner() =
        runTest {
            val clock = FakeClock(T0)
            val data = openTestData(clock)
            data.receiving(1, T0)
            data.manifests.put(ChunkManifest.empty(transferId(1), 0, 2, T0))
            clock.now = T0 + 2 * HOUR
            assertEquals(emptyList(), data.resumeDataCleaner(clock) {}.runOnce().purged)
            assertEquals(listOf(transferId(1)), data.resumeDataCleaner(clock, retentionMillis = HOUR) {}.runOnce().purged)
            assertFailsWith<IllegalArgumentException> { data.resumeDataCleaner(clock, retentionMillis = -1) {} }
        }

    @Test
    fun runsPeriodicallyAndSurvivesAFailingPass() =
        runTest {
            val clock = FakeClock(T0)
            val data = openTestData(clock)
            data.receiving(1, T0)
            data.manifests.put(ChunkManifest.empty(transferId(1), 0, 2, T0))
            val reports = mutableListOf<ResumeDataCleaner.Report>()
            val errors = mutableListOf<Exception>()
            var passes = 0
            val cleaner =
                ResumeDataCleaner(data.transfers, data.manifests, clock) { _ -> }
            val flaky =
                ResumeDataCleaner(data.transfers, data.manifests, clock) { _ -> }
            val job =
                backgroundScope.launch {
                    cleaner.runPeriodically(
                        intervalMillis = HOUR,
                        onReport = {
                            passes++
                            reports += it
                            clock.advance(HOUR)
                        },
                        onError = { errors += it },
                    )
                }
            runCurrent()
            assertEquals(1, passes)
            assertEquals(emptyList(), reports.single().purged)
            advanceTimeBy(HOUR * 30)
            runCurrent()
            assertTrue(passes >= 25)
            assertEquals(listOf(transferId(1)), reports.flatMap { it.purged })
            assertEquals(emptyList(), errors)
            job.cancel()

            data.close()
            val failing = backgroundScope.launch { flaky.runPeriodically(HOUR, onError = { errors += it }) }
            runCurrent()
            advanceTimeBy(HOUR)
            runCurrent()
            assertEquals(2, errors.size, "each failing pass is reported and the loop goes on")
            failing.cancel()
            assertFailsWith<IllegalArgumentException> { cleaner.runPeriodically(0) }
        }
}
