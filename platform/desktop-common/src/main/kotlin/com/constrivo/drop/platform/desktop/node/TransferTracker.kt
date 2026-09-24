package com.constrivo.drop.platform.desktop.node

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
import com.constrivo.drop.core.ladder.engine.LadderTransferBridge
import com.constrivo.drop.core.protocol.BundlePlan
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferState
import com.constrivo.drop.core.transfer.Releasable
import com.constrivo.drop.core.transfer.engine.FileStatus
import com.constrivo.drop.core.transfer.engine.Transfer
import com.constrivo.drop.core.transfer.engine.TransferProgress
import com.constrivo.drop.core.transfer.session.Endpoint
import com.constrivo.drop.core.transfer.session.SecureSession
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
import java.nio.file.Path
import com.constrivo.drop.core.data.WifiBand as StoredBand

/**
 * One transfer on this node: its [NodeTransfer] for the UI, derived from the engine's progress and the ladder's state,
 * and its History row (architecture §12 `transfer`, F‑G2): status as the reducer persists it, bytes at most every
 * [progressPersistMillis], the link and band, the hints, and the outcome at the end. Everything written to the
 * database goes through one coroutine per transfer, in order, and failures are reported, never thrown at the engine.
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

    @Volatile
    var transfer: Transfer? = null
        private set

    @Volatile
    var bridge: LadderTransferBridge? = null
        private set

    /** Whether History has a row for this transfer (created by [recordCreated]). */
    @Volatile
    var hasRow: Boolean = false
        private set

    @Volatile
    private var keepAwake: Releasable? = null

    /** The peer's verified identity key, once the handshake ran. */
    @Volatile
    var peerIdentity: ByteArray? = null

    /** The session the transfer started on (its handshake result holds the SAS and the recognition secret). */
    @Volatile
    var session: SecureSession? = null

    /** Sender: the LAN endpoints the peer was reached at (reconnects try them first, N3). */
    @Volatile
    var endpoints: List<Endpoint> = emptyList()

    /** Sender: this side's user confirmed the SAS during this transfer (the trust exchange follows it, S3). */
    @Volatile
    var pairedHere: Boolean = false

    // Confined to the mapper coroutine.
    private var everAccepted = false
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

    /** Updates what the UI shows before the engine runs (dialling, a failure to connect). */
    fun update(change: (NodeTransfer) -> NodeTransfer) = mutableState.update(change)

    /** The sender's SAS while the pairing is not confirmed (F‑B3); null hides it. */
    fun setPairingCode(code: String?) {
        pairing.value = code
        mutableState.update { it.copy(pairingCode = code) }
    }

    val pairingCode: String? get() = pairing.value

    /** The History row exists (created by the node on the `Offer`). */
    fun recordCreated() {
        hasRow = true
    }

    /** Holds a keep-awake request until the transfer ends (architecture §9). */
    fun holdAwake(releasable: Releasable) {
        keepAwake?.release()
        keepAwake = releasable
    }

    /** The reducer asked to persist [state] (called in the engine's actor: queue only). */
    fun onPersist(state: TransferState) {
        if (!hasRow || state.phase.isTerminal) return
        val status = TransferStatus.of(state.phase)
        writes.trySend { data.transfers.updateStatus(id, status) }
    }

    /**
     * Follows [transfer] (and [bridge]'s ladder) until it ends, mapping every change to [state] and History.
     * [localCaps] and [peerCaps] feed the hint rules when no ladder state exists yet.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun attach(
        transfer: Transfer,
        bridge: LadderTransferBridge?,
        localCaps: Capabilities,
        peerCaps: Capabilities,
    ) {
        this.transfer = transfer
        this.bridge = bridge
        val ladder = bridge?.runner?.flatMapLatest { runner -> runner?.state ?: flowOf(null) } ?: flowOf(null)
        mapper =
            scope.launch {
                combine(transfer.progress, transfer.state, ladder, pairing) { p, s, l, code -> Snapshot(p, s, l, code) }
                    .collect { snap -> onSnapshot(snap, transfer, localCaps, peerCaps) }
            }
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
        if (snap.state.acceptedAtMillis != null) everAccepted = true
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
        val stage = NodeStage.of(p.phase, p.declineReason, p.cancelReason, everAccepted)
        mutableState.update {
            it.copy(
                stage = stage,
                peerName = p.peerName.ifEmpty { it.peerName },
                fileCount = p.fileCount,
                bytesTotal = p.bytesTotal,
                bytesDone = p.bytesDone,
                bytesPerSecond = p.bytesPerSecond.takeIf { v -> v > 0 }?.toLong(),
                etaMillis = p.etaMillis,
                badge = badge,
                hint = hint,
                pairingCode = snap.pairingCode.takeIf { !stage.isFinal },
                failure = p.failure,
            )
        }
        persistProgress(p, now)
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

    /**
     * The transfer ended with [final]: files are reconciled with the engine's last word (the sender marks what the
     * receiver confirmed; the receiver's store already wrote its files, anything missing is written now), the row is
     * finished with the outcome (F‑G2, F‑G4), and the keep-awake request is released. Suspends until History is
     * written.
     */
    suspend fun finish(
        final: TransferProgress,
        finalState: TransferState?,
        folder: Path?,
    ) {
        keepAwake?.release()
        keepAwake = null
        mapper?.cancelAndJoin()
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
                pairingCode = null,
                failure = final.failure,
                folder = folder,
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
                transport = final.linkKind,
                band = StoredBand.fromFrequencyMhz(final.freqMhz),
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
     * The files that arrived, by index, with where the receiver saved them: the receiver's verified files; on the
     * sender, every file of a done transfer except those the receiver reported failed (the sender's own rows only
     * count bytes).
     */
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
        keepAwake?.release()
        keepAwake = null
        mapper?.cancel()
        writes.close()
    }

    companion object {
        /** The badge of the link carrying data (F‑F2): the ladder's once it runs, else the engine's link. */
        fun badgeOf(
            progress: TransferProgress,
            ladder: LadderState?,
        ): TransportBadge? = ladder?.badge ?: progress.linkKind?.let { TransportBadge.of(it, progress.freqMhz) }
    }
}
