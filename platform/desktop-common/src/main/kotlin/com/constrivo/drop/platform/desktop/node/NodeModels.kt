package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.SecretStorage
import com.constrivo.drop.core.crypto.handshake.HandshakeClock
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.LocalCalendar
import com.constrivo.drop.core.data.ResumeDataCleaner
import com.constrivo.drop.core.data.SystemZoneCalendar
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.LanDiscovery
import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.discovery.SystemMonotonicClock
import com.constrivo.drop.core.discovery.SystemWallClock
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.ladder.LadderHint
import com.constrivo.drop.core.ladder.LinkLifecycle
import com.constrivo.drop.core.ladder.TransportBadge
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.DeclineReason
import com.constrivo.drop.core.protocol.Preview
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.transfer.TransferClock
import com.constrivo.drop.core.transfer.engine.EngineConfig
import com.constrivo.drop.platform.desktop.AppDirectories
import com.constrivo.drop.platform.desktop.DesktopPlatformServices
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.net.InetAddress
import java.nio.file.Path

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
 * One transfer as the desktop UI shows it (F‑G1, design §4.2, §5.2, §6 Live), from the engine's `TransferProgress`
 * and the ladder's state.
 *
 * @property id the 32 hex digits of the `transfer_id`, known from the start (the sender picks it before dialling).
 * @property peerKey the radar key of the peer (`NearbyDevice.key`) when it is on the radar: the bubble the user dropped
 *   on for a send; for a receive, the peer's trusted bubble. Null otherwise.
 * @property pairingCode the SAS of a first-time pairing on the sender (F‑B3), until the user confirms it or the transfer
 *   ends; null for a trusted peer.
 * @property folder the receiver's destination folder (the per-drop subfolder above 20 files, design §9), once known.
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
    val folder: Path? = null,
)

/**
 * An `Offer` waiting for the user (F‑D1, design §5.1): the incoming card's content.
 *
 * @property id the transfer id (32 hex digits); [DesktopNode.accept] and [DesktopNode.decline] take it.
 * @property senderKey the sender's radar key when it is on the radar, else `d:<deviceId>` (the avatar hash only).
 * @property trusted the verified sender identity is a trusted device (F‑B4).
 * @property sas the six-digit code of a first-time pairing (F‑B3); null for a trusted sender.
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
)

/**
 * A received file that was verified and published (F‑D3: the tray, the completion notification).
 *
 * @property id `<transfer id>:<file index>`, unique per file.
 * @property executable the file installs or runs code (F‑D5: open only after the warning).
 */
data class ReceivedItem(
    val id: String,
    val transferId: String,
    val path: Path,
    val name: String,
    val mimeType: String?,
    val senderName: String?,
    val executable: Boolean,
)

/** Things the UI and the diagnostics log react to. */
sealed interface NodeEvent {
    /** A transfer ended (the completion notification with its "Open" action, design §9). */
    data class TransferFinished(
        val transfer: NodeTransfer,
        /** Where the received files are (receiver, [NodeStage.DONE]); null otherwise. */
        val folder: Path?,
        val receivedFiles: Int,
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

/** Where the browser receive path for a computer without the app stands (F‑H4, architecture §10.3 with N15). */
sealed interface BrowserShareStatus {
    data object Idle : BrowserShareStatus

    data object Starting : BrowserShareStatus

    /** Serving at [url] (the LAN address; a shared LAN may already have a `drop.local`, so no mDNS name is claimed). */
    data class Ready(
        val url: String,
        val fileCount: Int,
    ) : BrowserShareStatus

    data class Failed(
        val message: String,
    ) : BrowserShareStatus
}

/**
 * Timing and limits of a [DesktopNode]; the defaults are the production values, tests shorten them.
 *
 * @property dialTimeoutMillis per candidate endpoint (`EndpointDialer`); a LAN answers in well under a second.
 * @property offerWaitMillis how long an inbound connection may take to send its `Offer` after the handshake.
 * @property finishedRetentionMillis how long a finished transfer stays in [DesktopNode.transfers] (the completion tick,
 *   "Declined", design §4.2).
 * @property progressPersistMillis `transfer.bytes_done` is written at most this often (F‑F1 shows 250 ms; History does
 *   not need it).
 * @property trustSyncWaitMillis after pairing, how long the sender waits for the peer's `TrustShare` (S3).
 * @property maxPendingOffers more offers waiting for an answer are declined `busy`.
 */
data class NodeTuning(
    val dialTimeoutMillis: Long = 3_000,
    val offerWaitMillis: Long = 60_000,
    val finishedRetentionMillis: Long = 10_000,
    val progressPersistMillis: Long = 1_000,
    val trustSyncWaitMillis: Long = 3_000,
    val maxPendingOffers: Int = 4,
    val lingerMillis: Long = EngineConfig.DEFAULT_LINGER_MILLIS,
    val sweepIntervalMillis: Long = ResumeDataCleaner.DEFAULT_INTERVAL_MILLIS,
    val lifecycle: LinkLifecycle = LinkLifecycle(),
) {
    init {
        require(dialTimeoutMillis > 0 && offerWaitMillis > 0 && trustSyncWaitMillis > 0) { "timeouts must be positive" }
        require(finishedRetentionMillis >= 0 && progressPersistMillis > 0 && sweepIntervalMillis > 0) { "intervals out of range" }
        require(maxPendingOffers >= 1) { "at least one offer must be able to wait" }
    }
}

/**
 * Everything a [DesktopNode] is built from (the composition root's inputs).
 *
 * @property directories the app directories: partial files, the default Received folder and the database file.
 * @property secrets where the identity, `k_adv` and the database key live ([com.constrivo.drop.platform.desktop.FileSecretStorage]
 *   wrapped by the OS keychain).
 * @property lan mDNS ([com.constrivo.drop.platform.desktop.lan.JmdnsLanDiscovery], or the in-memory network in tests).
 * @property lanAddress the LAN interface address ([com.constrivo.drop.platform.desktop.lan.LanInterfaces.select]); the
 *   control listener, the LAN links and the browser receive server bind it, never the wildcard.
 * @property controlPort the control listener's port; 0 picks a free one (announced in the mDNS record).
 * @property database an open database, or null to open [AppDirectories.database]; a given one is not closed by the node.
 */
class DesktopNodeConfig(
    val directories: AppDirectories,
    val secrets: SecretStorage,
    val lan: LanDiscovery,
    val lanAddress: InetAddress,
    val defaultNickname: String,
    val platform: DevicePlatform = DevicePlatform.LAPTOP,
    val services: DesktopPlatformServices = DesktopPlatformServices.portable(),
    val controlPort: Int = 0,
    val database: DropData? = null,
    val crypto: CryptoProvider = JcaCryptoProvider(),
    val wallClock: WallClock = SystemWallClock,
    val monotonicClock: MonotonicClock = SystemMonotonicClock,
    val handshakeClock: HandshakeClock = HandshakeClock.SYSTEM,
    val transferClock: TransferClock = TransferClock.SYSTEM,
    val calendar: LocalCalendar = SystemZoneCalendar,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val tuning: NodeTuning = NodeTuning(),
) {
    init {
        require(!lanAddress.isAnyLocalAddress) { "bind to the LAN interface address, never the wildcard" }
        require(controlPort in 0..MAX_PORT) { "control port out of range" }
        require(platform.isKnown && platform != DevicePlatform.BROWSER_PROXY) { "a desktop is a laptop or a desktop" }
    }

    private companion object {
        const val MAX_PORT = 65535
    }
}
