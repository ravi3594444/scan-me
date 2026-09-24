package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.protocol.LinkKind
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The F-E11 restore accounting (T-14): what counts as displaced, and the 5 s budget. */
class WifiRestoreTest {
    private val policy = RestorePolicy(budgetMillis = 5_000, maxWaitMillis = 10_000)

    private suspend fun TestScope.restore(
        stationBefore: Boolean,
        monitor: StationMonitor?,
        startedAt: Long = currentTime,
    ) = WifiRestore.awaitRestore(LinkKind.HOTSPOT, LinkMode.HOTSPOT, stationBefore, monitor, virtualClock(), policy, startedAt)

    @Test
    fun nothingKnownOrNothingDisplacedIsRestoredAtOnce() =
        runTest {
            for (report in listOf(restore(true, null), restore(false, FakeStation(false)), restore(true, FakeStation(true)))) {
                assertFalse(report.displaced)
                assertTrue(report.restored)
                assertEquals(0, report.durationMillis)
                assertFalse(report.overdue)
            }
        }

    @Test
    fun aDisplacedStationIsTimedFromTheStartOfTheTeardown() =
        runTest {
            val station = FakeStation(false)
            val started = currentTime
            advanceTimeBy(500) // the release itself took half a second
            val report = async { restore(true, station, startedAt = started) }
            advanceTimeBy(1_500)
            station.set(true)
            val result = report.await()
            assertTrue(result.displaced && result.restored)
            assertEquals(2_000, result.durationMillis)
            assertFalse(result.overdue)
        }

    @Test
    fun overTheBudgetIsOverdue() =
        runTest {
            val station = FakeStation(false)
            val report = async { restore(true, station) }
            advanceTimeBy(5_001)
            station.set(true)
            assertTrue(report.await().overdue)
        }

    @Test
    fun theWaitEndsAtItsBoundWhenTheStationNeverComesBack() =
        runTest {
            val start = currentTime
            val report = restore(true, FakeStation(false))
            assertEquals(10_000, currentTime - start)
            assertFalse(report.restored)
            assertEquals(10_000, report.durationMillis)
            assertTrue(report.overdue)
        }

    @Test
    fun aTeardownThatAlreadyUsedTheWholeWaitDoesNotWaitMore() =
        runTest {
            val started = currentTime
            advanceTimeBy(12_000)
            val report = restore(true, FakeStation(false), startedAt = started)
            assertEquals(12_000, currentTime - started)
            assertFalse(report.restored)
        }

    @Test
    fun thePolicyIsChecked() {
        assertFailsWith<IllegalArgumentException> { RestorePolicy(budgetMillis = 0) }
        assertFailsWith<IllegalArgumentException> { RestorePolicy(budgetMillis = 5_000, maxWaitMillis = 4_000) }
        assertEquals(5_000, RestorePolicy().budgetMillis)
    }
}
