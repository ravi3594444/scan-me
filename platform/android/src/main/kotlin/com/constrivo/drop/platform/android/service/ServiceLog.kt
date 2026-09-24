package com.constrivo.drop.platform.android.service

import com.constrivo.drop.core.discovery.WallClock
import java.time.Instant

/**
 * The local ring-buffer log (architecture §14): the last [capacity] events of the transfer service (node lifecycle,
 * transfer ends, link and thermal changes, wake-lock use, the Android 15 `dataSync` quota of spec change S9), kept in
 * memory only and exported as plain text from Settings → About for support. Nothing leaves the device unless the user
 * shares that export.
 *
 * Messages are single lines: control characters become spaces and a message longer than [MAX_MESSAGE_CHARS] is cut,
 * so an export stays readable whatever a peer's nickname or an exception message contains. Thread-safe.
 *
 * @param mirror receives every accepted line as well (the service forwards it to logcat in debuggable builds).
 */
class ServiceLog(
    private val wallClock: WallClock,
    val capacity: Int = CAPACITY,
    private val mirror: (Entry) -> Unit = {},
) {
    init {
        require(capacity > 0) { "the log keeps at least one event" }
    }

    /** One event: when it happened (wall clock), which part of the service logged it, and what happened. */
    data class Entry(
        val atMillis: Long,
        val tag: String,
        val message: String,
    ) {
        /** `2026-09-24T09:54:25.900Z [tag] message`. */
        fun format(): String = "${Instant.ofEpochMilli(atMillis)} [$tag] $message"
    }

    private val lock = Any()
    private val ring = arrayOfNulls<Entry>(capacity)
    private var next = 0
    private var count = 0
    private var droppedCount = 0L

    /** Events evicted because the ring was full since the service started. */
    val dropped: Long get() = synchronized(lock) { droppedCount }

    /** Events held now (at most [capacity]). */
    val size: Int get() = synchronized(lock) { count }

    /** Appends an event; the oldest goes when the ring is full. */
    fun log(
        tag: String,
        message: String,
    ) {
        val entry = Entry(wallClock.nowMillis(), clean(tag, MAX_TAG_CHARS), clean(message, MAX_MESSAGE_CHARS))
        synchronized(lock) {
            ring[next] = entry
            next = (next + 1) % capacity
            if (count < capacity) count++ else droppedCount++
        }
        runCatching { mirror(entry) }
    }

    /** A logger bound to [tag], for the components' `log: (String) -> Unit` parameters. */
    fun tagged(tag: String): (String) -> Unit = { message -> log(tag, message) }

    /** The events held now, oldest first. */
    fun snapshot(): List<Entry> =
        synchronized(lock) {
            val start = (next - count + capacity) % capacity
            List(count) { i -> checkNotNull(ring[(start + i) % capacity]) }
        }

    /**
     * The export for support: a header naming the app build and how many older events were dropped, then one
     * formatted line per event, oldest first, each ending with a newline.
     */
    fun export(header: String = ""): String {
        val entries = snapshot()
        val lost = dropped
        return buildString {
            if (header.isNotBlank()) appendLine(clean(header, MAX_MESSAGE_CHARS))
            appendLine("events: ${entries.size} (older dropped: $lost)")
            for (entry in entries) appendLine(entry.format())
        }
    }

    /** Forgets every event (Settings → "Clear history" does not touch it; only a new process or this call does). */
    fun clear() {
        synchronized(lock) {
            ring.fill(null)
            next = 0
            count = 0
            droppedCount = 0
        }
    }

    companion object {
        /** Architecture §14: the last 2,000 events. */
        const val CAPACITY: Int = 2_000

        const val MAX_MESSAGE_CHARS: Int = 500
        const val MAX_TAG_CHARS: Int = 24

        /** [text] on one line of at most [max] characters: control characters become spaces, the rest is cut. */
        internal fun clean(
            text: String,
            max: Int,
        ): String {
            val flat = buildString(minOf(text.length, max + 1)) { for (c in text) append(if (c.isISOControl()) ' ' else c) }.trim()
            return if (flat.length <= max) flat else flat.take(max - 1) + "…"
        }
    }
}
