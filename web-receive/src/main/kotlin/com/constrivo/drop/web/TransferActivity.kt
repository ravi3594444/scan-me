package com.constrivo.drop.web

import com.constrivo.drop.core.discovery.MonotonicClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts downloads and uploads in progress and decides when the server is idle (architecture §10.3: "shuts down 60 s
 * after the last download"). The idle timer starts when a transfer finishes (completed, failed or cancelled by the
 * browser) and nothing else is in progress; a new transfer stops it. Before the first transfer has finished the
 * server is never idle: how long the page may wait for its first download is the app's decision.
 *
 * Durations are measured on the injected [MonotonicClock].
 */
class TransferActivity(
    private val clock: MonotonicClock,
    private val idleTimeoutMillis: Long,
) {
    private var active = 0
    private var lastFinishedAt: Long? = null
    private var completed = 0
    private val sent = AtomicLong()
    private val received = AtomicLong()

    init {
        require(idleTimeoutMillis > 0) { "idle timeout must be positive" }
    }

    /** A transfer started. Pair every call with one [finished]; [track] does that. */
    @Synchronized
    fun started() {
        active++
    }

    /** A transfer ended, whatever the outcome. */
    @Synchronized
    fun finished() {
        check(active > 0) { "finished() without started()" }
        active--
        completed++
        lastFinishedAt = clock.elapsedMillis()
    }

    /** Runs [block] as one transfer. */
    suspend fun <T> track(block: suspend () -> T): T {
        started()
        try {
            return block()
        } finally {
            finished()
        }
    }

    /** Whether the idle timeout has passed since the last transfer ended, with nothing in progress. */
    @Synchronized
    fun isIdle(): Boolean {
        val last = lastFinishedAt ?: return false
        return active == 0 && clock.elapsedMillis() - last >= idleTimeoutMillis
    }

    internal fun addSent(bytes: Int) {
        sent.addAndGet(bytes.toLong())
    }

    internal fun addReceived(bytes: Int) {
        received.addAndGet(bytes.toLong())
    }

    /** Counters for the phone UI and the support log. */
    @Synchronized
    fun snapshot(): Snapshot = Snapshot(active, completed, sent.get(), received.get())

    /**
     * @property inProgress downloads and uploads running now.
     * @property finished transfers that ended (including failed and cancelled ones).
     * @property bytesSent file and zip bytes written to browsers.
     * @property bytesReceived upload bytes accepted.
     */
    data class Snapshot(
        val inProgress: Int,
        val finished: Int,
        val bytesSent: Long,
        val bytesReceived: Long,
    )
}
