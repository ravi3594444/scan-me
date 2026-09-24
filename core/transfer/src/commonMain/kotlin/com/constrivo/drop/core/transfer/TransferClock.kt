package com.constrivo.drop.core.transfer

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * The engine's time source (architecture §7.8 timers, §7.4 throughput meter, F-F1).
 *
 * [nowMillis] is epoch milliseconds: the transfer state machine stores it (`created_at`, the 24 h parked deadline) and
 * the meter measures intervals with it. The engine also waits with coroutine `delay` on its dispatchers, so the clock
 * must advance at the same rate as those dispatchers' time: the system clock with real dispatchers, or the test
 * scheduler's `currentTime` with a virtual-time test dispatcher.
 */
fun interface TransferClock {
    fun nowMillis(): Long

    companion object {
        /** The platform wall clock. */
        val SYSTEM: TransferClock = TransferClock { currentTimeMillis() }
    }
}

/** The platform wall clock in epoch milliseconds. */
internal expect fun currentTimeMillis(): Long

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
