package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.FakeClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The policy of [PairingAttemptLimiter] on its own, with a hand-moved clock. */
class PairingAttemptLimiterTest {
    private val clock = FakeClock()
    private val limiter = PairingAttemptLimiter(clock)
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun failOnce() = checkNotNull(limiter.tryAcquire()) { "expected a free slot" }.failed()

    @Test
    fun freeFailuresThenDoublingLockoutsUpToTheCap() {
        repeat(5) { failOnce() }
        assertEquals(0, limiter.lockoutRemainingMillis())
        val expected = listOf(1, 2, 4, 8, 16, 32, 60, 60).map { it * minute }
        for (lockout in expected) {
            failOnce()
            assertEquals(lockout, limiter.lockoutRemainingMillis())
            assertNull(limiter.tryAcquire())
            clock.advance(lockout - 1)
            assertNull(limiter.tryAcquire(), "still locked one millisecond before the end")
            clock.advance(1)
        }
        assertNotNull(limiter.tryAcquire())
    }

    @Test
    fun oneFailureIsForgivenPerQuietHour() {
        repeat(7) { limiter.recordFailure() }
        assertEquals(7, limiter.failures)
        clock.advance(hour - 1)
        assertEquals(7, limiter.failures)
        clock.advance(1)
        assertEquals(6, limiter.failures)
        clock.advance(3 * hour)
        assertEquals(3, limiter.failures)
        clock.advance(10 * hour)
        assertEquals(0, limiter.failures)
        // Back to the free allowance.
        repeat(5) { failOnce() }
        assertEquals(0, limiter.lockoutRemainingMillis())
    }

    @Test
    fun successIsNeutralAndSettlingTwiceCountsOnce() {
        repeat(3) { checkNotNull(limiter.tryAcquire()).succeeded() }
        assertEquals(0, limiter.failures)
        val permit = checkNotNull(limiter.tryAcquire())
        permit.failed()
        permit.failed()
        permit.succeeded()
        assertEquals(1, limiter.failures)
        val cancelled = checkNotNull(limiter.tryAcquire())
        cancelled.cancelled()
        assertEquals(1, limiter.failures)
        assertNotNull(limiter.tryAcquire())
    }

    @Test
    fun oneUntrustedHandshakeAtATime() {
        val first = checkNotNull(limiter.tryAcquire())
        assertNull(limiter.tryAcquire())
        assertNull(limiter.tryAcquire())
        assertEquals(0, limiter.failures, "a refusal is not a failure")
        first.succeeded()
        assertNotNull(limiter.tryAcquire())
    }

    @Test
    fun anUnsettledPermitTimesOutAsAFailureAtItsDeadline() {
        val stale = checkNotNull(limiter.tryAcquire())
        clock.advance(30_000)
        val next = assertNotNull(limiter.tryAcquire())
        assertEquals(1, limiter.failures)
        stale.succeeded() // too late: the time-out already counted it
        stale.failed()
        assertEquals(1, limiter.failures)
        assertNull(limiter.tryAcquire(), "the stale permit does not free the new one's slot")
        next.succeeded()

        // A permit abandoned for hours is counted at its deadline, so it does not lock out a user who returns now.
        repeat(5) { failOnce() } // six failures: locked for a minute from now
        clock.advance(minute)
        checkNotNull(limiter.tryAcquire())
        clock.advance(5 * hour)
        assertEquals(0, limiter.lockoutRemainingMillis())
        assertNotNull(limiter.tryAcquire())
    }

    @Test
    fun recordFailureCountsUserVisibleFailures() {
        repeat(6) { limiter.recordFailure() }
        assertEquals(6, limiter.failures)
        assertEquals(minute, limiter.lockoutRemainingMillis())
        assertNull(limiter.tryAcquire())
    }

    @Test
    fun aYearOfProbingYieldsFewerThanTenThousandGuesses() {
        // The attacker retries every second, and every attempt it gets ends unverified.
        var guesses = 0
        val year = 365 * 24 * hour
        while (clock.millis < year) {
            val permit = limiter.tryAcquire()
            if (permit != null) {
                guesses++
                permit.failed()
            }
            val wait = limiter.lockoutRemainingMillis()
            clock.advance(if (wait > 0) wait else 1_000)
        }
        assertTrue(guesses < 10_000, "$guesses guesses")
        assertTrue(guesses > 8_000, "$guesses guesses: the cap is about one per hour, not less")
    }

    @Test
    fun concurrentCallersNeverHoldTwoSlots() {
        val pool = Executors.newFixedThreadPool(8)
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        val granted = AtomicInteger()
        val start = CountDownLatch(1)
        repeat(8) {
            pool.execute {
                start.await()
                repeat(2_000) {
                    val permit = limiter.tryAcquire() ?: return@repeat
                    granted.incrementAndGet()
                    val now = inside.incrementAndGet()
                    maxInside.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    inside.decrementAndGet()
                    permit.succeeded()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(1, maxInside.get())
        assertTrue(granted.get() > 0)
    }

    @Test
    fun policyIsValidated() {
        assertFailsWith<IllegalArgumentException> { PairingAttemptLimiter.Policy(freeFailures = -1) }
        assertFailsWith<IllegalArgumentException> { PairingAttemptLimiter.Policy(firstLockoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { PairingAttemptLimiter.Policy(firstLockoutMillis = 10, maxLockoutMillis = 5) }
        assertFailsWith<IllegalArgumentException> { PairingAttemptLimiter.Policy(forgiveAfterMillis = 0) }
        assertFailsWith<IllegalArgumentException> { PairingAttemptLimiter.Policy(inFlightTimeoutMillis = 0) }
        // A huge cap does not overflow the doubling.
        val wide = PairingAttemptLimiter(clock, PairingAttemptLimiter.Policy(freeFailures = 0, maxLockoutMillis = Long.MAX_VALUE / 4))
        repeat(80) { wide.recordFailure() }
        assertTrue(wide.lockoutRemainingMillis() > 0)
    }
}
