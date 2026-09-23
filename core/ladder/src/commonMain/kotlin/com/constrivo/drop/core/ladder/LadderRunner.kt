package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkReady
import com.constrivo.drop.core.protocol.WifiCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the ladder needs from the transfer engine (WP4): the control stream and the first data stream of each link.
 */
interface LadderSession {
    /** Sends [message] to the peer on the control stream (§7.2). */
    suspend fun sendLinkReady(message: LinkReady)

    /**
     * Opens the first authenticated data stream over [link] (`StreamOpen`, §7.1), which proves the path end to end: the
     * joiner connects to the address and port in the host's [peer] `LinkReady`, the host accepts on its own port.
     * Returns once the stream is connected; throws when it cannot be; is cancelled when the ladder gives up on the link.
     */
    suspend fun openStream(
        link: ActiveLink,
        peer: LinkReady,
    )
}

/**
 * Link generations (`LinkReady.generation`, named again by `StreamOpen`) for one ladder run: [PER_RUN] numbers from the
 * run's base, two per rung kind (first formation, re-form). Both devices derive the same number for the same rung and
 * attempt even when one of them skipped a rung, so the peer's `LinkReady` is matched without extra messages. A new run
 * (after a reconnect) starts at the next base, `base + PER_RUN`.
 */
object LadderGenerations {
    const val PER_RUN: Int = 6

    fun of(
        base: Int,
        mode: LinkMode,
        attempt: Int,
    ): Int {
        require(base >= 0 && base <= Int.MAX_VALUE - PER_RUN) { "generation base $base out of range" }
        require(attempt in 0 until LinkLifecycle.MAX_FORMATIONS) { "attempt $attempt out of range" }
        val slot =
            when (mode) {
                LinkMode.LAN -> 0
                LinkMode.P2P, LinkMode.P2P_LEGACY -> 1
                LinkMode.HOTSPOT -> 2
                LinkMode.BLUETOOTH -> throw IllegalArgumentException("Bluetooth has no link generation")
            }
        return base + slot * LinkLifecycle.MAX_FORMATIONS + attempt
    }

    /** The link kind a generation of the run at [base] belongs to, or null when it is outside the run. */
    fun kindOf(
        base: Int,
        generation: Int,
    ): LinkKind? =
        when ((generation.toLong() - base).takeIf { it in 0 until PER_RUN }?.toInt()?.div(LinkLifecycle.MAX_FORMATIONS)) {
            0 -> LinkKind.LAN
            1 -> LinkKind.P2P
            2 -> LinkKind.HOTSPOT
            else -> null
        }
}

/**
 * How a [LadderRunner] runs its plan.
 *
 * @property generationBase first link generation of this run ([LadderGenerations]).
 * @property p2pCredentials the Wi-Fi Direct credentials agreed in `Offer` / `Accept` (S5): this device's own when it
 *   is the group owner, the peer's when it joins and they were pre-shared. Null while joining means they arrive in the
 *   host's `LinkReady`. [LadderNegotiation] produces them.
 * @property announceCredentials the group owner puts [p2pCredentials] in its `LinkReady`, because the peer does not
 *   have them yet (the owner was decided by the `Accept`). The hotspot's credentials are always announced (N15).
 * @property persistent a trusted pair: persistent groups and remembered network approvals (F-F5, N7).
 * @property prewarm the link is brought up before a transfer runs (F-F4); the 60 s idle teardown applies until
 *   [LadderRunner.onTransferStarted].
 */
data class LadderConfig(
    val generationBase: Int = 0,
    val p2pCredentials: WifiCredentials? = null,
    val announceCredentials: Boolean = false,
    val persistent: Boolean = false,
    val prewarm: Boolean = false,
) {
    init {
        require(generationBase >= 0 && generationBase <= Int.MAX_VALUE - LadderGenerations.PER_RUN) { "generation base out of range" }
    }
}

/**
 * What a [LadderRunner] publishes: the lifecycle state plus the link data flows on.
 *
 * @property started false until [LadderRunner.start] ran.
 * @property dataLink the link the engine must send and receive data on; null means the Bluetooth stream.
 * @property badge the transport badge (F-F2).
 * @property hints link conditions for [HintRules] ([HintInputs.from]).
 */
data class LadderState(
    val plan: LadderPlan,
    val started: Boolean,
    val phase: LadderPhase,
    val attempts: List<LinkAttempt>,
    val dataIndex: Int?,
    val dataLink: ActiveLink?,
    val badge: TransportBadge?,
    val hints: List<LadderHint>,
    val restoreOverdue: Boolean = false,
    val restoreMillis: Long? = null,
) {
    val dataCandidate: LinkCandidate? get() = dataIndex?.let { plan.candidates[it] }

    /** Measured channel of the link carrying data, when known. */
    val freqMhz: Int? get() = dataIndex?.let { attempts[it].freqMhz }

    companion object {
        internal fun initial(plan: LadderPlan) =
            LadderState(
                plan = plan,
                started = false,
                phase = LadderPhase.CONNECTING,
                attempts = plan.candidates.map { LinkAttempt(it) },
                dataIndex = null,
                dataLink = null,
                badge = if (plan.hasBluetooth) TransportBadge.BLUETOOTH else null,
                hints = plan.initialHints,
            )
    }
}

/** Ladder events for the local ring-buffer log (architecture §14). [atMillis] is on the runner's monotonic clock. */
sealed interface LadderLogEvent {
    val atMillis: Long

    data class CandidateStarted(
        override val atMillis: Long,
        val index: Int,
        val mode: LinkMode,
        val attempt: Int,
        val generation: Int,
    ) : LadderLogEvent

    data class CandidateStopped(
        override val atMillis: Long,
        val index: Int,
        val mode: LinkMode,
        val reason: LinkEndReason,
    ) : LadderLogEvent

    /** Rung [index] failed on this device: [reason] and the platform's [message]. */
    data class CandidateError(
        override val atMillis: Long,
        val index: Int,
        val mode: LinkMode,
        val reason: LinkEndReason,
        val message: String?,
    ) : LadderLogEvent

    data class PhaseChanged(
        override val atMillis: Long,
        val phase: LadderPhase,
    ) : LadderLogEvent

    /** Data moved to [kind] ([LinkKind.BLUETOOTH] or null: back to the Bluetooth stream, or none). */
    data class DataLinkChanged(
        override val atMillis: Long,
        val kind: LinkKind?,
        val freqMhz: Int?,
        val badge: TransportBadge?,
    ) : LadderLogEvent

    data class HintsChanged(
        override val atMillis: Long,
        val hints: List<LadderHint>,
    ) : LadderLogEvent

    /** A `LinkReady` from the peer that belongs to no rung of this run. */
    data class PeerLinkReadyIgnored(
        override val atMillis: Long,
        val generation: Int,
        val kind: String,
    ) : LadderLogEvent

    /** A link's teardown threw; the restore carries on with the rest. */
    data class TeardownError(
        override val atMillis: Long,
        val mode: LinkMode,
        val message: String?,
    ) : LadderLogEvent

    /** The 5 s restore budget passed before the previous network was back (F-E11). */
    data class RestoreOverdue(
        override val atMillis: Long,
    ) : LadderLogEvent

    data class RestoreFinished(
        override val atMillis: Long,
        val durationMillis: Long,
        val overdue: Boolean,
    ) : LadderLogEvent
}

/**
 * Runs a [LadderPlan] against the platform's [WifiLinkProvider]s (architecture §4), driven by the [LinkLifecycle]
 * reducer: it starts rungs, races the LAN probe against Wi-Fi Direct formation when the plan says so and cancels the
 * loser (N9), enforces the timeouts, re-forms a 2.4 GHz group once, tears everything down and restores the previous
 * network when the transfer ends (F-E11), and publishes [state] and a log of [events].
 *
 * Each rung runs as: host or join with the provider; announce it with `LinkReady` (with the measured frequency, and
 * credentials the peer does not have yet); wait for the peer's `LinkReady` of the same generation; open the first data
 * stream through [session]; report it connected. The engine then moves data to [LadderState.dataLink] and feeds
 * [onThroughputSample] (needed for the LAN check), [onLinkLost] and the transfer events.
 *
 * All inputs are thread-safe. Time comes from [clock] and the dispatcher of [scope]; tests pass a virtual clock and
 * dispatcher. Call [start] once. End the run with [onTransferEnded] or [close] before cancelling [scope]: a cancelled
 * scope skips the teardown.
 */
class LadderRunner(
    val plan: LadderPlan,
    private val providers: List<WifiLinkProvider>,
    private val session: LadderSession,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val config: LadderConfig = LadderConfig(),
    private val lifecycle: LinkLifecycle = LinkLifecycle(),
) {
    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(LadderState.initial(plan))
    private val mutableEvents =
        MutableSharedFlow<LadderLogEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val peerReady = MutableStateFlow<Map<Int, LinkReady>>(emptyMap())

    /** The ladder as it is now. */
    val state: StateFlow<LadderState> = mutableState.asStateFlow()

    /** Log events; slow collectors lose the oldest ones. */
    val events: SharedFlow<LadderLogEvent> = mutableEvents.asSharedFlow()

    // Confined to the actor coroutine below.
    private var current: LinkLifecycleState? = null
    private val attempts = HashMap<Int, AttemptHandle>()
    private val teardowns = HashMap<Int, Job>()
    private val timerJobs = HashMap<LinkTimer, Job>()

    init {
        scope.launch {
            for (input in inputs) handle(input)
        }
    }

    /** Starts the ladder: the first rungs come up and the Bluetooth stream carries data meanwhile. Idempotent. */
    fun start() {
        inputs.trySend(Input.Start)
    }

    /** The peer announced a link (§7.2). Messages that belong to no rung of this run are logged and dropped. */
    fun onPeerLinkReady(message: LinkReady) {
        val kind = LadderGenerations.kindOf(config.generationBase, message.generation)
        if (kind == null || message.linkKind != kind) {
            emit(LadderLogEvent.PeerLinkReadyIgnored(now(), message.generation, message.kind))
            return
        }
        peerReady.update { it + (message.generation to message) }
    }

    /** [bytes] moved over the [kind] link since the previous sample (the engine's 250 ms samples, §7.4). */
    fun onThroughputSample(
        kind: LinkKind,
        bytes: Long,
    ) {
        if (bytes >= 0) inputs.trySend(Input.Sample(kind, bytes))
    }

    /** The [kind] link went down (streams failed, the group disappeared). */
    fun onLinkLost(kind: LinkKind) {
        inputs.trySend(Input.Lost(kind))
    }

    /** A transfer starts using the link (the pre-warmed one, or the next queued one). */
    fun onTransferStarted() {
        inputs.trySend(Input.Event(LinkEvent.TransferStarted))
    }

    /** The transfer completed or was cancelled; without [moreQueued] the links go and the previous network returns within 5 s. */
    fun onTransferEnded(
        cancelled: Boolean = false,
        moreQueued: Boolean = false,
    ) {
        inputs.trySend(Input.Event(LinkEvent.TransferEnded(cancelled, moreQueued)))
    }

    /** Tears everything down now and restores the previous network. */
    fun close() = onTransferEnded(cancelled = true)

    /** Suspends until the previous network is back ([LadderPhase.CLOSED]). */
    suspend fun awaitClosed(): LadderState = state.first { it.phase == LadderPhase.CLOSED }

    // ---- Actor ----

    private sealed interface Input {
        data object Start : Input

        data class Event(
            val event: LinkEvent,
        ) : Input

        /** From rung coroutine [handle]; dropped when that rung has been stopped or re-formed since. */
        class FromAttempt(
            val handle: AttemptHandle,
            val event: LinkEvent,
        ) : Input

        data class Sample(
            val kind: LinkKind,
            val bytes: Long,
        ) : Input

        data class Lost(
            val kind: LinkKind,
        ) : Input
    }

    /** One formation of one rung; [link] is set once the provider returned it (read after [job] completes, or by the actor). */
    private class AttemptHandle(
        val index: Int,
        val candidate: LinkCandidate,
        val attempt: Int,
    ) {
        lateinit var job: Job
        val link = MutableStateFlow<ActiveLink?>(null)
    }

    private fun handle(input: Input) {
        val state = current
        when (input) {
            Input.Start -> {
                if (state == null) apply(lifecycle.start(plan, now(), transferRunning = !config.prewarm))
            }

            is Input.Event -> {
                reduce(input.event)
            }

            is Input.FromAttempt -> {
                if (attempts[input.handle.index] === input.handle) reduce(input.event)
            }

            is Input.Sample -> {
                val index = state?.attempts?.indexOfFirst { it.candidate.kind == input.kind && it.status == AttemptStatus.MEASURING }
                if (index != null && index >= 0) reduce(LinkEvent.ThroughputSample(index, input.bytes))
            }

            is Input.Lost -> {
                val index = state?.attempts?.indexOfFirst { it.candidate.kind == input.kind && it.status in UP }
                if (index != null && index >= 0) reduce(LinkEvent.LinkLost(index))
            }
        }
    }

    private fun reduce(event: LinkEvent) {
        val state = current ?: return
        val transition = lifecycle.reduce(state, event, now())
        if (transition.handled) apply(transition)
    }

    private fun apply(transition: LinkTransition) {
        val before = current
        val after = transition.state
        current = after
        for (effect in transition.effects) execute(effect)
        publish(before, after)
        if (after.phase == LadderPhase.CLOSED) {
            timerJobs.values.forEach { it.cancel() }
            timerJobs.clear()
            inputs.close()
        }
    }

    /** Runs one effect. [LinkEffect.UseLink] needs nothing here: [publish] reads the data link from the new state. */
    private fun execute(effect: LinkEffect) {
        when (effect) {
            is LinkEffect.StartCandidate -> startAttempt(effect)
            is LinkEffect.StopCandidate -> stopAttempt(effect.index, effect.reason)
            is LinkEffect.UseLink -> Unit
            is LinkEffect.StartTimer -> startTimer(effect.timer, effect.atMillis)
            is LinkEffect.CancelTimer -> timerJobs.remove(effect.timer)?.cancel()
            LinkEffect.RestoreNetwork -> restore()
        }
    }

    private fun startAttempt(effect: LinkEffect.StartCandidate) {
        val handle = AttemptHandle(effect.index, effect.candidate, effect.attempt)
        val previousTeardown = teardowns[effect.index]
        attempts[effect.index] = handle
        emit(
            LadderLogEvent.CandidateStarted(
                now(),
                effect.index,
                effect.candidate.mode,
                effect.attempt,
                LadderGenerations.of(config.generationBase, effect.candidate.mode, effect.attempt),
            ),
        )
        handle.job =
            scope.launch {
                previousTeardown?.join() // a re-form waits until the first group is gone
                runAttempt(handle)
            }
    }

    private fun stopAttempt(
        index: Int,
        reason: LinkEndReason,
    ) {
        val handle = attempts.remove(index) ?: return
        handle.job.cancel()
        emit(LadderLogEvent.CandidateStopped(now(), index, handle.candidate.mode, reason))
        val previous = teardowns[index]
        teardowns[index] =
            scope.launch {
                previous?.join()
                handle.job.join()
                handle.link.value?.let { teardown(handle.candidate.mode, it) }
            }
    }

    private fun restore() {
        val handles = attempts.values.toList()
        attempts.clear()
        handles.forEach { it.job.cancel() }
        val pending = teardowns.values.toList()
        teardowns.clear()
        scope.launch {
            for (handle in handles) {
                handle.job.join()
                handle.link.value?.let { teardown(handle.candidate.mode, it) }
            }
            pending.forEach { it.join() }
            inputs.trySend(Input.Event(LinkEvent.RestoreCompleted))
        }
    }

    private fun startTimer(
        timer: LinkTimer,
        atMillis: Long,
    ) {
        timerJobs.remove(timer)?.cancel()
        timerJobs[timer] =
            scope.launch {
                delay((atMillis - now()).coerceAtLeast(0))
                inputs.trySend(Input.Event(LinkEvent.TimerFired(timer)))
            }
    }

    /** One formation of one rung, from the provider call to the first connected stream. */
    private suspend fun runAttempt(handle: AttemptHandle) {
        val index = handle.index
        val candidate = handle.candidate
        val role = candidate.localRole ?: return
        val generation = LadderGenerations.of(config.generationBase, candidate.mode, handle.attempt)

        fun report(event: LinkEvent) {
            inputs.trySend(Input.FromAttempt(handle, event))
        }

        fun fail(
            reason: LinkEndReason,
            message: String?,
        ) {
            emit(LadderLogEvent.CandidateError(now(), index, candidate.mode, reason, message))
            report(LinkEvent.Failed(index, reason))
        }

        try {
            val provider = providers.firstOrNull { it.kind == candidate.kind && it.supports(candidate.mode, role) }
            if (provider == null) return fail(LinkEndReason.UNSUPPORTED, "no provider can ${role.name.lowercase()} ${candidate.mode}")

            val link =
                if (role == LinkRole.HOST) {
                    val credentials =
                        if (candidate.kind == LinkKind.P2P) {
                            config.p2pCredentials?.let(P2pCredentials::requireValidGroup)
                                ?: return fail(LinkEndReason.INVALID_CREDENTIALS, "no Wi-Fi Direct credentials to host with")
                        } else {
                            null
                        }
                    provider.host(HostRequest(candidate.mode, credentials, candidate.requestFiveGhz, config.persistent, handle.attempt))
                } else {
                    val credentials =
                        when (candidate.mode) {
                            LinkMode.LAN -> {
                                null
                            }

                            LinkMode.P2P, LinkMode.P2P_LEGACY -> {
                                P2pCredentials.requireValidGroup(config.p2pCredentials ?: peerCredentials(generation))
                            }

                            LinkMode.HOTSPOT -> {
                                P2pCredentials.requireValidHotspot(peerCredentials(generation))
                            }

                            LinkMode.BLUETOOTH -> {
                                return
                            }
                        }
                    val known = peerReady.value[generation]
                    provider.join(
                        JoinRequest(
                            candidate.mode,
                            credentials,
                            candidate.requestFiveGhz,
                            config.persistent,
                            known?.address,
                            known?.port,
                            handle.attempt,
                        ),
                    )
                }
            handle.link.value = link

            val ownFreq = LinkLifecycle.validFrequency(link.frequencyMhz)
            if (ownFreq != null) report(LinkEvent.FrequencyReported(index, ownFreq))
            val announced =
                when {
                    role != LinkRole.HOST || !candidate.mode.isDirect -> {
                        null
                    }

                    candidate.mode == LinkMode.HOTSPOT -> {
                        link.credentials
                            ?: throw LadderException("the hotspot provider returned no credentials")
                    }

                    config.announceCredentials -> {
                        link.credentials ?: config.p2pCredentials
                    }

                    else -> {
                        null
                    }
                }
            session.sendLinkReady(LinkReady(candidate.kind, link.localAddress, link.localPort, ownFreq ?: 0, announced, generation))

            val peer = awaitPeer(generation)
            val peerFreq = LinkLifecycle.validFrequency(peer.freqMhz)
            if (ownFreq == null && peerFreq != null) report(LinkEvent.FrequencyReported(index, peerFreq))
            session.openStream(link, peer)
            report(LinkEvent.Connected(index, ownFreq ?: peerFreq))
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkCredentialsException) {
            fail(LinkEndReason.INVALID_CREDENTIALS, e.message)
        } catch (e: Exception) {
            fail(LinkEndReason.ERROR, e.message ?: e::class.simpleName)
        }
    }

    private suspend fun peerCredentials(generation: Int): WifiCredentials =
        awaitPeer(generation).credentials ?: throw LinkCredentialsException("the host announced no credentials")

    private suspend fun awaitPeer(generation: Int): LinkReady = peerReady.map { it[generation] }.filterNotNull().first()

    private suspend fun teardown(
        mode: LinkMode,
        link: ActiveLink,
    ) {
        try {
            withContext(NonCancellable) { link.teardown() }
        } catch (e: Exception) {
            emit(LadderLogEvent.TeardownError(now(), mode, e.message ?: e::class.simpleName))
        }
    }

    private fun publish(
        before: LinkLifecycleState?,
        after: LinkLifecycleState,
    ) {
        val at = now()
        val dataLink = after.dataLink?.let { attempts[it]?.link?.value }
        val previous = mutableState.value
        val next =
            LadderState(
                plan = plan,
                started = true,
                phase = after.phase,
                attempts = after.attempts,
                dataIndex = after.dataLink,
                dataLink = dataLink,
                badge = after.badge,
                hints = after.hints,
                restoreOverdue = after.restoreOverdue,
                restoreMillis = after.restoreMillis,
            )
        mutableState.value = next
        if (before == null || before.phase != after.phase) emit(LadderLogEvent.PhaseChanged(at, after.phase))
        if (previous.dataIndex != next.dataIndex || previous.badge != next.badge) {
            emit(LadderLogEvent.DataLinkChanged(at, next.dataCandidate?.kind, next.freqMhz, next.badge))
        }
        if (previous.hints != next.hints) emit(LadderLogEvent.HintsChanged(at, next.hints))
        if (before?.restoreOverdue == false && after.restoreOverdue) emit(LadderLogEvent.RestoreOverdue(at))
        if (before?.phase == LadderPhase.TEARING_DOWN && after.phase == LadderPhase.CLOSED) {
            emit(LadderLogEvent.RestoreFinished(at, after.restoreMillis ?: 0, after.restoreOverdue))
        }
    }

    private fun emit(event: LadderLogEvent) {
        mutableEvents.tryEmit(event)
    }

    private fun now(): Long = clock.elapsedMillis()

    private companion object {
        const val EVENT_BUFFER = 256
        val UP = setOf(AttemptStatus.VERIFYING, AttemptStatus.MEASURING, AttemptStatus.STANDBY, AttemptStatus.ACTIVE)
    }
}
