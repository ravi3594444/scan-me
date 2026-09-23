package com.constrivo.drop.core.data

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferId

/**
 * A transfer to create (architecture §12 `transfer`): the sender on its `Offer`, the receiver when one arrives.
 *
 * @property mimeHistogram the `Offer`'s MIME histogram (N12), so History can summarise a declined offer too.
 */
data class NewTransfer(
    val id: TransferId,
    val peerDeviceId: String,
    val direction: TransferDirection,
    val bytesTotal: Long,
    val fileCount: Int,
    val mimeHistogram: Map<String, Int> = emptyMap(),
    val status: TransferStatus = TransferStatus.OFFERED,
) {
    init {
        DeviceIds.requireValid(peerDeviceId)
        require(bytesTotal >= 0) { "bytes total must be non-negative" }
        require(fileCount in 1..ProtocolConstants.MAX_FILES_PER_TRANSFER) { "file count $fileCount out of range" }
        require(!status.isTerminal) { "a transfer starts in a non-terminal status; finish it with TransferRepository.finish" }
        require(mimeHistogram.values.all { it >= 1 } && mimeHistogram.values.sumOf { it.toLong() } <= fileCount) {
            "histogram counts must be positive and sum to at most the file count"
        }
        require(mimeHistogram.keys.all { it.isNotEmpty() }) { "histogram MIME types must not be empty" }
    }
}

/**
 * How a transfer ended, for [TransferRepository.finish].
 *
 * @property status [TransferStatus.DONE], [TransferStatus.FAILED] or [TransferStatus.CANCELLED].
 * @property bytesDone bytes of the files that arrived and verified (receiver) or were acknowledged (sender); Stats
 *   (F-G4) sums it for done transfers. Values above the transfer's total are stored as the total.
 * @property transport the link the data last flowed over; null keeps the one recorded with [TransferRepository.recordLink].
 * @property band the Wi-Fi band of that link; null keeps the recorded one.
 * @property avgSpeedBytesPerSecond payload bytes over the time data was moving (F-F1), null if unknown.
 * @property hints hint codes that fired; added to those already recorded.
 * @property failedFiles files that failed verification (`Complete.status = partial` when some did).
 */
data class TransferOutcome(
    val status: TransferStatus,
    val bytesDone: Long,
    val transport: LinkKind? = null,
    val band: WifiBand? = null,
    val avgSpeedBytesPerSecond: Long? = null,
    val hints: List<HintCode> = emptyList(),
    val failedFiles: Int = 0,
) {
    init {
        require(status.isTerminal) { "an outcome is done, failed or cancelled, not $status" }
        require(bytesDone >= 0) { "bytes done must be non-negative" }
        require(avgSpeedBytesPerSecond == null || avgSpeedBytesPerSecond >= 0) { "average speed must be non-negative" }
        require(failedFiles >= 0) { "failed files must be non-negative" }
    }
}

/**
 * One transfer as History, the Live tab and Stats see it (F-G1, F-G2, F-G4; architecture §12).
 *
 * @property peerName the peer's display name now (its custom name, else its nickname), not at transfer time.
 * @property transport the link data last flowed over (the badge, F-F2); null before any data moved.
 * @property updatedAtMillis the last activity; the 24 h clean-up measures from it (§7.6).
 * @property hints hint codes in the order they first fired (F-F3).
 */
data class TransferRecord(
    val id: TransferId,
    val peerDeviceId: String,
    val peerName: String,
    val peerPlatform: DevicePlatform,
    val direction: TransferDirection,
    val status: TransferStatus,
    val transport: LinkKind?,
    val band: WifiBand?,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
    val updatedAtMillis: Long,
    val bytesTotal: Long,
    val bytesDone: Long,
    val avgSpeedBytesPerSecond: Long?,
    val hints: List<HintCode>,
    val fileCount: Int,
    val mimeHistogram: Map<String, Int>,
    val failedFiles: Int,
) {
    /** Wall time from start to finish (History "duration"); null while the transfer runs. */
    val durationMillis: Long? get() = finishedAtMillis?.let { (it - startedAtMillis).coerceAtLeast(0) }

    /** Not yet done, failed or cancelled: shown on the Live tab. */
    val isActive: Boolean get() = !status.isTerminal

    /** Done, but some files failed verification (`Complete.status = partial`). */
    val isPartial: Boolean get() = status == TransferStatus.DONE && failedFiles > 0

    /** The keyset position of this record in History. */
    val cursor: HistoryCursor get() = HistoryCursor(startedAtMillis, id)
}

/**
 * A position in History (F-G2): the start time and id of the last record of a page. History is ordered by start
 * time, newest first, then by id descending, so a cursor names exactly one place even when start times tie.
 */
data class HistoryCursor(
    val startedAtMillis: Long,
    val id: TransferId,
)

/** One page of History, newest first; [next] is null on the last page. */
data class HistoryPage(
    val transfers: List<TransferRecord>,
    val next: HistoryCursor?,
)
