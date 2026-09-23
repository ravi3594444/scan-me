package com.constrivo.drop.core.data

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
