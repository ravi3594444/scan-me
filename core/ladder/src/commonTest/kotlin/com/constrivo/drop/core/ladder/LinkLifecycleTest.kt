package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.ladder.AttemptStatus.ACTIVE
import com.constrivo.drop.core.ladder.AttemptStatus.FAILED
import com.constrivo.drop.core.ladder.AttemptStatus.MEASURING
import com.constrivo.drop.core.ladder.AttemptStatus.PENDING
import com.constrivo.drop.core.ladder.AttemptStatus.READY
import com.constrivo.drop.core.ladder.AttemptStatus.STANDBY
import com.constrivo.drop.core.ladder.AttemptStatus.STARTING
import com.constrivo.drop.core.ladder.AttemptStatus.STOPPED
import com.constrivo.drop.core.ladder.AttemptStatus.VERIFYING
import com.constrivo.drop.core.ladder.Caps.onWifi
import com.constrivo.drop.core.ladder.LinkEffect.AnnounceSelection
import com.constrivo.drop.core.ladder.LinkEffect.CancelTimer
import com.constrivo.drop.core.ladder.LinkEffect.RestoreNetwork
import com.constrivo.drop.core.ladder.LinkEffect.StartCandidate
import com.constrivo.drop.core.ladder.LinkEffect.StartTimer
import com.constrivo.drop.core.ladder.LinkEffect.StopCandidate
import com.constrivo.drop.core.ladder.LinkEffect.UseLink
import com.constrivo.drop.core.ladder.LinkEvent.Connected
import com.constrivo.drop.core.ladder.LinkEvent.Failed
import com.constrivo.drop.core.ladder.LinkEvent.FrequencyReported
import com.constrivo.drop.core.ladder.LinkEvent.LinkLost
import com.constrivo.drop.core.ladder.LinkEvent.PeerSelected
import com.constrivo.drop.core.ladder.LinkEvent.RestoreCompleted
import com.constrivo.drop.core.ladder.LinkEvent.ThroughputSample
import com.constrivo.drop.core.ladder.LinkEvent.TimerFired
import com.constrivo.drop.core.ladder.LinkEvent.TransferEnded
import com.constrivo.drop.core.ladder.LinkEvent.TransferStarted
import com.constrivo.drop.core.ladder.LinkTimer.Deadline
import com.constrivo.drop.core.ladder.LinkTimer.Idle
import com.constrivo.drop.core.ladder.LinkTimer.Measure
import com.constrivo.drop.core.ladder.LinkTimer.Restore
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.ProtocolConstants.HOTSPOT_TIMEOUT_MS
import com.constrivo.drop.core.protocol.ProtocolConstants.LAN_CONNECT_TIMEOUT_MS
import com.constrivo.drop.core.protocol.ProtocolConstants.LAN_MEASURE_MS
import com.constrivo.drop.core.protocol.ProtocolConstants.LINK_IDLE_TEARDOWN_MS
import com.constrivo.drop.core.protocol.ProtocolConstants.P2P_FORMATION_TIMEOUT_MS
import com.constrivo.drop.core.protocol.ProtocolConstants.WIFI_RESTORE_BUDGET_MS
import com.constrivo.drop.core.protocol.TransferRole
import com.constrivo.drop.core.protocol.TransferRole.RECEIVER
import com.constrivo.drop.core.protocol.TransferRole.SENDER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Architecture §4, §7.8 and §9 with N9, as a pure reducer driven by a fake clock. Most cases run on the receiver, the
 * ladder's authority; the "follower" cases run on the sender.
 */
class LinkLifecycleTest {
    private val lifecycle = LinkLifecycle()
    private val t0 = 5_000_000L

    /** Plans from the receiver's side (the authority) unless [role] says otherwise. */
    private fun rplan(
        local: LinkFacts,
        peer: LinkFacts,
        role: TransferRole = RECEIVER,
        localRadio: RadioState = RadioState.ALL_ON,
        lanReachable: Boolean = false,
    ): LadderPlan = plan(local, peer, role, localRadio = localRadio, lanReachable = lanReachable)

    private val sameRouterFacts = phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER)

    /** Rungs: 0 LAN (the sender listens), 1 Wi-Fi Direct (hosted here), 2 hotspot (here), 3 Bluetooth. */
    private val sameRouter = rplan(sameRouterFacts, sameRouterFacts)

    /** Rungs: 0 Wi-Fi Direct (hosted here), 1 hotspot (here), 2 Bluetooth. */
    private val mobileData = rplan(phone(), phone())

    /** Rungs: 0 LAN (the Mac listens), 1 legacy join of this phone's group, 2 hotspot, 3 Bluetooth. */
    private val macOnSameLan = rplan(sameRouterFacts, laptop(Caps.MAC.onWifi(true), HOME_ROUTER), lanReachable = true)

    /** The same pair seen from the sender, which follows the receiver's decisions. */
    private val sameRouterFollower = rplan(sameRouterFacts, sameRouterFacts, SENDER)

    private inner class Driver(
        plan: LadderPlan,
        transferRunning: Boolean = true,
    ) {
        var now = t0
        var last: LinkTransition = lifecycle.start(plan, now, transferRunning)
        val state: LinkLifecycleState get() = last.state
        val effects: List<LinkEffect> get() = last.effects

        fun on(
            event: LinkEvent,
            at: Long = now,
        ): LinkTransition {
            now = at
            last = lifecycle.reduce(state, event, now)
            return last
        }

        /** Fires every timer due by [time], earliest first, then sets the clock to [time]. */
        fun advanceTo(time: Long) {
            while (true) {
                val (timer, deadline) = state.timers.entries.minByOrNull { it.value }?.toPair() ?: break
                if (deadline > time) break
                on(TimerFired(timer), deadline)
            }
            now = time
        }

        fun status(index: Int): AttemptStatus = state.attempts[index].status

        fun badgeText(): String? = state.badge?.englishText

        fun hintCodes(): List<HintCode> = state.hints.map { it.code }
    }

    // ---- Start and the N9 race ----

    @Test
    fun n9_lanProbeAndWifiDirectStartTogether() {
        val d = Driver(sameRouter)
        assertEquals(
            listOf(
                StartCandidate(0, sameRouter.candidates[0], 0),
                StartTimer(Deadline(0), t0 + LAN_CONNECT_TIMEOUT_MS),
                StartCandidate(1, sameRouter.candidates[1], 0),
                StartTimer(Deadline(1), t0 + P2P_FORMATION_TIMEOUT_MS),
            ),
            d.effects,
        )
        assertEquals(LadderPhase.CONNECTING, d.state.phase)
        assertEquals(PENDING, d.status(2))
        assertNull(d.state.dataLink)
        assertEquals("Bluetooth", d.badgeText())
    }

    @Test
    fun fE4_lanAcceptedWhenItMovesTenMegabytesInTheFirstSecond() {
        val d = Driver(sameRouter)
        d.on(Connected(0), t0 + 150)
        assertEquals(listOf(CancelTimer(Deadline(0)), StartTimer(Measure(0), t0 + 150 + LAN_MEASURE_MS), UseLink(0)), d.effects)
        assertEquals(MEASURING, d.status(0))
        assertEquals("Same network", d.badgeText())

        d.on(ThroughputSample(0, 4_000_000), t0 + 400)
        d.on(ThroughputSample(0, 4_000_000), t0 + 650)
        assertEquals(MEASURING, d.status(0))
        d.on(ThroughputSample(0, 2_000_000), t0 + 900)
        assertEquals(ACTIVE, d.status(0))
        // The peer is told, and the loser of the race is cancelled (N9).
        assertEquals(
            listOf(CancelTimer(Measure(0)), AnnounceSelection(0, 0), CancelTimer(Deadline(1)), StopCandidate(1, LinkEndReason.SUPERSEDED)),
            d.effects,
        )
        assertEquals(STOPPED, d.status(1))
        assertEquals(PENDING, d.status(2))
        assertEquals(LadderPhase.ACTIVE, d.state.phase)
        assertEquals(emptyList(), d.state.hints)
        assertTrue(d.state.timers.isEmpty())
    }

    @Test
    fun n9_wifiDirectWinsWhileTheLanIsStillMeasuring() {
        val d = Driver(sameRouter)
        d.on(Connected(0), t0 + 100)
        d.on(ThroughputSample(0, 1_000_000), t0 + 350)
        d.on(Connected(1, 5180), t0 + 800)
        assertEquals(ACTIVE, d.status(1))
        assertEquals(
            listOf(
                CancelTimer(Deadline(1)),
                UseLink(1),
                AnnounceSelection(1, 0),
                CancelTimer(Measure(0)),
                StopCandidate(0, LinkEndReason.SUPERSEDED),
            ),
            d.effects,
        )
        assertEquals(1, d.state.dataLink)
        assertEquals("Wi\u2011Fi Direct \u00B7 5 GHz", d.badgeText())
    }

    @Test
    fun fE4_slowLanKeepsCarryingDataUntilTheDirectLinkIsUp() {
        val d = Driver(sameRouter)
        d.on(Connected(0), t0 + 100)
        d.on(ThroughputSample(0, 3_000_000), t0 + 600)
        d.advanceTo(t0 + 1_100)
        assertEquals(STANDBY, d.status(0))
        assertEquals(listOf(HintCode.LAN_SLOW), d.hintCodes())
        assertEquals("Switching to a direct link\u2026", d.state.hints.single().englishText)
        assertEquals(0, d.state.dataLink)
        assertEquals(emptyList(), d.effects) // nothing new starts: Wi-Fi Direct is already forming

        d.on(Connected(1, 5745), t0 + 2_500)
        assertEquals(
            listOf(CancelTimer(Deadline(1)), UseLink(1), AnnounceSelection(1, 0), StopCandidate(0, LinkEndReason.SUPERSEDED)),
            d.effects,
        )
        assertEquals(emptyList(), d.state.hints)
        assertEquals(LadderPhase.ACTIVE, d.state.phase)
    }

    @Test
    fun fE4_slowLanIsUsedWhenNoDirectLinkIsLeft() {
        val lanOnly = rplan(laptop(Caps.WINDOWS, HOME_ROUTER), laptop(Caps.MAC, HOME_ROUTER))
        val d = Driver(lanOnly)
        d.on(Connected(0), t0 + 100)
        d.on(ThroughputSample(0, 2_000_000), t0 + 700)
        d.advanceTo(t0 + 1_100)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(LadderPhase.ACTIVE, d.state.phase)
        assertEquals(emptyList(), d.state.hints) // no "switching" when there is nothing to switch to
        assertEquals("Same network", d.badgeText())
    }

    @Test
    fun fE4_slowLanIsStoppedWhenTheHotspotJoinerMustLeaveTheNetwork() {
        val d = Driver(sameRouter)
        d.on(Connected(0), t0 + 100)
        d.advanceTo(t0 + 1_100) // nothing measured: standby
        d.advanceTo(t0 + P2P_FORMATION_TIMEOUT_MS) // Wi-Fi Direct times out
        assertEquals(FAILED, d.status(1))
        assertEquals(FAILED, d.status(0))
        assertEquals(LinkEndReason.SLOW, d.state.attempts[0].endReason)
        assertEquals(
            listOf(
                UseLink(null),
                StopCandidate(0, LinkEndReason.SLOW),
                StartCandidate(2, sameRouter.candidates[2], 0),
                StartTimer(Deadline(2), t0 + P2P_FORMATION_TIMEOUT_MS + HOTSPOT_TIMEOUT_MS),
            ),
            d.effects.dropWhile { it !is UseLink },
        )
        assertEquals(listOf(HintCode.LAN_SLOW), d.hintCodes())
    }

    @Test
    fun n9_legacyJoinerWaitsForTheLanVerdict() {
        val d = Driver(macOnSameLan)
        assertEquals(
            listOf(StartCandidate(0, macOnSameLan.candidates[0], 0), StartTimer(Deadline(0), t0 + LAN_CONNECT_TIMEOUT_MS)),
            d.effects,
        )
        assertEquals(PENDING, d.status(1))
        d.advanceTo(t0 + LAN_CONNECT_TIMEOUT_MS)
        assertEquals(FAILED, d.status(0))
        assertEquals(LinkEndReason.TIMEOUT, d.state.attempts[0].endReason)
        assertEquals(
            listOf(
                StopCandidate(0, LinkEndReason.TIMEOUT),
                StartCandidate(1, macOnSameLan.candidates[1], 0),
                StartTimer(Deadline(1), t0 + LAN_CONNECT_TIMEOUT_MS + P2P_FORMATION_TIMEOUT_MS),
            ),
            d.effects,
        )
    }

    @Test
    fun n9_legacyJoinerStopsASlowLanBeforeJoining() {
        val d = Driver(macOnSameLan)
        d.on(Connected(0), t0 + 50)
        d.advanceTo(t0 + 1_050)
        assertEquals(FAILED, d.status(0))
        assertEquals(STARTING, d.status(1))
        assertEquals(listOf(UseLink(null), StopCandidate(0, LinkEndReason.SLOW)), d.effects.take(2))
        assertEquals(listOf(HintCode.LAN_SLOW), d.hintCodes())
        d.on(Connected(1, 5500), t0 + 3_000)
        assertEquals(emptyList(), d.state.hints)
    }

    // ---- Timeouts and fall-through ----

    @Test
    fun fE1_eachRungTimesOutAndFallsThroughToBluetooth() {
        val d = Driver(mobileData)
        assertEquals(
            listOf(StartCandidate(0, mobileData.candidates[0], 0), StartTimer(Deadline(0), t0 + P2P_FORMATION_TIMEOUT_MS)),
            d.effects,
        )
        d.advanceTo(t0 + P2P_FORMATION_TIMEOUT_MS - 1)
        assertEquals(STARTING, d.status(0))
        d.advanceTo(t0 + P2P_FORMATION_TIMEOUT_MS)
        assertEquals(FAILED, d.status(0))
        assertEquals(STARTING, d.status(1))
        assertEquals(t0 + P2P_FORMATION_TIMEOUT_MS + HOTSPOT_TIMEOUT_MS, d.state.timers[Deadline(1)])
        assertEquals("Bluetooth", d.badgeText()) // the Bluetooth stream keeps the transfer alive meanwhile

        d.advanceTo(t0 + P2P_FORMATION_TIMEOUT_MS + HOTSPOT_TIMEOUT_MS)
        assertEquals(FAILED, d.status(1))
        assertEquals(LadderPhase.BLUETOOTH_ONLY, d.state.phase)
        assertEquals("Bluetooth", d.badgeText())
        assertEquals(emptyList(), d.state.hints) // Wi-Fi is on: "Wi-Fi is off" would be false
        assertTrue(d.state.timers.isEmpty())
    }

    @Test
    fun fE1_aFailingRungGivesWayAtOnce() {
        val d = Driver(mobileData)
        d.on(Failed(0, LinkEndReason.UNSUPPORTED), t0 + 20)
        assertEquals(
            listOf(
                CancelTimer(Deadline(0)),
                StopCandidate(0, LinkEndReason.UNSUPPORTED),
                StartCandidate(1, mobileData.candidates[1], 0),
                StartTimer(Deadline(1), t0 + 20 + HOTSPOT_TIMEOUT_MS),
            ),
            d.effects,
        )
        assertEquals(LinkEndReason.UNSUPPORTED, d.state.attempts[0].endReason)
    }

    @Test
    fun fE1_lanConnectTimeoutLeavesTheRacingWifiDirectAlone() {
        val d = Driver(sameRouter)
        d.advanceTo(t0 + LAN_CONNECT_TIMEOUT_MS)
        assertEquals(FAILED, d.status(0))
        assertEquals(listOf(StopCandidate(0, LinkEndReason.TIMEOUT)), d.effects)
        assertEquals(STARTING, d.status(1))
        assertEquals(PENDING, d.status(2))
    }

    @Test
    fun fE1_wifiOffGoesStraightToBluetoothWithTheSlowModeHint() {
        val offPlan = rplan(phone(), phone(), localRadio = RadioState(wifiEnabled = false, bluetoothEnabled = true))
        val d = Driver(offPlan)
        assertEquals(LadderPhase.BLUETOOTH_ONLY, d.state.phase)
        assertEquals(listOf(HintCode.BT_FALLBACK), d.hintCodes())
        assertEquals(emptyList(), d.effects)
        assertEquals("Bluetooth", d.badgeText())
    }

    @Test
    fun fE1_noPathAtAllIsUnreachable() {
        val none = rplan(phone(), laptop(Caps.WIRED_DESKTOP, OFFICE_ROUTER))
        val d = Driver(none)
        assertEquals(LadderPhase.UNREACHABLE, d.state.phase)
        assertNull(d.state.badge)
        d.on(TransferEnded(cancelled = true), t0 + 10)
        assertEquals(LadderPhase.TEARING_DOWN, d.state.phase)
        assertEquals(listOf(RestoreNetwork, StartTimer(Restore, t0 + 10 + WIFI_RESTORE_BUDGET_MS)), d.effects)
    }

    @Test
    fun t04_lostGroupIsReformedOnceOn24GhzThenTheNextRungTakesOver() {
        val d = Driver(mobileData)
        d.on(Connected(0, 5180), t0 + 1_000)
        assertEquals(LadderPhase.ACTIVE, d.state.phase)
        d.on(LinkLost(0), t0 + 30_000) // the edge of range
        assertEquals(STARTING, d.status(0)) // formed again at once, with its second formation
        assertEquals(2, d.state.attempts[0].tries)
        assertEquals(LadderPhase.CONNECTING, d.state.phase)
        val onTwoPointFour = mobileData.candidates[0].copy(requestFiveGhz = false)
        assertEquals(
            listOf(
                UseLink(null),
                StopCandidate(0, LinkEndReason.LOST),
                StartCandidate(0, onTwoPointFour, 1),
                StartTimer(Deadline(0), t0 + 30_000 + P2P_FORMATION_TIMEOUT_MS),
            ),
            d.effects,
        )
        assertEquals("Bluetooth", d.badgeText())
        assertEquals(PENDING, d.status(1)) // Wi-Fi Direct first, not the hotspot
        // The 2.4 GHz group is accepted with its hint, without a re-form.
        d.on(Connected(0, 2437), t0 + 32_000)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(listOf(HintCode.BAND24), d.hintCodes())
        assertEquals("Wi\u2011Fi Direct \u00B7 2.4 GHz", d.badgeText())

        // Lost again: both formations are spent, so the hotspot takes over.
        d.on(LinkLost(0), t0 + 50_000)
        assertEquals(FAILED, d.status(0))
        assertEquals(STARTING, d.status(1))
        assertEquals(emptyList(), d.state.hints)
        // A failure report for an accepted link counts as a loss too; the hotspot gets its second formation.
        d.on(Connected(1, 2437), t0 + 52_000)
        d.on(Failed(1), t0 + 60_000)
        assertTrue(StopCandidate(1, LinkEndReason.LOST) in d.effects)
        assertEquals(listOf(StartCandidate(1, mobileData.candidates[1], 1)), d.effects.filterIsInstance<StartCandidate>())
        d.advanceTo(t0 + 60_000 + HOTSPOT_TIMEOUT_MS)
        assertEquals(LadderPhase.BLUETOOTH_ONLY, d.state.phase)
    }

    @Test
    fun fE1_lanWonThenLostTriesWifiDirectBeforeTheHotspot() {
        val d = Driver(sameRouter)
        d.on(Connected(0), t0 + 100)
        d.on(ThroughputSample(0, 12_000_000), t0 + 500)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(STOPPED, d.status(1))
        d.on(LinkLost(0), t0 + 40_000)
        assertEquals(
            listOf(StartCandidate(0, sameRouter.candidates[0], 1), StartCandidate(1, sameRouter.candidates[1], 1)),
            d.effects.filterIsInstance<StartCandidate>(),
        )
        assertEquals(PENDING, d.status(2))
        d.on(Connected(1, 5180), t0 + 41_500)
        assertEquals(ACTIVE, d.status(1))
        assertEquals("Wi\u2011Fi Direct \u00B7 5 GHz", d.badgeText())
    }

    @Test
    fun fE4_slowLanStoppedForTheHotspotComesBackWhenTheHotspotFails() {
        val d = Driver(sameRouter)
        d.on(Connected(0), t0 + 100)
        d.advanceTo(t0 + 1_100) // too slow: standby
        d.advanceTo(t0 + P2P_FORMATION_TIMEOUT_MS) // Wi-Fi Direct times out; the LAN is stopped for the hotspot joiner
        assertEquals(LinkEndReason.SLOW, d.state.attempts[0].endReason)
        assertEquals(STARTING, d.status(2))
        d.advanceTo(t0 + P2P_FORMATION_TIMEOUT_MS + HOTSPOT_TIMEOUT_MS) // the hotspot fails too
        assertEquals(FAILED, d.status(2))
        assertEquals(listOf(StartCandidate(0, sameRouter.candidates[0], 1)), d.effects.filterIsInstance<StartCandidate>())
        assertEquals(LadderPhase.CONNECTING, d.state.phase)
        d.on(Connected(0), t0 + 12_100)
        d.on(ThroughputSample(0, 3_000_000), t0 + 12_600)
        d.advanceTo(t0 + 13_100) // still slow, but nothing is left and it beats Bluetooth
        assertEquals(ACTIVE, d.status(0))
        assertEquals(LadderPhase.ACTIVE, d.state.phase)
        assertEquals("Same network", d.badgeText())
        assertEquals(emptyList(), d.state.hints)
    }

    // ---- The follower (N9: one device decides) ----

    @Test
    fun n9_followerNeverAcceptsOnItsOwnMeterAndTakesTheAuthoritysChoice() {
        val d = Driver(sameRouterFollower)
        d.on(Connected(0), t0 + 100)
        assertEquals(listOf(CancelTimer(Deadline(0)), UseLink(0)), d.effects) // carries data, no measurement window
        assertEquals(MEASURING, d.status(0))
        assertEquals("Same network", d.badgeText())
        d.on(ThroughputSample(0, 12_000_000), t0 + 400) // bytes sent here decide nothing
        assertEquals(MEASURING, d.status(0))
        d.advanceTo(t0 + 3_000)
        assertEquals(MEASURING, d.status(0))
        assertEquals(emptyList(), d.state.hints)

        d.on(Connected(1, 5180), t0 + 3_200) // up here, but the receiver decides
        assertEquals(READY, d.status(1))
        assertEquals(0, d.state.dataLink)
        assertEquals(t0 + P2P_FORMATION_TIMEOUT_MS + LadderTimeouts.DEFAULT_SELECTION_GRACE_MS, d.state.timers[Deadline(1)])

        d.on(PeerSelected(1, 0), t0 + 3_300)
        assertEquals(ACTIVE, d.status(1))
        assertEquals(STOPPED, d.status(0))
        assertEquals(listOf(CancelTimer(Deadline(1)), UseLink(1), StopCandidate(0, LinkEndReason.SUPERSEDED)), d.effects)
        assertEquals("Wi\u2011Fi Direct \u00B7 5 GHz", d.badgeText())
    }

    @Test
    fun n9_followerTakesTheAuthoritysLanVerdict() {
        val d = Driver(sameRouterFollower)
        d.on(Connected(0), t0 + 100)
        d.on(Connected(1, 5180), t0 + 900)
        d.on(PeerSelected(0, 0), t0 + 950)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(STOPPED, d.status(1))
        assertEquals(LinkEndReason.SUPERSEDED, d.state.attempts[1].endReason)
        assertEquals(LadderPhase.ACTIVE, d.state.phase)
    }

    @Test
    fun n9_selectionThatArrivesBeforeTheLinkIsUpHereIsKept() {
        val d = Driver(sameRouterFollower)
        assertTrue(d.on(PeerSelected(1, 0), t0 + 700).handled)
        assertEquals(LinkSelection(1, 0), d.state.pendingSelection)
        assertEquals(STARTING, d.status(1))
        d.on(Connected(1, 5180), t0 + 720)
        assertEquals(ACTIVE, d.status(1))
        assertNull(d.state.pendingSelection)
        // Stale or foreign selections change nothing.
        assertFalse(d.on(PeerSelected(1, 0), t0 + 800).handled)
        assertFalse(d.on(PeerSelected(3, 0), t0 + 800).handled) // Bluetooth
        assertFalse(d.on(PeerSelected(9, 0), t0 + 800).handled)
    }

    @Test
    fun n9_followerReformsTogetherWithTheAuthority() {
        // The re-form is decided from the channel both devices share, so the follower re-forms on its own.
        val d = Driver(rplan(phone(), phone(), SENDER))
        d.on(Connected(0, 2437), t0 + 1_000)
        assertEquals(STARTING, d.status(0))
        assertEquals(2, d.state.attempts[0].tries)
        d.on(Connected(0, 2437), t0 + 3_000)
        assertEquals(READY, d.status(0))
        d.on(PeerSelected(0, 1), t0 + 3_100)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(listOf(HintCode.BAND24), d.hintCodes())
    }

    @Test
    fun n9_followerGivesUpOnALinkTheAuthorityNeverSelects() {
        val d = Driver(rplan(phone(), phone(), SENDER))
        d.on(Connected(0, 5180), t0 + 1_000)
        assertEquals(READY, d.status(0))
        d.advanceTo(t0 + P2P_FORMATION_TIMEOUT_MS + LadderTimeouts.DEFAULT_SELECTION_GRACE_MS - 1)
        assertEquals(READY, d.status(0))
        d.advanceTo(t0 + P2P_FORMATION_TIMEOUT_MS + LadderTimeouts.DEFAULT_SELECTION_GRACE_MS)
        assertEquals(FAILED, d.status(0))
        assertEquals(LinkEndReason.TIMEOUT, d.state.attempts[0].endReason)
        assertEquals(STARTING, d.status(1))
    }

    @Test
    fun n9_followerMovesOnWhenTheAuthorityStopsASlowLan() {
        // The phone sends to a Mac; the Mac (receiver) found the LAN too slow and stopped it before joining the group.
        val d = Driver(rplan(sameRouterFacts, laptop(Caps.MAC.onWifi(true), HOME_ROUTER), SENDER, lanReachable = true))
        d.on(Connected(0), t0 + 100)
        d.advanceTo(t0 + 5_000)
        assertEquals(MEASURING, d.status(0)) // no verdict of its own: the legacy join waits
        assertEquals(PENDING, d.status(1))
        d.on(LinkLost(0), t0 + 5_100)
        assertEquals(LinkEndReason.LOST, d.state.attempts[0].endReason)
        assertEquals(STARTING, d.status(1))
    }

    // ---- 5 GHz verification (§9) ----

    @Test
    fun fE2_fiveGhzGroupIsAcceptedWithoutAHint() {
        val d = Driver(mobileData)
        d.on(Connected(0, 5180), t0 + 1_500)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(1, d.state.attempts[0].tries)
        assertEquals(emptyList(), d.state.hints)
        assertEquals("Wi\u2011Fi Direct \u00B7 5 GHz", d.badgeText())
        assertEquals(t0 + 1_500, d.state.activeAtMillis)
    }

    @Test
    fun fE2_twoPointFourIsReformedExactlyOnceThenAcceptedWithBand24() {
        val d = Driver(mobileData)
        d.on(Connected(0, 2437), t0 + 1_500)
        assertEquals(
            listOf(
                CancelTimer(Deadline(0)),
                StopCandidate(0, LinkEndReason.REFORM),
                StartCandidate(0, mobileData.candidates[0], 1),
                StartTimer(Deadline(0), t0 + 1_500 + P2P_FORMATION_TIMEOUT_MS),
            ),
            d.effects,
        )
        assertEquals(STARTING, d.status(0))
        assertEquals(2, d.state.attempts[0].tries)
        assertNull(d.state.dataLink)
        assertEquals("Bluetooth", d.badgeText())

        d.on(Connected(0, 2412), t0 + 4_000)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(listOf(HintCode.BAND24), d.hintCodes())
        assertEquals("Move closer for full speed", d.state.hints.single().englishText)
        assertEquals("Wi\u2011Fi Direct \u00B7 2.4 GHz", d.badgeText())
        assertTrue(d.effects.none { it is StartCandidate }) // no second re-form
    }

    @Test
    fun fE2_reformThatReaches5GhzHasNoHint() {
        val d = Driver(mobileData)
        d.on(Connected(0, 2462), t0 + 1_000)
        d.on(Connected(0, 5805), t0 + 3_000)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(emptyList(), d.state.hints)
    }

    @Test
    fun fE2_reformThatTimesOutFallsThrough() {
        val d = Driver(mobileData)
        d.on(Connected(0, 2437), t0 + 1_000)
        d.advanceTo(t0 + 1_000 + P2P_FORMATION_TIMEOUT_MS)
        assertEquals(FAILED, d.status(0))
        assertEquals(STARTING, d.status(1))
    }

    @Test
    fun n9_stationOn24PinsTheChannel() {
        val pinned = rplan(phone(Caps.FLAGSHIP.onWifi(false)), laptop(Caps.MAC))
        val d = Driver(pinned)
        d.on(Connected(0, 2437), t0 + 1_000)
        assertEquals(STARTING, d.status(0)) // still re-formed once
        d.on(Connected(0, 2437), t0 + 3_000)
        assertEquals(ACTIVE, d.status(0))
        val hint = d.state.hints.single()
        assertEquals(HintCode.STATION_BAND24, hint.code)
        assertEquals("Your Wi\u2011Fi network is on 2.4 GHz", hint.englishText)

        val peerPinned = rplan(laptop(Caps.MAC), phone(Caps.FLAGSHIP.onWifi(false)))
        val p = Driver(peerPinned)
        p.on(Connected(0, 2437), t0 + 1_000)
        p.on(Connected(0, 2437), t0 + 3_000)
        assertEquals("Asha's Wi\u2011Fi network is on 2.4 GHz", p.state.hints.single().englishText)
    }

    @Test
    fun fF3_twoPointFourOnlyPeerIsAcceptedAtOnceWithItsHint() {
        val d = Driver(rplan(phone(), phone(Caps.BAND24_ONLY)))
        d.on(Connected(0, 2437), t0 + 1_000)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(1, d.state.attempts[0].tries)
        assertEquals("Asha's device supports 2.4 GHz only", d.state.hints.single().englishText)
    }

    @Test
    fun fF3_twoPointFourOnlyHereShowsNothingHere() {
        val d = Driver(rplan(phone(Caps.BAND24_ONLY), phone()))
        d.on(Connected(0, 2437), t0 + 1_000)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(emptyList(), d.state.hints)
    }

    @Test
    fun fE2_frequencyReportedBeforeTheConnectionIsUsed() {
        val d = Driver(mobileData)
        d.on(FrequencyReported(0, 5200), t0 + 800)
        assertEquals(5200, d.state.attempts[0].freqMhz)
        d.on(Connected(0), t0 + 1_200)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(5200, d.state.attempts[0].freqMhz)
    }

    @Test
    fun fE2_frequencyArrivingAfterTheConnectionCompletesTheCheck() {
        val d = Driver(mobileData)
        d.on(Connected(0, 0), t0 + 1_000)
        assertEquals(VERIFYING, d.status(0))
        assertEquals("Bluetooth", d.badgeText())
        d.on(FrequencyReported(0, 5745), t0 + 1_100)
        assertEquals(ACTIVE, d.status(0))
    }

    @Test
    fun fE2_aTwoPointFourChannelLearntOnlyAfterTheConnectionIsAcceptedAsItIs() {
        // Only the Connected channel is shared by both devices; a later one cannot re-form both groups together.
        val d = Driver(mobileData)
        d.on(Connected(0), t0 + 1_000)
        assertEquals(VERIFYING, d.status(0))
        d.on(FrequencyReported(0, 2437), t0 + 1_200)
        assertEquals(ACTIVE, d.status(0))
        assertTrue(d.effects.none { it is StartCandidate })
        assertEquals(listOf(HintCode.BAND24), d.hintCodes())
    }

    @Test
    fun fE2_aPairWithout5GhzIsAcceptedAtOnceEvenWithoutAChannel() {
        val d = Driver(rplan(phone(), phone(Caps.BAND24_ONLY)))
        d.on(Connected(0, null), t0 + 500)
        assertEquals(ACTIVE, d.status(0))
        assertEquals(0, d.state.dataLink)
        assertEquals("Wi\u2011Fi Direct", d.badgeText())
    }

    @Test
    fun fF2_frequencyNeverReportedIsAcceptedAtTheDeadlineWithAnUnknownBand() {
        val d = Driver(mobileData)
        d.on(Connected(0), t0 + 1_000)
        d.advanceTo(t0 + P2P_FORMATION_TIMEOUT_MS)
        assertEquals(ACTIVE, d.status(0))
        assertEquals("Wi\u2011Fi Direct", d.badgeText())
        // T-04: the band drops at the edge of range after acceptance: the badge and hint follow, no re-form.
        d.on(FrequencyReported(0, 2437), t0 + 20_000)
        assertEquals("Wi\u2011Fi Direct \u00B7 2.4 GHz", d.badgeText())
        assertEquals(listOf(HintCode.BAND24), d.hintCodes())
        assertTrue(d.effects.isEmpty())
    }

    @Test
    fun fE3_hotspotIsAcceptedOnWhateverBandTheSystemChose() {
        val hotspotOnly = rplan(phone(Caps.NO_WIFI_DIRECT), phone(Caps.NO_WIFI_DIRECT))
        val d = Driver(hotspotOnly)
        d.on(Connected(0, 2437), t0 + 3_000)
        assertEquals(ACTIVE, d.status(0))
        assertEquals("Hotspot \u00B7 2.4 GHz", d.badgeText())
        assertEquals(emptyList(), d.state.hints) // "move closer" would not help: the band is not selectable (N8)

        val band24Peer = rplan(phone(Caps.NO_WIFI_DIRECT), phone(Capabilities.of(Flag.CAN_HOST_LOCAL_HOTSPOT)))
        val p = Driver(band24Peer)
        p.on(Connected(0, 2412), t0 + 3_000)
        assertEquals(listOf(HintCode.PEER_BAND24_ONLY), p.hintCodes())
    }

    // ---- Teardown and restore (F-E11) ----

    @Test
    fun fE11_completeTearsDownAndRestoresWithinTheBudget() {
        val d = Driver(mobileData)
        d.on(Connected(0, 5180), t0 + 1_000)
        d.on(TransferEnded(), t0 + 20_000)
        assertEquals(
            listOf(
                UseLink(null),
                StopCandidate(0, LinkEndReason.TEARDOWN),
                RestoreNetwork,
                StartTimer(
                    Restore,
                    t0 + 20_000 + WIFI_RESTORE_BUDGET_MS,
                ),
            ),
            d.effects,
        )
        assertEquals(LadderPhase.TEARING_DOWN, d.state.phase)
        assertNull(d.state.badge)
        assertEquals(emptyList(), d.state.hints)
        d.on(RestoreCompleted, t0 + 21_200)
        assertEquals(LadderPhase.CLOSED, d.state.phase)
        assertEquals(listOf(CancelTimer(Restore)), d.effects)
        assertEquals(1_200L, d.state.restoreMillis)
        assertFalse(d.state.restoreOverdue)
    }

    @Test
    fun fE11_cancelMidLadderStopsEverythingThatRuns() {
        val d = Driver(sameRouter)
        d.on(Connected(0), t0 + 100)
        d.on(TransferEnded(cancelled = true), t0 + 500)
        assertEquals(
            listOf(
                UseLink(null),
                CancelTimer(Measure(0)),
                StopCandidate(0, LinkEndReason.TEARDOWN),
                CancelTimer(Deadline(1)),
                StopCandidate(1, LinkEndReason.TEARDOWN),
                RestoreNetwork,
                StartTimer(Restore, t0 + 500 + WIFI_RESTORE_BUDGET_MS),
            ),
            d.effects,
        )
        assertEquals(PENDING, d.status(2))
    }

    @Test
    fun fE11_lateRestoreIsFlaggedOverdue() {
        val d = Driver(mobileData)
        d.on(TransferEnded(), t0 + 100)
        d.advanceTo(t0 + 100 + WIFI_RESTORE_BUDGET_MS)
        assertTrue(d.state.restoreOverdue)
        assertEquals(LadderPhase.TEARING_DOWN, d.state.phase)
        d.on(RestoreCompleted, t0 + 7_100)
        assertEquals(LadderPhase.CLOSED, d.state.phase)
        assertEquals(7_000L, d.state.restoreMillis)
    }

    @Test
    fun fE11_prewarmedLinkIsTornDownAfterSixtySecondsIdle() {
        val d = Driver(mobileData, transferRunning = false)
        assertEquals(StartTimer(Idle, t0 + LINK_IDLE_TEARDOWN_MS), d.effects.first())
        d.on(Connected(0, 5180), t0 + 900)
        d.advanceTo(t0 + LINK_IDLE_TEARDOWN_MS - 1)
        assertEquals(LadderPhase.ACTIVE, d.state.phase)
        d.advanceTo(t0 + LINK_IDLE_TEARDOWN_MS)
        assertEquals(LadderPhase.TEARING_DOWN, d.state.phase)
        assertEquals(t0 + LINK_IDLE_TEARDOWN_MS, d.state.teardownStartedAtMillis)
    }

    @Test
    fun fE11_queuedTransfersKeepTheLinkAndIdleCountsFromTheLastOne() {
        val d = Driver(mobileData, transferRunning = false)
        d.on(TransferStarted, t0 + 2_000)
        assertEquals(listOf(CancelTimer(Idle)), d.effects)
        d.on(Connected(0, 5180), t0 + 2_500)
        d.on(TransferEnded(moreQueued = true), t0 + 30_000)
        assertEquals(listOf(StartTimer(Idle, t0 + 30_000 + LINK_IDLE_TEARDOWN_MS)), d.effects)
        assertEquals(LadderPhase.ACTIVE, d.state.phase)
        d.on(TransferStarted, t0 + 40_000)
        assertEquals(listOf(CancelTimer(Idle)), d.effects)
        d.on(TransferEnded(moreQueued = true), t0 + 50_000)
        d.advanceTo(t0 + 50_000 + LINK_IDLE_TEARDOWN_MS)
        assertEquals(LadderPhase.TEARING_DOWN, d.state.phase)
    }

    // ---- Stale and malformed input ----

    @Test
    fun staleAndInapplicableEventsAreIgnored() {
        val d = Driver(sameRouter)
        val start = d.state
        assertFalse(d.on(TimerFired(Deadline(0)), t0 + 10).handled) // before its deadline
        assertFalse(d.on(TimerFired(Idle), t0 + 10).handled) // not armed
        assertFalse(d.on(Connected(2), t0 + 10).handled) // never started
        assertFalse(d.on(Connected(9), t0 + 10).handled) // no such rung
        assertFalse(d.on(Connected(3), t0 + 10).handled) // Bluetooth is not set up by the ladder
        assertFalse(d.on(ThroughputSample(1, 5_000_000), t0 + 10).handled) // not measuring
        assertFalse(d.on(FrequencyReported(1, 0), t0 + 10).handled) // unknown frequency
        assertFalse(d.on(FrequencyReported(1, 250_000), t0 + 10).handled) // garbage frequency
        assertFalse(d.on(LinkLost(2), t0 + 10).handled)
        assertFalse(d.on(TransferStarted, t0 + 10).handled) // already running
        assertFalse(d.on(RestoreCompleted, t0 + 10).handled)
        assertFalse(d.on(PeerSelected(1, 0), t0 + 10).handled) // the authority takes no orders
        assertEquals(start, d.state)

        d.on(Connected(1, 5180), t0 + 500)
        assertFalse(d.on(TimerFired(Deadline(1)), t0 + P2P_FORMATION_TIMEOUT_MS).handled) // cancelled on acceptance
        assertFalse(d.on(Connected(1, 5180), t0 + 600).handled) // duplicate
        d.on(TransferEnded(), t0 + 1_000)
        d.on(RestoreCompleted, t0 + 1_500)
        assertFalse(d.on(TransferEnded(), t0 + 2_000).handled)
        assertFalse(d.on(TransferStarted, t0 + 2_000).handled)
        assertFalse(d.on(Connected(2), t0 + 2_000).handled)
    }

    @Test
    fun timeoutsAreValidated() {
        assertEquals(10_000_000L, LadderTimeouts().lanMinBytes)
        assertEquals(5_000_000L, LadderTimeouts(lanMeasureMillis = 500).lanMinBytes)
        assertFailsWith<IllegalArgumentException> { LadderTimeouts(lanConnectMillis = 0) }
        assertFailsWith<IllegalArgumentException> { LadderTimeouts(selectionGraceMillis = 0) }
        assertFailsWith<IllegalArgumentException> { LadderTimeouts(lanMinBytesPerSecond = Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { LadderTimeouts().connectTimeout(LinkMode.BLUETOOTH) }
        assertFailsWith<IllegalArgumentException> { ThroughputSample(0, -1) }
    }
}
