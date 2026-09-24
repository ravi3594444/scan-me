package com.constrivo.drop.core.transfer.hash

import com.constrivo.drop.core.protocol.ChunkHash
import net.openhft.hashing.LongTupleHashFunction
import java.security.MessageDigest

private object Xxh3ChunkHasher : ChunkHasher {
    private val function: LongTupleHashFunction = LongTupleHashFunction.xx128()

    override fun hash(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): ChunkHash {
        val result = compute(buffer, offset, length)
        val out = ByteArray(ChunkHash.SIZE)
        putLong(out, 0, result[1]) // high 64 bits first (canonical form)
        putLong(out, 8, result[0])
        return ChunkHash(out)
    }

    override fun matches(
        expected: ChunkHash,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        val result = compute(buffer, offset, length)
        val bytes = expected.toByteArray()
        return getLong(bytes, 0) == result[1] && getLong(bytes, 8) == result[0]
    }

    private fun compute(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): LongArray {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
        val result = LongArray(2)
        // result[0] is the low 64 bits, result[1] the high 64 bits.
        function.hashBytes(buffer, offset, length, result)
        return result
    }

    private fun putLong(
        out: ByteArray,
        at: Int,
        value: Long,
    ) {
        for (i in 0 until 8) out[at + i] = (value ushr (56 - 8 * i)).toByte()
    }

    private fun getLong(
        bytes: ByteArray,
        at: Int,
    ): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[at + i].toLong() and 0xFF)
        return v
    }
}

actual fun xxh3ChunkHasher(): ChunkHasher = Xxh3ChunkHasher

private class JcaDigest(
    algorithm: String,
) : StreamingDigest {
    private val digest = MessageDigest.getInstance(algorithm)

    override fun update(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) = digest.update(buffer, offset, length)

    override fun digest(): ByteArray = digest.digest()

    override fun reset() = digest.reset()
}

actual fun sha256Digest(): StreamingDigest = JcaDigest("SHA-256")
