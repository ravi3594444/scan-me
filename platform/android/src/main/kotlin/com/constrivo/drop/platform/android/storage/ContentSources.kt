package com.constrivo.drop.platform.android.storage

import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** What the content resolver says about a file to send; any field may be missing. */
data class SourceFacts(
    val name: String?,
    val size: Long?,
    val mimeType: String?,
    val modifiedMillis: Long? = null,
)

/**
 * The seam between [ContentSources] and the platform's content resolver ([AndroidPendingItemResolver] on a device, a
 * fake in tests). Every call is blocking and may throw [IOException] or [SecurityException] (the grant is gone).
 */
interface ContentSourceResolver {
    /** Name, size and type of [uri] (`OpenableColumns`, the provider's type), or null when it cannot be read. */
    fun describe(uri: String): SourceFacts?

    /** [uri] opened for positional reads, or null when the provider hands out a stream that cannot seek (a pipe). */
    fun openRead(uri: String): PositionalFile?

    /** A new stream over [uri] from its start. */
    fun openStream(uri: String): InputStream
}

/**
 * Files to send from `content:` URIs (architecture §10.1: "read via SAF/MediaStore URIs, no broad storage
 * permission"): photos from MediaStore, documents from the system picker, and a share's streams, which stay readable
 * while the transfer service holds the grant the share's activity handed it (`ClipData` with
 * `FLAG_GRANT_READ_URI_PERMISSION`).
 *
 * A source reads with positional reads when its provider gives a seekable descriptor (every MediaStore and document
 * file), so retransmits and resumes read any unit again; a provider that only streams is read sequentially and opened
 * again for a unit behind the current position. The size must be known when the transfer starts (the `Offer` promises
 * it): from `OpenableColumns.SIZE`, else the descriptor's; a file whose size cannot be learned cannot be sent.
 */
class ContentSources(
    private val resolver: ContentSourceResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * A source for [uri], named [name] (the picker's name; the provider's when null), with [expectedSize] when the
     * picker knew it.
     *
     * @throws StorageException when the file cannot be read, or its size cannot be learned.
     */
    suspend fun open(
        uri: String,
        name: String? = null,
        expectedSize: Long? = null,
    ): SourceFile =
        withContext(io) {
            try {
                val facts = resolver.describe(uri) ?: throw StorageException("cannot read $uri")
                val size =
                    facts.size?.takeIf { it >= 0 }
                        ?: resolver.openRead(uri)?.use { it.length() }?.takeIf { it >= 0 }
                        ?: expectedSize?.takeIf { it >= 0 }
                        ?: throw StorageException("the size of $uri is unknown")
                val display = name?.takeIf { it.isNotBlank() } ?: facts.name?.takeIf { it.isNotBlank() } ?: uri.substringAfterLast('/')
                ContentSourceFile(uri, display, size, facts.mimeType, facts.modifiedMillis, resolver, io)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                throw e
            } catch (e: IOException) {
                throw StorageException("cannot read $uri: ${e.message}", e)
            } catch (e: SecurityException) {
                throw StorageException("no permission to read $uri", e)
            }
        }
}

/**
 * One file to send over its content URI. The descriptor opens on the first read and again after [close]; reads and
 * [close] are serialised, so a close never breaks a read in progress.
 */
internal class ContentSourceFile(
    private val uri: String,
    override val name: String,
    override val size: Long,
    override val mimeType: String?,
    override val modifiedMillis: Long?,
    private val resolver: ContentSourceResolver,
    private val io: CoroutineDispatcher,
) : SourceFile {
    private val lock = Mutex()
    private var file: PositionalFile? = null
    private var seekable: Boolean? = null
    private var stream: InputStream? = null
    private var streamPosition = 0L

    override suspend fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        if (length == 0) return 0
        if (position >= size) return -1
        val wanted = minOf(length.toLong(), size - position, READ_SLICE.toLong()).toInt()
        return lock.withLock {
            withContext(io) {
                try {
                    readLocked(position, buffer, offset, wanted)
                } catch (e: IOException) {
                    throw StorageException("reading $name failed: ${e.message}", e)
                } catch (e: SecurityException) {
                    throw StorageException("no permission to read $name any more", e)
                }
            }
        }
    }

    private fun readLocked(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (seekable != false) {
            val open = file ?: resolver.openRead(uri).also { file = it }
            if (open != null) {
                seekable = true
                return open.read(position, buffer, offset, length)
            }
            seekable = false
        }
        var input = stream
        if (input == null || position < streamPosition) {
            runCatching { input?.close() }
            input = resolver.openStream(uri)
            stream = input
            streamPosition = 0
        }
        skipTo(input, position)
        val n = input.read(buffer, offset, length)
        if (n > 0) streamPosition += n
        return n
    }

    private fun skipTo(
        input: InputStream,
        position: Long,
    ) {
        var remaining = position - streamPosition
        var scratch: ByteArray? = null
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                streamPosition += skipped
                continue
            }
            val bytes = scratch ?: ByteArray(SKIP_BUFFER).also { scratch = it }
            val n = input.read(bytes, 0, minOf(remaining, bytes.size.toLong()).toInt())
            if (n < 0) throw EOFException("$name ended at $streamPosition, before $position")
            remaining -= n
            streamPosition += n
        }
    }

    override suspend fun close() {
        lock.withLock {
            runCatching { file?.close() }
            runCatching { stream?.close() }
            file = null
            stream = null
            streamPosition = 0
        }
    }

    private companion object {
        /** Reads are sliced like the desktop's, so native staging buffers stay small (architecture §15). */
        const val READ_SLICE = 256 * 1024
        const val SKIP_BUFFER = 8 * 1024
    }
}
