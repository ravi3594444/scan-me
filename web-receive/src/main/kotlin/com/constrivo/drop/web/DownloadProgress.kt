package com.constrivo.drop.web

import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * How far one tagged download has got: a download whose URL carries `?dl=<id>`
 * ([ReceiveRoutes.DOWNLOAD_ID_PARAMETER]). The page tags the downloads it hands to the browser's own download manager
 * (large files, the zip, and browsers without `ReadableStream`), so the bytes go straight to disk, and polls
 * [ReceiveRoutes.PROGRESS] to show the progress bar and MB/s.
 *
 * @property total the size of the whole file or archive.
 * @property end where this response stops: [total], or the end of the requested range.
 */
internal class DownloadProgress(
    val total: Long,
    offset: Long,
    private val end: Long,
) {
    private val sent = AtomicLong(offset)

    /** Position in the file: the range start plus the bytes written so far. */
    val position: Long get() = sent.get()

    @Volatile
    var state: State = State.RUNNING
        private set

    fun add(bytes: Int) {
        sent.addAndGet(bytes.toLong())
    }

    /**
     * The response ended; it only counts as done when [ok] and every promised byte was written. The first call
     * settles the state.
     */
    @Synchronized
    fun finish(ok: Boolean) {
        if (state != State.RUNNING) return
        state = if (ok && sent.get() == end) State.DONE else State.FAILED
    }

    enum class State(
        val wire: String,
    ) {
        RUNNING("running"),
        DONE("done"),
        FAILED("failed"),
    }
}

/** One browser's tagged downloads, the newest [capacity] of them (a page tags one at a time). */
internal class DownloadProgressTable(
    private val capacity: Int = 16,
) {
    private val entries = LinkedHashMap<String, DownloadProgress>()

    /** Starts tracking a response for [id], replacing an earlier one (a resumed download reuses its URL). */
    @Synchronized
    fun start(
        id: String,
        total: Long,
        offset: Long,
        end: Long,
    ): DownloadProgress {
        val progress = DownloadProgress(total, offset, end)
        entries.remove(id)
        entries[id] = progress
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return progress
    }

    @Synchronized
    operator fun get(id: String): DownloadProgress? = entries[id]

    companion object {
        private const val MIN_ID = 8
        private const val MAX_ID = 64

        /** 8 to 64 characters of `[A-Za-z0-9_-]`; anything else is not tracked. */
        fun isValidId(id: String): Boolean =
            id.length in MIN_ID..MAX_ID && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    }
}

/** Writes through to [out] and counts every byte into [progress]. */
internal class ProgressOutputStream(
    private val out: OutputStream,
    private val progress: DownloadProgress,
) : OutputStream() {
    override fun write(b: Int) {
        out.write(b)
        progress.add(1)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        out.write(b, off, len)
        progress.add(len)
    }

    override fun flush() = out.flush()

    override fun close() = out.close()
}
