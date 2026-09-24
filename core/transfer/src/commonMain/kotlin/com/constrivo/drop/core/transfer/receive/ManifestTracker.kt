package com.constrivo.drop.core.transfer.receive

import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferLayout
import com.constrivo.drop.core.protocol.TransferUnit

/**
 * The receiver's durable-unit bookkeeping (architecture §7.6, N5): per tracking key, which units are on disk and
 * synced, their XXH3-128 hashes, and the synced prefix of one unit received as Bluetooth blocks (S1). [dirtyManifests]
 * yields the keys changed since the last flush as [UnitManifest]s for the write-behind batch. Resume-record math
 * (bitmap bits, hash offsets) matches `ChunkManifest` in `core/data`. Not thread-safe; the receiver guards it.
 */
internal class ManifestTracker(
    private val transferId: TransferId,
    private val layout: TransferLayout,
) {
    private class Key(
        val key: Int,
        val count: Int,
    ) {
        val bits = ByteArray(UnitManifest.bitmapSize(count))
        val hashes = ByteArray(count * ChunkHash.SIZE)
        var received = 0
        var partialUnit: Int? = null
        var partialBytes = 0
        var dirty = false
    }

    private val keys: Map<Int, Key> = layout.trackingKeys.associateWith { Key(it, layout.unitCount(it)) }

    /** Units whose bit is set, over all keys. */
    val receivedUnits: Long get() = keys.values.sumOf { it.received.toLong() }

    fun isReceived(unit: TransferUnit): Boolean {
        val key = keys[unit.fileIndex] ?: return false
        if (unit.chunkIndex !in 0 until key.count) return false
        return (key.bits[unit.chunkIndex ushr 3].toInt() ushr (unit.chunkIndex and 7)) and 1 == 1
    }

    fun hashOf(unit: TransferUnit): ChunkHash? {
        if (!isReceived(unit)) return null
        val key = keys.getValue(unit.fileIndex)
        val at = unit.chunkIndex * ChunkHash.SIZE
        return ChunkHash(key.hashes.copyOfRange(at, at + ChunkHash.SIZE))
    }

    /** Bytes `0 until n` of [unit] are synced (Bluetooth blocks); 0 when none or the unit is received. */
    fun partialPrefix(unit: TransferUnit): Int {
        val key = keys[unit.fileIndex] ?: return 0
        return if (key.partialUnit == unit.chunkIndex) key.partialBytes else 0
    }

    /** Sets [unit]'s bit with [hash]; call only after its bytes are written and synced (N5). */
    fun markReceived(
        unit: TransferUnit,
        hash: ChunkHash,
    ) {
        val key = keyOf(unit)
        val c = unit.chunkIndex
        if (!isReceived(unit)) {
            key.bits[c ushr 3] = (key.bits[c ushr 3].toInt() or (1 shl (c and 7))).toByte()
            key.received++
        }
        hash.toByteArray().copyInto(key.hashes, c * ChunkHash.SIZE)
        if (key.partialUnit == c) {
            key.partialUnit = null
            key.partialBytes = 0
        }
        key.dirty = true
    }

    /** Clears [unit]'s bit (its bytes failed a re-hash or a whole-file check named it). */
    fun markMissing(unit: TransferUnit) {
        val key = keyOf(unit)
        val c = unit.chunkIndex
        if (isReceived(unit)) {
            key.bits[c ushr 3] = (key.bits[c ushr 3].toInt() and (1 shl (c and 7)).inv()).toByte()
            key.received--
        }
        key.hashes.fill(0, c * ChunkHash.SIZE, (c + 1) * ChunkHash.SIZE)
        if (key.partialUnit == c) {
            key.partialUnit = null
            key.partialBytes = 0
        }
        key.dirty = true
    }

    /** Bytes `0 until bytes` of the missing [unit] are synced (S1); 0 clears the prefix. At most one per key. */
    fun setPartial(
        unit: TransferUnit,
        bytes: Int,
    ) {
        val key = keyOf(unit)
        if (isReceived(unit)) return
        require(bytes in 0 until ProtocolConstants.CHUNK_SIZE) { "partial bytes $bytes out of range" }
        if (bytes == 0) {
            if (key.partialUnit == unit.chunkIndex) {
                key.partialUnit = null
                key.partialBytes = 0
                key.dirty = true
            }
            return
        }
        key.partialUnit = unit.chunkIndex
        key.partialBytes = bytes
        key.dirty = true
    }

    /**
     * Loads stored [manifests]; a manifest whose unit count does not match this layout is ignored (its key starts
     * empty), since it cannot describe these units.
     */
    fun load(manifests: Map<Int, UnitManifest>) {
        for ((trackingKey, manifest) in manifests) {
            val key = keys[trackingKey] ?: continue
            if (manifest.unitCount != key.count || manifest.transferId != transferId) continue
            manifest.receivedBitmap().copyInto(key.bits)
            manifest.unitHashes().copyInto(key.hashes)
            key.received = manifest.receivedCount
            key.partialUnit = manifest.partialUnit
            key.partialBytes = manifest.partialBytes
            key.dirty = false
        }
    }

    /** The keys changed since the last call, as manifests stamped [atMillis]; clears their dirty flags. */
    fun dirtyManifests(atMillis: Long): List<UnitManifest> {
        val out = ArrayList<UnitManifest>()
        for (key in keys.values) {
            if (!key.dirty) continue
            key.dirty = false
            out += UnitManifest(transferId, key.key, key.count, key.bits, key.hashes, key.partialUnit, key.partialBytes, atMillis)
        }
        return out
    }

    private fun keyOf(unit: TransferUnit): Key {
        val key = keys[unit.fileIndex] ?: throw IllegalArgumentException("$unit has no tracking key")
        require(unit.chunkIndex in 0 until key.count) { "$unit out of range" }
        return key
    }
}
