package com.constrivo.drop.platform.android.service

import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.web.SharedFile
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream

/**
 * A file of a send offered to the browser receive page (F-D6): web-receive's [SharedFile] over the same [SourceFile]
 * the engine would read (a `content:` URI on a device), so the page serves exactly what the picker chose, with the
 * grant the transfer service holds. [open] is called on the server's I/O threads, so its blocking reads wait for the
 * source's suspending ones.
 */
internal class SourceSharedFile(
    private val source: SourceFile,
    override val name: String = source.name,
) : SharedFile {
    override val size: Long get() = source.size
    override val mimeType: String? get() = source.mimeType

    override fun open(offset: Long): InputStream {
        require(offset in 0..size) { "offset $offset outside 0..$size" }
        return SourceStream(source, offset, size)
    }

    private class SourceStream(
        private val source: SourceFile,
        private var position: Long,
        private val size: Long,
    ) : InputStream() {
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (closed) throw IOException("stream closed")
            if (len == 0) return 0
            if (position >= size) return -1
            val wanted = minOf(len.toLong(), size - position).toInt()
            val n =
                try {
                    runBlocking { source.read(position, b, off, wanted) }
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("reading ${source.name} failed: ${e.message}", e)
                }
            if (n > 0) position += n
            return n
        }

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val skipped = minOf(n, size - position).coerceAtLeast(0)
            position += skipped
            return skipped
        }

        override fun available(): Int = 0

        override fun close() {
            closed = true
        }
    }
}
