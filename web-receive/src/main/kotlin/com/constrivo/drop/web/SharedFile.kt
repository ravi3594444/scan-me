package com.constrivo.drop.web

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * One file the browser page offers (architecture §10.3, design §10, F‑D6).
 *
 * The server needs only these four things, so Android can back a file with a content URI (`ContentResolver`
 * query for name, size and type; `openAssetFileDescriptor` for [open] at an offset) and desktops with a [Path]
 * ([PathSharedFile]).
 *
 * The server trusts [size]: it is promised to the browser in `Content-Length` and in the zip layout before any byte
 * is read. A stream that ends early fails that response with [SharedFileChangedException]; bytes past [size] are
 * never read.
 */
interface SharedFile {
    /** The name the sender picked. The server sanitises it ([FileNames]) before it reaches a header or a zip entry. */
    val name: String

    /** Exact size in bytes, at least 0. */
    val size: Long

    /** MIME type such as `image/jpeg`, or null when unknown (served as `application/octet-stream`). */
    val mimeType: String?

    /**
     * Opens a new, independent stream positioned at [offset] (`0 <= offset <= size`). Called on an IO thread; the
     * server closes the stream. [skipFully] helps implementations whose source cannot seek.
     *
     * @throws IOException when the file cannot be read any more (deleted, permission revoked).
     */
    fun open(offset: Long): InputStream
}

/** The file changed after it was offered: its stream ended before the size the server had promised. */
class SharedFileChangedException(
    message: String,
) : IOException(message)

/**
 * Skips exactly [count] bytes, falling back to reading when [InputStream.skip] makes no progress.
 *
 * @throws EOFException when the stream ends first.
 */
fun InputStream.skipFully(count: Long) {
    require(count >= 0) { "count must not be negative" }
    var remaining = count
    var scratch: ByteArray? = null
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        val buffer = scratch ?: ByteArray(SKIP_BUFFER).also { scratch = it }
        val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        if (read < 0) throw EOFException("stream ended $remaining bytes before the requested offset")
        remaining -= read
    }
}

private const val SKIP_BUFFER = 8 * 1024

/**
 * A [SharedFile] on the local file system (desktop, tests, the Playwright launcher). The size is read once, when the
 * object is created, as the server's promise to the browser.
 */
class PathSharedFile(
    private val path: Path,
    override val name: String = path.fileName?.toString() ?: "file",
    override val mimeType: String? = probeMimeType(path),
) : SharedFile {
    override val size: Long = Files.size(path)

    override fun open(offset: Long): InputStream {
        require(offset in 0..size) { "offset $offset outside 0..$size" }
        val channel = Files.newByteChannel(path, StandardOpenOption.READ)
        try {
            channel.position(offset)
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        return Channels.newInputStream(channel)
    }

    override fun toString(): String = "PathSharedFile($path)"

    private companion object {
        fun probeMimeType(path: Path): String? =
            try {
                Files.probeContentType(path)
            } catch (_: IOException) {
                null
            }
    }
}
