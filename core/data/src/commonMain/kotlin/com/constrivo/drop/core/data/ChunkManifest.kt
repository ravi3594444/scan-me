package com.constrivo.drop.core.data

import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferId

/**
 * The receiver's resume state for one tracking key of a transfer (architecture §7.6 with S1, S4, N5): which units
 * are on disk, their hashes, and the durable prefix of one unit received as Bluetooth blocks. Immutable; the `with*`
 * functions return updated copies.
 *
 * - [trackingKey] is a chunked file's index, or [ProtocolConstants.BUNDLE_FILE_INDEX] (-1; `0xFFFFFFFF` on the wire)
 *   for the bundles, as `TransferLayout.trackingKeys` in `core/protocol` defines them; [unitCount] is
 *   `TransferLayout.unitCount(key)`.
 * - Bit *u* of the received bitmap (byte `u / 8`, mask `1 << (u % 8)`) says unit *u* is on disk. **Set it only
 *   after the unit's bytes were written and `fsync`ed** (N5): a manifest persisted before the data would, after an
 *   app kill, claim bytes that are not there. The engine therefore flushes the partial file, then calls
 *   [withReceived], then persists the manifest (write-behind ≤ 100 ms).
 * - The unit hashes hold, at offset `16 × u`, the 16-byte XXH3-128 of unit *u*'s complete plaintext (decision 6),
 *   zeros while the bit is clear, so after a restart the receiver can re-hash the `.part` file and request exactly
 *   the units whose bytes no longer match, and name the bad unit when a whole-file SHA-256 fails (N5).
 * - [partialUnit] / [partialBytes]: bytes `0 until partialBytes` of unit [partialUnit] are on disk (verified
 *   Bluetooth blocks, S1, written and synced like whole units), reported as `MissingChunks.first_block_offset` so the
 *   sender continues from there. At most one partial unit per key, never one whose bit is set.
 *
 * @throws IllegalArgumentException from the constructor for inconsistent sizes or bits beyond [unitCount].
 */
class ChunkManifest(
    val transferId: TransferId,
    val trackingKey: Int,
    val unitCount: Int,
    receivedBitmap: ByteArray,
    unitHashes: ByteArray,
    val partialUnit: Int? = null,
    val partialBytes: Int = 0,
    val updatedAtMillis: Long,
) {
    private val bitmap: ByteArray = receivedBitmap.copyOf()
    private val hashes: ByteArray = unitHashes.copyOf()

    init {
        require(trackingKey >= ProtocolConstants.BUNDLE_FILE_INDEX) {
            "tracking key $trackingKey is neither a file index nor the bundle key"
        }
        require(unitCount in 0..MAX_UNITS) { "unit count $unitCount out of range 0..$MAX_UNITS" }
        require(bitmap.size == bitmapSize(unitCount)) {
            "bitmap must be ${bitmapSize(unitCount)} bytes for $unitCount units, got ${bitmap.size}"
        }
        require(hashes.size == unitCount * HASH_SIZE) { "unit hashes must be ${unitCount * HASH_SIZE} bytes, got ${hashes.size}" }
        val spare = bitmap.size * 8 - unitCount
        if (spare > 0) require((bitmap.last().toInt() and 0xFF) ushr (8 - spare) == 0) { "bits beyond the unit count are set" }
        require((partialUnit == null) == (partialBytes == 0)) { "a partial unit and its byte count go together" }
        if (partialUnit != null) {
            require(partialUnit in 0 until unitCount) { "partial unit $partialUnit out of range" }
            require(partialBytes in 1 until ProtocolConstants.CHUNK_SIZE) { "partial bytes $partialBytes out of range" }
            require(!isReceived(partialUnit)) { "unit $partialUnit is both received and partial" }
        }
    }

    /** Units whose bit is set. */
    val receivedCount: Int get() = bitmap.sumOf { it.toInt().and(0xFF).countOneBits() }

    /** Every unit is on disk. */
    val isComplete: Boolean get() = receivedCount == unitCount

    fun isReceived(unit: Int): Boolean {
        require(unit in 0 until unitCount) { "unit $unit out of range 0 until $unitCount" }
        return (bitmap[unit ushr 3].toInt() ushr (unit and 7)) and 1 == 1
    }

    /** The hash stored for [unit], or null while it is not received. */
    fun hashOf(unit: Int): ChunkHash? =
        if (isReceived(unit)) ChunkHash(hashes.copyOfRange(unit * HASH_SIZE, (unit + 1) * HASH_SIZE)) else null

    /**
     * Leading bytes of the missing [unit] already on disk: [partialBytes] for the partial unit, else 0. This is the
     * `presentBytes` argument of `TransferLayout.missingUnits` in `core/protocol`.
     */
    fun presentPrefix(unit: Int): Int = if (unit == partialUnit) partialBytes else 0

    /** A copy of the received bitmap, as stored. */
    fun receivedBitmap(): ByteArray = bitmap.copyOf()

    /** A copy of the unit hashes, as stored (`16 × unitCount` bytes). */
    fun unitHashes(): ByteArray = hashes.copyOf()

    /**
     * This manifest with [unit] received with [hash] at [atMillis]; clears the partial prefix if it was this unit.
     * Call only once the unit is durably on disk (see the class comment).
     */
    fun withReceived(
        unit: Int,
        hash: ChunkHash,
        atMillis: Long,
    ): ChunkManifest {
        require(unit in 0 until unitCount) { "unit $unit out of range 0 until $unitCount" }
        val newBitmap = bitmap.copyOf()
        newBitmap[unit ushr 3] = (newBitmap[unit ushr 3].toInt() or (1 shl (unit and 7))).toByte()
        val newHashes = hashes.copyOf()
        hash.toByteArray().copyInto(newHashes, unit * HASH_SIZE)
        val keepPartial = partialUnit != null && partialUnit != unit
        return ChunkManifest(
            transferId,
            trackingKey,
            unitCount,
            newBitmap,
            newHashes,
            if (keepPartial) partialUnit else null,
            if (keepPartial) partialBytes else 0,
            atMillis,
        )
    }

    /**
     * This manifest with [unit] no longer received (its bytes failed a re-hash, or a whole-file mismatch named it),
     * so a `Resume` requests it again.
     */
    fun withMissing(
        unit: Int,
        atMillis: Long,
    ): ChunkManifest {
        require(unit in 0 until unitCount) { "unit $unit out of range 0 until $unitCount" }
        val newBitmap = bitmap.copyOf()
        newBitmap[unit ushr 3] = (newBitmap[unit ushr 3].toInt() and (1 shl (unit and 7)).inv()).toByte()
        val newHashes = hashes.copyOf()
        newHashes.fill(0, unit * HASH_SIZE, (unit + 1) * HASH_SIZE)
        return ChunkManifest(transferId, trackingKey, unitCount, newBitmap, newHashes, partialUnit, partialBytes, atMillis)
    }

    /** This manifest with bytes `0 until bytes` of [unit] durably on disk (S1); `bytes` 0 clears the partial prefix. */
    fun withPartial(
        unit: Int,
        bytes: Int,
        atMillis: Long,
    ): ChunkManifest =
        if (bytes == 0) {
            ChunkManifest(transferId, trackingKey, unitCount, bitmap, hashes, null, 0, atMillis)
        } else {
            ChunkManifest(transferId, trackingKey, unitCount, bitmap, hashes, unit, bytes, atMillis)
        }

    override fun equals(other: Any?): Boolean =
        other is ChunkManifest &&
            transferId == other.transferId &&
            trackingKey == other.trackingKey &&
            unitCount == other.unitCount &&
            bitmap.contentEquals(other.bitmap) &&
            hashes.contentEquals(other.hashes) &&
            partialUnit == other.partialUnit &&
            partialBytes == other.partialBytes &&
            updatedAtMillis == other.updatedAtMillis

    override fun hashCode(): Int = (transferId.hashCode() * 31 + trackingKey) * 31 + bitmap.contentHashCode()

    override fun toString(): String =
        "ChunkManifest(${transferId.toHex()}, key=$trackingKey, $receivedCount/$unitCount received" +
            (partialUnit?.let { ", unit $it partial at $partialBytes" } ?: "") + ", updated=$updatedAtMillis)"

    companion object {
        /** Bytes per unit hash (XXH3-128). */
        const val HASH_SIZE: Int = ChunkHash.SIZE

        /**
         * Most units one key can track: 4 Mi units, 16 TiB at the 4 MiB chunk size, a 64 MiB hash column. Each
         * write-behind rewrites the key's whole row, so very large files cost proportionally more per flush.
         */
        const val MAX_UNITS: Int = 1 shl 22

        /** A manifest with nothing received yet. */
        fun empty(
            transferId: TransferId,
            trackingKey: Int,
            unitCount: Int,
            atMillis: Long,
        ): ChunkManifest {
            require(unitCount in 0..MAX_UNITS) { "unit count $unitCount out of range 0..$MAX_UNITS" }
            return ChunkManifest(
                transferId,
                trackingKey,
                unitCount,
                ByteArray(bitmapSize(unitCount)),
                ByteArray(unitCount * HASH_SIZE),
                null,
                0,
                atMillis,
            )
        }

        internal fun bitmapSize(unitCount: Int): Int = (unitCount + 7) / 8
    }
}
