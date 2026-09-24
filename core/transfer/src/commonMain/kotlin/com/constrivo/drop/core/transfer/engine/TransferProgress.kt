package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.CompleteStatus
import com.constrivo.drop.core.protocol.DeclineReason
import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferRole
import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.receive.FileNameSanitizer
import com.constrivo.drop.core.transfer.receive.FileTypes
import com.constrivo.drop.core.transfer.withLock

/** Where one file of a transfer is (the per-file rows of the sending card and the tray, design §5–6). */
enum class FileStatus {
    PENDING,
    IN_PROGRESS,

    /** Receiver: all bytes are on disk and the SHA-256 is being checked (S2). */
    VERIFYING,
    DONE,

    /** Failed after three mismatches (§7.8), or cancelled with the transfer. */
    FAILED,
}

/**
 * One file's progress. On the receiver [name] is the sanitised name (F-D5) until publishing, then [savedName] is the
 * final one; [isExecutable] / [isAndroidPackage] drive the "can run code" warning (F-D5).
 */
data class FileProgress(
    val index: Int,
    val name: String,
    val size: Long,
    val mimeType: String?,
    val bytesDone: Long,
    val status: FileStatus,
    val savedUri: String? = null,
    val savedName: String? = null,
    val isExecutable: Boolean = false,
    val isAndroidPackage: Boolean = false,
)

/**
 * The transfer as the UI and the foreground service see it (F-G1, F-F1), published on a `StateFlow` at most every
 * 250 ms and on every state change.
 *
 * @property phase the state machine's phase (§7.7, S8): `Reconnecting` and `Parked` show "Waiting for {name}".
 * @property bytesDone acked bytes (sender) or verified bytes (receiver), file data only.
 * @property bytesPerSecond the smoothed speed (EWMA α 0.3 over 250 ms samples); the UI shows decimal MB/s (S6).
 * @property etaMillis remaining bytes ÷ [bytesPerSecond]; null while unknown.
 * @property linkKind the link data flows over (the badge, F-F2); `bluetooth` during the head start.
 * @property streams Wi-Fi data streams in use (4, 8, or 2 under thermal load; §7.4).
 * @property hints speed hints that hold now, this device's and the peer's (`bundling`, `thermal`, `sdcard`, …).
 * @property files per-file rows, in file-index order; empty until the file list is known.
 * @property waitingForPeer parked: waiting for the peer's beacon (report it with `Transfer.peerRediscovered`).
 */
data class TransferProgress(
    val transferId: TransferId,
    val role: TransferRole,
    val phase: TransferPhase,
    val peerName: String,
    val fileCount: Int,
    val bytesTotal: Long,
    val bytesDone: Long,
    val bytesPerSecond: Double = 0.0,
    val etaMillis: Long? = null,
    val linkKind: LinkKind? = null,
    val freqMhz: Int = 0,
    val streams: Int = 0,
    val hints: Set<HintCode> = emptySet(),
    val files: List<FileProgress> = emptyList(),
    val cancelReason: CancelReason? = null,
    val declineReason: DeclineReason? = null,
    val completeStatus: CompleteStatus? = null,
    val failure: String? = null,
    val waitingForPeer: Boolean = false,
) {
    val isTerminal: Boolean get() = phase.isTerminal

    /** Fraction done in 0..1 (1 for an empty transfer). */
    val fraction: Double get() = if (bytesTotal <= 0) 1.0 else (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0)
}

/**
 * The per-file rows behind [TransferProgress.files]: mutable arrays updated by the engine, copied into an immutable
 * [snapshot] only when something changed, so a 100,000-file transfer does not allocate a row object per file per tick.
 * Thread-safe.
 */
internal class FileProgressTable(
    files: List<FileEntry>,
    receiver: Boolean,
) {
    private val lock = TransferLock()
    private val names: List<String> = files.map { if (receiver) FileNameSanitizer.sanitize(it.name) else it.name }
    private val sizes: LongArray = LongArray(files.size) { files[it].size }
    private val mimes: List<String?> = files.map { it.mime }
    private val executable: BooleanArray = BooleanArray(files.size) { receiver && FileTypes.isExecutable(names[it], mimes[it]) }
    private val androidPackage: BooleanArray = BooleanArray(files.size) { receiver && FileTypes.isAndroidPackage(names[it], mimes[it]) }
    private val done = LongArray(files.size)
    private val status = Array(files.size) { FileStatus.PENDING }
    private val savedUris = arrayOfNulls<String>(files.size)
    private val savedNames = arrayOfNulls<String>(files.size)
    private var dirty = true
    private var cached: List<FileProgress> = emptyList()

    val size: Int get() = sizes.size

    fun nameOf(index: Int): String = names[index]

    fun addBytes(
        index: Int,
        bytes: Long,
    ) = lock.withLock {
        done[index] = (done[index] + bytes).coerceIn(0, sizes[index])
        if (status[index] == FileStatus.PENDING && done[index] > 0) status[index] = FileStatus.IN_PROGRESS
        dirty = true
    }

    fun setBytes(
        index: Int,
        bytes: Long,
    ) = lock.withLock {
        done[index] = bytes.coerceIn(0, sizes[index])
        dirty = true
    }

    fun bytesOf(index: Int): Long = lock.withLock { done[index] }

    fun statusOf(index: Int): FileStatus = lock.withLock { status[index] }

    fun setStatus(
        index: Int,
        value: FileStatus,
    ) = lock.withLock {
        status[index] = value
        if (value == FileStatus.DONE) done[index] = sizes[index]
        dirty = true
    }

    fun setSaved(
        index: Int,
        uri: String,
        name: String,
    ) = lock.withLock {
        savedUris[index] = uri
        savedNames[index] = name
        dirty = true
    }

    /** Marks every file that did not finish as failed (the transfer ended). */
    fun failUnfinished() =
        lock.withLock {
            for (i in status.indices) if (status[i] != FileStatus.DONE) status[i] = FileStatus.FAILED
            dirty = true
        }

    fun snapshot(): List<FileProgress> =
        lock.withLock {
            if (dirty) {
                cached =
                    Rows(
                        names,
                        sizes,
                        mimes,
                        done.copyOf(),
                        status.copyOf(),
                        savedUris.copyOf(),
                        savedNames.copyOf(),
                        executable,
                        androidPackage,
                    )
                dirty = false
            }
            cached
        }

    private class Rows(
        private val names: List<String>,
        private val sizes: LongArray,
        private val mimes: List<String?>,
        private val done: LongArray,
        private val status: Array<FileStatus>,
        private val uris: Array<String?>,
        private val savedNames: Array<String?>,
        private val executable: BooleanArray,
        private val androidPackage: BooleanArray,
    ) : AbstractList<FileProgress>() {
        override val size: Int get() = sizes.size

        override fun get(index: Int): FileProgress =
            FileProgress(
                index,
                names[index],
                sizes[index],
                mimes[index],
                done[index],
                status[index],
                uris[index],
                savedNames[index],
                executable[index],
                androidPackage[index],
            )
    }
}
