package com.constrivo.drop.core.transfer.hash

import com.constrivo.drop.core.protocol.ChunkHash

/**
 * The per-frame and per-unit hash of architecture §7.3: XXH3-128 (decision 6), a fast non-cryptographic hash that
 * catches corruption between the file and the AEAD (T-28). Integrity against an attacker comes from the AEAD and the
 * whole-file SHA-256 (S2).
 *
 * The 16 bytes are XXH3's canonical form: the high 64 bits, then the low 64 bits, each big-endian (the
 * `XXH128_canonicalFromHash` layout, so values match the reference `xxhsum -H2`). Implementations are thread-safe.
 */
interface ChunkHasher {
    /** XXH3-128 of `buffer[offset until offset + length]`. */
    fun hash(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): ChunkHash

    /** True if `buffer[offset until offset + length]` hashes to [expected]. */
    fun matches(
        expected: ChunkHash,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean = hash(buffer, offset, length) == expected
}

/**
 * An incremental message digest. [digest] returns the result and resets the instance for reuse. Not thread-safe: one
 * instance hashes one stream of bytes.
 */
interface StreamingDigest {
    fun update(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    )

    fun digest(): ByteArray

    fun reset()
}

/** The platform's XXH3-128 ([ChunkHasher]); on the JVM `zero-allocation-hashing` (decision 6). */
expect fun xxh3ChunkHasher(): ChunkHasher

/** A new SHA-256 [StreamingDigest]; on the JVM the JCA `MessageDigest`, which uses SHA hardware instructions (§9). */
expect fun sha256Digest(): StreamingDigest

/** SHA-256 of an empty input, the `FileDone` hash of an empty file. */
val EMPTY_SHA256: ByteArray by lazy { sha256Digest().digest() }
