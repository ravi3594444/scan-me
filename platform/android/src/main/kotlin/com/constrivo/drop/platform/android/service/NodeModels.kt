package com.constrivo.drop.platform.android.service

import com.constrivo.drop.core.crypto.qr.QrFailure
import com.constrivo.drop.core.data.ResumeDataCleaner
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.ladder.LadderHint
import com.constrivo.drop.core.ladder.LinkLifecycle
import com.constrivo.drop.core.ladder.TransportBadge
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.DeclineReason
import com.constrivo.drop.core.protocol.Preview
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.transfer.engine.EngineConfig

/** Whether this device sends or receives a transfer. */
enum class NodeDirection { SEND, RECEIVE }

/**
 * Where a transfer stands, in the words the UI uses (design §4.2, §5.1; the shared UI's `TransferStage` mirrors it one
 * to one). [DECLINED] and [NO_ANSWER] are the two ends the engine folds into `Cancelled` that the sender's bubble
 * names ("Declined", "No answer").
 */
enum class NodeStage(
    val isFinal: Boolean = false,
) {
    /** Dialling, handshake and `Offer` on the way. */
    CONNECTING,

    /** The `Offer` is out; the receiver's card is up (or, on the receiver, the card waits for the user). */
    AWAITING_ACCEPT,
    TRANSFERRING,

    /** S8 `Reconnecting`: the link dropped, reconnecting for up to 2 min. */
    RECONNECTING,

    /** S8 `Parked`: waiting up to 24 h for the peer to appear again ("Waiting for {name}"). */
    WAITING_FOR_PEER,
    VERIFYING,
    DONE(isFinal = true),
    FAILED(isFinal = true),
    CANCELLED(isFinal = true),
    DECLINED(isFinal = true),
    NO_ANSWER(isFinal = true),
    ;

    /** Moving bytes, or about to: the service runs `dataSync` for these (S9); parked transfers only wait. */
    val isActive: Boolean get() = !isFinal && this != WAITING_FOR_PEER

    companion object {
        /**
         * The stage of an engine [phase]: [declineReason] and [cancelReason] tell a decline and an unanswered `Offer`
         * apart from a cancel; [everAccepted] tells an unanswered `Offer` (never accepted) from a timeout later on.
         */
        fun of(
            phase: TransferPhase,
            declineReason: DeclineReason? = null,
            cancelReason: CancelReason? = null,
            everAccepted: Boolean = true,
        ): NodeStage =
            when (phase) {
                TransferPhase.OFFERED -> {
                    AWAITING_ACCEPT
                }

                TransferPhase.ACCEPTED, TransferPhase.STREAMING_BLUETOOTH, TransferPhase.STREAMING_WIFI -> {
                    TRANSFERRING
                }

                TransferPhase.VERIFYING -> {
                    VERIFYING
                }

                TransferPhase.RECONNECTING -> {
                    RECONNECTING
                }

                TransferPhase.PARKED -> {
                    WAITING_FOR_PEER
                }

                TransferPhase.DONE -> {
                    DONE
                }

                TransferPhase.FAILED -> {
                    FAILED
                }

                TransferPhase.CANCELLED -> {
                    when {
                        declineReason == DeclineReason.TIMEOUT -> NO_ANSWER
                        cancelReason == CancelReason.TIMEOUT && !everAccepted -> NO_ANSWER
                        declineReason != null -> DECLINED
                        else -> CANCELLED
                    }
                }
            }
    }
}

/**
 * One transfer as the UI and the notifications show it (F-G1, design §4.2, §5.2, §6 Live), from the engine's
 * `TransferProgress` and the ladder's state.
 *
 * @property id the 32 hex digits of the `transfer_id`, known from the start (the sender picks it before dialling).
 * @property peerKey the radar key of the peer (`NearbyDevice.key`) when it is on the radar; null otherwise.
 * @property pairingCode the SAS of a first-time pairing on the sender (F-B3) until the user answers it.
 * @property autoAccepted a receive that started without the incoming card (F-D2: trusted with auto-accept on).
 * @property receivedUris where a receive's verified files were published, by file index, as they land.
 */
data class NodeTransfer(
    val id: String,
    val direction: NodeDirection,
    val peerKey: String?,
    val peerDeviceId: String?,
    val peerName: String,
    val peerPlatform: DevicePlatform,
    val stage: NodeStage,
    val fileCount: Int,
    val bytesTotal: Long,
    val bytesDone: Long,
    val bytesPerSecond: Long? = null,
    val etaMillis: Long? = null,
    val badge: TransportBadge? = null,
    val hint: LadderHint? = null,
    val mimeHistogram: Map<String, Int> = emptyMap(),
    val pairingCode: String? = null,
    val failure: String? = null,
    val autoAccepted: Boolean = false,
    val receivedUris: Map<Int, String> = emptyMap(),
)

/**
 * An `Offer` waiting for the user (F-D1, design §5.1): the incoming card's content, and the heads-up notification's
 * while the app is in the background.
 *
 * @property id the transfer id (32 hex digits); [AndroidNode.accept] and [AndroidNode.decline] take it.
 * @property senderKey the sender's radar key when it is on the radar, else `d:<deviceId>` (the avatar hash only).
 * @property trusted the session proved the pairing both ways (F-B4).
 * @property sas the six-digit code of a pairing (F-B3) whenever the session did not prove one; null for a trusted
 *   sender and for a resumed transfer.
 * @property arrivedAtElapsedMillis on the node's monotonic clock, for the card's 30 s bar.
 */
data class NodeOffer(
    val id: String,
    val senderDeviceId: String,
    val senderKey: String,
    val senderName: String,
    val senderPlatform: DevicePlatform,
    val trusted: Boolean,
    val sas: String?,
    val fileCount: Int,
    val totalBytes: Long,
    val mimeHistogram: Map<String, Int>,
    val previewNames: List<String>,
    val previews: List<Preview>,
    val arrivedAtElapsedMillis: Long,
    val timeoutMillis: Long = ProtocolConstants.OFFER_TIMEOUT_MS,
    val isResume: Boolean = false,
)

/**
 * A received file that was verified and published (F-D3: the tray, the completion notification's "Open").
 *
 * @property id `<transfer id>:<file index>`, unique per file.
 * @property uri the `content:` URI it was published at (a MediaStore row or a document in the picked folder).
 * @property executable the file installs or runs code (F-D5: opened only after the warning).
 */
data class ReceivedItem(
    val id: String,
    val transferId: String,
    val uri: String,
    val name: String,
    val mimeType: String?,
    val senderName: String?,
    val executable: Boolean,
)

/** Things the UI, the notifications and the ring-buffer log react to. */
sealed interface NodeEvent {
    /** A transfer ended (the completion notification with its "Open" action). */
    data class TransferFinished(
        val transfer: NodeTransfer,
        /** The files a receive published, in file-index order (empty for a send or a failed receive). */
        val received: List<ReceivedItem>,
    ) : NodeEvent

    /** Both the pairing and its advertising secrets are stored for this device (S3). */
    data class Paired(
        val deviceId: String,
        val name: String,
    ) : NodeEvent

    /** Something went wrong that the user did not directly cause, for the ring-buffer log (architecture §14). */
    data class Problem(
        val message: String,
        val error: Throwable? = null,
    ) : NodeEvent
}

/** Where the browser receive path for a computer without the app stands (F-D6, architecture §10.3 with N15). */
sealed interface BrowserShareStatus {
    data object Idle : BrowserShareStatus

    data object Starting : BrowserShareStatus

    /**
     * Serving at [url] (`http://drop.local:<port>/t/<token>/`) and [ipUrl] (the same page by IP address) on the network
     * [ssid] with [password] (N15: the real values the phone's group or hotspot uses).
     */
    data class Ready(
        val url: String,
        val ipUrl: String,
        val ssid: String,
        val password: String,
        val fileCount: Int,
    ) : BrowserShareStatus

    data class Failed(
        val message: String,
    ) : BrowserShareStatus
}

/** A scanned code (F-B5, T-12), as [AndroidNode.resolveCode] judges it. */
sealed interface CodeScan {
    /** A verified code: send to [deviceKey] (a radar key, or `q:<device id>` for a device reached through the code). */
    data class Verified(
        val deviceKey: String,
        val deviceId: String,
    ) : CodeScan

    /** The code's five minutes are over (T-12: "Refused with clear message"). */
    data object Expired : CodeScan

    /** Not a drop code, a forged or damaged one, or this device's own. */
    data class Invalid(
        val reason: QrFailure?,
    ) : CodeScan
}

/**
 * This device's code for "Show my code" (F-B5, architecture §6.3): the signed QR [payload], the fallback to type (null:
 * a phone has no address to type), and its validity on the wall clock.
 */
data class NodeCode(
    val payload: String,
    val fallback: String?,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
)

/** What "Clear partial files" removed (F-G5). */
data class PartialsCleared(
    val transfersCleared: Int,
    val failed: Int,
)

/**
 * What keeps the transfer service running (architecture §10.1, S9): counts the service's life cycle reads
 * ([ServiceLifecycle]).
 */
data class NodeActivity(
    /** Transfers moving bytes or about to (connecting, awaiting an answer, transferring, reconnecting, verifying). */
    val active: Int = 0,
    /** Transfers parked until their peer is seen again (S8): they need the radio session to watch for its beacon. */
    val parked: Int = 0,
    /** Offers waiting for the user. */
    val pendingOffers: Int = 0,
    /** The browser receive page is being served. */
    val browserShare: Boolean = false,
    /** The monotonic time the last transfer ended, or null when none ended yet. */
    val lastEndedAtElapsedMillis: Long? = null,
) {
    /** Whether bytes move (or are about to): the `dataSync` part of the foreground service. */
    val transferring: Boolean get() = active > 0 || browserShare
}

/**
 * Timing and limits of an [AndroidNode]; the defaults are the production values, tests shorten them.
 *
 * @property dialTimeoutMillis per candidate (a Bluetooth path tries L2CAP, then the GATT stream, within this).
 * @property offerWaitMillis how long an inbound session that proved a pairing may take to send its `Offer` (or, for the
 *   trust exchange after a pairing, its `TrustShare`) after the handshake.
 * @property untrustedOfferWaitMillis the same for a session that proved no pairing.
 * @property finishedRetentionMillis how long a finished transfer stays in [AndroidNode.transfers].
 * @property pairingRetentionMillis how long a finished send keeps a code its user has not answered yet (F-B3).
 * @property progressPersistMillis `transfer.bytes_done` is written at most this often.
 * @property trustSyncWaitMillis in the trust exchange after a pairing, how long to wait for the peer's `TrustShare`.
 * @property trustSyncRetryMillis the first pause before a trust exchange that could not reach the peer runs again.
 * @property maxPendingOffers more offers waiting for an answer are declined `busy`.
 * @property reconnectWindowMillis S8 `Reconnecting`: a sender whose link dropped offers the transfer again for this long.
 * @property parkedWindowMillis S8 `Parked`: after this long since the interruption the transfer is given up.
 * @property handshakeTimeoutMillis the whole handshake, Finished included (GATT links are slow, §6.1 note).
 * @property maxInboundHandshakes inbound handshakes running at once; more channels are closed at once (§13).
 * @property maxWaitingForOffer authenticated inbound sessions that may wait for their first message at once.
 */
data class NodeTuning(
    val dialTimeoutMillis: Long = 12_000,
    val offerWaitMillis: Long = 60_000,
    val untrustedOfferWaitMillis: Long = 15_000,
    val finishedRetentionMillis: Long = 10_000,
    val pairingRetentionMillis: Long = 10 * 60_000,
    val progressPersistMillis: Long = 1_000,
    val trustSyncWaitMillis: Long = 5_000,
    val trustSyncRetryMillis: Long = 5_000,
    val maxPendingOffers: Int = 4,
    val lingerMillis: Long = EngineConfig.DEFAULT_LINGER_MILLIS,
    val sweepIntervalMillis: Long = ResumeDataCleaner.DEFAULT_INTERVAL_MILLIS,
    val lifecycle: LinkLifecycle = LinkLifecycle(),
    val reconnectWindowMillis: Long = ProtocolConstants.RECONNECT_WINDOW_MS,
    val parkedWindowMillis: Long = ProtocolConstants.PARKED_WINDOW_MS,
    val handshakeTimeoutMillis: Long = 10_000,
    val maxInboundHandshakes: Int = 4,
    val maxWaitingForOffer: Int = 8,
) {
    init {
        require(dialTimeoutMillis > 0 && offerWaitMillis > 0 && untrustedOfferWaitMillis > 0 && trustSyncWaitMillis > 0) {
            "timeouts must be positive"
        }
        require(handshakeTimeoutMillis > 0 && trustSyncRetryMillis > 0) { "timeouts must be positive" }
        require(finishedRetentionMillis >= 0 && pairingRetentionMillis >= 0 && progressPersistMillis > 0 && sweepIntervalMillis > 0) {
            "intervals out of range"
        }
        require(reconnectWindowMillis > 0 && parkedWindowMillis >= reconnectWindowMillis) {
            "the parked window contains the reconnect window"
        }
        require(maxPendingOffers >= 1 && maxInboundHandshakes >= 1 && maxWaitingForOffer >= 1) { "limits must admit one" }
    }

    companion object {
        /** A trust exchange that could not reach the peer is tried this often before it is given up. */
        const val TRUST_SYNC_ATTEMPTS: Int = 6
    }
}
