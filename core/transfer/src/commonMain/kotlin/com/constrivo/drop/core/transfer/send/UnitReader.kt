package com.constrivo.drop.core.transfer.send

import com.constrivo.drop.core.protocol.BundleIndex
import com.constrivo.drop.core.protocol.ResumeUnit
import com.constrivo.drop.core.protocol.Sha256Digest
import com.constrivo.drop.core.protocol.TransferLayout
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.hash.sha256Digest
import kotlinx.coroutines.CancellationException

private const val MAX_ZERO_READS = 1000

/** A source file could not be read as planned: it shrank, vanished or failed; the sender cancels with `source`. */
class SourceReadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Reads the plaintext payload of a unit from the picked files (architecture §7.3): a chunk is `chunk_size` bytes of
 * one file; a bundle is its index (`u32 count`, then `{file_index, offset_in_bundle, len}` per entry) followed by the
 * bytes of its small files, as the deterministic bundle plan says (S4). A unit resumed from a byte offset (S1) is read
 * from that offset. Bundled files are hashed whole while their bundle is built ([onFileHashed]), since every bundled
 * file lives in exactly one bundle.
 */
internal class UnitReader(
    private val layout: TransferLayout,
    private val sources: List<SourceFile>,
) {
    /**
     * Reads [unit] from its start offset into `buffer[at until at + n]` and returns `n`, the unit length minus the start
     * offset. The buffer must hold `at + unitLength` bytes (a bundle is assembled whole first).
     *
     * @throws SourceReadException if a source ends early or fails.
     */
    suspend fun read(
        unit: ResumeUnit,
        buffer: ByteArray,
        at: Int,
        onFileHashed: (fileIndex: Int, sha256: Sha256Digest) -> Unit = { _, _ -> },
    ): Int {
        val u = unit.unit
        val length = layout.unitLength(u)
        require(unit.fromOffset in 0 until maxOf(length, 1)) { "start offset ${unit.fromOffset} outside $u" }
        require(at >= 0 && buffer.size - at >= length) { "buffer too small for $u" }
        if (!u.isBundle) {
            val plan = checkNotNull(layout.chunkPlan(u.fileIndex)) { "file ${u.fileIndex} is bundled" }
            val position = plan.chunkOffset(u.chunkIndex) + unit.fromOffset
            val n = length - unit.fromOffset
            readFully(u.fileIndex, position, buffer, at, n)
            return n
        }
        val bundle = layout.bundlePlan.bundles[u.chunkIndex]
        val index = BundleIndex.encodeIndex(bundle.entries)
        index.copyInto(buffer, at)
        val dataStart = at + index.size
        for (entry in bundle.entries) {
            val from = dataStart + entry.offset
            readFully(entry.fileIndex, 0, buffer, from, entry.length)
            val digest = sha256Digest()
            digest.update(buffer, from, entry.length)
            onFileHashed(entry.fileIndex, Sha256Digest(digest.digest()))
        }
        if (unit.fromOffset > 0) buffer.copyInto(buffer, at, at + unit.fromOffset, at + length)
        return length - unit.fromOffset
    }

    /** Reads exactly [length] bytes of file [fileIndex] at [position]. */
    suspend fun readFully(
        fileIndex: Int,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val source = sources[fileIndex]
        var done = 0
        var zeroReads = 0
        while (done < length) {
            val n =
                try {
                    source.read(position + done, buffer, offset + done, length - done)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw SourceReadException("reading ${source.name} failed: ${e.message}", e)
                }
            if (n < 0) throw SourceReadException("${source.name} ended at ${position + done}, expected ${layout.fileSize(fileIndex)} bytes")
            if (n > length - done) throw SourceReadException("${source.name} returned $n bytes for a ${length - done}-byte read")
            if (n == 0) {
                if (++zeroReads >= MAX_ZERO_READS) throw SourceReadException("${source.name} keeps returning no data")
                continue
            }
            zeroReads = 0
            done += n
        }
    }
}
