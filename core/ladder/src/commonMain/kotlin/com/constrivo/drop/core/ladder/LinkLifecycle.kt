package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants

/** Where the ladder is (architecture §4). */
enum class LadderPhase {
    /** Wi-Fi rungs are being tried; Bluetooth (or a LAN on probation) carries the data meanwhile. */
    CONNECTING,

    /** A Wi-Fi link passed its checks and carries the data. */
    ACTIVE,

    /** No Wi-Fi link could be set up: the Bluetooth stream carries the whole transfer (§4 "badge = Bluetooth"). */
    BLUETOOTH_ONLY,

    /** No link at all: no Wi-Fi rung worked and there is no Bluetooth to fall back on (a browser or a wired desktop). */
    UNREACHABLE,

    /** Links are down and the previous network is being restored (F-E11: within 5 s). */
    TEARING_DOWN,

    /** The previous network is back; nothing more happens. */
    CLOSED,
}

/** Progress of one rung. */
enum class AttemptStatus {
    /** Not started yet. */
    PENDING,

    /** Being brought up: group forming, joining, or the LAN connecting; its deadline runs. */
    STARTING,

    /** Wi-Fi Direct connected but the channel frequency is not known yet; its deadline still runs. */
    VERIFYING,

    /** LAN connected and carrying data while its throughput is measured for 1 s (§4). */
    MEASURING,

    /** LAN measured below 10 MB/s: it keeps carrying data while a direct link is set up, and is used if none comes up. */
    STANDBY,

    /**
     * Follower only (the sender, [LadderPlan.localIsAuthority] false): up end to end on this device and waiting for the
     * authority to select it or another link ([LinkEvent.PeerSelected]). A follower never accepts a link on its own.
     */
    READY,

    /** Accepted: this link carries the data. */
    ACTIVE,

    /** Failed, timed out, was too slow or was lost ([LinkAttempt.endReason]). */
    FAILED,

    /** Stopped by the ladder: another link won, or the ladder was torn down. */
    STOPPED,
    ;

    /** Undecided and running: no other direct rung starts beside it, and a joiner that leaves its network waits for it. */
    val isLive: Boolean get() = this == STARTING || this == VERIFYING || this == MEASURING || this == READY

    val isEnded: Boolean get() = this == FAILED || this == STOPPED

    /** Up end to end on this device, so the engine can lose it ([LinkEvent.LinkLost]). */
    val isUp: Boolean get() = this == VERIFYING || this == MEASURING || this == STANDBY || this == READY || this == ACTIVE
}

/** Why a rung stopped (also the reason of a [LinkEffect.StopCandidate]). */
enum class LinkEndReason {
    /** Not up within its timeout (LAN 1 s to connect, Wi-Fi Direct 6 s, hotspot 6 s; §7.8). */
    TIMEOUT,

    /** The platform reported an error. */
    ERROR,

    /** No provider on this device can play the needed role. */
    UNSUPPORTED,

    /** The peer's credentials break the rules ([LinkCredentialsException]). */
    INVALID_CREDENTIALS,

    /** LAN slower than 10 MB/s, stopped so that a joiner could leave the network for a direct link. */
    SLOW,

    /** The link went down after it was up. */
    LOST,

    /** Another link was accepted first (the loser of the N9 race). */
    SUPERSEDED,

    /** The group came up on 2.4 GHz although both devices support 5 GHz: it is re-formed once (§9). */
    REFORM,

    /** The ladder is torn down (transfer complete, cancelled or idle; F-E11). */
    TEARDOWN,
}

/**
 * State of one rung.
 *
 * @property candidate the rung as it is brought up now: the plan's, except that a Wi-Fi Direct group re-formed after its
 *   accepted link was lost asks for 2.4 GHz (T-04).
 * @property tries formations started so far, at most [LinkLifecycle.MAX_FORMATIONS]; the current formation is attempt
 *   `tries - 1` (its link generation, [LadderGenerations]).
 * @property freqMhz the measured channel, from this device's provider or the peer's `LinkReady`.
 * @property measuredBytes LAN only: bytes moved during the measurement window (the authority's meter decides).
 * @property acceptedAtMillis when the current formation was accepted, if it was.
 */
data class LinkAttempt(
    val candidate: LinkCandidate,
    val status: AttemptStatus = AttemptStatus.PENDING,
    val tries: Int = 0,
    val startedAtMillis: Long? = null,
    val connectedAtMillis: Long? = null,
    val freqMhz: Int? = null,
    val measuredBytes: Long = 0,
    val endReason: LinkEndReason? = null,
    val acceptedAtMillis: Long? = null,
)

/** A link generation chosen by the authority: rung [index], formation [attempt] (`tries - 1`). */
data class LinkSelection(
    val index: Int,
    val attempt: Int,
)

/** Timers the reducer asks for; each comes back as [LinkEvent.TimerFired]. */
sealed interface LinkTimer {
    /**
     * Rung [index] must be up by then (LAN connect, Wi-Fi Direct formation, hotspot start and join); on the follower,
     * a rung that is up must be selected by then ([LadderTimeouts.selectionGraceMillis]).
     */
    data class Deadline(
        val index: Int,
    ) : LinkTimer

    /** End of the LAN measurement window of rung [index]. */
    data class Measure(
        val index: Int,
    ) : LinkTimer

    /** No transfer for 60 s: tear down (F-E11). */
    data object Idle : LinkTimer

    /** The 5 s restore budget (F-E11); firing marks the restore overdue for the log. */
    data object Restore : LinkTimer
}

/**
 * The timeouts of architecture §4 and §7.8; the defaults are the [ProtocolConstants] values.
 *
 * @property lanMinBytesPerSecond the LAN must move this much on average over the measurement window (10 MB/s).
 * @property selectionGraceMillis follower only: how long past its own deadline a rung that is up waits for the
 *   authority's selection. It covers the skew between the two devices' deadlines and the control message's latency,
 *   since the authority may accept a link whose channel it never learnt at its own deadline.
 */
data class LadderTimeouts(
    val lanConnectMillis: Long = ProtocolConstants.LAN_CONNECT_TIMEOUT_MS,
    val lanMeasureMillis: Long = ProtocolConstants.LAN_MEASURE_MS,
    val lanMinBytesPerSecond: Long = ProtocolConstants.LAN_MIN_BPS,
    val p2pFormationMillis: Long = ProtocolConstants.P2P_FORMATION_TIMEOUT_MS,
    val hotspotMillis: Long = ProtocolConstants.HOTSPOT_TIMEOUT_MS,
    val idleTeardownMillis: Long = ProtocolConstants.LINK_IDLE_TEARDOWN_MS,
    val restoreBudgetMillis: Long = ProtocolConstants.WIFI_RESTORE_BUDGET_MS,
    val selectionGraceMillis: Long = DEFAULT_SELECTION_GRACE_MS,
) {
    init {
        require(
            lanConnectMillis > 0 && lanMeasureMillis > 0 && lanMinBytesPerSecond > 0 && p2pFormationMillis > 0 &&
                hotspotMillis > 0 && idleTeardownMillis > 0 && restoreBudgetMillis > 0 && selectionGraceMillis > 0,
        ) { "timeouts and the LAN threshold must be positive" }
        require(lanMinBytesPerSecond <= Long.MAX_VALUE / lanMeasureMillis) { "LAN threshold overflows" }
    }

    /**
     * Bytes the LAN must move within the window: 10 MB in 1 s. Reaching it early accepts the LAN at once, which is
     * the same verdict as the full-window average.
     */
    val lanMinBytes: Long get() = (lanMinBytesPerSecond * lanMeasureMillis + 999) / 1000

    /** How long [mode] may take to come up. */
    fun connectTimeout(mode: LinkMode): Long =
        when (mode) {
            LinkMode.LAN -> lanConnectMillis
            LinkMode.P2P, LinkMode.P2P_LEGACY -> p2pFormationMillis
            LinkMode.HOTSPOT -> hotspotMillis
            LinkMode.BLUETOOTH -> throw IllegalArgumentException("Bluetooth is not set up by the ladder")
        }

    companion object {
        /** Default [selectionGraceMillis]: 2 s. */
        const val DEFAULT_SELECTION_GRACE_MS: Long = 2_000
    }
}

/**
 * The ladder's state, immutable.
 *
 * @property attempts one per [LadderPlan.candidates] entry, same order.
 * @property dataLink index of the rung carrying data now (an accepted link, or the LAN while measured or on standby);
 *   null means Bluetooth (or nothing).
 * @property hints the link conditions that hold now ([HintRules] picks what to show): `bt_fallback`, `lan_slow` and the
 *   band hint of the accepted link.
 * @property timers running timers and their deadlines; a [LinkEvent.TimerFired] for a timer not in here, or before its
 *   deadline, is stale and ignored.
 * @property transferRunning a transfer uses the link; when false the 60 s idle timer runs.
 * @property pendingSelection follower only: the authority selected a formation that is not up here yet; it is
 *   accepted as soon as it is.
 * @property restoreOverdue the previous network was not back within the 5 s budget (F-E11), for the log.
 */
data class LinkLifecycleState(
    val plan: LadderPlan,
    val phase: LadderPhase,
    val attempts: List<LinkAttempt>,
    val dataLink: Int? = null,
    val hints: List<LadderHint> = emptyList(),
    val timers: Map<LinkTimer, Long> = emptyMap(),
    val transferRunning: Boolean = true,
    val pendingSelection: LinkSelection? = null,
    val startedAtMillis: Long,
    val updatedAtMillis: Long = startedAtMillis,
    val activeAtMillis: Long? = null,
    val teardownStartedAtMillis: Long? = null,
    val restoredAtMillis: Long? = null,
    val restoreOverdue: Boolean = false,
) {
    init {
        require(attempts.size == plan.candidates.size) { "one attempt per candidate" }
        require(dataLink == null || dataLink in attempts.indices) { "data link $dataLink out of range" }
    }

    /** Index of the accepted link, if any. */
    val activeIndex: Int? get() = attempts.indexOfFirst { it.status == AttemptStatus.ACTIVE }.takeIf { it >= 0 }

    val dataAttempt: LinkAttempt? get() = dataLink?.let { attempts[it] }

    /** The badge for the link carrying data (F-F2): Bluetooth until a Wi-Fi link carries data. */
    val badge: TransportBadge?
        get() {
            if (phase == LadderPhase.TEARING_DOWN || phase == LadderPhase.CLOSED) return null
            val data = dataAttempt ?: return if (plan.hasBluetooth) TransportBadge.BLUETOOTH else null
            return TransportBadge.of(data.candidate.kind, data.freqMhz)
        }

    /** Time from the start of the teardown to the restored network (F-E11 budget: 5 s), once known. */
    val restoreMillis: Long? get() = restoredAtMillis?.let { end -> teardownStartedAtMillis?.let { end - it } }
}

/** Inputs of [LinkLifecycle.reduce]. Events naming a rung index that does not exist are ignored. */
sealed interface LinkEvent {
    /**
     * Rung [index] is up end to end: its first data stream connected and authenticated. [freqMhz] is the channel both
     * devices know at this point, null or 0 when neither knows it: the host's measurement, else the joiner's, each
     * device holding its own and the one in the peer's `LinkReady` ([LadderRunner] computes it). Both devices therefore
     * take the same re-form decision without another message.
     */
    data class Connected(
        val index: Int,
        val freqMhz: Int? = null,
    ) : LinkEvent

    /**
     * The channel of rung [index] was measured after it connected (this device's provider, or a repeated `LinkReady`
     * from the peer). It updates the badge and band hint and completes a pending verification, but never re-forms a
     * group: only [Connected] carries the frequency both devices share.
     */
    data class FrequencyReported(
        val index: Int,
        val freqMhz: Int,
    ) : LinkEvent

    /** [bytes] moved over rung [index] since the previous sample (the engine's 250 ms throughput samples, §7.4). */
    data class ThroughputSample(
        val index: Int,
        val bytes: Long,
    ) : LinkEvent {
        init {
            require(bytes >= 0) { "sample bytes must be non-negative" }
        }
    }

    /** Rung [index] failed while being set up (or after, which counts as [LinkLost]). */
    data class Failed(
        val index: Int,
        val reason: LinkEndReason = LinkEndReason.ERROR,
    ) : LinkEvent

    /** The link of rung [index] went down (socket closed, group removed, out of range). */
    data class LinkLost(
        val index: Int,
    ) : LinkEvent

    /**
     * Follower only: the authority (the receiver) selected formation [attempt] of rung [index] to carry the data
     * ([LadderRunner.onPeerSelected]). It is accepted now if it is up here, or as soon as it connects.
     */
    data class PeerSelected(
        val index: Int,
        val attempt: Int,
    ) : LinkEvent

    /** A timer requested with [LinkEffect.StartTimer] fired. */
    data class TimerFired(
        val timer: LinkTimer,
    ) : LinkEvent

    /** A transfer (the pre-warmed one, or the next queued one) starts using the link: the idle timer stops. */
    data object TransferStarted : LinkEvent

    /**
     * The transfer completed or was cancelled. With [moreQueued] the link stays up for the next transfer, for at most
     * the idle time; otherwise it is torn down now and the previous network restored within 5 s (F-E11).
     */
    data class TransferEnded(
        val cancelled: Boolean = false,
        val moreQueued: Boolean = false,
    ) : LinkEvent

    /** Every link is down and the previous network is back (the engine ran [LinkEffect.RestoreNetwork]). */
    data object RestoreCompleted : LinkEvent
}

/** What the runner must do after a transition, in order. */
sealed interface LinkEffect {
    /** Bring up rung [index]; [attempt] is 0 for the first formation and 1 for the re-form. */
    data class StartCandidate(
        val index: Int,
        val candidate: LinkCandidate,
        val attempt: Int,
    ) : LinkEffect

    /** Stop rung [index]: cancel its setup and tear down whatever of it is up. */
    data class StopCandidate(
        val index: Int,
        val reason: LinkEndReason,
    ) : LinkEffect

    /** Move the data streams to rung [index] (null: back to Bluetooth). Always comes before the old link's stop. */
    data class UseLink(
        val index: Int?,
    ) : LinkEffect

    /**
     * Authority only: tell the peer that formation [attempt] of rung [index] carries the data
     * ([LadderSession.sendLinkSelected]), so that it accepts the same link and cancels the same losers. Comes right
     * after the [UseLink] and before the losers' [StopCandidate]s.
     */
    data class AnnounceSelection(
        val index: Int,
        val attempt: Int,
    ) : LinkEffect

    data class StartTimer(
        val timer: LinkTimer,
        val atMillis: Long,
    ) : LinkEffect

    data class CancelTimer(
        val timer: LinkTimer,
    ) : LinkEffect

    /** Tear every link down and restore the previous network (F-E11); report [LinkEvent.RestoreCompleted]. */
    data object RestoreNetwork : LinkEffect
}

/**
 * Result of [LinkLifecycle.reduce]. [handled] is false when the event did not apply (a stale timer or duplicate, a
 * sample outside a measurement, anything after [LadderPhase.CLOSED]); the state is then unchanged and there are no
 * effects.
 */
data class LinkTransition(
    val state: LinkLifecycleState,
    val effects: List<LinkEffect>,
    val handled: Boolean = true,
)

/**
 * The link lifecycle of architecture §4, §7.8 and §9 with spec change N9, as a pure reducer: no clocks, no I/O. The
 * caller passes the current time; timers are requested as effects and come back as events.
 *
 * Rules:
 * - **One authority.** Both devices run this reducer, but only the receiver ([LadderPlan.localIsAuthority], S5)
 *   decides which link carries the data: it measures the LAN, accepts a link, and cancels the losers, and announces
 *   each acceptance ([LinkEffect.AnnounceSelection]). The sender follows: a link that is up there waits
 *   ([AttemptStatus.READY], or the LAN carrying data unmeasured) until [LinkEvent.PeerSelected] names it or another
 *   one, and the sender never supersedes a rung on its own measurement. Everything else (timeouts, failures, the order
 *   of the rungs, re-forms, teardown) follows from what both devices see, so the two stay in step without further
 *   messages.
 * - **Order.** Rungs start in plan order. The LAN starts first; a [LinkMode.P2P] rung starts beside it (N9 race), a
 *   rung whose joiner leaves its network waits for the LAN verdict. Only one direct rung (Wi-Fi Direct or hotspot)
 *   runs at a time; the next one starts when it fails. The first link accepted wins and every other running rung is
 *   stopped ([LinkEndReason.SUPERSEDED]). While nothing is accepted the Bluetooth stream carries the transfer.
 * - **LAN (F-E4).** 1 s to connect, then it carries data for a 1 s measurement on the authority's meter (bytes
 *   received): 10 MB in the window accepts it (at once, when reached early); less puts it on standby with `lan_slow`
 *   ("Switching to a direct link...") while a direct rung is tried. A standby LAN keeps carrying data, is stopped when
 *   a joiner must leave the network, and is accepted when no direct rung is left, since it still beats Bluetooth.
 * - **Wi-Fi Direct (§9).** 6 s to come up. A channel of 4900 MHz or more, or a pair that cannot do 5 GHz, accepts it.
 *   Otherwise the group is re-formed exactly once, and the second 2.4 GHz result is accepted with its hint:
 *   `sta_band24` when the host's station pins the channel (N9), `band24` otherwise, `peer_band24_only` when the peer
 *   has no 5 GHz ([BandHints]). The re-form is decided from the channel in [LinkEvent.Connected], which both devices
 *   share; a channel learnt only later accepts the link as it is, and one that never arrives accepts it at the
 *   deadline with an unknown band.
 * - **Hotspot.** 6 s to start and be joined; accepted as it comes, since apps cannot choose its band (N8).
 * - **Fall-through.** A rung that fails or times out gives way to the next one; with none left a LAN that was stopped
 *   while it worked (slow, or lost before a verdict) gets one more try, and then the transfer stays on Bluetooth
 *   (`bt_fallback` when Wi-Fi is off on one device), or the ladder is [LadderPhase.UNREACHABLE].
 * - **Loss.** When the accepted link is lost, every Wi-Fi rung that still has a spare formation (each gets
 *   [MAX_FORMATIONS] per run, which keeps two link generations per rung kind) and did not fail for good (no provider,
 *   bad credentials) is tried again in plan order: the race loser, a slow LAN, a rung that timed out earlier, and the
 *   lost link itself. A lost Wi-Fi Direct group is re-formed on 2.4 GHz, whose range is longer, and accepted with its
 *   hint (T-04).
 * - **Teardown (F-E11).** On `Complete` or `Cancel` with nothing queued, or after 60 s without a transfer (counted
 *   from a pre-warmed start, or from the end of the last transfer when more are queued), every link is stopped and
 *   [LinkEffect.RestoreNetwork] runs; the restore should finish within 5 s.
 */
class LinkLifecycle(
    val timeouts: LadderTimeouts = LadderTimeouts(),
) {
    /**
     * The initial state for [plan] at [nowMillis]: the first rungs start. With [transferRunning] false (pre-warm on
     * file pick, F-F4) the idle timer runs until [LinkEvent.TransferStarted].
     */
    fun start(
        plan: LadderPlan,
        nowMillis: Long,
        transferRunning: Boolean = true,
    ): LinkTransition {
        val state =
            LinkLifecycleState(
                plan = plan,
                phase = LadderPhase.CONNECTING,
                attempts = plan.candidates.map { LinkAttempt(it) },
                hints = plan.initialHints,
                transferRunning = transferRunning,
                startedAtMillis = nowMillis,
            )
        val tx = Tx(state, nowMillis)
        if (!transferRunning) tx.armIdle()
        tx.advance()
        return tx.result()
    }

    /** Applies [event] at [nowMillis]. Never throws; inapplicable events are not [LinkTransition.handled]. */
    fun reduce(
        state: LinkLifecycleState,
        event: LinkEvent,
        nowMillis: Long,
    ): LinkTransition {
        if (state.phase == LadderPhase.CLOSED) return ignore(state)
        val tx = Tx(state, nowMillis)
        val handled =
            when (event) {
                is LinkEvent.Connected -> tx.connected(event.index, event.freqMhz)
                is LinkEvent.FrequencyReported -> tx.frequency(event.index, event.freqMhz)
                is LinkEvent.ThroughputSample -> tx.sample(event.index, event.bytes)
                is LinkEvent.Failed -> tx.failed(event.index, event.reason)
                is LinkEvent.LinkLost -> tx.lost(event.index)
                is LinkEvent.PeerSelected -> tx.peerSelected(event.index, event.attempt)
                is LinkEvent.TimerFired -> tx.timerFired(event.timer)
                LinkEvent.TransferStarted -> tx.transferStarted()
                is LinkEvent.TransferEnded -> tx.transferEnded(event.moreQueued)
                LinkEvent.RestoreCompleted -> tx.restoreCompleted()
            }
        return if (handled) tx.result() else ignore(state)
    }

    private fun ignore(state: LinkLifecycleState) = LinkTransition(state, emptyList(), handled = false)

    /** One transition under construction: the evolving state and the effects in order. */
    private inner class Tx(
        var state: LinkLifecycleState,
        val now: Long,
    ) {
        val effects = ArrayList<LinkEffect>()

        fun result(): LinkTransition = LinkTransition(state.copy(updatedAtMillis = now), effects.toList())

        private val plan: LadderPlan get() = state.plan

        /** This device decides which link carries the data (the receiver, S5). */
        private val authority: Boolean get() = plan.localIsAuthority

        private fun attempt(index: Int): LinkAttempt = state.attempts[index]

        private fun valid(index: Int): Boolean = index in state.attempts.indices

        private fun set(
            index: Int,
            attempt: LinkAttempt,
        ) {
            state = state.copy(attempts = state.attempts.toMutableList().also { it[index] = attempt })
        }

        // ---- Events ----

        fun connected(
            index: Int,
            freqMhz: Int?,
        ): Boolean {
            if (!valid(index)) return false
            val a = attempt(index)
            if (a.status != AttemptStatus.STARTING) return false
            val shared = validFrequency(freqMhz)
            val up = a.copy(connectedAtMillis = now, freqMhz = shared ?: a.freqMhz)
            val selected = state.pendingSelection == LinkSelection(index, a.tries - 1)
            if (selected) state = state.copy(pendingSelection = null)
            when (a.candidate.mode) {
                LinkMode.LAN -> {
                    cancelTimer(LinkTimer.Deadline(index))
                    set(index, up.copy(status = AttemptStatus.MEASURING, measuredBytes = 0))
                    // The follower carries data on it unmeasured and waits for the authority's verdict.
                    if (authority) startTimer(LinkTimer.Measure(index), now + timeouts.lanMeasureMillis)
                    useLink(index)
                    if (selected) accept(index)
                }

                LinkMode.HOTSPOT -> {
                    set(index, up)
                    if (authority || selected) accept(index) else awaitSelection(index)
                }

                LinkMode.P2P, LinkMode.P2P_LEGACY -> {
                    set(index, up)
                    when {
                        selected -> accept(index)
                        shared != null && needsReform(up, shared) -> reform(index)
                        !authority -> awaitSelection(index)
                        !up.candidate.requestFiveGhz || up.freqMhz != null -> accept(index)
                        else -> set(index, up.copy(status = AttemptStatus.VERIFYING))
                    }
                }

                LinkMode.BLUETOOTH -> {
                    return false
                }
            }
            return true
        }

        fun frequency(
            index: Int,
            freqMhz: Int,
        ): Boolean {
            if (!valid(index)) return false
            val freq = validFrequency(freqMhz) ?: return false
            val a = attempt(index)
            when (a.status) {
                AttemptStatus.STARTING, AttemptStatus.MEASURING, AttemptStatus.STANDBY, AttemptStatus.READY -> {
                    set(index, a.copy(freqMhz = freq))
                }

                // Only the authority verifies; a channel learnt after the connection accepts the link as it is.
                AttemptStatus.VERIFYING -> {
                    set(index, a.copy(freqMhz = freq))
                    accept(index)
                }

                AttemptStatus.ACTIVE -> {
                    set(index, a.copy(freqMhz = freq))
                    setBandHint(bandHint(index))
                }

                else -> {
                    return false
                }
            }
            return true
        }

        fun sample(
            index: Int,
            bytes: Long,
        ): Boolean {
            if (!valid(index)) return false
            val a = attempt(index)
            if (a.status != AttemptStatus.MEASURING) return false
            val total = if (Long.MAX_VALUE - a.measuredBytes < bytes) Long.MAX_VALUE else a.measuredBytes + bytes
            set(index, a.copy(measuredBytes = total))
            // The follower's meter counts something else (bytes sent) and never decides.
            if (authority && total >= timeouts.lanMinBytes) accept(index)
            return true
        }

        fun failed(
            index: Int,
            reason: LinkEndReason,
        ): Boolean {
            if (!valid(index)) return false
            return when (attempt(index).status) {
                AttemptStatus.STARTING, AttemptStatus.VERIFYING, AttemptStatus.MEASURING, AttemptStatus.STANDBY, AttemptStatus.READY -> {
                    stop(index, reason, AttemptStatus.FAILED)
                    advance()
                    true
                }

                AttemptStatus.ACTIVE -> {
                    lost(index)
                }

                else -> {
                    false
                }
            }
        }

        fun lost(index: Int): Boolean {
            if (!valid(index)) return false
            val status = attempt(index).status
            if (status == AttemptStatus.PENDING || status.isEnded) return false
            stop(index, LinkEndReason.LOST, AttemptStatus.FAILED)
            if (status == AttemptStatus.ACTIVE) {
                setBandHint(null)
                state = state.copy(phase = LadderPhase.CONNECTING)
                reviveAfterLoss(index)
            }
            advance()
            return true
        }

        fun peerSelected(
            index: Int,
            selectedAttempt: Int,
        ): Boolean {
            if (authority || !valid(index) || !isRunning()) return false
            val a = attempt(index)
            if (!a.candidate.mode.isWifi || selectedAttempt !in 0 until MAX_FORMATIONS) return false
            val current = a.tries - 1
            if (selectedAttempt == current && a.status in SELECTABLE) {
                accept(index)
                return true
            }
            // Not up here yet (or a formation still to come): accept it the moment it connects.
            val stale = selectedAttempt < current || (selectedAttempt == current && (a.status.isEnded || a.status == AttemptStatus.ACTIVE))
            if (stale) return false
            val selection = LinkSelection(index, selectedAttempt)
            if (state.pendingSelection == selection) return false
            state = state.copy(pendingSelection = selection)
            return true
        }

        fun timerFired(timer: LinkTimer): Boolean {
            val deadline = state.timers[timer] ?: return false
            if (now < deadline) return false
            state = state.copy(timers = state.timers - timer)
            when (timer) {
                is LinkTimer.Deadline -> {
                    val index = timer.index
                    if (!valid(index)) return false
                    when (attempt(index).status) {
                        // Not up in time, or (follower) up but never selected by the authority.
                        AttemptStatus.STARTING, AttemptStatus.READY -> {
                            stop(index, LinkEndReason.TIMEOUT, AttemptStatus.FAILED)
                            advance()
                        }

                        // Up, but the channel was never reported: use it with an unknown band rather than drop it.
                        AttemptStatus.VERIFYING -> {
                            accept(index)
                        }

                        else -> {
                            return false
                        }
                    }
                }

                is LinkTimer.Measure -> {
                    val index = timer.index
                    if (!valid(index) || attempt(index).status != AttemptStatus.MEASURING) return false
                    slowLan(index)
                }

                LinkTimer.Idle -> {
                    if (state.transferRunning || !isRunning()) return false
                    teardown()
                }

                LinkTimer.Restore -> {
                    if (state.phase != LadderPhase.TEARING_DOWN) return false
                    state = state.copy(restoreOverdue = true)
                }
            }
            return true
        }

        fun transferStarted(): Boolean {
            if (!isRunning() || state.transferRunning) return false
            state = state.copy(transferRunning = true)
            cancelTimer(LinkTimer.Idle)
            return true
        }

        fun transferEnded(moreQueued: Boolean): Boolean {
            if (!isRunning()) return false
            state = state.copy(transferRunning = false)
            if (moreQueued) armIdle() else teardown()
            return true
        }

        fun restoreCompleted(): Boolean {
            if (state.phase != LadderPhase.TEARING_DOWN) return false
            cancelTimer(LinkTimer.Restore)
            state = state.copy(phase = LadderPhase.CLOSED, restoredAtMillis = now)
            return true
        }

        // ---- Rules ----

        /** Starts what may start now, or settles when nothing is left (class comment, "Order" and "Fall-through"). */
        fun advance() {
            if (state.phase != LadderPhase.CONNECTING) return
            val lan = plan.indexOf(LinkMode.LAN).takeIf { it >= 0 }
            if (lan != null && attempt(lan).status == AttemptStatus.PENDING) startAttempt(lan)
            val lanLive = lan != null && attempt(lan).status.isLive
            if (state.attempts.indices.any { it != lan && attempt(it).status.isLive }) return
            val standby = lan?.takeIf { attempt(it).status == AttemptStatus.STANDBY }
            val next =
                state.attempts.indices.firstOrNull {
                    attempt(it).status == AttemptStatus.PENDING &&
                        attempt(it).candidate.mode.isDirect
                }
            if (next != null) {
                if (attempt(next).candidate.mode.joinerLeavesNetwork) {
                    if (lanLive) return
                    if (standby != null) stop(standby, LinkEndReason.SLOW, AttemptStatus.FAILED)
                }
                startAttempt(next)
                return
            }
            if (lanLive) return
            if (standby != null) {
                accept(standby)
                return
            }
            if (lan != null && reviveStoppedLan(lan)) {
                advance()
                return
            }
            fallBack()
        }

        private fun startAttempt(index: Int) {
            val a = attempt(index)
            val tries = a.tries + 1
            set(
                index,
                a.copy(
                    status = AttemptStatus.STARTING,
                    tries = tries,
                    startedAtMillis = now,
                    connectedAtMillis = null,
                    freqMhz = null,
                    measuredBytes = 0,
                    endReason = null,
                    acceptedAtMillis = null,
                ),
            )
            effects += LinkEffect.StartCandidate(index, a.candidate, attempt = tries - 1)
            startTimer(LinkTimer.Deadline(index), now + timeouts.connectTimeout(a.candidate.mode))
        }

        /** The §9 check on the channel both devices share: 2.4 GHz when 5 GHz was asked for, with a formation to spare. */
        private fun needsReform(
            a: LinkAttempt,
            sharedFreqMhz: Int,
        ): Boolean = a.candidate.requestFiveGhz && !WifiBand.isFiveGhzOrAbove(sharedFreqMhz) && a.tries < MAX_FORMATIONS

        private fun reform(index: Int) {
            cancelTimer(LinkTimer.Deadline(index))
            effects += LinkEffect.StopCandidate(index, LinkEndReason.REFORM)
            startAttempt(index)
        }

        /** Follower: rung [index] is up here; it waits for the authority's selection, a little past its own deadline. */
        private fun awaitSelection(index: Int) {
            val deadline = state.timers[LinkTimer.Deadline(index)] ?: now
            cancelTimer(LinkTimer.Deadline(index))
            set(index, attempt(index).copy(status = AttemptStatus.READY))
            startTimer(LinkTimer.Deadline(index), maxOf(deadline, now) + timeouts.selectionGraceMillis)
        }

        private fun slowLan(index: Int) {
            set(index, attempt(index).copy(status = AttemptStatus.STANDBY))
            val switching = state.attempts.any { it.candidate.mode.isDirect && (it.status == AttemptStatus.PENDING || it.status.isLive) }
            if (switching) addHint(LadderHint.lanSlow())
            advance()
        }

        /**
         * The accepted link [lostIndex] went down: every Wi-Fi rung with a spare formation that did not fail for good
         * gets another try, in plan order ("Loss" in the class comment). Both devices see the loss and hold the same
         * formation counts, so they revive the same rungs.
         */
        private fun reviveAfterLoss(lostIndex: Int) {
            for (index in state.attempts.indices) {
                val a = attempt(index)
                if (!a.candidate.mode.isWifi || !a.status.isEnded || a.endReason in PERMANENT_FAILURES) continue
                if (a.tries >= MAX_FORMATIONS) continue
                // T-04: a group lost at the edge of range comes back on 2.4 GHz, which reaches further.
                val candidate =
                    if (index == lostIndex && a.candidate.kind == LinkKind.P2P) {
                        a.candidate.copy(requestFiveGhz = false)
                    } else {
                        a.candidate
                    }
                set(index, a.copy(candidate = candidate, status = AttemptStatus.PENDING))
            }
        }

        /**
         * Nothing else is left: a LAN that was stopped while it worked (slow, for a joiner that had to leave the network,
         * or lost before any verdict, which is how the follower sees that stop) is tried once more, since it beats
         * Bluetooth.
         */
        private fun reviveStoppedLan(lan: Int): Boolean {
            val a = attempt(lan)
            val stoppedWhileWorking =
                a.status.isEnded &&
                    (a.endReason == LinkEndReason.SLOW || (a.endReason == LinkEndReason.LOST && a.acceptedAtMillis == null))
            if (!stoppedWhileWorking || a.tries >= MAX_FORMATIONS) return false
            set(lan, a.copy(status = AttemptStatus.PENDING))
            return true
        }

        private fun accept(index: Int) {
            cancelTimer(LinkTimer.Deadline(index))
            cancelTimer(LinkTimer.Measure(index))
            val a = attempt(index)
            set(index, a.copy(status = AttemptStatus.ACTIVE, acceptedAtMillis = now))
            useLink(index)
            if (authority) effects += LinkEffect.AnnounceSelection(index, a.tries - 1)
            for (other in state.attempts.indices) {
                val status = attempt(other).status
                if (other != index && (status.isLive || status == AttemptStatus.STANDBY)) {
                    stop(other, LinkEndReason.SUPERSEDED, AttemptStatus.STOPPED)
                }
            }
            state = state.copy(phase = LadderPhase.ACTIVE, activeAtMillis = now, pendingSelection = null)
            removeHint(HintCode.LAN_SLOW)
            removeHint(HintCode.BT_FALLBACK)
            setBandHint(bandHint(index))
        }

        private fun fallBack() {
            removeHint(HintCode.LAN_SLOW)
            useLink(null)
            if (plan.hasBluetooth) {
                state = state.copy(phase = LadderPhase.BLUETOOTH_ONLY)
                if (plan.wifiOffOnOneDevice) addHint(LadderHint.btFallback())
            } else {
                state = state.copy(phase = LadderPhase.UNREACHABLE)
            }
        }

        private fun teardown() {
            for (index in state.attempts.indices) {
                val status = attempt(index).status
                if (status != AttemptStatus.PENDING && !status.isEnded) stop(index, LinkEndReason.TEARDOWN, AttemptStatus.STOPPED)
            }
            useLink(null)
            for (timer in state.timers.keys.toList()) cancelTimer(timer)
            state =
                state.copy(
                    phase = LadderPhase.TEARING_DOWN,
                    teardownStartedAtMillis = now,
                    hints = emptyList(),
                    pendingSelection = null,
                )
            effects += LinkEffect.RestoreNetwork
            startTimer(LinkTimer.Restore, now + timeouts.restoreBudgetMillis)
        }

        /** Ends rung [index]: data leaves it first (make before break), then its timers stop and it is torn down. */
        private fun stop(
            index: Int,
            reason: LinkEndReason,
            status: AttemptStatus,
        ) {
            if (state.dataLink == index) useLink(null)
            cancelTimer(LinkTimer.Deadline(index))
            cancelTimer(LinkTimer.Measure(index))
            set(index, attempt(index).copy(status = status, endReason = reason))
            if (state.pendingSelection?.index == index && state.pendingSelection?.attempt == attempt(index).tries - 1) {
                state = state.copy(pendingSelection = null)
            }
            effects += LinkEffect.StopCandidate(index, reason)
        }

        fun armIdle() = startTimer(LinkTimer.Idle, now + timeouts.idleTeardownMillis)

        private fun isRunning(): Boolean = state.phase != LadderPhase.TEARING_DOWN && state.phase != LadderPhase.CLOSED

        private fun useLink(index: Int?) {
            if (state.dataLink == index) return
            state = state.copy(dataLink = index)
            effects += LinkEffect.UseLink(index)
        }

        private fun startTimer(
            timer: LinkTimer,
            atMillis: Long,
        ) {
            state = state.copy(timers = state.timers + (timer to atMillis))
            effects += LinkEffect.StartTimer(timer, atMillis)
        }

        private fun cancelTimer(timer: LinkTimer) {
            if (timer !in state.timers) return
            state = state.copy(timers = state.timers - timer)
            effects += LinkEffect.CancelTimer(timer)
        }

        private fun bandHint(index: Int): LadderHint? {
            val a = attempt(index)
            val input = plan.input
            return BandHints.forLink(
                kind = a.candidate.kind,
                freqMhz = a.freqMhz,
                localSupportsFiveGhz = input.local.supportsFiveGhz,
                peerSupportsFiveGhz = input.peer.supportsFiveGhz,
                host = a.candidate.host,
                hostStationOn24 = a.candidate.hostStationOn24,
                peerName = input.peerName,
            )
        }

        private fun addHint(hint: LadderHint) {
            if (state.hints.none { it.code == hint.code }) state = state.copy(hints = state.hints + hint)
        }

        private fun removeHint(code: HintCode) {
            if (state.hints.any { it.code == code }) state = state.copy(hints = state.hints.filterNot { it.code == code })
        }

        private fun setBandHint(hint: LadderHint?) {
            state = state.copy(hints = state.hints.filterNot { it.code in BAND_CODES } + listOfNotNull(hint))
        }
    }

    companion object {
        /** Formations per rung and ladder run: the first plus one more (the §9 re-form, or a retry after a loss). */
        const val MAX_FORMATIONS: Int = 2

        private val BAND_CODES = setOf(HintCode.BAND24, HintCode.PEER_BAND24_ONLY, HintCode.STATION_BAND24)

        /** Failures another try cannot fix on this device. */
        private val PERMANENT_FAILURES = setOf(LinkEndReason.UNSUPPORTED, LinkEndReason.INVALID_CREDENTIALS)

        /** Follower states in which the authority's selection is accepted at once. */
        private val SELECTABLE =
            setOf(AttemptStatus.READY, AttemptStatus.VERIFYING, AttemptStatus.MEASURING, AttemptStatus.STANDBY)

        /** A usable channel frequency, or null for an unknown one (`LinkReady.freq_mhz` = 0) or garbage. */
        internal fun validFrequency(freqMhz: Int?): Int? = freqMhz?.takeIf { it in 1..MAX_FREQ_MHZ }

        private const val MAX_FREQ_MHZ = 100_000
    }
}
