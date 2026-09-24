package com.constrivo.drop.platform.android.storage

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.constrivo.drop.core.transfer.StorageFullException
import java.io.IOException

/**
 * A [PositionalFile] over a `ParcelFileDescriptor` from `ContentResolver.openFileDescriptor` (N14: MediaStore and
 * document descriptors opened `"rw"`), with `pread`, `pwrite`, `fstat` and `fsync` from [Os], so writes land at any
 * offset without a `FileChannel` (a descriptor's input stream channel cannot write and its output stream channel cannot
 * read). A full volume (`ENOSPC`, `EDQUOT`) is [StorageFullException]; every other failure an [IOException].
 */
internal class DescriptorFile(
    private val descriptor: ParcelFileDescriptor,
) : PositionalFile {
    @Volatile
    private var closed = false

    override fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        while (true) {
            try {
                val n = Os.pread(descriptor.fileDescriptor, buffer, offset, length, position)
                return if (n == 0) -1 else n
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINTR) throw failure("read", e)
            }
        }
    }

    override fun write(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        var written = 0
        while (written < length) {
            try {
                val n = Os.pwrite(descriptor.fileDescriptor, buffer, offset + written, length - written, position + written)
                if (n <= 0) throw IOException("write made no progress at ${position + written}")
                written += n
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINTR) throw failure("write", e)
            }
        }
    }

    override fun length(): Long =
        try {
            Os.fstat(descriptor.fileDescriptor).st_size
        } catch (e: ErrnoException) {
            throw failure("stat", e)
        }

    override fun sync() {
        try {
            Os.fsync(descriptor.fileDescriptor)
        } catch (e: ErrnoException) {
            throw failure("sync", e)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { descriptor.close() }
    }

    private fun failure(
        what: String,
        e: ErrnoException,
    ): Exception =
        if (e.errno == OsConstants.ENOSPC || e.errno == OsConstants.EDQUOT) {
            StorageFullException("$what failed: the volume is full", e)
        } else {
            IOException("$what failed: ${e.message}", e)
        }
}
