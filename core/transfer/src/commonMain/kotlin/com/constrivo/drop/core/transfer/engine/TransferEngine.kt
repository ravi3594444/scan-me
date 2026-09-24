package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.Accept
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.DeclineReason
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.Heartbeat
import com.constrivo.drop.core.protocol.Hint
import com.constrivo.drop.core.protocol.LinkIntent
import com.constrivo.drop.core.protocol.LinkOption
import com.constrivo.drop.core.protocol.LinkReady
import com.constrivo.drop.core.protocol.Offer
import com.constrivo.drop.core.protocol.Preview
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.protocol.TransferEvent
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferRole
import com.constrivo.drop.core.protocol.TransferState
import com.constrivo.drop.core.protocol.TrustShare
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.send.OfferBuilder
import com.constrivo.drop.core.transfer.session.SecureSession
import com.constrivo.drop.core.transfer.session.SessionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How to send: the transfer id (random by default; pass the old one to restart an interrupted transfer after an app
 * kill, T-07), previews for the incoming card (N12), the ladder's link options (S5), and where a lost primary connection
 * comes back from (N3).
 */
class SendOptions(
    val transferId: TransferId? = null,
    val previews: List<Preview> = emptyList(),
    val linkOptions: List<LinkOption> = emptyList(),
    val chunkSize: Int = ProtocolConstants.CHUNK_SIZE,
    val bundleSmall: Boolean = true,
    val reconnect: PrimaryLinkSource? = null,
    val listener: TransferLinkListener? = null,
)

/** How to receive: where a lost primary connection comes back from (N3), and the ladder's listener. */
class ReceiveOptions(
    val reconnect: PrimaryLinkSource? = null,
    val listener: TransferLinkListener? = null,
)

/** Counters for tests and the bench (architecture §14). */
data class TransferStats(
    /** Sender: plaintext payload bytes written in chunk frames (bundle indexes included). */
    val payloadBytesSent: Long,
    /** Sender: file bytes among them; equals the transfer's total bytes on a clean run (no byte sent twice). */
    val fileBytesSent: Long,
    /** The session in use: 0 for the first, +1 per reconnect (N3). */
    val sessionEpoch: Int,
    /** Wi-Fi data streams of the current session that are up. */
    val dataConnections: Int,
)

/**
 * The transfer engine (architecture §7): sends and receives transfers over an authenticated [SecureSession], with
 * Bluetooth head start, Wi-Fi data streams added as the ladder brings links up, resume after reconnects, and live
 * progress on a `StateFlow`. One transfer per session in v1 (a second offer is declined `busy` by the app).
 *
 * Everything runs in [scope] on [EngineConfig.io] and the scope's dispatcher, with [EngineConfig.clock]; cancel the
 * scope to drop every transfer at once (which is how the app-kill test simulates a crash: resume data stays).
 */
class TransferEngine(
    val config: EngineConfig,
    private val scope: CoroutineScope,
) {
    /**
     * Starts sending [files] to the peer of [session]: the summary `Offer` goes out at once (N12); the file list, the
     * head start and the data follow the peer's `Accept`.
     *
     * @throws IllegalArgumentException for an empty or oversized file list (see [OfferBuilder.build]).
     */
    fun send(
        session: SecureSession,
        files: List<SourceFile>,
        options: SendOptions = SendOptions(),
    ): Transfer {
        val id = options.transferId ?: TransferId(config.session.crypto.randomBytes(TransferId.SIZE))
        val plan = OfferBuilder.build(id, files, options.previews, options.linkOptions, options.chunkSize, options.bundleSmall)
        val run =
            TransferRun(config, id, TransferRole.SENDER, session, scope, options.reconnect, plan.offer.fileCount, plan.offer.totalBytes)
        run.listener = options.listener
        run.sender = SendSide(run, plan)
        run.start { run.sendControl(plan.offer) }
        return Transfer(run, plan.offer)
    }

    /**
     * Waits on [session] for the peer's `Offer` and returns it as an [IncomingTransfer] for the incoming card; the
     * 30 s answer timer runs from here (§7.8). A `TrustShare` that arrives first is passed to
     * [EngineConfig.onTrustShare] (S3).
     *
     * @throws SessionException if the session closes first.
     * @throws ProtocolException if the peer sends something other than an `Offer`.
     */
    suspend fun receive(
        session: SecureSession,
        options: ReceiveOptions = ReceiveOptions(),
    ): IncomingTransfer {
        val primary = session.primary
        while (true) {
            val header = primary.readHeader() ?: throw SessionException("the session closed before an Offer arrived")
            if (header.type != FrameType.CONTROL) throw ProtocolException("expected an Offer, got a ${header.type} frame")
            when (val message = primary.readControl(header)) {
                is Offer -> return incoming(session, message, options)
                is TrustShare -> runCatching { config.onTrustShare(session.peerIdentityKey, message) }
                null, is Heartbeat, is Hint -> Unit
                else -> throw ProtocolException("expected an Offer, got ${message.type}")
            }
        }
    }

    private suspend fun incoming(
        session: SecureSession,
        offer: Offer,
        options: ReceiveOptions,
    ): IncomingTransfer {
        val record = config.resumeStore.load(offer.transferId)
        val run =
            TransferRun(
                config,
                offer.transferId,
                TransferRole.RECEIVER,
                session,
                scope,
                options.reconnect,
                offer.fileCount,
                offer.totalBytes,
            )
        run.listener = options.listener
        val side = ReceiveSide(run, offer, record)
        run.receiver = side
        run.start {}
        val compatible = offer.version == ProtocolConstants.PROTOCOL_VERSION
        if (!compatible) run.call { run.reduce(TransferEvent.LocalDecline(DeclineReason.INCOMPATIBLE)) }
        return IncomingTransfer(run, side, offer, compatible)
    }
}

/**
 * An `Offer` waiting for the user (the incoming card, design §5.1): [accept] or [decline] within 30 s, or it is declined
 * with `timeout` (§7.8). [isResume] marks an interrupted transfer this device already holds part of (T-07).
 */
class IncomingTransfer internal constructor(
    private val run: TransferRun,
    private val side: ReceiveSide,
    val offer: Offer,
    /** False when the offer's protocol version is not supported; it was declined `incompatible` already. */
    val isCompatible: Boolean,
) {
    val transfer: Transfer = Transfer(run, offer)

    /** The sender's verified nickname (display only, T-13). */
    val peerName: String get() = run.peerName

    /** The sender's verified identity key. */
    val peerIdentityKey: ByteArray get() = run.peerIdentityKey.copyOf()

    val isResume: Boolean get() = side.isResume

    /**
     * Accepts: checks free space against the bytes still missing (declining with `storage` and clearing any partials
     * when they do not fit, §7.8, T-27), then sends `Accept` with [link] (the ladder's intent, S5), the resume state of a
     * resumed transfer, and [streamCount]. Returns the running [transfer].
     */
    suspend fun accept(
        link: LinkIntent? = null,
        streamCount: Int = ProtocolConstants.MAX_STREAMS,
    ): Transfer {
        if (run.machineState.phase != TransferPhase.OFFERED) return transfer
        val accept = side.prepareAccept(link, streamCount)
        if (accept == null) {
            val declined = CompletableDeferred<Unit>()
            run.call {
                run.reduce(TransferEvent.LocalDecline(DeclineReason.STORAGE))
                declined.complete(Unit)
            }
            declined.await()
            side.discardStored()
            return transfer
        }
        val done = CompletableDeferred<Unit>()
        run.call {
            run.reduce(TransferEvent.LocalAccept(accept))
            if (run.machineState.phase != TransferPhase.OFFERED && !run.machineState.phase.isTerminal) side.accepted()
            done.complete(Unit)
        }
        done.await()
        return transfer
    }

    /** Declines with [reason] (§7.2). */
    fun decline(reason: DeclineReason = DeclineReason.USER): Transfer {
        run.call { run.reduce(TransferEvent.LocalDecline(reason)) }
        return transfer
    }
}

/**
 * A running transfer, sender or receiver side: live [progress] for the UI and the service (F-G1, F-F1), the reducer
 * [state] for persistence, and the operations the ladder and the app drive it with.
 */
class Transfer internal constructor(
    private val run: TransferRun,
    val offer: Offer,
) {
    val transferId: TransferId get() = run.transferId
    val role: TransferRole get() = run.role

    /** Progress, published every 250 ms while streaming and on every state change. */
    val progress: StateFlow<TransferProgress> = run.progressFlow.asStateFlow()

    /** The state machine's state (§7.7), for `transfer.status` and friends. */
    val state: StateFlow<TransferState> = run.stateFlow.asStateFlow()

    /** Sender: the receiver's `Accept` once it arrived (its link intent feeds `LadderNegotiation.adopt`, S5). */
    val accept: StateFlow<Accept?> = run.acceptFlow.asStateFlow()

    /** The Wi-Fi stream count the policy wants now (§7.4: 4, 8 above 40 MB/s, 2 when hot). */
    val streamTarget: StateFlow<Int> = run.streamTarget.asStateFlow()

    /** The peer's verified nickname. */
    val peerName: String get() = run.peerName

    /** Link events for the ladder; set before links come up. */
    var linkListener: TransferLinkListener?
        get() = run.listener
        set(value) {
            run.listener = value
        }

    val stats: TransferStats
        get() =
            TransferStats(
                payloadBytesSent = run.sender?.payloadBytesSent ?: 0,
                fileBytesSent = run.sender?.fileBytesSent ?: 0,
                sessionEpoch = run.epoch,
                dataConnections = run.liveConnections().count { !it.isPrimary },
            )

    /** Suspends until the transfer ends and returns its final progress. */
    suspend fun await(): TransferProgress = run.result.await()

    /** Cancels with [reason] (§7.8); the receiver clears its partials. */
    fun cancel(reason: CancelReason = CancelReason.USER) = run.cancel(reason)

    /**
     * Brings up [link]'s first data stream (an authenticated `StreamOpen`, S7) and keeps its stream count at the
     * policy's target. With [use] (a stand-alone engine) the sender moves data to it at once and both sides move their
     * control stream to it (N13); the ladder adapter passes false and calls [useLink] and [moveControl] itself.
     *
     * @throws Exception when the stream cannot be opened (the link is not usable).
     */
    suspend fun connectLink(
        link: DataLink,
        use: Boolean = true,
    ) = run.connectLink(link, use)

    /** Sender: move data to link [generation] (null: back to the Bluetooth stream, §7.5). */
    fun useLink(generation: Int?) = run.useLink(generation)

    /** Move this side's control stream to link [generation] with `ControlMoved` (N13). */
    fun moveControl(generation: Int) = run.moveControl(generation)

    /** Send a `LinkReady` (§7.2) on the control stream (the ladder announces its links). */
    fun sendLinkReady(message: LinkReady) = run.sendLinkReady(message)

    /** While parked: the peer's beacon was seen again, reconnect actively (S8). */
    fun peerRediscovered() = run.peerRediscovered()

    /** A new primary channel to the peer (a manual reconnect): the handshake runs again (N3), then `Resume`. */
    suspend fun reconnect(channel: DataChannel) = run.reconnectWith(channel)

    override fun toString(): String = "Transfer(${transferId.toHex().take(8)}, $role, ${progress.value.phase})"
}
