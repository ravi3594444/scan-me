package com.constrivo.drop.core.transfer.receive

import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.Offer
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.Sha256Digest
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.withLock

/**
 * The receiver's resume state for one tracking key of a transfer (architecture §7.6 with S1, S4, N5), immutable.
 * The same shape as `ChunkManifest` in `core/data` (the app adapts that repository to [ResumeStore]; `core/transfer`
 * does not depend on `core/data`).
 *
 * - [trackingKey] is a chunked file's index, or [ProtocolConstants.BUNDLE_FILE_INDEX] for the bundles, as
 *   `TransferLayout.trackingKeys` defines them; [unitCount] is `TransferLayout.unitCount(key)`.
 * - Bit *u* of [receivedBitmap] (byte `u / 8`, mask `1 << (u % 8)`) says unit *u* is on disk. The engine sets it only
 *   after the unit's bytes were written and `fsync`ed (N5).
 * - [unitHashes] holds at `16 × u` the XXH3-128 of unit *u*'s complete plaintext (zeros while the bit is clear), so the
 *   receiver can re-hash a `.part` after a crash and name the bad unit after a whole-file mismatch (N5).
 * - Bytes `0 until partialBytes` of unit [partialUnit] are on disk too (verified Bluetooth blocks of a chunk, S1).
 */
class UnitManifest(
    val transferId: TransferId,
    val trackingKey: Int,
    val unitCount: Int,
    receivedBitmap: ByteArray,
    unitHashes: ByteArray,
    val partialUnit: Int? = null,
    val partialBytes: Int = 0,
    val updatedAtMillis: Long,
) {
    private val bitmap = receivedBitmap.copyOf()
    private val hashes = unitHashes.copyOf()

    init {
        require(trackingKey >= ProtocolConstants.BUNDLE_FILE_INDEX) { "tracking key $trackingKey out of range" }
        require(unitCount >= 0) { "unit count must be non-negative" }
        require(bitmap.size == bitmapSize(unitCount)) { "bitmap must be ${bitmapSize(unitCount)} bytes for $unitCount units" }
        require(hashes.size == unitCount * ChunkHash.SIZE) { "unit hashes must be ${unitCount * ChunkHash.SIZE} bytes" }
        require((partialUnit == null) == (partialBytes == 0)) { "a partial unit and its byte count go together" }
        if (partialUnit != null) {
            require(partialUnit in 0 until unitCount) { "partial unit $partialUnit out of range" }
            require(partialBytes in 1 until ProtocolConstants.CHUNK_SIZE) { "partial bytes $partialBytes out of range" }
            require(!isReceived(partialUnit)) { "unit $partialUnit is both received and partial" }
        }
    }

    fun isReceived(unit: Int): Boolean {
        require(unit in 0 until unitCount) { "unit $unit out of range 0 until $unitCount" }
        return (bitmap[unit ushr 3].toInt() ushr (unit and 7)) and 1 == 1
    }

    /** The stored hash of [unit], or null while it is not received. */
    fun hashOf(unit: Int): ChunkHash? =
        if (isReceived(unit)) ChunkHash(hashes.copyOfRange(unit * ChunkHash.SIZE, (unit + 1) * ChunkHash.SIZE)) else null

    val receivedCount: Int get() = bitmap.sumOf { (it.toInt() and 0xFF).countOneBits() }

    fun receivedBitmap(): ByteArray = bitmap.copyOf()

    fun unitHashes(): ByteArray = hashes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is UnitManifest && transferId == other.transferId && trackingKey == other.trackingKey && unitCount == other.unitCount &&
            bitmap.contentEquals(other.bitmap) && hashes.contentEquals(other.hashes) && partialUnit == other.partialUnit &&
            partialBytes == other.partialBytes && updatedAtMillis == other.updatedAtMillis

    override fun hashCode(): Int = (transferId.hashCode() * 31 + trackingKey) * 31 + bitmap.contentHashCode()

    override fun toString(): String =
        "UnitManifest(key=$trackingKey, $receivedCount/$unitCount" + (partialUnit?.let { ", unit $it partial at $partialBytes" } ?: "") +
            ")"

    companion object {
        fun bitmapSize(unitCount: Int): Int = (unitCount + 7) / 8

        fun empty(
            transferId: TransferId,
            trackingKey: Int,
            unitCount: Int,
            atMillis: Long,
        ): UnitManifest =
            UnitManifest(
                transferId,
                trackingKey,
                unitCount,
                ByteArray(bitmapSize(unitCount)),
                ByteArray(unitCount * ChunkHash.SIZE),
                updatedAtMillis = atMillis,
            )
    }
}

/** Per-file receive state (`transfer_file.status`, `sha256`, `saved_uri` of architecture §12). */
enum class FileResumeStatus {
    /** Not verified yet. */
    PENDING,

    /** Verified and published at [FileResumeState.savedUri]. */
    DONE,

    /** Failed after three mismatches (§7.8); its partial is gone. */
    FAILED,
}

/**
 * The receiver's state for one file: [status], the whole-file SHA-256 from `FileDone` once it arrived (S2, kept so a
 * restarted receiver can verify a file whose `FileDone` came before the crash), and where it was published.
 */
data class FileResumeState(
    val status: FileResumeStatus,
    val sha256: Sha256Digest? = null,
    val savedUri: String? = null,
)

/** The `Offer` summary a resumed transfer must repeat exactly (§7.6: same files, same unit plan). */
data class ResumeSummary(
    val fileCount: Int,
    val totalBytes: Long,
    val chunkSize: Int,
    val bundleSmall: Boolean,
    val bundleCount: Int,
) {
    companion object {
        fun of(offer: Offer): ResumeSummary =
            ResumeSummary(offer.fileCount, offer.totalBytes, offer.chunkSize, offer.bundleSmall, offer.bundleCount)
    }
}

/** Everything a receiver stored about one unfinished transfer. */
class ResumeRecord(
    val transferId: TransferId,
    val summary: ResumeSummary,
    /** The complete file list from the `FileList` pages. */
    val files: List<FileEntry>,
    /** Manifests by tracking key. */
    val manifests: Map<Int, UnitManifest>,
    /** File states by file index; a missing entry is [FileResumeStatus.PENDING] without a hash. */
    val fileStates: Map<Int, FileResumeState>,
    val updatedAtMillis: Long,
)

/**
 * Durable resume state of the receiver (architecture §7.6; spec change N5): the file list, the unit manifests with their
 * hashes, and the per-file states. The engine writes it in the N5 order: unit bytes written, the `.part` `fsync`ed, then
 * the manifest stored, in write-behind batches at most 100 ms apart. A store may lag (a lost write only makes the
 * receiver ask for a unit it has) but must never persist a manifest ahead of the engine's calls.
 *
 * `core/data`'s `ChunkManifestRepository` and `TransferFileRepository` have the same semantics; the app layer adapts
 * them (a [UnitManifest] maps field for field onto a `ChunkManifest`). Implementations must be safe to call from any
 * coroutine.
 */
interface ResumeStore {
    /** The stored record of [transferId], or null. */
    suspend fun load(transferId: TransferId): ResumeRecord?

    /** Starts a record for [transferId] (replacing any old one) once its file list is known, with empty manifests. */
    suspend fun create(
        transferId: TransferId,
        summary: ResumeSummary,
        files: List<FileEntry>,
        atMillis: Long,
    )

    /** Stores [manifests] (one write-behind batch), replacing the stored ones of their keys; ignored without a record. */
    suspend fun putManifests(manifests: Collection<UnitManifest>)

    /** Stores the state of one file; ignored without a record. */
    suspend fun putFileState(
        transferId: TransferId,
        fileIndex: Int,
        state: FileResumeState,
        atMillis: Long,
    )

    /** Deletes everything stored for [transferId] (completed, cancelled, or its partials were cleared). */
    suspend fun delete(transferId: TransferId)
}

/**
 * A [ResumeStore] in memory, for tests and for a process that cannot persist. It survives the engines that use it,
 * which is how the app-kill test (T-07) simulates a restart. Thread-safe.
 */
class InMemoryResumeStore : ResumeStore {
    private class Entry(
        val summary: ResumeSummary,
        val files: List<FileEntry>,
        val manifests: MutableMap<Int, UnitManifest> = HashMap(),
        val fileStates: MutableMap<Int, FileResumeState> = HashMap(),
        var updatedAtMillis: Long,
    )

    private val lock = TransferLock()
    private val entries = HashMap<TransferId, Entry>()

    /** Manifest batches stored so far (a batch for no known record does not count). */
    var manifestWrites: Int = 0
        private set

    override suspend fun load(transferId: TransferId): ResumeRecord? =
        lock.withLock {
            entries[transferId]?.let {
                ResumeRecord(transferId, it.summary, it.files, HashMap(it.manifests), HashMap(it.fileStates), it.updatedAtMillis)
            }
        }

    override suspend fun create(
        transferId: TransferId,
        summary: ResumeSummary,
        files: List<FileEntry>,
        atMillis: Long,
    ) {
        lock.withLock { entries[transferId] = Entry(summary, files.toList(), updatedAtMillis = atMillis) }
    }

    override suspend fun putManifests(manifests: Collection<UnitManifest>) {
        lock.withLock {
            var stored = false
            for (manifest in manifests) {
                val entry = entries[manifest.transferId] ?: continue
                stored = true
                entry.manifests[manifest.trackingKey] = manifest
                entry.updatedAtMillis = maxOf(entry.updatedAtMillis, manifest.updatedAtMillis)
            }
            if (stored) manifestWrites++
        }
    }

    override suspend fun putFileState(
        transferId: TransferId,
        fileIndex: Int,
        state: FileResumeState,
        atMillis: Long,
    ) {
        lock.withLock {
            val entry = entries[transferId] ?: return@withLock
            entry.fileStates[fileIndex] = state
            entry.updatedAtMillis = maxOf(entry.updatedAtMillis, atMillis)
        }
    }

    override suspend fun delete(transferId: TransferId) {
        lock.withLock { entries.remove(transferId) }
    }

    /** Test access: whether a record exists. */
    fun contains(transferId: TransferId): Boolean = lock.withLock { transferId in entries }
}
