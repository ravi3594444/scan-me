package com.constrivo.drop.platform.android.service

import com.constrivo.drop.core.crypto.handshake.HandshakeResult
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.TransferFileStatus
import com.constrivo.drop.core.data.TransferOutcome
import com.constrivo.drop.core.data.TransferStatus
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.ladder.HintInputs
import com.constrivo.drop.core.ladder.HintRules
import com.constrivo.drop.core.ladder.LadderState
import com.constrivo.drop.core.ladder.TransportBadge
import com.constrivo.drop.core.protocol.BundlePlan
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferState
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.Releasable
import com.constrivo.drop.core.transfer.engine.FileStatus
import com.constrivo.drop.core.transfer.engine.Transfer
import com.constrivo.drop.core.transfer.engine.TransferProgress
import com.constrivo.drop.core.transfer.session.Endpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import com.constrivo.drop.core.data.WifiBand as StoredBand

/**
 * One transfer on this phone: its [NodeTransfer] for the UI and the notifications, derived from the engine's progress
 * and the ladder's state, and its History row (architecture §12 `transfer`, F-G2): status as the reducer persists it,
 * bytes at most every [progressPersistMillis], the link and band, the hints, and the outcome at the end. Everything
 * written to the database goes through one coroutine per transfer, in order, and failures are reported, never thrown
 * at the engine.
 *
 * A transfer may take several engine runs ([Attempt]s): a link that drops is followed by a new session on which the
 * sender offers the same transfer again and the receiver resumes it (S8, T-07). The tracker follows one attempt at a
 * time ([attach], [detach]) and keeps what spans them: the History row, the time spent streaming, the hints, whether
 * the user accepted, and the sender's pairing code, which outlives the transfer until the user answers it (F-B3).
 */
internal class TransferTracker(
    val id: TransferId,
    val direction: NodeDirection,
    initial: NodeTransfer,
    private val data: DropData,
    private val scope: CoroutineScope,
    private val monotonicClock: MonotonicClock,
    private val progressPersistMillis: Long,
    private val report: (String, Throwable?) -> Unit,
) {
    val hex: String = id.toHex()
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<NodeTransfer> = mutableState.asStateFlow()

    private val pairing = MutableStateFlow<String?>(null)
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var writer: Job? = null
    private var mapper: Job? = null

    /** The engine run this tracker follows now; null between attempts. */
    @Volatile
    var attempt: Attempt? = null
        private set

    val transfer: Transfer? get() = attempt?.transfer

    val bridge: com.constrivo.drop.core.ladder.engine.LadderTransferBridge? get() = attempt?.bridge

    /** Whether History has a row for this transfer (created by [recordCreated]). */
    @Volatile
    var hasRow: Boolean = false
        private set

    @Volatile
    private var keepAwake: Releasable? = null

    val isAwake: Boolean get() = keepAwake != null

    /** The peer's verified identity key, once a handshake ran (or, for a send restored after a restart, from History). */
    @Volatile
    var peerIdentity: ByteArray? = null

    /** Sender: the LAN endpoints the peer was reached at (a new session tries them first). */
    @Volatile
    var endpoints: List<Endpoint> = emptyList()

    /** Sender: what to offer again after an interruption. */
    @Volatile
    var sendSpec: SendSpec? = null

    /** Receiver: the store every attempt of this transfer writes to. */
    @Volatile
    var store: FileStore? = null

    /** Sender: the first session's handshake, while its code waits for the user ([pairingCode]). */
    @Volatile
    var pairingResult: HandshakeResult? = null

    /** The local user cancelled while no attempt ran (the sender's reconnect loop ends). */
    @Volatile
    var cancelRequested: Boolean = false

    /** Bumped to wake the sender's reconnect loop (a cancel, a new sighting). */
    val wake = MutableStateFlow(0)

    /** The transfer was accepted in this process (by the user, auto-accept, or a resume of an accepted transfer). */
    @Volatile
    var everAccepted: Boolean = false
        private set

    @Volatile
    private var finished = false

    /** The tracker reached its end ([finish], [abort] or [abandon]); a later offer of the same id is not a resume. */
    val isFinished: Boolean get() = finished

    /** Receiver: the published files by index, for the completion notification. */
    val published: MutableMap<Int, ReceivedItem> = ConcurrentHashMap()

    // Touched only by the mapper coroutine, or by [detach] and [finish] after it stopped.
    private var resuming = false
    private var streamingSince: Long? = null
    private var activeMillis = 0L
    private val hintsSeen = LinkedHashSet<HintCode>()
    private val persistedHints = HashSet<HintCode>()
    private var lastLink: LinkKind? = null
    private var lastFreq = 0
    private var lastPersistedBytes = -1L
    private var lastPersistAt = Long.MIN_VALUE

    init {
        writer =
            scope.launch {
                for (write in writes) {
                    try {
                        write()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        report("History write for transfer $hex failed", e)
                    }
                }
            }
    }

    /** Updates what the UI shows outside an attempt (dialling, reconnecting, a failure to connect). */
    fun update(change: (NodeTransfer) -> NodeTransfer) = mutableState.update(change)

    /** The sender's SAS while the pairing is not answered (F-B3); null hides it. It stays after the transfer ends. */
    fun setPairingCode(code: String?) {
        pairing.value = code
        if (code == null) pairingResult = null
        mutableState.update { it.copy(pairingCode = code) }
    }

    val pairingCode: String? get() = pairing.value

    /** Emits the pairing code as it changes (the finished-transfer retention waits for it to be answered). */
    val pairingState: StateFlow<String?> = pairing.asStateFlow()

    /** The History row exists (created by the node on the `Offer`). */
    fun recordCreated() {
        hasRow = true
    }

    /** Marks the transfer accepted in this process (a later offer of it resumes without a card). */
    fun markAccepted() {
        everAccepted = true
    }

    /** A receive published file [item] (the tray, "Open" on the completion notification). */
    fun onPublished(
        index: Int,
        item: ReceivedItem,
    ) {
        published[index] = item
        mutableState.update { it.copy(receivedUris = it.receivedUris + (index to item.uri)) }
    }

    /** Holds a keep-awake request until the transfer ends (architecture §9, F-E12: a partial wake lock). */
    fun holdAwake(releasable: Releasable) {
        keepAwake?.release()
        keepAwake = releasable
    }

    /** The reducer asked to persist [state] (called in the engine's actor: queue only). */
    fun onPersist(state: TransferState) {
        if (!hasRow || state.phase.isTerminal || finished) return
        val status = TransferStatus.of(state.phase)
        writes.trySend { data.transfers.updateStatus(id, status) }
    }

    /**
     * Follows [attempt] (its transfer and its ladder) until it ends or is [detach]ed, mapping every change to [state]
     * and History. [localCaps] and [peerCaps] feed the hint rules when no ladder state exists yet. [resumed] marks an
     * attempt that offers or answers an interrupted transfer again: until it is accepted it shows as reconnecting, with
     * the bytes that already arrived.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun attach(
        attempt: Attempt,
        localCaps: Capabilities,
        peerCaps: Capabilities,
        resumed: Boolean,
    ) {
        check(mapper == null) { "detach the previous attempt first" }
        this.attempt = attempt
        resuming = resumed
        val transfer = attempt.transfer
        val ladder = attempt.bridge?.runner?.flatMapLatest { runner -> runner?.state ?: flowOf(null) } ?: flowOf(null)
        mapper =
            scope.launch {
                combine(transfer.progress, transfer.state, ladder, pairing) { p, s, l, code -> Snapshot(p, s, l, code) }
                    .collect { snap -> onSnapshot(snap, transfer, localCaps, peerCaps) }
            }
    }

    /** Stops following the current attempt (it is being retired); the tracker keeps its state for the next one. */
    suspend fun detach() {
        mapper?.cancelAndJoin()
        mapper = null
        attempt = null
        streamingSince?.let { activeMillis += monotonicClock.elapsedMillis() - it }
        streamingSince = null
    }

    private class Snapshot(
        val progress: TransferProgress,
        val state: TransferState,
        val ladder: LadderState?,
        val pairingCode: String?,
    )

    private fun onSnapshot(
        snap: Snapshot,
        transfer: Transfer,
        localCaps: Capabilities,
        peerCaps: Capabilities,
    ) {
        val p = snap.progress
        if (snap.state.acceptedAtMillis != null) {
            everAccepted = true
            resuming = false
        }
        val now = monotonicClock.elapsedMillis()
        val streaming = p.phase == TransferPhase.STREAMING_BLUETOOTH || p.phase == TransferPhase.STREAMING_WIFI
        if (streaming && streamingSince == null) streamingSince = now
        if (!streaming) {
            streamingSince?.let { activeMillis += now - it }
            streamingSince = null
        }
        hintsSeen += p.hints
        val badge = badgeOf(p, snap.ladder)
        val bundled =
            if (HintCode.BUNDLING in p.hints) {
                val limit = BundlePlan.smallFileLimit(transfer.offer.chunkSize)
                p.files.count { it.size < limit }
            } else {
                0
            }
        val hint =
            if (snap.ladder != null) {
                HintRules.select(HintInputs.from(snap.ladder, bundledFiles = bundled))
            } else {
                HintRules.select(
                    HintInputs(linkKind = p.linkKind, freqMhz = p.freqMhz, local = localCaps, peer = peerCaps, bundledFiles = bundled),
                )
            }
        var stage = NodeStage.of(p.phase, p.declineReason, p.cancelReason, everAccepted)
        // A transfer offered again after a drop is still the one the user accepted: it reconnects, it does not wait.
        val waitingAgain = resuming && everAccepted && stage == NodeStage.AWAITING_ACCEPT
        if (waitingAgain) stage = NodeStage.RECONNECTING
        mutableState.update {
            it.copy(
                stage = stage,
                peerName = p.peerName.ifEmpty { it.peerName },
                fileCount = p.fileCount,
                bytesTotal = p.bytesTotal,
                bytesDone = if (waitingAgain) maxOf(it.bytesDone, p.bytesDone) else p.bytesDone,
                bytesPerSecond = p.bytesPerSecond.takeIf { v -> v > 0 }?.toLong(),
                etaMillis = p.etaMillis,
                badge = badge,
                hint = hint,
                pairingCode = snap.pairingCode,
                failure = p.failure,
            )
        }
        if (!waitingAgain) persistProgress(p, now)
    }

    private fun persistProgress(
        p: TransferProgress,
        now: Long,
    ) {
        if (!hasRow || p.isTerminal) return
        val link = p.linkKind
        if (link != null && (link != lastLink || p.freqMhz != lastFreq)) {
            lastLink = link
            lastFreq = p.freqMhz
            val band = StoredBand.fromFrequencyMhz(p.freqMhz)
            writes.trySend { data.transfers.recordLink(id, link, band) }
        }
        for (hint in p.hints) {
            if (hint !in persistedHints) {
                persistedHints += hint
                writes.trySend { data.transfers.addHint(id, hint) }
            }
        }
        if (p.bytesDone != lastPersistedBytes && now - lastPersistAt >= progressPersistMillis) {
            lastPersistedBytes = p.bytesDone
            lastPersistAt = now
            val bytes = p.bytesDone
            writes.trySend { data.transfers.updateProgress(id, bytes) }
        }
    }

    /** Between attempts (S8): the link dropped and the transfer waits for its next session. */
    fun markInterrupted(stage: NodeStage = NodeStage.RECONNECTING) {
        mutableState.update { it.copy(stage = stage, bytesPerSecond = null, etaMillis = null) }
    }

    /**
     * The transfer ended with [final]: files are reconciled with the engine's last word, the row is finished with the
     * outcome (F-G2, F-G4), and the keep-awake request is released. The sender's pairing code stays until the user
     * answers it. Suspends until History is written.
     */
    suspend fun finish(
        final: TransferProgress,
        finalState: TransferState?,
    ) {
        finished = true
        keepAwake?.release()
        keepAwake = null
        mapper?.cancelAndJoin()
        mapper = null
        val now = monotonicClock.elapsedMillis()
        streamingSince?.let { activeMillis += now - it }
        streamingSince = null
        hintsSeen += final.hints
        val stage = NodeStage.of(final.phase, final.declineReason, final.cancelReason, everAccepted || final.phase == TransferPhase.DONE)
        mutableState.update {
            it.copy(
                stage = stage,
                bytesDone = final.bytesDone,
                bytesPerSecond = null,
                etaMillis = null,
                pairingCode = pairing.value,
                failure = final.failure,
            )
        }
        if (!hasRow) {
            writes.close()
            writer?.join()
            return
        }
        val status = TransferStatus.of(final.phase).takeIf { it.isTerminal } ?: TransferStatus.CANCELLED
        val doneFiles = doneFiles(final, finalState)
        val failed = if (status == TransferStatus.DONE) (final.fileCount - doneFiles.size).coerceAtLeast(0) else 0
        val average = if (activeMillis > 0 && final.bytesDone > 0) final.bytesDone * 1000 / activeMillis else null
        val outcome =
            TransferOutcome(
                status = status,
                bytesDone = final.bytesDone,
                transport = final.linkKind ?: lastLink,
                band = StoredBand.fromFrequencyMhz(if (final.linkKind != null) final.freqMhz else lastFreq),
                avgSpeedBytesPerSecond = average,
                hints = hintsSeen.toList(),
                failedFiles = failed.coerceAtMost(final.fileCount),
            )
        writes.trySend {
            reconcileFiles(doneFiles)
            data.transfers.finish(id, outcome)
        }
        writes.close()
        writer?.join()
    }

    /**
     * The transfer ended between attempts ([stage]: the user cancelled it, or the peer never came back, S8): History
     * is finished with what arrived so far. Suspends until History is written.
     */
    suspend fun abort(
        stage: NodeStage,
        failure: String?,
    ) {
        finished = true
        keepAwake?.release()
        keepAwake = null
        mapper?.cancelAndJoin()
        mapper = null
        mutableState.update { it.copy(stage = stage, bytesPerSecond = null, etaMillis = null, failure = failure) }
        if (!hasRow) {
            writes.close()
            writer?.join()
            return
        }
        val status = if (stage == NodeStage.FAILED) TransferStatus.FAILED else TransferStatus.CANCELLED
        val bytes = state.value.bytesDone
        val average = if (activeMillis > 0 && bytes > 0) bytes * 1000 / activeMillis else null
        val outcome =
            TransferOutcome(
                status = status,
                bytesDone = bytes,
                transport = lastLink,
                band = StoredBand.fromFrequencyMhz(lastFreq),
                avgSpeedBytesPerSecond = average,
                hints = hintsSeen.toList(),
            )
        writes.trySend { data.transfers.finish(id, outcome) }
        writes.close()
        writer?.join()
    }

    /** The files that arrived, by index, with where the receiver saved them. */
    private fun doneFiles(
        final: TransferProgress,
        finalState: TransferState?,
    ): Map<Int, String?> =
        when (direction) {
            NodeDirection.RECEIVE -> {
                final.files.filter { it.status == FileStatus.DONE }.associate { it.index to it.savedUri }
            }

            NodeDirection.SEND -> {
                if (final.phase != TransferPhase.DONE) {
                    emptyMap()
                } else {
                    val failed = finalState?.failedFiles
                    (0 until final.fileCount).filter { failed == null || it !in failed }.associateWith { null }
                }
            }
        }

    /** Marks the files that arrived and that History does not show as done yet. */
    private suspend fun reconcileFiles(done: Map<Int, String?>) {
        if (done.isEmpty()) return
        val counts = data.transferFiles.statusCounts(id)
        if ((counts[TransferFileStatus.DONE] ?: 0) >= done.size) return
        for (row in data.transferFiles.files(id)) {
            if (row.status == TransferFileStatus.DONE || row.index !in done) continue
            data.transferFiles.complete(id, row.index, done[row.index])
        }
    }

    /** Cancels the mapping and the writer without writing anything more (the node is shutting down). */
    fun abandon() {
        finished = true
        keepAwake?.release()
        keepAwake = null
        mapper?.cancel()
        writes.close()
    }

    companion object {
        /** The badge of the link carrying data (F-F2): the ladder's once it runs, else the engine's link. */
        fun badgeOf(
            progress: TransferProgress,
            ladder: LadderState?,
        ): TransportBadge? = ladder?.badge ?: progress.linkKind?.let { TransportBadge.of(it, progress.freqMhz) }
    }
}
