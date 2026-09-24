package com.constrivo.drop.platform.android.power

import com.constrivo.drop.core.transfer.ThermalLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The thermal mapping (F-F6) and the wake lock kept while transferring (F-E12), on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PowerTest {
    @Test
    fun `every thermal status maps to the same step, and unknown ones never read as cool`() {
        val expected =
            listOf(
                ThermalLevel.NONE,
                ThermalLevel.LIGHT,
                ThermalLevel.MODERATE,
                ThermalLevel.SEVERE,
                ThermalLevel.CRITICAL,
                ThermalLevel.EMERGENCY,
                ThermalLevel.SHUTDOWN,
            )
        // THERMAL_STATUS_NONE (0) … THERMAL_STATUS_SHUTDOWN (6).
        for ((status, level) in expected.withIndex()) assertEquals(level, ThermalLevels.fromStatus(status), "status $status")
        assertEquals(ThermalLevel.SHUTDOWN, ThermalLevels.fromStatus(7))
        assertEquals(ThermalLevel.SHUTDOWN, ThermalLevels.fromStatus(Int.MAX_VALUE))
        assertEquals(ThermalLevel.NONE, ThermalLevels.fromStatus(-1))
    }

    @Test
    fun `the engine throttles from severe up`() {
        assertEquals(
            listOf(ThermalLevel.SEVERE, ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN),
            ThermalLevel.entries.filter(ThermalLevels::throttles),
        )
    }

    private class RecordingLock : WakeLockHandle {
        val events = ArrayList<String>()
        var held = false

        override fun acquire(timeoutMillis: Long) {
            events += "acquire $timeoutMillis"
            held = true
        }

        override fun release() {
            events += "release"
            held = false
        }
    }

    @Test
    fun `the lock is shared by the holders and renewed with a lease while any holds it`() =
        runTest {
            val lock = RecordingLock()
            val changes = ArrayList<List<String>>()
            val counter = KeepAwakeCounter(lock, backgroundScope, leaseMillis = 10_000, renewMillis = 4_000) { changes += it }

            val send = counter.hold("sending a")
            val receive = counter.hold("receiving b")
            assertEquals(listOf("acquire 10000"), lock.events, "one acquire for both")
            assertEquals(listOf("sending a", "receiving b"), counter.reasons)

            advanceTimeBy(4_001)
            runCurrent()
            assertEquals(listOf("acquire 10000", "acquire 10000"), lock.events, "renewed before the lease ends")

            send.release()
            send.release()
            assertTrue(lock.held, "the other holder keeps it")
            receive.release()
            assertFalse(lock.held)
            assertFalse(counter.isHeld)
            assertEquals(listOf("acquire 10000", "acquire 10000", "release"), lock.events)

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(3, lock.events.size, "no renewal once released")
            assertEquals(listOf(listOf("sending a"), listOf("sending a", "receiving b"), listOf("receiving b"), emptyList()), changes)
        }

    @Test
    fun `releasing everything lets go at once and old holders stay released`() =
        runTest {
            val lock = RecordingLock()
            val counter = KeepAwakeCounter(lock, backgroundScope, leaseMillis = 10_000, renewMillis = 4_000)
            val a = counter.hold("a")
            counter.hold("b")
            counter.releaseAll()
            assertFalse(lock.held)
            a.release()
            counter.hold("c")
            assertTrue(lock.held)
            assertEquals(listOf("c"), counter.reasons)
            assertEquals(listOf("acquire 10000", "release", "acquire 10000"), lock.events)
        }
}
