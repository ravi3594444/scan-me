package com.constrivo.drop.platform.android.notification

import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.android.service.BrowserShareStatus
import com.constrivo.drop.platform.android.service.NodeDirection
import com.constrivo.drop.platform.android.service.NodeOffer
import com.constrivo.drop.platform.android.service.NodeStage
import com.constrivo.drop.platform.android.service.NodeTransfer
import com.constrivo.drop.platform.android.service.ReceivedItem

/** What the ongoing (foreground-service) notification shows. */
enum class OngoingKind {
    /** The radio session: visible to nearby devices, nothing transfers (the session channel). */
    SESSION,

    /** One or more transfers (the progress channel). */
    PROGRESS,

    /** The browser receive page (F-D6; the progress channel). */
    BROWSER,
}

/**
 * The ongoing notification's content.
 *
 * @property percent 0..100, or null for an indeterminate bar ([showProgress]) or none.
 * @property cancelTransferId the transfer the Cancel action cancels: set when exactly one transfer runs.
 * @property stageKey what changes only with a stage (the kind, and each transfer's id and stage): a new key is
 *   posted at once, a progress-only change at most every [ProgressThrottle.INTERVAL_MILLIS].
 */
data class OngoingContent(
    val kind: OngoingKind,
    val title: String,
    val text: String,
    val percent: Int?,
    val showProgress: Boolean,
    val cancelTransferId: String?,
    val stageKey: List<Any>,
)

/**
 * The heads-up card of an incoming offer (design §5.1) while the app is in the background: Accept and Decline
 * actions, the pairing code when there is one, and a deadline at the end of the 30 s after which it withdraws.
 */
data class OfferContent(
    val offerId: String,
    val title: String,
    val text: String,
    val code: String?,
    val deadlineElapsedMillis: Long,
)

/**
 * A completed transfer's notification (the completion channel), with "Open" for a receive: [openItem] is the one
 * received file it opens, or null to open the app (several files, or an installer, which opens only after the warning
 * of F-D5).
 */
data class CompletionContent(
    val transferId: String,
    val title: String,
    val text: String?,
    val success: Boolean,
    val offersOpen: Boolean,
    val openItem: ReceivedItem?,
)

/**
 * The pure part of the transfer notifications (design §5, F-D2, §11): what each one says, from the node's state. The
 * Android side ([TransferNotifications]) only builds and posts them.
 */
object NotificationModel {
    /**
     * The ongoing notification for [transfers] (those still running; finished ones are left out), the browser page
     * [browser] and the [visibility] in force: the single transfer with its stage and percentage, several at once
     * with their combined percentage, the browser page, or the radio session when nothing runs.
     */
    fun ongoing(
        transfers: List<NodeTransfer>,
        browser: BrowserShareStatus,
        visibility: Visibility,
        texts: NotificationTexts,
    ): OngoingContent {
        val running = transfers.filter { shows(it) }
        if (running.size == 1) return single(running[0], texts)
        if (running.size > 1) return several(running, texts)
        if (browser is BrowserShareStatus.Starting || browser is BrowserShareStatus.Ready) {
            val ready = browser as? BrowserShareStatus.Ready
            return OngoingContent(
                kind = OngoingKind.BROWSER,
                title = texts.browserTitle(),
                text = texts.browserText(ready?.ssid, ready?.url),
                percent = null,
                showProgress = ready == null,
                cancelTransferId = null,
                stageKey = listOf(OngoingKind.BROWSER, ready != null),
            )
        }
        return OngoingContent(
            kind = OngoingKind.SESSION,
            title = texts.sessionTitle(),
            text = texts.sessionText(visibility),
            percent = null,
            showProgress = false,
            cancelTransferId = null,
            stageKey = listOf(OngoingKind.SESSION, visibility),
        )
    }

    /**
     * Whether [transfer] belongs in the ongoing notification: not finished, and not an offer still on the card (that
     * is the heads-up's; an auto-accepted one shows at once, F-D2).
     */
    fun shows(transfer: NodeTransfer): Boolean =
        !transfer.stage.isFinal &&
            !(transfer.direction == NodeDirection.RECEIVE && transfer.stage == NodeStage.AWAITING_ACCEPT && !transfer.autoAccepted)

    /** The heads-up content of [offer]. */
    fun offer(
        offer: NodeOffer,
        texts: NotificationTexts,
    ): OfferContent =
        OfferContent(
            offerId = offer.id,
            title = texts.offerTitle(offer.senderName),
            text = texts.offerSummary(offer.fileCount, offer.totalBytes, offer.mimeHistogram),
            code = offer.sas?.let { texts.offerCode(it) },
            deadlineElapsedMillis = offer.arrivedAtElapsedMillis + offer.timeoutMillis,
        )

    /**
     * The completion notification of [transfer], which ended with [received] published files; null while it runs, and
     * for a receive that was declined or never answered (the user saw its card, or its heads-up timed out).
     */
    fun completion(
        transfer: NodeTransfer,
        received: List<ReceivedItem>,
        texts: NotificationTexts,
    ): CompletionContent? {
        val peer = transfer.peerName
        val receive = transfer.direction == NodeDirection.RECEIVE
        return when (transfer.stage) {
            NodeStage.DONE -> {
                val count = if (receive && received.isNotEmpty()) received.size else transfer.fileCount
                val single = received.singleOrNull()?.takeIf { !it.executable }
                CompletionContent(
                    transferId = transfer.id,
                    title = if (receive) texts.received(peer, count) else texts.sent(peer, count),
                    text = single?.name ?: received.firstOrNull()?.name,
                    success = true,
                    offersOpen = receive,
                    openItem = single,
                )
            }

            NodeStage.FAILED -> {
                CompletionContent(
                    transfer.id,
                    texts.failed(peer, transfer.failure),
                    null,
                    success = false,
                    offersOpen = false,
                    openItem = null,
                )
            }

            NodeStage.CANCELLED -> {
                CompletionContent(
                    transfer.id,
                    texts.cancelled(peer),
                    transfer.failure,
                    success = false,
                    offersOpen = false,
                    openItem = null,
                )
            }

            NodeStage.DECLINED -> {
                if (receive) {
                    null
                } else {
                    CompletionContent(
                        transfer.id,
                        texts.declined(peer),
                        null,
                        success = false,
                        offersOpen = false,
                        openItem = null,
                    )
                }
            }

            NodeStage.NO_ANSWER -> {
                if (receive) {
                    null
                } else {
                    CompletionContent(
                        transfer.id,
                        texts.noAnswer(peer),
                        null,
                        success = false,
                        offersOpen = false,
                        openItem = null,
                    )
                }
            }

            else -> {
                null
            }
        }
    }

    /** [done] of [total] bytes as a whole percentage in 0..100, or null when the total is unknown. */
    fun percentOf(
        done: Long,
        total: Long,
    ): Int? {
        if (total <= 0) return null
        val clamped = done.coerceIn(0, total)
        // Exact integer arithmetic without overflow for totals up to Long.MAX_VALUE / 100.
        return if (total <= Long.MAX_VALUE / 100) (clamped * 100 / total).toInt() else (clamped / (total / 100)).toInt().coerceAtMost(100)
    }

    private fun single(
        t: NodeTransfer,
        texts: NotificationTexts,
    ): OngoingContent {
        val title = if (t.direction == NodeDirection.SEND) texts.sending(t.peerName) else texts.receiving(t.peerName)
        val percent =
            if (t.stage == NodeStage.TRANSFERRING ||
                t.stage == NodeStage.VERIFYING
            ) {
                percentOf(t.bytesDone, t.bytesTotal)
            } else {
                null
            }
        val text =
            when (t.stage) {
                NodeStage.CONNECTING -> {
                    texts.connecting()
                }

                NodeStage.AWAITING_ACCEPT -> {
                    if (t.direction ==
                        NodeDirection.SEND
                    ) {
                        texts.waitingForAnswer(t.peerName)
                    } else {
                        texts.connecting()
                    }
                }

                NodeStage.TRANSFERRING -> {
                    texts.progress(percent ?: 0, t.bytesPerSecond, t.etaMillis)
                }

                NodeStage.RECONNECTING -> {
                    texts.reconnecting()
                }

                NodeStage.WAITING_FOR_PEER -> {
                    texts.waitingForPeer(t.peerName)
                }

                NodeStage.VERIFYING -> {
                    texts.verifying()
                }

                else -> {
                    texts.connecting()
                }
            }
        return OngoingContent(
            kind = OngoingKind.PROGRESS,
            title = title,
            text = text,
            percent = percent,
            showProgress = t.stage != NodeStage.WAITING_FOR_PEER,
            cancelTransferId = t.id,
            stageKey = listOf(OngoingKind.PROGRESS, t.id, t.stage),
        )
    }

    private fun several(
        running: List<NodeTransfer>,
        texts: NotificationTexts,
    ): OngoingContent {
        val moving = running.filter { it.stage == NodeStage.TRANSFERRING || it.stage == NodeStage.VERIFYING }
        val percent = if (moving.isEmpty()) null else percentOf(moving.sumOf { it.bytesDone }, moving.sumOf { it.bytesTotal })
        val speed = moving.mapNotNull { it.bytesPerSecond }.takeIf { it.isNotEmpty() }?.sum()
        val eta = moving.mapNotNull { it.etaMillis }.maxOrNull()
        val text = if (percent != null) texts.progress(percent, speed, eta) else texts.connecting()
        return OngoingContent(
            kind = OngoingKind.PROGRESS,
            title = texts.severalTransfers(running.size),
            text = text,
            percent = percent,
            showProgress = true,
            cancelTransferId = null,
            stageKey = listOf(OngoingKind.PROGRESS) + running.flatMap { listOf(it.id, it.stage) },
        )
    }
}

/**
 * At most one progress post every [intervalMillis] (design §5: 500 ms), while a new stage ([OngoingContent.stageKey])
 * goes out at once. The caller posts when [admit] returns 0 and otherwise tries again after the returned wait with the
 * latest content, so the last update is never lost. Not thread-safe.
 */
class ProgressThrottle(
    private val intervalMillis: Long = INTERVAL_MILLIS,
) {
    init {
        require(intervalMillis > 0) { "interval must be positive" }
    }

    private var lastKey: Any? = null
    private var lastPostedAt = 0L
    private var posted = false

    /** 0 when content with [key] may be posted at [now] (recorded as posted), else how many ms to wait. */
    fun admit(
        key: Any,
        now: Long,
    ): Long {
        if (!posted || key != lastKey || now - lastPostedAt >= intervalMillis) {
            posted = true
            lastKey = key
            lastPostedAt = now
            return 0
        }
        return lastPostedAt + intervalMillis - now
    }

    /** Forgets the last post (the notification was removed): the next content goes out at once. */
    fun reset() {
        posted = false
        lastKey = null
    }

    companion object {
        const val INTERVAL_MILLIS: Long = 500
    }
}

/**
 * The accessibility announcements of progress (design §11): every [stepPercent] (25 %) per transfer, each milestone
 * once, 100 % included. A transfer that resumes past a milestone announces the highest one it passed. Not thread-safe.
 */
class ProgressAnnouncer(
    private val stepPercent: Int = STEP_PERCENT,
) {
    init {
        require(stepPercent in 1..100) { "step must be 1..100 percent" }
    }

    private val announced = HashMap<String, Int>()

    /** The milestone [percent] reached for [transferId] that was not announced yet, or null. */
    fun milestone(
        transferId: String,
        percent: Int?,
    ): Int? {
        if (percent == null) return null
        val reached = (percent.coerceIn(0, 100) / stepPercent) * stepPercent
        val previous = announced[transferId] ?: 0
        if (reached <= previous) return null
        announced[transferId] = reached
        return reached
    }

    /** Keeps the state of [transferIds] only (the rest ended). */
    fun retain(transferIds: Collection<String>) {
        announced.keys.retainAll(transferIds.toSet())
    }

    companion object {
        const val STEP_PERCENT: Int = 25
    }
}
