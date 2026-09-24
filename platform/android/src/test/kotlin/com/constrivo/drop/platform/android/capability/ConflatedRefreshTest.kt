package com.constrivo.drop.platform.android.capability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Capability detection's worker ([AndroidCapabilityDetector]): serial, conflated, never stale at the end. */
class ConflatedRefreshTest {
    @Test
    fun requestsBeforeARunAreConflatedIntoOne() =
        runTest {
            var runs = 0
            val refresh = ConflatedRefresh(CoroutineScope(StandardTestDispatcher(testScheduler))) { runs++ }
            repeat(5) { refresh.request() }
            runCurrent()
            assertEquals(1, runs)
            refresh.request()
            runCurrent()
            assertEquals(2, runs)
        }

    @Test
    fun aRequestDuringARunIsFollowedByAnotherRunThatSeesTheLatestState() =
        runBlocking {
            // A network callback (connectivity thread) and a broadcast (main thread) race: the detection that read the
            // networks before onLost must not be the last to publish.
            val networks = AtomicReference("connected")
            val published = ArrayList<String>()
            val insideFirstRun = CountDownLatch(1)
            val release = CountDownLatch(1)
            val runs = AtomicInteger()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val refresh =
                    ConflatedRefresh(scope) {
                        val seen = networks.get()
                        if (runs.incrementAndGet() == 1) {
                            insideFirstRun.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                        synchronized(published) { published += seen }
                    }
                refresh.request()
                assertTrue(insideFirstRun.await(5, TimeUnit.SECONDS))
                // onLost lands while the first detection is still reading.
                networks.set("lost")
                refresh.request()
                refresh.request()
                release.countDown()
                withTimeout(5_000) {
                    while (synchronized(published) { published.size } < 2) delay(10)
                }
                delay(100)
                assertEquals(listOf("connected", "lost"), synchronized(published) { published.toList() })
                assertEquals(2, runs.get())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun aFailingRunDoesNotStopTheWorker() =
        runTest {
            var runs = 0
            val refresh =
                ConflatedRefresh(CoroutineScope(StandardTestDispatcher(testScheduler))) {
                    runs++
                    if (runs == 1) throw IllegalStateException("vendor quirk")
                }
            refresh.request()
            runCurrent()
            refresh.request()
            runCurrent()
            assertEquals(2, runs)
        }
}
