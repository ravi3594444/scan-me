package com.constrivo.drop.core.transfer

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * The engine's time source (architecture §7.8 timers, §7.4 throughput meter, F-F1).
 *
 * [nowMillis] is epoch milliseconds: the transfer state machine stores it (`created_at`, the 24 h parked deadline).
 * [elapsedMillis] is a monotonic time line for intervals and deadlines (watchdog feeds, heartbeat pacing, ack batching,
 * meter samples, the per-connection watchdog), so a wall-clock step (NTP, a time-zone or network-time correction)
 * neither stops heartbeats nor stalls acks. The engine also waits with coroutine `delay` on its dispatchers, so both
 * must advance at the same rate as those dispatchers' time: the system clocks with real dispatchers, or the test
 * scheduler's `currentTime` with a virtual-time test dispatcher (the default [elapsedMillis] is [nowMillis], which is
 * right for such a clock).
 */
fun interface TransferClock {
    fun nowMillis(): Long

    /** Monotonic milliseconds for intervals; only differences are meaningful. */
    fun elapsedMillis(): Long = nowMillis()

    companion object {
        /** The platform wall clock, and the platform's monotonic clock for intervals. */
        val SYSTEM: TransferClock =
            object : TransferClock {
                override fun nowMillis(): Long = currentTimeMillis()

                override fun elapsedMillis(): Long = monotonicMillis()
            }
    }
}

/** The platform wall clock in epoch milliseconds. */
internal expect fun currentTimeMillis(): Long

/** The platform's monotonic clock in milliseconds (`System.nanoTime` on the JVM). */
internal expect fun monotonicMillis(): Long

/** A mutual-exclusion lock for the engine's small shared structures; `commonMain` has no `synchronized`. Reentrant. */
internal expect class TransferLock() {
    fun lock()

    fun unlock()
}

/** Runs [block] holding the lock. */
@OptIn(ExperimentalContracts::class)
internal inline fun <T> TransferLock.withLock(block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
