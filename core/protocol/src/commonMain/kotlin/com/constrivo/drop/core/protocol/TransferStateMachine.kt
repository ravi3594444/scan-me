package com.constrivo.drop.core.protocol

/** Which end of a transfer this state machine runs on. The two roles share states but not every event. */
enum class TransferRole {
    SENDER,
    RECEIVER,
}

/**
 * States of architecture §7.7 with spec change S8: the old `Interrupted` is split into [RECONNECTING] (active
 * reconnect attempts for [ProtocolConstants.RECONNECT_WINDOW_MS]) and [PARKED] (waiting for the peer's beacon until
 * [ProtocolConstants.PARKED_WINDOW_MS] after the interruption), so the UI can show "Waiting for {name}".
 *
 * [storedStatus] is the `transfer.status` value of architecture §12.
 */
enum class TransferPhase(
    val storedStatus: String,
    val isTerminal: Boolean = false,
) {
    OFFERED("offered"),
    ACCEPTED("accepted"),
    STREAMING_BLUETOOTH("streaming"),
    STREAMING_WIFI("streaming"),
    VERIFYING("verifying"),
    RECONNECTING("interrupted"),
    PARKED("interrupted"),
    DONE("done", isTerminal = true),
    CANCELLED("cancelled", isTerminal = true),
    FAILED("failed", isTerminal = true),
    ;

    /** A link to the peer is expected to be up: the heartbeat watchdog runs. */
    val isConnected: Boolean get() = this == ACCEPTED || this == STREAMING_BLUETOOTH || this == STREAMING_WIFI || this == VERIFYING

    val isInterrupted: Boolean get() = this == RECONNECTING || this == PARKED
}

/** Timers the reducer asks the engine to run; each fires a [TransferEvent.TimerFired]. */
enum class TransferTimer {
    /** Offer unanswered (§7.8, 30 s). */
    OFFER,

    /** Heartbeat watchdog (§7.8, 6 s without a heartbeat). */
    HEARTBEAT,

    /** End of active reconnecting (S8, 2 min). */
    RECONNECT_WINDOW,

    /** End of the parked window, measured from the interruption (S8, 24 h); partials are then cleared. */
    PARKED_WINDOW,
}

/** Timeouts of §7.8; the defaults are the [ProtocolConstants] values. */
data class TransferTimeouts(
    val offerMillis: Long = ProtocolConstants.OFFER_TIMEOUT_MS,
    val heartbeatLostMillis: Long = ProtocolConstants.HEARTBEAT_LOST_MS,
    val reconnectWindowMillis: Long = ProtocolConstants.RECONNECT_WINDOW_MS,
    val parkedWindowMillis: Long = ProtocolConstants.PARKED_WINDOW_MS,
    /** Mismatches on one unit (or one file's SHA-256) before the file fails. */
    val maxMismatches: Int = ProtocolConstants.MAX_CHUNK_MISMATCHES,
) {
    init {
        require(offerMillis > 0 && heartbeatLostMillis > 0 && reconnectWindowMillis > 0 && parkedWindowMillis > 0) {
            "timeouts must be positive"
        }
        require(maxMismatches >= 1) { "maxMismatches must be at least 1" }
    }
}

/** An immutable set of file indices (a copy-on-write bit set), for verified and failed files. */
class FileIndexSet private constructor(
    private val words: LongArray,
    val size: Int,
) {
    operator fun contains(index: Int): Boolean = index >= 0 && index / 64 < words.size && (words[index / 64] ushr (index % 64)) and 1L != 0L

    /** This set with [index] added (the same instance if already present). */
    operator fun plus(index: Int): FileIndexSet {
        require(index >= 0) { "file index must be non-negative" }
        if (contains(index)) return this
        val copy = words.copyOf(maxOf(words.size, index / 64 + 1))
        copy[index / 64] = copy[index / 64] or (1L shl (index % 64))
        return FileIndexSet(copy, size + 1)
    }

    fun isEmpty(): Boolean = size == 0

    /** The members in ascending order. */
    fun toList(): List<Int> {
        val out = ArrayList<Int>(size)
        for (w in words.indices) {
            var bits = words[w]
            while (bits != 0L) {
                val bit = bits.countTrailingZeroBits()
                out += w * 64 + bit
                bits = bits and (bits - 1)
            }
        }
        return out
    }

    override fun equals(other: Any?): Boolean = other is FileIndexSet && other.size == size && other.toList() == toList()

    override fun hashCode(): Int = toList().hashCode()

    override fun toString(): String = "FileIndexSet(${toList()})"

    companion object {
        val EMPTY: FileIndexSet = FileIndexSet(LongArray(0), 0)

        fun of(vararg indices: Int): FileIndexSet = indices.fold(EMPTY) { set, i -> set + i }
    }
}

/**
 * The state of one transfer on one device. Produced only by [TransferStateMachine]; persisted by the engine on
 * [TransferEffect.Persist].
 *
 * @property timers deadlines (epoch milliseconds) of the timers that are running; a [TransferEvent.TimerFired]
 *   whose deadline is not in here, or not reached, is stale and ignored.
 * @property link the link data currently flows over (the badge), or the last one while interrupted.
 * @property allUnitsAcked every unit has been acked (sender) or received and acked (receiver).
 * @property verifiedFiles receiver: files whose SHA-256 matched; [bytesVerified] sums their sizes.
 * @property failedFiles files that failed after [TransferTimeouts.maxMismatches] mismatches (receiver), or that the
 *   receiver's `Complete` reported (sender).
 */
data class TransferState(
    val transferId: TransferId,
    val role: TransferRole,
    val phase: TransferPhase,
    val fileCount: Int,
    val totalBytes: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val acceptedAtMillis: Long? = null,
    val link: LinkKind? = null,
    val freqMhz: Int = 0,
    val allUnitsAcked: Boolean = false,
    val timers: Map<TransferTimer, Long> = emptyMap(),
    val interruptedAtMillis: Long? = null,
    val parkedDeadlineMillis: Long? = null,
    val unitStrikes: Map<TransferUnit, Int> = emptyMap(),
    val fileStrikes: Map<Int, Int> = emptyMap(),
    val verifiedFiles: FileIndexSet = FileIndexSet.EMPTY,
    val bytesVerified: Long = 0,
    val failedFiles: FileIndexSet = FileIndexSet.EMPTY,
    val cancelReason: CancelReason? = null,
    val declineReason: DeclineReason? = null,
    val completeStatus: CompleteStatus? = null,
    val failure: String? = null,
) {
    init {
        require(fileCount >= 1) { "a transfer has at least one file" }
        require(totalBytes >= 0) { "total bytes must be non-negative" }
    }

    /** Files that are verified or failed. */
    val resolvedFiles: Int get() = verifiedFiles.size + failedFiles.size
}

/** Inputs to [TransferStateMachine.reduce]. Events that carry a message are ignored if its transfer id differs. */
sealed interface TransferEvent {
    /** Receiver: the user (or auto-accept) accepted; the reducer sends [accept]. */
    data class LocalAccept(
        val accept: Accept,
    ) : TransferEvent

    /** Sender: the peer's `Accept` arrived. */
    data class AcceptReceived(
        val accept: Accept,
    ) : TransferEvent

    /** Receiver: the user declined; the reducer sends `Decline`. */
    data class LocalDecline(
        val reason: DeclineReason = DeclineReason.USER,
    ) : TransferEvent

    /** Sender: the peer's `Decline` arrived. */
    data class DeclineReceived(
        val decline: Decline,
    ) : TransferEvent

    /** The first chunk frame went out (sender) or came in (receiver) over Bluetooth (§7.5 head start). */
    data object FirstChunkOverBluetooth : TransferEvent

    /** `LinkReady` was exchanged and a data stream on [kind] connected (§7.5 hop). */
    data class WifiStreamConnected(
        val kind: LinkKind,
        val freqMhz: Int = 0,
    ) : TransferEvent {
        init {
            require(kind != LinkKind.BLUETOOTH) { "use FirstChunkOverBluetooth for the Bluetooth stream" }
        }
    }

    /**
     * Every unit is acked. Sender: raise it after the last `FileDone` went out; the reducer then sends
     * `Complete`. Receiver: every unit was received, chunk-verified and acked.
     */
    data object AllChunksAcked : TransferEvent

    /** A heartbeat (or any authenticated control frame, at the engine's choice) arrived: re-arms the watchdog. */
    data object HeartbeatReceived : TransferEvent

    /** The link failed outright (socket closed, radio off); same as the watchdog firing. */
    data object LinkLost : TransferEvent

    /** After an interruption, the session is back over [kind] and `Resume` was exchanged. */
    data class Resumed(
        val kind: LinkKind,
        val freqMhz: Int = 0,
    ) : TransferEvent

    /** While parked, the peer's beacon was seen again: reconnect actively. */
    data object PeerRediscovered : TransferEvent

    /** Sender: the receiver re-requested units (a `Resume` while connected, after a mismatch). */
    data class RetransmitRequested(
        val resume: Resume,
    ) : TransferEvent

    /** Receiver: file [fileIndex] ([bytes] long) matched its `FileDone` SHA-256. */
    data class FileVerified(
        val fileIndex: Int,
        val bytes: Long,
    ) : TransferEvent

    /**
     * Receiver: [unit] failed its per-frame XXH3-128 check. [files] are the files it carries (one file for a chunk,
     * all its files for a bundle); they fail when the unit reaches the mismatch limit.
     */
    data class ChunkHashMismatch(
        val unit: TransferUnit,
        val files: List<Int>,
    ) : TransferEvent

    /**
     * Receiver: file [fileIndex]'s SHA-256 did not match its `FileDone`. [suspects] are the units to request again
     * (from re-hashing the partial against the stored chunk hashes, N5, or the whole file).
     */
    data class FileHashMismatch(
        val fileIndex: Int,
        val suspects: MissingUnits,
    ) : TransferEvent

    /** The peer's `Complete` arrived (sender: the verified outcome; receiver: the sender's summary). */
    data class CompleteReceived(
        val complete: Complete,
    ) : TransferEvent

    /** The user cancelled on this device. */
    data class LocalCancel(
        val reason: CancelReason = CancelReason.USER,
    ) : TransferEvent

    /** The peer's `Cancel` arrived. */
    data class CancelReceived(
        val cancel: Cancel,
    ) : TransferEvent

    /** Receiver: the destination is full (§7.8): cancel with `storage` and clear partials. */
    data object StorageFull : TransferEvent

    /** The peer broke the protocol on an authenticated stream (a [ProtocolException]). */
    data class ProtocolViolation(
        val detail: String,
    ) : TransferEvent

    /** A timer requested with [TransferEffect.StartTimer] fired. */
    data class TimerFired(
        val timer: TransferTimer,
    ) : TransferEvent
}

/** What the engine must do after a transition, in order. */
sealed interface TransferEffect {
    /** Send [message] on the control stream (best effort while interrupted: the engine may drop it). */
    data class Send(
        val message: ControlMessage,
    ) : TransferEffect

    /** (Re)start [timer] to fire at [atMillis]; replaces a running timer of the same kind. */
    data class StartTimer(
        val timer: TransferTimer,
        val atMillis: Long,
    ) : TransferEffect

    data class CancelTimer(
        val timer: TransferTimer,
    ) : TransferEffect

    /** Write the new state (`transfer.status` and the fields the engine keeps). */
    data object Persist : TransferEffect

    /** Publish the new state to the UI. */
    data object NotifyUi : TransferEffect

    /** Sender: start the Bluetooth head start and the transport ladder. Receiver: prepare partials and take data. */
    data object StartStreaming : TransferEffect

    /** Start active reconnect attempts (Reconnecting). */
    data object StartReconnect : TransferEffect

    data object StopReconnect : TransferEffect

    /** Wait passively for the peer's beacon (Parked); report it with [TransferEvent.PeerRediscovered]. */
    data object WatchForPeer : TransferEffect

    data object StopWatchingForPeer : TransferEffect

    /** Receiver: [fileIndex] failed verification for good; drop its partial and stop expecting it. */
    data class FileFailed(
        val fileIndex: Int,
    ) : TransferEffect

    /** Receiver: delete this transfer's partial files and manifest. */
    data object ClearPartials : TransferEffect

    /** Tear down the Wi-Fi link and restore the previous network (§4: within 5 s). */
    data object ReleaseLink : TransferEffect
}

/**
 * Result of [TransferStateMachine.reduce]: the new [state] and the [effects] to run. [handled] is false when the
 * event did not apply to the current state (a stale timer, a duplicate, an event for the other role, or anything
 * after a terminal state); the state is then unchanged and there are no effects.
 */
data class Transition(
    val state: TransferState,
    val effects: List<TransferEffect>,
    val handled: Boolean = true,
)

/**
 * The transfer state machine of architecture §7.7 and §7.8 with spec change S8, as a pure reducer: no clocks, no
 * I/O. The caller passes the current time; timers are requested as effects and come back as events.
 *
 * Main paths (both roles unless noted):
 * - `Offered` → `Accepted` on accept; → `Cancelled` on decline, cancel or the 30 s offer timer.
 * - `Accepted` → `StreamingBluetooth` on the first Bluetooth chunk, or straight to `StreamingWifi` when a Wi-Fi
 *   stream connects first; `StreamingBluetooth` → `StreamingWifi` on the hop.
 * - Streaming → `Verifying` when every unit is acked (the sender then sends `Complete`); the receiver reaches `Done`
 *   when every file is verified or failed, and sends `Complete`; the sender reaches `Done` on the receiver's
 *   `Complete` (`Failed` if it reports failure).
 * - A mismatch re-requests the unit with `Resume` (from `Verifying`, back to streaming); the third mismatch on a unit
 *   or file fails the file; if every file fails the transfer is `Failed`.
 * - Heartbeat lost (6 s) or link lost → `Reconnecting` (2 min of active attempts) → `Parked` (until 24 h after the
 *   interruption, waiting for the beacon) → `Cancelled` (`timeout`, partials cleared). `Resumed` returns to streaming
 *   (or `Verifying` if everything was already acked); a rediscovered beacon turns `Parked` back into `Reconnecting`.
 * - Cancel (local, remote, storage) → `Cancelled`; a protocol violation → `Failed`. The receiver clears partials on
 *   every end but `Done`; every end after `Offered` releases the link.
 */
class TransferStateMachine(
    val timeouts: TransferTimeouts = TransferTimeouts(),
) {
    /** The initial `Offered` state: the sender after sending the `Offer`, the receiver after receiving it. */
    fun start(
        transferId: TransferId,
        role: TransferRole,
        fileCount: Int,
        totalBytes: Long,
        nowMillis: Long,
    ): Transition {
        val deadline = nowMillis + timeouts.offerMillis
        val state =
            TransferState(
                transferId = transferId,
                role = role,
                phase = TransferPhase.OFFERED,
                fileCount = fileCount,
                totalBytes = totalBytes,
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
                timers = mapOf(TransferTimer.OFFER to deadline),
            )
        return Transition(
            state,
            listOf(TransferEffect.StartTimer(TransferTimer.OFFER, deadline), TransferEffect.Persist, TransferEffect.NotifyUi),
        )
    }

    /** Applies [event] at [nowMillis]. Never throws for a well-formed event; inapplicable events are not [Transition.handled]. */
    fun reduce(
        state: TransferState,
        event: TransferEvent,
        nowMillis: Long,
    ): Transition {
        if (state.phase.isTerminal) return ignore(state)
        val sender = state.role == TransferRole.SENDER
        val phase = state.phase
        return when (event) {
            is TransferEvent.LocalAccept -> {
                if (!sender && phase == TransferPhase.OFFERED && event.accept.transferId == state.transferId) {
                    accept(state, nowMillis, TransferEffect.Send(event.accept))
                } else {
                    ignore(state)
                }
            }

            is TransferEvent.AcceptReceived -> {
                if (sender && phase == TransferPhase.OFFERED && event.accept.transferId == state.transferId) {
                    accept(state, nowMillis, null)
                } else {
                    ignore(state)
                }
            }

            is TransferEvent.LocalDecline -> {
                if (!sender && phase == TransferPhase.OFFERED) {
                    finish(
                        state.copy(declineReason = event.reason),
                        TransferPhase.CANCELLED,
                        nowMillis,
                        listOf(TransferEffect.Send(Decline(state.transferId, event.reason))),
                    )
                } else {
                    ignore(state)
                }
            }

            is TransferEvent.DeclineReceived -> {
                if (sender && phase == TransferPhase.OFFERED && event.decline.transferId == state.transferId) {
                    finish(state.copy(declineReason = event.decline.reason), TransferPhase.CANCELLED, nowMillis, emptyList())
                } else {
                    ignore(state)
                }
            }

            TransferEvent.FirstChunkOverBluetooth -> {
                if (phase == TransferPhase.ACCEPTED) {
                    move(state.copy(phase = TransferPhase.STREAMING_BLUETOOTH, link = LinkKind.BLUETOOTH, freqMhz = 0), nowMillis)
                } else {
                    ignore(state)
                }
            }

            is TransferEvent.WifiStreamConnected -> {
                wifiConnected(state, event, nowMillis)
            }

            TransferEvent.AllChunksAcked -> {
                allAcked(state, nowMillis)
            }

            TransferEvent.HeartbeatReceived -> {
                if (phase.isConnected) {
                    val deadline = nowMillis + timeouts.heartbeatLostMillis
                    Transition(
                        state.copy(timers = state.timers + (TransferTimer.HEARTBEAT to deadline), updatedAtMillis = nowMillis),
                        listOf(TransferEffect.StartTimer(TransferTimer.HEARTBEAT, deadline)),
                    )
                } else {
                    ignore(state)
                }
            }

            TransferEvent.LinkLost -> {
                if (phase.isConnected) interrupt(state, nowMillis) else ignore(state)
            }

            is TransferEvent.Resumed -> {
                if (phase.isInterrupted) resume(state, event, nowMillis) else ignore(state)
            }

            TransferEvent.PeerRediscovered -> {
                if (phase == TransferPhase.PARKED) {
                    val deadline = minOf(nowMillis + timeouts.reconnectWindowMillis, state.parkedDeadlineMillis ?: Long.MAX_VALUE)
                    move(
                        state.copy(
                            phase = TransferPhase.RECONNECTING,
                            timers = state.timers + (TransferTimer.RECONNECT_WINDOW to deadline),
                        ),
                        nowMillis,
                        listOf(
                            TransferEffect.StopWatchingForPeer,
                            TransferEffect.StartTimer(TransferTimer.RECONNECT_WINDOW, deadline),
                            TransferEffect.StartReconnect,
                        ),
                    )
                } else {
                    ignore(state)
                }
            }

            is TransferEvent.RetransmitRequested -> {
                when {
                    !sender || event.resume.transferId != state.transferId -> {
                        ignore(state)
                    }

                    phase == TransferPhase.VERIFYING -> {
                        move(
                            state.copy(phase = streamingPhase(state.link), allUnitsAcked = false),
                            nowMillis,
                        )
                    }

                    phase == TransferPhase.STREAMING_BLUETOOTH || phase == TransferPhase.STREAMING_WIFI -> {
                        Transition(
                            state.copy(updatedAtMillis = nowMillis),
                            emptyList(),
                        )
                    }

                    else -> {
                        ignore(state)
                    }
                }
            }

            is TransferEvent.FileVerified -> {
                fileVerified(state, event, nowMillis)
            }

            is TransferEvent.ChunkHashMismatch -> {
                chunkMismatch(state, event, nowMillis)
            }

            is TransferEvent.FileHashMismatch -> {
                fileMismatch(state, event, nowMillis)
            }

            is TransferEvent.CompleteReceived -> {
                completeReceived(state, event.complete, nowMillis)
            }

            is TransferEvent.LocalCancel -> {
                finish(
                    state.copy(cancelReason = event.reason),
                    TransferPhase.CANCELLED,
                    nowMillis,
                    listOf(TransferEffect.Send(Cancel(state.transferId, event.reason))),
                )
            }

            is TransferEvent.CancelReceived -> {
                if (event.cancel.transferId == state.transferId) {
                    finish(state.copy(cancelReason = event.cancel.reason), TransferPhase.CANCELLED, nowMillis, emptyList())
                } else {
                    ignore(state)
                }
            }

            TransferEvent.StorageFull -> {
                if (!sender && phase != TransferPhase.OFFERED) {
                    finish(
                        state.copy(cancelReason = CancelReason.STORAGE),
                        TransferPhase.CANCELLED,
                        nowMillis,
                        listOf(TransferEffect.Send(Cancel(state.transferId, CancelReason.STORAGE))),
                    )
                } else {
                    ignore(state)
                }
            }

            is TransferEvent.ProtocolViolation -> {
                finish(
                    state.copy(failure = event.detail, cancelReason = CancelReason.PROTOCOL),
                    TransferPhase.FAILED,
                    nowMillis,
                    listOf(TransferEffect.Send(Cancel(state.transferId, CancelReason.PROTOCOL))),
                )
            }

            is TransferEvent.TimerFired -> {
                timerFired(state, event.timer, nowMillis)
            }
        }
    }

    private fun accept(
        state: TransferState,
        now: Long,
        send: TransferEffect.Send?,
    ): Transition {
        val deadline = now + timeouts.heartbeatLostMillis
        val next =
            state.copy(
                phase = TransferPhase.ACCEPTED,
                acceptedAtMillis = now,
                timers = state.timers - TransferTimer.OFFER + (TransferTimer.HEARTBEAT to deadline),
            )
        return move(
            next,
            now,
            listOfNotNull(
                send,
                TransferEffect.CancelTimer(TransferTimer.OFFER),
                TransferEffect.StartTimer(TransferTimer.HEARTBEAT, deadline),
                TransferEffect.StartStreaming,
            ),
        )
    }

    private fun wifiConnected(
        state: TransferState,
        event: TransferEvent.WifiStreamConnected,
        now: Long,
    ): Transition =
        when (state.phase) {
            TransferPhase.ACCEPTED, TransferPhase.STREAMING_BLUETOOTH -> {
                move(state.copy(phase = TransferPhase.STREAMING_WIFI, link = event.kind, freqMhz = event.freqMhz), now)
            }

            TransferPhase.STREAMING_WIFI, TransferPhase.VERIFYING -> {
                if (state.link == event.kind && state.freqMhz == event.freqMhz) {
                    Transition(state.copy(updatedAtMillis = now), emptyList())
                } else {
                    move(state.copy(link = event.kind, freqMhz = event.freqMhz), now)
                }
            }

            else -> {
                ignore(state)
            }
        }

    private fun allAcked(
        state: TransferState,
        now: Long,
    ): Transition {
        val phase = state.phase
        if (phase != TransferPhase.ACCEPTED && phase != TransferPhase.STREAMING_BLUETOOTH && phase != TransferPhase.STREAMING_WIFI) {
            return ignore(state)
        }
        val acked = state.copy(allUnitsAcked = true)
        return if (state.role == TransferRole.SENDER) {
            val complete = Complete(state.transferId, CompleteStatus.OK, state.totalBytes, duration(state, now))
            move(acked.copy(phase = TransferPhase.VERIFYING), now, listOf(TransferEffect.Send(complete)))
        } else if (acked.resolvedFiles == acked.fileCount) {
            resolveReceiver(acked, now, emptyList())
        } else {
            move(acked.copy(phase = TransferPhase.VERIFYING), now)
        }
    }

    private fun fileVerified(
        state: TransferState,
        event: TransferEvent.FileVerified,
        now: Long,
    ): Transition {
        if (state.role != TransferRole.RECEIVER || state.phase == TransferPhase.OFFERED) return ignore(state)
        val index = event.fileIndex
        if (index !in 0 until state.fileCount || index in state.verifiedFiles || index in state.failedFiles) return ignore(state)
        val next =
            state.copy(
                verifiedFiles = state.verifiedFiles + index,
                bytesVerified = state.bytesVerified + event.bytes.coerceAtLeast(0),
                fileStrikes = state.fileStrikes - index,
            )
        return if (next.resolvedFiles == next.fileCount) resolveReceiver(next, now, emptyList()) else move(next, now)
    }

    private fun chunkMismatch(
        state: TransferState,
        event: TransferEvent.ChunkHashMismatch,
        now: Long,
    ): Transition {
        if (state.role != TransferRole.RECEIVER || state.phase == TransferPhase.OFFERED) return ignore(state)
        val strikes = (state.unitStrikes[event.unit] ?: 0) + 1
        if (strikes < timeouts.maxMismatches) {
            val retry = MissingUnits(chunks = listOf(MissingChunks(event.unit.fileIndex, listOf(IndexRange(event.unit.chunkIndex, 1)))))
            return retryUnits(state.copy(unitStrikes = state.unitStrikes + (event.unit to strikes)), retry, now)
        }
        return failFiles(state.copy(unitStrikes = state.unitStrikes - event.unit), event.files, now)
    }

    private fun fileMismatch(
        state: TransferState,
        event: TransferEvent.FileHashMismatch,
        now: Long,
    ): Transition {
        val index = event.fileIndex
        if (state.role != TransferRole.RECEIVER || state.phase == TransferPhase.OFFERED) return ignore(state)
        if (index !in 0 until state.fileCount || index in state.verifiedFiles || index in state.failedFiles) return ignore(state)
        val strikes = (state.fileStrikes[index] ?: 0) + 1
        if (strikes < timeouts.maxMismatches) {
            val retry = if (event.suspects.isEmpty) MissingUnits(files = listOf(IndexRange(index, 1))) else event.suspects
            return retryUnits(state.copy(fileStrikes = state.fileStrikes + (index to strikes)), retry, now)
        }
        return failFiles(state.copy(fileStrikes = state.fileStrikes - index), listOf(index), now)
    }

    /** Re-requests [missing]; from `Verifying` the transfer goes back to streaming. No send while interrupted. */
    private fun retryUnits(
        state: TransferState,
        missing: MissingUnits,
        now: Long,
    ): Transition {
        val effects = if (state.phase.isInterrupted) emptyList() else listOf(TransferEffect.Send(Resume(state.transferId, missing)))
        val next =
            if (state.phase ==
                TransferPhase.VERIFYING
            ) {
                state.copy(phase = streamingPhase(state.link), allUnitsAcked = false)
            } else {
                state
            }
        return move(next, now, effects)
    }

    private fun failFiles(
        state: TransferState,
        files: List<Int>,
        now: Long,
    ): Transition {
        var failed = state.failedFiles
        val effects = ArrayList<TransferEffect>()
        for (f in files.distinct().sorted()) {
            if (f !in 0 until state.fileCount || f in state.verifiedFiles || f in failed) continue
            failed += f
            effects += TransferEffect.FileFailed(f)
        }
        val next = state.copy(failedFiles = failed)
        return if (next.resolvedFiles == next.fileCount) resolveReceiver(next, now, effects) else move(next, now, effects)
    }

    /** Receiver: every file is verified or failed. Sends `Complete` and ends in `Done`, or `Failed` if none verified. */
    private fun resolveReceiver(
        state: TransferState,
        now: Long,
        before: List<TransferEffect>,
    ): Transition {
        val status =
            when {
                state.failedFiles.isEmpty() -> CompleteStatus.OK
                state.verifiedFiles.isEmpty() -> CompleteStatus.FAILED
                else -> CompleteStatus.PARTIAL
            }
        val failed = state.failedFiles.toList().take(ProtocolConstants.MAX_RESUME_ENTRIES)
        val complete = Complete(state.transferId, status, state.bytesVerified, duration(state, now), failed)
        val phase = if (status == CompleteStatus.FAILED) TransferPhase.FAILED else TransferPhase.DONE
        val next =
            state.copy(
                completeStatus = status,
                cancelReason =
                    if (phase ==
                        TransferPhase.FAILED
                    ) {
                        CancelReason.VERIFICATION
                    } else {
                        null
                    },
            )
        return finish(next, phase, now, before + TransferEffect.Send(complete))
    }

    private fun completeReceived(
        state: TransferState,
        complete: Complete,
        now: Long,
    ): Transition {
        if (complete.transferId != state.transferId || state.phase == TransferPhase.OFFERED) return ignore(state)
        if (state.role == TransferRole.RECEIVER) {
            // The sender's summary: informative, unless it reports failure.
            return if (complete.status == CompleteStatus.FAILED) {
                finish(state.copy(completeStatus = CompleteStatus.FAILED), TransferPhase.FAILED, now, emptyList())
            } else {
                Transition(state.copy(updatedAtMillis = now), emptyList())
            }
        }
        val failed = complete.failedFiles.filter { it < state.fileCount }.fold(FileIndexSet.EMPTY) { set, f -> set + f }
        val next = state.copy(completeStatus = complete.status, failedFiles = failed)
        val phase = if (complete.status == CompleteStatus.FAILED) TransferPhase.FAILED else TransferPhase.DONE
        return finish(next, phase, now, emptyList())
    }

    private fun interrupt(
        state: TransferState,
        now: Long,
    ): Transition {
        val reconnect = now + timeouts.reconnectWindowMillis
        val parked = now + timeouts.parkedWindowMillis
        val next =
            state.copy(
                phase = TransferPhase.RECONNECTING,
                interruptedAtMillis = now,
                parkedDeadlineMillis = parked,
                timers =
                    state.timers - TransferTimer.HEARTBEAT + (TransferTimer.RECONNECT_WINDOW to reconnect) +
                        (TransferTimer.PARKED_WINDOW to parked),
            )
        return move(
            next,
            now,
            listOf(
                TransferEffect.CancelTimer(TransferTimer.HEARTBEAT),
                TransferEffect.StartTimer(TransferTimer.RECONNECT_WINDOW, reconnect),
                TransferEffect.StartTimer(TransferTimer.PARKED_WINDOW, parked),
                TransferEffect.StartReconnect,
            ),
        )
    }

    private fun resume(
        state: TransferState,
        event: TransferEvent.Resumed,
        now: Long,
    ): Transition {
        val heartbeat = now + timeouts.heartbeatLostMillis
        val phase = if (state.allUnitsAcked) TransferPhase.VERIFYING else streamingPhase(event.kind)
        val stop = if (state.phase == TransferPhase.RECONNECTING) TransferEffect.StopReconnect else TransferEffect.StopWatchingForPeer
        val cancels = state.timers.keys.filter { it != TransferTimer.HEARTBEAT }.sorted().map { TransferEffect.CancelTimer(it) }
        val next =
            state.copy(
                phase = phase,
                link = event.kind,
                freqMhz = event.freqMhz,
                interruptedAtMillis = null,
                parkedDeadlineMillis = null,
                timers = mapOf(TransferTimer.HEARTBEAT to heartbeat),
            )
        return move(next, now, listOf(stop) + cancels + TransferEffect.StartTimer(TransferTimer.HEARTBEAT, heartbeat))
    }

    private fun timerFired(
        state: TransferState,
        timer: TransferTimer,
        now: Long,
    ): Transition {
        val deadline = state.timers[timer] ?: return ignore(state)
        if (now < deadline) return ignore(state)
        return when (timer) {
            TransferTimer.OFFER -> {
                if (state.phase != TransferPhase.OFFERED) return ignore(state)
                val send =
                    if (state.role == TransferRole.SENDER) {
                        Cancel(state.transferId, CancelReason.TIMEOUT)
                    } else {
                        Decline(state.transferId, DeclineReason.TIMEOUT)
                    }
                val declined = if (state.role == TransferRole.RECEIVER) DeclineReason.TIMEOUT else null
                finish(
                    state.copy(cancelReason = CancelReason.TIMEOUT, declineReason = declined),
                    TransferPhase.CANCELLED,
                    now,
                    listOf(TransferEffect.Send(send)),
                )
            }

            TransferTimer.HEARTBEAT -> {
                if (state.phase.isConnected) interrupt(state, now) else ignore(state)
            }

            TransferTimer.RECONNECT_WINDOW -> {
                if (state.phase != TransferPhase.RECONNECTING) return ignore(state)
                val parkedUntil = state.parkedDeadlineMillis ?: now
                if (now >= parkedUntil) {
                    finish(state.copy(cancelReason = CancelReason.TIMEOUT), TransferPhase.CANCELLED, now, emptyList())
                } else {
                    move(
                        state.copy(phase = TransferPhase.PARKED, timers = state.timers - TransferTimer.RECONNECT_WINDOW),
                        now,
                        listOf(TransferEffect.StopReconnect, TransferEffect.WatchForPeer),
                    )
                }
            }

            TransferTimer.PARKED_WINDOW -> {
                if (state.phase.isInterrupted) {
                    finish(state.copy(cancelReason = CancelReason.TIMEOUT), TransferPhase.CANCELLED, now, emptyList())
                } else {
                    ignore(state)
                }
            }
        }
    }

    /** A non-terminal transition: the given [effects], then persist and notify. */
    private fun move(
        next: TransferState,
        now: Long,
        effects: List<TransferEffect> = emptyList(),
    ): Transition = Transition(next.copy(updatedAtMillis = now), effects + TransferEffect.Persist + TransferEffect.NotifyUi)

    /**
     * Enters the terminal [phase]: [first] (typically the last message), then cancels every running timer, stops
     * reconnecting or watching, clears partials (receiver, unless done), releases the link (unless still offered),
     * persists and notifies.
     */
    private fun finish(
        state: TransferState,
        phase: TransferPhase,
        now: Long,
        first: List<TransferEffect>,
    ): Transition {
        val effects = ArrayList(first)
        state.timers.keys.sorted().forEach { effects += TransferEffect.CancelTimer(it) }
        if (state.phase == TransferPhase.RECONNECTING) effects += TransferEffect.StopReconnect
        if (state.phase == TransferPhase.PARKED) effects += TransferEffect.StopWatchingForPeer
        val wasAccepted = state.phase != TransferPhase.OFFERED
        if (state.role == TransferRole.RECEIVER && wasAccepted && phase != TransferPhase.DONE) effects += TransferEffect.ClearPartials
        if (wasAccepted) effects += TransferEffect.ReleaseLink
        effects += TransferEffect.Persist
        effects += TransferEffect.NotifyUi
        val next =
            state.copy(
                phase = phase,
                timers = emptyMap(),
                interruptedAtMillis = null,
                parkedDeadlineMillis = null,
                updatedAtMillis = now,
            )
        return Transition(next, effects)
    }

    private fun ignore(state: TransferState): Transition = Transition(state, emptyList(), handled = false)

    private fun streamingPhase(link: LinkKind?): TransferPhase =
        if (link == null || link == LinkKind.BLUETOOTH) TransferPhase.STREAMING_BLUETOOTH else TransferPhase.STREAMING_WIFI

    private fun duration(
        state: TransferState,
        now: Long,
    ): Long = (now - (state.acceptedAtMillis ?: now)).coerceAtLeast(0)
}
