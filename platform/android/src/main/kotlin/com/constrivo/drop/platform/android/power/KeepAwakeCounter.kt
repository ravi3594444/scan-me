package com.constrivo.drop.platform.android.power

import com.constrivo.drop.core.transfer.Releasable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The lock [KeepAwakeCounter] drives: a partial `PowerManager.WakeLock` on a device ([AndroidPowerPolicy]), a
 * recording fake in tests. Not reference counted: [acquire] on a held lock only renews its timeout.
 */
interface WakeLockHandle {
    /** Acquires the lock, or renews it, for at most [timeoutMillis]. */
    fun acquire(timeoutMillis: Long)

    /** Releases the lock; a no-op when it is not held. */
    fun release()
}

/**
 * Keeps the processor awake while at least one transfer asks (architecture §9 "Stay awake and cool", F-E12: a partial
 * wake lock while transferring): the first [hold] acquires the lock, the last release lets it go, and the holders in
 * between share it. The lock is never held open-ended: it is acquired for [leaseMillis] and renewed every
 * [renewMillis] while someone holds it, so a process that stops renewing (a bug, a hang) loses it within one lease
 * instead of draining the battery.
 *
 * Every [Releasable] is idempotent. Thread-safe; renewals run in [scope] (on its dispatcher's clock, so a virtual-time
 * test drives them).
 *
 * @param onChange the reasons of the current holders after every change, for the ring-buffer log.
 */
class KeepAwakeCounter(
    private val lock: WakeLockHandle,
    private val scope: CoroutineScope,
    private val leaseMillis: Long = DEFAULT_LEASE_MILLIS,
    private val renewMillis: Long = DEFAULT_RENEW_MILLIS,
    private val onChange: (List<String>) -> Unit = {},
) {
    init {
        require(renewMillis in 1 until leaseMillis) { "renew before the lease ends" }
    }

    private val guard = Any()
    private val holders = ArrayList<Holder>()
    private var renewer: Job? = null

    /** The reasons of the current holders, oldest first. */
    val reasons: List<String> get() = synchronized(guard) { holders.map { it.reason } }

    /** Whether the lock is held now. */
    val isHeld: Boolean get() = synchronized(guard) { holders.isNotEmpty() }

    /** Holds the lock for [reason] until the returned [Releasable] is released. */
    fun hold(reason: String): Releasable {
        val holder = Holder(reason)
        val snapshot =
            synchronized(guard) {
                holders += holder
                if (holders.size == 1) {
                    lock.acquire(leaseMillis)
                    renewer = scope.launch { renewLoop() }
                }
                holders.map { it.reason }
            }
        onChange(snapshot)
        return holder
    }

    /** Releases every holder at once (the service stops). */
    fun releaseAll() {
        val had =
            synchronized(guard) {
                if (holders.isEmpty()) return
                holders.forEach { it.released.set(true) }
                holders.clear()
                renewer?.cancel()
                renewer = null
                lock.release()
                true
            }
        if (had) onChange(emptyList())
    }

    private suspend fun renewLoop() {
        while (scope.isActive) {
            delay(renewMillis)
            synchronized(guard) {
                if (holders.isEmpty()) return
                lock.acquire(leaseMillis)
            }
        }
    }

    private inner class Holder(
        val reason: String,
    ) : Releasable {
        val released = AtomicBoolean(false)

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            val snapshot =
                synchronized(guard) {
                    if (!holders.remove(this)) return
                    if (holders.isEmpty()) {
                        renewer?.cancel()
                        renewer = null
                        lock.release()
                    }
                    holders.map { it.reason }
                }
            onChange(snapshot)
        }
    }

    companion object {
        /** One lease of the wake lock: long enough to survive a late renewal, short enough to bound a leak. */
        const val DEFAULT_LEASE_MILLIS: Long = 10 * 60_000L

        /** Renewed well before the lease ends. */
        const val DEFAULT_RENEW_MILLIS: Long = 4 * 60_000L
    }
}
