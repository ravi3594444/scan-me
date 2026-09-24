package com.constrivo.drop.core.ladder.engine

import com.constrivo.drop.core.ladder.ActiveLink
import com.constrivo.drop.core.ladder.AttemptStatus
import com.constrivo.drop.core.ladder.LadderException
import com.constrivo.drop.core.ladder.LadderGenerations
import com.constrivo.drop.core.ladder.LadderRunner
import com.constrivo.drop.core.ladder.LadderSession
import com.constrivo.drop.core.ladder.LadderState
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.protocol.ControlMoved
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkReady
import com.constrivo.drop.core.transfer.engine.DataLink
import com.constrivo.drop.core.transfer.engine.DataLinkRole
import com.constrivo.drop.core.transfer.engine.Transfer
import com.constrivo.drop.core.transfer.engine.TransferLinkListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Wires the transport ladder to a running transfer (the WP5 → WP4 carry-forward): it is the [LadderSession] of the
 * [LadderRunner] and the [TransferLinkListener] of the [Transfer].
 *
 * Ladder → engine:
 * - [sendLinkReady] goes out on the transfer's control stream (§7.2).
 * - [openStream] hands the link to the engine as a [DataLink]: the joiner connects to the address and port of the host's
 *   `LinkReady`, the host accepts on its own port; the engine writes or checks the authenticated `StreamOpen` (S7) and
 *   returns once that first stream is up, then keeps the link's stream count at its target (§7.4).
 * - [sendLinkSelected] (the receiver, the ladder's authority) moves the control stream to the selected link with
 *   `ControlMoved` (N13), which the sender reads as the selection.
 * - The runner's [LadderState.dataLink] decides where the sender's data flows ([Transfer.useLink]; null is the
 *   Bluetooth stream). The sender, the follower, moves its own control stream once its ladder has accepted the link.
 *
 * Engine → ladder: the peer's `LinkReady` ([LadderRunner.onPeerLinkReady]), the peer's `ControlMoved`
 * ([LadderRunner.onPeerSelected]; the authority's runner ignores it), the 250 ms throughput samples (the receiver's
 * count of arrived bytes decides the LAN check), link losses, and the start and end of the transfer.
 *
 * Runs: the first run starts when the transfer starts streaming ([onTransferStarted]); every reconnect (a new session,
 * N3) closes the old run and starts a new one whose generation base is [LadderGenerations.PER_RUN] higher, so the new
 * run's `LinkReady` and `StreamOpen` generations never collide with the old ones. [runnerFactory] builds a run's runner
 * from this session and the run's generation base (the app computes the plan and `LadderNegotiation` agreement; on the
 * sender it reads [Transfer.accept] for the receiver's link intent).
 *
 * Install the bridge as [Transfer.linkListener] (directly or behind a listener that forwards every call) right after
 * `send` returns on the sender and before `accept` on the receiver, so it sees the start of the streaming.
 */
class LadderTransferBridge(
    private val transfer: Transfer,
    private val scope: CoroutineScope,
    private val runnerFactory: (session: LadderSession, generationBase: Int) -> LadderRunner,
) : LadderSession,
    TransferLinkListener {
    private val current = MutableStateFlow<LadderRunner?>(null)
    private val generations = MutableStateFlow<Map<ActiveLink, Int>>(emptyMap())

    @Volatile
    private var base = 0

    @Volatile
    private var followJob: Job? = null

    @Volatile
    private var started = false

    /** The runner of the current ladder run, or null between runs. */
    val runner: StateFlow<LadderRunner?> = current.asStateFlow()

    /** The generation base of the current run. */
    val generationBase: Int get() = base

    // ---- LadderSession ----

    override suspend fun sendLinkReady(message: LinkReady) = transfer.sendLinkReady(message)

    override suspend fun openStream(
        link: ActiveLink,
        peer: LinkReady,
    ) {
        val generation = peer.generation
        val freq = link.frequencyMhz ?: peer.freqMhz
        val dataLink =
            when (link.role) {
                LinkRole.JOIN -> {
                    val address = peer.address ?: throw LadderException("the host's LinkReady names no address")
                    val port = peer.port ?: throw LadderException("the host's LinkReady names no port")
                    DataLink(link.kind, generation, DataLinkRole.CONNECT, freq) { link.connect(address, port) }
                }

                LinkRole.HOST -> {
                    val port = link.localPort ?: throw LadderException("the hosted link has no listening port")
                    DataLink(link.kind, generation, DataLinkRole.ACCEPT, freq) { link.accept(port) }
                }
            }
        generations.update { it + (link to generation) }
        transfer.connectLink(dataLink, use = false)
    }

    override suspend fun sendLinkSelected(generation: Int) = transfer.moveControl(generation)

    // ---- TransferLinkListener ----

    override fun onPeerLinkReady(message: LinkReady) {
        current.value?.onPeerLinkReady(message)
    }

    override fun onPeerControlMoved(message: ControlMoved) {
        current.value?.onPeerSelected(message.generation)
    }

    override fun onThroughputSample(
        kind: LinkKind,
        bytes: Long,
    ) {
        current.value?.onThroughputSample(kind, bytes)
    }

    override fun onLinkLost(
        kind: LinkKind,
        generation: Int,
    ) {
        current.value?.onLinkLost(kind)
    }

    override fun onTransferStarted() {
        if (started) return
        started = true
        launchRun(0)
    }

    override fun onSessionLost(epoch: Int) {
        val old = current.value ?: return
        current.value = null
        followJob?.cancel()
        generations.value = emptyMap()
        scope.launch { old.closeAndAwait() }
    }

    override fun onSessionStarted(epoch: Int) {
        if (epoch == 0 || !started) return
        current.value?.let { old ->
            followJob?.cancel()
            scope.launch { old.closeAndAwait() }
        }
        base += LadderGenerations.PER_RUN
        launchRun(base)
    }

    override fun onTransferEnded(cancelled: Boolean) {
        followJob?.cancel()
        current.value?.onTransferEnded(cancelled = cancelled)
    }

    private fun launchRun(generationBase: Int) {
        val runner = runnerFactory(this, generationBase)
        current.value = runner
        followJob = scope.launch { follow(runner) }
        runner.start()
        runner.onTransferStarted()
    }

    /** Moves data (sender) and the follower's control stream as the runner's state says. */
    private suspend fun follow(runner: LadderRunner) {
        var dataGeneration: Int? = null
        var controlGeneration: Int? = null
        runner.state.collect { state ->
            val link = state.dataLink
            val generation = link?.let { generations.value[it] }
            if (link == null || generation != null) {
                if (generation != dataGeneration) {
                    dataGeneration = generation
                    transfer.useLink(generation)
                }
            }
            val index = state.dataIndex
            if (!state.plan.localIsAuthority && generation != null && index != null && generation != controlGeneration &&
                state.attempts[index].status == AttemptStatus.ACTIVE
            ) {
                controlGeneration = generation
                transfer.moveControl(generation)
            }
        }
    }
}
