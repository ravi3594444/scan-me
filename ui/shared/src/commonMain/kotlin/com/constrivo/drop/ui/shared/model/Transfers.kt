package com.constrivo.drop.ui.shared.model

import androidx.compose.runtime.Immutable
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.ladder.LadderHint
import com.constrivo.drop.core.ladder.TransportBadge
import com.constrivo.drop.core.protocol.TransferPhase

enum class Direction { SEND, RECEIVE }

/**
 * Where a transfer stands, as the UI words it. The engine's [TransferPhase] maps through [of]; the end reasons the
 * state machine folds into `CANCELLED` (a decline, an unanswered Offer) are separate here because the sender's bubble
 * says "Declined" or "No answer" (design §5.1).
 */
enum class TransferStage(
    val isFinal: Boolean = false,
) {
    /** Handshake and Offer on the way. */
    CONNECTING,

    /** Offer sent; the receiver's card is up (design §5.1). */
    AWAITING_ACCEPT,
    TRANSFERRING,

    /** S8 `Reconnecting`: the link dropped, reconnecting for up to 2 min. */
    RECONNECTING,

    /** S8 `Parked`: waiting up to 24 h for the peer's beacon ("Waiting for {name}"). */
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
         * The stage of an engine [phase]. [awaitingAccept] distinguishes an `OFFERED` transfer whose Offer is out (the
         * card is showing) from one still connecting.
         */
        fun of(
            phase: TransferPhase,
            awaitingAccept: Boolean = true,
        ): TransferStage =
            when (phase) {
                TransferPhase.OFFERED -> if (awaitingAccept) AWAITING_ACCEPT else CONNECTING
                TransferPhase.ACCEPTED, TransferPhase.STREAMING_BLUETOOTH, TransferPhase.STREAMING_WIFI -> TRANSFERRING
                TransferPhase.VERIFYING -> VERIFYING
                TransferPhase.RECONNECTING -> RECONNECTING
                TransferPhase.PARKED -> WAITING_FOR_PEER
                TransferPhase.DONE -> DONE
                TransferPhase.CANCELLED -> CANCELLED
                TransferPhase.FAILED -> FAILED
            }
    }
}

/**
 * One transfer as the engine reports it to the UI (F‑G1, design §4.2, §5.2, §6 Live). The app layer builds these from
 * the engine's `StateFlow`s; [com.constrivo.drop.ui.shared.presenter.RadarPresenter] and the Live tab map them.
 *
 * @property peerKey the radar key ([com.constrivo.drop.core.discovery.NearbyDevice.key]) of the peer while it is on the
 *   radar, so its bubble shows the progress ring; null when the peer is not on the radar (a browser, a parked peer).
 * @property bytesPerSecond smoothed payload speed (F‑F1, decimal MB in the UI, S6); null before the first sample.
 * @property etaMillis time left at that speed; null when unknown.
 * @property badge the transport badge of the link carrying data (F‑F2); null before a link exists.
 * @property hint the hint to show, already chosen by `HintRules` (F‑F3); null when none applies.
 * @property pairingCode the SAS of a first-time pairing (F‑B3) until the user confirms it; shown as soon as the
 *   handshake result exists (WP1–WP3 carry-forward).
 * @property thumbnails previews for the drop animation (up to 8 are used, design §4.2).
 * @property canAddFiles whether "Add files" is offered on the Live tab (F‑C5).
 */
@Immutable
data class TransferSnapshot(
    val id: String,
    val peerKey: String?,
    val peerName: String,
    val peerPlatform: DevicePlatform,
    val direction: Direction,
    val stage: TransferStage,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long? = null,
    val etaMillis: Long? = null,
    val badge: TransportBadge? = null,
    val hint: LadderHint? = null,
    val summary: ItemSummary,
    val thumbnails: List<FileThumb> = emptyList(),
    val paused: Boolean = false,
    val resumed: Boolean = false,
    val pairingCode: String? = null,
    val canAddFiles: Boolean = false,
) {
    init {
        require(bytesTotal >= 0 && bytesDone >= 0) { "byte counts must not be negative" }
        require(bytesPerSecond == null || bytesPerSecond >= 0) { "speed must not be negative" }
        require(etaMillis == null || etaMillis >= 0) { "eta must not be negative" }
    }

    /** Progress in `[0, 1]`; 0 for an empty transfer until it is done. */
    val fraction: Float
        get() =
            when {
                stage == TransferStage.DONE -> 1f
                bytesTotal <= 0L -> 0f
                else -> (bytesDone.toDouble() / bytesTotal.toDouble()).coerceIn(0.0, 1.0).toFloat()
            }
}

/** Accessibility: progress is announced in 25% steps (design §11), so the live region changes only at 0, 25, 50, 75, 100. */
object ProgressAnnouncements {
    const val STEP_PERCENT: Int = 25

    /** The last 25% step reached by [fraction] (`[0, 1]`, clamped). */
    fun step(fraction: Float): Int {
        val clamped = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        return (clamped * 100f / STEP_PERCENT + 1e-4f).toInt() * STEP_PERCENT
    }
}
