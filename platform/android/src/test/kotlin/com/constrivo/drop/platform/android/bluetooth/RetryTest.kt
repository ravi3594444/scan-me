package com.constrivo.drop.platform.android.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The listener's retry of a GATT server that did not open ([BluetoothChannelListener]): it keeps trying with a
 * back-off, and a refresh (a permission grant) retries at once.
 */
class RetryTest {
    @Test
    fun retriesWithADoublingCappedBackOffUntilItSucceeds() =
        runTest {
            val attempts = ArrayList<Long>()
            val retryNow = MutableStateFlow(0L)
            val done =
                backgroundScope.launch {
                    retryWithBackoff(2_000, 10_000, retryNow) { number ->
                        attempts += testScheduler.currentTime
                        number == 6
                    }
                }
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(done.isCompleted)
            // 0, then 2 s, 4 s, 8 s and 10 s (capped) later.
            assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 24_000L, 34_000L), attempts)
        }

    @Test
    fun aRefreshRetriesAtOnceAndStartsTheBackOffOver() =
        runTest {
            val attempts = ArrayList<Long>()
            val retryNow = MutableStateFlow(0L)
            var succeed = false
            val done =
                backgroundScope.launch {
                    retryWithBackoff(2_000, 30_000, retryNow) {
                        attempts += testScheduler.currentTime
                        succeed
                    }
                }
            // Attempts at 0, 2 s, 6 s; the next would be at 14 s.
            advanceTimeBy(7_000)
            assertEquals(listOf(0L, 2_000L, 6_000L), attempts)
            // BLUETOOTH_CONNECT was granted: the owner calls refresh().
            retryNow.update { it + 1 }
            runCurrent()
            assertEquals(7_000L, attempts.last())
            // Still failing: the back-off starts over at 2 s.
            advanceTimeBy(2_001)
            assertEquals(9_000L, attempts.last())
            succeed = true
            retryNow.update { it + 1 }
            runCurrent()
            assertTrue(done.isCompleted)
        }

    @Test
    fun aRefreshDuringAnAttemptIsNotLost() =
        runTest {
            val retryNow = MutableStateFlow(0L)
            var attempts = 0
            val done =
                backgroundScope.launch {
                    retryWithBackoff(60_000, 60_000, retryNow) {
                        attempts++
                        // The grant arrives while the first attempt is still failing.
                        if (attempts == 1) retryNow.update { it + 1 }
                        attempts == 2
                    }
                }
            runCurrent()
            assertTrue(done.isCompleted)
            assertEquals(2, attempts)
        }

    @Test
    fun cancellationStopsTheRetries() =
        runTest {
            var attempts = 0
            val job =
                backgroundScope.launch {
                    retryWithBackoff(1_000, 1_000, MutableStateFlow(0L)) {
                        attempts++
                        false
                    }
                }
            advanceTimeBy(3_500)
            job.cancel()
            advanceTimeBy(10_000)
            assertEquals(4, attempts)
        }
}
