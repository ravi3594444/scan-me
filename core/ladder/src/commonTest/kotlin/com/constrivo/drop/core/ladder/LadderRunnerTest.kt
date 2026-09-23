package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.ladder.Caps.onWifi
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkReady
import com.constrivo.drop.core.protocol.ProtocolConstants.HOTSPOT_TIMEOUT_MS
import com.constrivo.drop.core.protocol.ProtocolConstants.LINK_IDLE_TEARDOWN_MS
import com.constrivo.drop.core.protocol.ProtocolConstants.P2P_FORMATION_TIMEOUT_MS
import com.constrivo.drop.core.protocol.ProtocolConstants.WIFI_RESTORE_BUDGET_MS
import com.constrivo.drop.core.protocol.TransferRole.RECEIVER
import com.constrivo.drop.core.protocol.TransferRole.SENDER
import com.constrivo.drop.core.protocol.WifiCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [LadderRunner] against fake providers under virtual time (architecture §4, N9, F-E11). The single-runner cases run
 * on the receiver, the ladder's authority, with a fake peer that echoes `LinkReady`; the pair cases wire a sender's
 * and a receiver's runner back to back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LadderRunnerTest {
    /** How one provider call behaves: time to come up, the channel it reports, or failure. */
    private data class Step(
        val millis: Long = 0,
        val freqMhz: Int? = null,
        val never: Boolean = false,
        val fail: Boolean = false,
        val teardownMillis: Long = 0,
        val credentials: WifiCredentials? = null,
        /** Hands the link over and then never returns: the return value is lost, as with prompt cancellation. */
        val hangAfterUp: Boolean = false,
    )

    private class FakeLink(
        override val kind: LinkKind,
        override val mode: LinkMode,
        override val role: LinkRole,
        override val frequencyMhz: Int?,
        override val credentials: WifiCredentials?,
        override val localAddress: String?,
        override val localPort: Int?,
        private val teardownMillis: Long,
        private val now: () -> Long,
        private val onTeardown: (LinkKind) -> Unit,
    ) : ActiveLink {
        var tornDownAt: Long? = null

        override suspend fun connect(
            address: String,
            port: Int,
        ): DataChannel = throw UnsupportedOperationException()

        override suspend fun accept(port: Int): DataChannel = throw UnsupportedOperationException()

        override suspend fun teardown() {
            delay(teardownMillis)
            if (tornDownAt == null) onTeardown(kind)
            tornDownAt = now()
        }
    }

    private class FakeProvider(
        override val kind: LinkKind,
        private val now: () -> Long,
        private val steps: List<Step>,
        private val roles: Set<LinkRole> = setOf(LinkRole.HOST, LinkRole.JOIN),
    ) : WifiLinkProvider {
        val hosts = ArrayList<Pair<Long, HostRequest>>()
        val joins = ArrayList<Pair<Long, JoinRequest>>()
        val cancelledAt = ArrayList<Long>()
        val links = ArrayList<FakeLink>()

        /** Called when a link of this provider is torn down (the pair rig tells the other device). */
        var onTeardown: (LinkKind) -> Unit = {}

        override fun supports(
            mode: LinkMode,
            role: LinkRole,
        ): Boolean = mode.kind == kind && role in roles

        override suspend fun host(
            request: HostRequest,
            onUp: (ActiveLink) -> Unit,
        ): ActiveLink {
            hosts += now() to request
            return run(request.mode, LinkRole.HOST, hosts.size + joins.size - 1, onUp)
        }

        override suspend fun join(
            request: JoinRequest,
            onUp: (ActiveLink) -> Unit,
        ): ActiveLink {
            joins += now() to request
            return run(request.mode, LinkRole.JOIN, hosts.size + joins.size - 1, onUp)
        }

        private suspend fun run(
            mode: LinkMode,
            role: LinkRole,
            call: Int,
            onUp: (ActiveLink) -> Unit,
        ): ActiveLink {
            val step = steps[minOf(call, steps.lastIndex)]
            try {
                if (step.never) awaitCancellation()
                delay(step.millis)
            } catch (e: CancellationException) {
                cancelledAt += now()
                throw e
            }
            if (step.fail) throw IllegalStateException("radio said no")
            val address = if (role == LinkRole.HOST) "192.168.49.1" else null
            val port = if (role == LinkRole.HOST) 4000 + call else null
            val link = FakeLink(kind, mode, role, step.freqMhz, step.credentials, address, port, step.teardownMillis, now, onTeardown)
            links += link
            onUp(link)
            if (step.hangAfterUp) awaitCancellation()
            return link
        }
    }

    /** The engine side: records `LinkReady`s, lets a simulated peer answer them, and opens the first streams. */
    private class FakeSession(
        private val scope: CoroutineScope,
        private val now: () -> Long,
        private val replyMillis: Long? = 20,
        private val streamMillis: Map<LinkKind, Long?> = emptyMap(),
    ) : LadderSession {
        lateinit var runner: LadderRunner
        val sent = ArrayList<Pair<Long, LinkReady>>()
        val opened = ArrayList<Pair<Long, LinkKind>>()
        val selected = ArrayList<Int>()

        override suspend fun sendLinkReady(message: LinkReady) {
            sent += now() to message
            val delayMillis = replyMillis ?: return
            scope.launch {
                delay(delayMillis)
                runner.onPeerLinkReady(LinkReady(message.kind, "192.168.49.2", 5000, message.freqMhz, null, message.generation))
            }
        }

        override suspend fun openStream(
            link: ActiveLink,
            peer: LinkReady,
        ) {
            val millis = if (link.kind in streamMillis) streamMillis[link.kind] else 20
            if (millis == null) awaitCancellation()
            delay(millis)
            opened += now() to link.kind
        }

        override suspend fun sendLinkSelected(generation: Int) {
            selected += generation
        }
    }

    private class Rig(
        val runner: LadderRunner,
        val session: FakeSession,
        val events: MutableList<LadderLogEvent>,
    ) {
        val state: LadderState get() = runner.state.value
    }

    private fun TestScope.rig(
        plan: LadderPlan,
        providers: List<WifiLinkProvider>,
        config: LadderConfig = LadderConfig(p2pCredentials = TEST_CREDENTIALS),
        replyMillis: Long? = 20,
        streamMillis: Map<LinkKind, Long?> = emptyMap(),
    ): Rig {
        val now = { testScheduler.currentTime }
        val session = FakeSession(backgroundScope, now, replyMillis, streamMillis)
        val runner = LadderRunner(plan, providers, session, backgroundScope, MonotonicClock { testScheduler.currentTime }, config)
        session.runner = runner
        val events = ArrayList<LadderLogEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runner.events.toList(events) }
        return Rig(runner, session, events)
    }

    private fun TestScope.provider(
        kind: LinkKind,
        vararg steps: Step,
        roles: Set<LinkRole> = setOf(LinkRole.HOST, LinkRole.JOIN),
    ) = FakeProvider(kind, { testScheduler.currentTime }, steps.toList(), roles)

    private fun TestScope.at(time: Long) {
        advanceTimeBy(time - testScheduler.currentTime)
        runCurrent()
    }

    private val sameRouterFacts = phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER)

    /** Receiver's rungs: 0 LAN (the sender listens, this device joins), 1 Wi-Fi Direct (hosted here), 2 hotspot, 3 Bluetooth. */
    private val sameRouter = plan(sameRouterFacts, sameRouterFacts, RECEIVER)

    /** Rungs: 0 Wi-Fi Direct hosted here, 1 hotspot hosted here, 2 Bluetooth. */
    private val hostHere = plan(phone(Caps.FLAGSHIP), phone(Caps.MIDRANGE), RECEIVER)

    /** Rungs: 0 Wi-Fi Direct (the peer hosts, this device joins as a client), 1 hotspot (peer), 2 Bluetooth. */
    private val peerHosts = plan(phone(Caps.MIDRANGE), phone(Caps.FLAGSHIP), RECEIVER)

    /** Rungs: 0 hotspot (the peer hosts), 1 Bluetooth. */
    private val peerHostsHotspot = plan(phone(Caps.NO_WIFI_DIRECT, battery = 10), phone(Caps.NO_WIFI_DIRECT, battery = 90), RECEIVER)

    @Test
    fun n9_lanWinsTheRaceAndWifiDirectFormationIsCancelled() =
        runTest {
            val lan = provider(LinkKind.LAN, Step(millis = 0, teardownMillis = 800))
            val p2p = provider(LinkKind.P2P, Step(millis = 3_000, freqMhz = 5180))
            val rig = rig(sameRouter, listOf(lan, p2p))
            rig.runner.start()
            at(0)
            assertEquals(1, lan.joins.size) // this receiver dials the sender's LAN listener
            assertEquals(1, p2p.hosts.size) // both started at once (N9)
            assertEquals(TEST_CREDENTIALS, p2p.hosts.single().second.credentials)
            assertEquals("Bluetooth", rig.state.badge?.englishText)

            at(40) // LinkReady answered at 20 ms, stream open at 40 ms
            assertEquals(AttemptStatus.MEASURING, rig.state.attempts[0].status)
            assertEquals("Same network", rig.state.badge?.englishText)
            assertSame(lan.links.single(), rig.state.dataLink)
            assertEquals(LinkReady(LinkKind.LAN, null, null, 0, null, 0), rig.session.sent.first().second)

            at(300)
            rig.runner.onThroughputSample(LinkKind.LAN, 12_000_000)
            at(300)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            assertEquals(AttemptStatus.STOPPED, rig.state.attempts[1].status)
            assertEquals(listOf(300L), p2p.cancelledAt) // the loser's formation was cancelled
            assertTrue(p2p.links.isEmpty())
            assertEquals(listOf(0), rig.session.selected) // the sender is told which link won
            assertTrue(rig.events.any { it is LadderLogEvent.SelectionAnnounced && it.generation == 0 })

            at(10_000)
            rig.runner.onTransferEnded()
            at(10_000)
            assertEquals(LadderPhase.TEARING_DOWN, rig.state.phase)
            assertNull(rig.state.dataLink)
            at(10_800)
            assertEquals(LadderPhase.CLOSED, rig.state.phase)
            assertEquals(10_800L, lan.links.single().tornDownAt)
            assertEquals(800L, rig.state.restoreMillis)
            assertTrue(rig.events.any { it is LadderLogEvent.RestoreFinished && it.durationMillis == 800L && !it.overdue })
        }

    @Test
    fun n9_wifiDirectWinsWhileTheLanIsSlowAndTheLanIsTornDown() =
        runTest {
            val lan = provider(LinkKind.LAN, Step(millis = 0))
            val p2p = provider(LinkKind.P2P, Step(millis = 1_500, freqMhz = 5180))
            val rig = rig(sameRouter, listOf(lan, p2p))
            rig.runner.start()
            at(40)
            rig.runner.onThroughputSample(LinkKind.LAN, 2_000_000)
            at(1_040) // the measurement window ends: too slow
            assertEquals(AttemptStatus.STANDBY, rig.state.attempts[0].status)
            assertEquals(listOf(HintCode.LAN_SLOW), rig.state.hints.map { it.code })
            assertSame(lan.links.single(), rig.state.dataLink) // still carrying data

            at(1_540) // group formed at 1500, LinkReady answered at 1520, stream at 1540
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            assertSame(p2p.links.single(), rig.state.dataLink)
            assertEquals("Wi‑Fi Direct · 5 GHz", rig.state.badge?.englishText)
            assertEquals(emptyList(), rig.state.hints)
            assertEquals(1_540L, lan.links.single().tornDownAt)
            assertEquals(LinkReady(LinkKind.P2P, "192.168.49.1", 4000, 5180, null, 2), rig.session.sent.last().second)
            assertEquals(listOf(2), rig.session.selected)
            assertTrue(rig.events.any { it is LadderLogEvent.CandidateStopped && it.index == 0 && it.reason == LinkEndReason.SUPERSEDED })
        }

    @Test
    fun fE1_timeoutsFallThroughWhileBluetoothCarriesTheTransfer() =
        runTest {
            val p2p = provider(LinkKind.P2P, Step(never = true))
            val hotspot = provider(LinkKind.HOTSPOT, Step(millis = 100))
            val rig = rig(peerHosts, listOf(p2p, hotspot), replyMillis = null) // the host never announces anything
            rig.runner.start()
            at(P2P_FORMATION_TIMEOUT_MS - 1)
            assertEquals(LadderPhase.CONNECTING, rig.state.phase)
            assertEquals("Bluetooth", rig.state.badge?.englishText)
            at(P2P_FORMATION_TIMEOUT_MS)
            assertEquals(listOf(P2P_FORMATION_TIMEOUT_MS), p2p.cancelledAt)
            assertEquals(AttemptStatus.FAILED, rig.state.attempts[0].status)
            assertEquals(AttemptStatus.STARTING, rig.state.attempts[1].status)
            // The hotspot joiner waits for the host's credentials, which never come.
            assertTrue(hotspot.joins.isEmpty())
            at(P2P_FORMATION_TIMEOUT_MS + HOTSPOT_TIMEOUT_MS - 1)
            assertEquals(LadderPhase.CONNECTING, rig.state.phase)
            at(P2P_FORMATION_TIMEOUT_MS + HOTSPOT_TIMEOUT_MS)
            assertEquals(LadderPhase.BLUETOOTH_ONLY, rig.state.phase)
            assertEquals("Bluetooth", rig.state.badge?.englishText)
            assertEquals(emptyList(), rig.state.hints)
            val closing = backgroundScope.async { rig.runner.closeAndAwait() }
            at(P2P_FORMATION_TIMEOUT_MS + HOTSPOT_TIMEOUT_MS)
            assertTrue(closing.isCompleted) // once this returns the caller may cancel its scope
            assertEquals(LadderPhase.CLOSED, rig.state.phase)
        }

    @Test
    fun fE2_twoPointFourIsReformedOnceThenAcceptedWithItsHint() =
        runTest {
            val p2p = provider(LinkKind.P2P, Step(millis = 800, freqMhz = 2437, teardownMillis = 300), Step(millis = 900, freqMhz = 2412))
            val rig = rig(hostHere, listOf(p2p))
            rig.runner.start()
            at(840) // formed at 800, answered at 820, stream at 840: 2.4 GHz, so re-form
            assertEquals(1, p2p.hosts.size)
            assertEquals(AttemptStatus.STARTING, rig.state.attempts[0].status)
            assertEquals(2, rig.state.attempts[0].tries)
            at(1_140) // the first group is gone...
            assertEquals(1_140L, p2p.links[0].tornDownAt)
            assertEquals(2, p2p.hosts.size) // ...before the second one is requested
            assertEquals(1_140L, p2p.hosts[1].first)
            at(2_080)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            assertEquals(listOf(0, 1), p2p.hosts.map { it.second.attempt })
            assertTrue(p2p.hosts.all { it.second.requestFiveGhz && it.second.credentials == TEST_CREDENTIALS })
            assertEquals(listOf(2, 3), rig.session.sent.map { it.second.generation })
            assertEquals(listOf(3), rig.session.selected)
            assertEquals(listOf(HintCode.BAND24), rig.state.hints.map { it.code })
            assertEquals("Wi‑Fi Direct · 2.4 GHz", rig.state.badge?.englishText)
            assertSame(p2p.links[1], rig.state.dataLink)
        }

    @Test
    fun fE11_restoreThatTakesTooLongIsReportedOverdue() =
        runTest {
            val p2p = provider(LinkKind.P2P, Step(millis = 500, freqMhz = 5500, teardownMillis = 7_000))
            val rig = rig(hostHere, listOf(p2p))
            rig.runner.start()
            at(540)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            rig.runner.onTransferEnded()
            at(540 + WIFI_RESTORE_BUDGET_MS)
            assertTrue(rig.state.restoreOverdue)
            assertEquals(LadderPhase.TEARING_DOWN, rig.state.phase)
            assertTrue(rig.events.any { it is LadderLogEvent.RestoreOverdue && it.atMillis == 540 + WIFI_RESTORE_BUDGET_MS })
            at(7_540)
            assertEquals(LadderPhase.CLOSED, rig.state.phase)
            assertEquals(7_000L, rig.state.restoreMillis)
        }

    @Test
    fun fE11_prewarmedLinkIsTornDownAfterSixtySecondsIdle() =
        runTest {
            val p2p = provider(LinkKind.P2P, Step(millis = 800, freqMhz = 5180, teardownMillis = 1_000))
            val rig = rig(hostHere, listOf(p2p), config = LadderConfig(p2pCredentials = TEST_CREDENTIALS, prewarm = true))
            rig.runner.start()
            at(840)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            at(LINK_IDLE_TEARDOWN_MS - 1)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            at(LINK_IDLE_TEARDOWN_MS)
            assertEquals(LadderPhase.TEARING_DOWN, rig.state.phase)
            at(LINK_IDLE_TEARDOWN_MS + 1_000)
            assertEquals(LadderPhase.CLOSED, rig.state.phase)
            assertEquals(LINK_IDLE_TEARDOWN_MS + 1_000, p2p.links.single().tornDownAt)
        }

    @Test
    fun fE11_aStartedTransferKeepsThePrewarmedLink() =
        runTest {
            val p2p = provider(LinkKind.P2P, Step(millis = 800, freqMhz = 5180))
            val rig = rig(hostHere, listOf(p2p), config = LadderConfig(p2pCredentials = TEST_CREDENTIALS, prewarm = true))
            rig.runner.start()
            at(5_000)
            rig.runner.onTransferStarted()
            at(LINK_IDLE_TEARDOWN_MS + 30_000)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            rig.runner.onTransferEnded(moreQueued = true)
            at(LINK_IDLE_TEARDOWN_MS + 30_000 + LINK_IDLE_TEARDOWN_MS)
            assertEquals(LadderPhase.CLOSED, rig.state.phase)
        }

    @Test
    fun fE1_missingProviderFallsThroughAtOnce() =
        runTest {
            val hotspot =
                provider(
                    LinkKind.HOTSPOT,
                    Step(millis = 700, freqMhz = 2437, credentials = WifiCredentials("AndroidShare_1234", "k3v9m2xq")),
                )
            val rig = rig(hostHere, listOf(hotspot)) // no Wi-Fi Direct provider on this device
            rig.runner.start()
            at(0)
            assertEquals(LinkEndReason.UNSUPPORTED, rig.state.attempts[0].endReason)
            assertEquals(1, hotspot.hosts.size)
            assertNull(hotspot.hosts.single().second.credentials) // the system picks them (N15)
            at(740)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            assertEquals("Hotspot · 2.4 GHz", rig.state.badge?.englishText)
            // The hotspot's system credentials always travel in LinkReady.
            assertEquals(WifiCredentials("AndroidShare_1234", "k3v9m2xq"), rig.session.sent.single().second.credentials)
            assertEquals(4, rig.session.sent.single().second.generation)
        }

    @Test
    fun fE3_joinerWaitsForTheHostsCredentialsAndDialsItsAddress() =
        runTest {
            val hotspot = provider(LinkKind.HOTSPOT, Step(millis = 1_000, freqMhz = 5180))
            val rig = rig(peerHostsHotspot, listOf(hotspot), replyMillis = null)
            rig.runner.start()
            at(300)
            assertTrue(hotspot.joins.isEmpty())
            val credentials = WifiCredentials("AndroidShare_77", "system-pass-1")
            rig.runner.onPeerLinkReady(LinkReady(LinkKind.HOTSPOT, "192.168.43.1", 4001, 5180, credentials, 4))
            at(300)
            val join = hotspot.joins.single().second
            assertEquals(credentials, join.credentials)
            assertEquals("192.168.43.1", join.hostAddress)
            assertEquals(4001, join.hostPort)
            at(1_320)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            assertEquals("Hotspot · 5 GHz", rig.state.badge?.englishText)
            assertNull(rig.session.sent.single().second.credentials) // a joiner announces none
        }

    @Test
    fun fE3_invalidCredentialsFromTheHostFallThrough() =
        runTest {
            val hotspot = provider(LinkKind.HOTSPOT, Step(millis = 100))
            val rig = rig(peerHostsHotspot, listOf(hotspot), replyMillis = null)
            rig.runner.start()
            at(500)
            rig.runner.onPeerLinkReady(LinkReady(LinkKind.HOTSPOT, "192.168.43.1", 4001, 2437, WifiCredentials("x", "pässwort1"), 4))
            at(500)
            assertTrue(hotspot.joins.isEmpty())
            assertEquals(LinkEndReason.INVALID_CREDENTIALS, rig.state.attempts[0].endReason)
            assertEquals(LadderPhase.BLUETOOTH_ONLY, rig.state.phase)
            val error = rig.events.filterIsInstance<LadderLogEvent.CandidateError>().single()
            assertTrue(error.message.orEmpty().isNotEmpty())
            assertTrue(!error.message.orEmpty().contains("pässwort1"))
        }

    @Test
    fun fE1_providerErrorFallsThroughAndIsLogged() =
        runTest {
            val p2p = provider(LinkKind.P2P, Step(millis = 200, fail = true))
            val hotspot = provider(LinkKind.HOTSPOT, Step(never = true))
            val rig = rig(hostHere, listOf(p2p, hotspot))
            rig.runner.start()
            at(200)
            assertEquals(LinkEndReason.ERROR, rig.state.attempts[0].endReason)
            assertEquals(1, hotspot.hosts.size)
            assertEquals("radio said no", rig.events.filterIsInstance<LadderLogEvent.CandidateError>().single().message)
        }

    @Test
    fun fE2_groupOwnerAnnouncesCredentialsTheJoinerDoesNotHave() =
        runTest {
            val p2p = provider(LinkKind.P2P, Step(millis = 400, freqMhz = 5220))
            val rig =
                rig(
                    hostHere,
                    listOf(p2p),
                    config = LadderConfig(p2pCredentials = TEST_CREDENTIALS, announceCredentials = true, persistent = true),
                )
            rig.runner.start()
            at(440)
            assertEquals(LinkReady(LinkKind.P2P, "192.168.49.1", 4000, 5220, TEST_CREDENTIALS, 2), rig.session.sent.single().second)
            assertTrue(p2p.hosts.single().second.persistent)
        }

    @Test
    fun fE2_joinerWithoutPreSharedCredentialsTakesThemFromLinkReady() =
        runTest {
            val p2p = provider(LinkKind.P2P, Step(millis = 300, freqMhz = null))
            val rig = rig(peerHosts, listOf(p2p), config = LadderConfig(), replyMillis = null)
            rig.runner.start()
            at(100)
            rig.runner.onPeerLinkReady(LinkReady(LinkKind.P2P, "192.168.49.1", 4100, 5745, TEST_CREDENTIALS, 2))
            at(420)
            assertEquals(TEST_CREDENTIALS, p2p.joins.single().second.credentials)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            assertEquals(5745, rig.state.freqMhz) // the host's measurement, since this side had none
            assertEquals(0, rig.session.sent.single().second.freqMhz)
        }

    @Test
    fun t04_lostGroupIsReformedOn24GhzBeforeTheHotspot() =
        runTest {
            val p2p = provider(LinkKind.P2P, Step(millis = 300, freqMhz = 5180), Step(millis = 700, freqMhz = 2437))
            val hotspot = provider(LinkKind.HOTSPOT, Step(never = true))
            val rig = rig(hostHere, listOf(p2p, hotspot))
            rig.runner.start()
            at(340)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            rig.runner.onLinkLost(LinkKind.P2P)
            at(500)
            assertEquals(LadderPhase.CONNECTING, rig.state.phase)
            assertNull(rig.state.dataLink)
            assertNotNull(p2p.links[0].tornDownAt)
            val reform = p2p.hosts[1].second
            assertEquals(1, reform.attempt)
            assertFalse(reform.requestFiveGhz) // 2.4 GHz reaches further (T-04)
            assertTrue(hotspot.hosts.isEmpty())
            at(1_300)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            assertEquals("Wi‑Fi Direct · 2.4 GHz", rig.state.badge?.englishText)
            assertEquals(listOf(HintCode.BAND24), rig.state.hints.map { it.code })
        }

    @Test
    fun fE11_aLinkHandedOverBeforeItsCallWasCancelledIsStillTornDown() =
        runTest {
            // The provider formed the group and handed it over, but its return was lost to the cancellation.
            val lan = provider(LinkKind.LAN, Step(millis = 0))
            val p2p = provider(LinkKind.P2P, Step(millis = 200, freqMhz = 5180, hangAfterUp = true, teardownMillis = 100))
            val rig = rig(sameRouter, listOf(lan, p2p))
            rig.runner.start()
            at(250)
            assertEquals(1, p2p.links.size)
            rig.runner.onThroughputSample(LinkKind.LAN, 12_000_000)
            at(250)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            assertNull(p2p.links.single().tornDownAt)
            at(400)
            assertEquals(350L, p2p.links.single().tornDownAt)
        }

    @Test
    fun fF2_aChannelLearntAfterTheConnectionReachesTheBadge() =
        runTest {
            val p2p = provider(LinkKind.P2P, Step(millis = 300, freqMhz = null))
            val rig = rig(hostHere, listOf(p2p))
            rig.runner.start()
            at(340) // up, but neither side knows the channel: verifying
            assertEquals(AttemptStatus.VERIFYING, rig.state.attempts[0].status)
            assertEquals("Bluetooth", rig.state.badge?.englishText)
            // The peer's group information arrives late, in a repeated LinkReady.
            rig.runner.onPeerLinkReady(LinkReady(LinkKind.P2P, "192.168.49.2", 5000, 5180, null, 2))
            at(340)
            assertEquals(LadderPhase.ACTIVE, rig.state.phase)
            assertEquals("Wi‑Fi Direct · 5 GHz", rig.state.badge?.englishText)
            // Then the channel drops to 2.4 GHz at the edge of range (T-04): badge and hint follow.
            rig.runner.onFrequency(LinkKind.P2P, 2437)
            at(340)
            assertEquals("Wi‑Fi Direct · 2.4 GHz", rig.state.badge?.englishText)
            assertEquals(listOf(HintCode.BAND24), rig.state.hints.map { it.code })
            assertEquals(1, p2p.hosts.size) // no re-form
        }

    @Test
    fun peerLinkReadyOutsideThisRunIsIgnored() =
        runTest {
            val rig = rig(peerHosts, emptyList(), config = LadderConfig(generationBase = 6))
            rig.runner.start()
            at(0)
            rig.runner.onPeerLinkReady(LinkReady(LinkKind.P2P, null, null, 0, null, 2)) // the previous run
            rig.runner.onPeerLinkReady(LinkReady(LinkKind.LAN, null, null, 0, null, 8)) // generation 8 is Wi-Fi Direct
            rig.runner.onPeerLinkReady(LinkReady("aware", null, null, 0, null, 6))
            rig.runner.onPeerSelected(8) // this device is the authority: it takes no orders
            at(0)
            assertEquals(listOf(2, 8, 6), rig.events.filterIsInstance<LadderLogEvent.PeerLinkReadyIgnored>().map { it.generation })
            assertEquals(listOf(8), rig.events.filterIsInstance<LadderLogEvent.PeerSelectionIgnored>().map { it.generation })
            assertEquals(LadderPhase.BLUETOOTH_ONLY, rig.state.phase) // no providers at all
        }

    @Test
    fun fE11_closeDuringSetupCancelsEverythingAndRestores() =
        runTest {
            val lan = provider(LinkKind.LAN, Step(millis = 0, teardownMillis = 50))
            val p2p = provider(LinkKind.P2P, Step(never = true))
            val rig = rig(sameRouter, listOf(lan, p2p), streamMillis = mapOf(LinkKind.LAN to null))
            rig.runner.start()
            at(500)
            rig.runner.close()
            at(500)
            assertEquals(listOf(500L), p2p.cancelledAt)
            at(550)
            assertEquals(LadderPhase.CLOSED, rig.state.phase)
            assertEquals(550L, lan.links.single().tornDownAt)
            assertTrue(rig.events.filterIsInstance<LadderLogEvent.CandidateStopped>().all { it.reason == LinkEndReason.TEARDOWN })
            // Nothing is accepted after the ladder closed.
            rig.runner.start()
            rig.runner.onTransferStarted()
            at(1_000)
            assertEquals(LadderPhase.CLOSED, rig.state.phase)
        }

    @Test
    fun n8_browserPlansAreNotRunByTheRunner() =
        runTest {
            val browser = plan(phone(), LinkFacts.browser())
            assertTrue(browser.isBrowserPlan)
            assertFailsWith<IllegalArgumentException> { rig(browser, emptyList()) }
        }

    @Test
    fun generationsArePerRungKindAndAttempt() {
        assertEquals(0, LadderGenerations.of(0, LinkMode.LAN, 0))
        assertEquals(2, LadderGenerations.of(0, LinkMode.P2P, 0))
        assertEquals(3, LadderGenerations.of(0, LinkMode.P2P_LEGACY, 1))
        assertEquals(5, LadderGenerations.of(0, LinkMode.HOTSPOT, 1))
        assertEquals(10, LadderGenerations.of(6, LinkMode.HOTSPOT, 0))
        assertEquals(LinkKind.P2P, LadderGenerations.kindOf(6, 9))
        assertNull(LadderGenerations.kindOf(6, 12))
        assertNull(LadderGenerations.kindOf(6, 5))
        assertNull(LadderGenerations.kindOf(0, -1))
        assertFailsWith<IllegalArgumentException> { LadderGenerations.of(0, LinkMode.BLUETOOTH, 0) }
        assertFailsWith<IllegalArgumentException> { LadderGenerations.of(0, LinkMode.P2P, 2) }
        assertFailsWith<IllegalArgumentException> { LadderConfig(generationBase = -1) }
    }

    // ---- Two devices back to back (N9: both must end on the same link) ----

    /** One device's engine in a pair: messages reach the other device after [latency]; a stream opens on both or neither. */
    private class PairSession(
        private val scope: CoroutineScope,
        private val latency: Long,
        private val streamMillis: Long,
    ) : LadderSession {
        lateinit var runner: LadderRunner
        lateinit var peer: PairSession
        private val streams = HashMap<Int, CompletableDeferred<Unit>>()

        fun stream(generation: Int): CompletableDeferred<Unit> = streams.getOrPut(generation) { CompletableDeferred() }

        override suspend fun sendLinkReady(message: LinkReady) {
            scope.launch {
                delay(latency)
                peer.runner.onPeerLinkReady(message)
            }
        }

        override suspend fun openStream(
            link: ActiveLink,
            peer: LinkReady,
        ) {
            stream(peer.generation).complete(Unit)
            this.peer.stream(peer.generation).await()
            delay(streamMillis)
        }

        override suspend fun sendLinkSelected(generation: Int) {
            scope.launch {
                delay(latency)
                peer.runner.onPeerSelected(generation)
            }
        }

        /** The other device notices a link this one tore down. */
        fun lost(kind: LinkKind) {
            scope.launch {
                delay(latency)
                peer.runner.onLinkLost(kind)
            }
        }
    }

    /** Timings of one device in a pair run. */
    private data class Timing(
        val lanMillis: Long,
        val p2pMillis: Long,
        val latency: Long,
        val streamMillis: Long,
        /** What this device's meter counts on the LAN, per second. */
        val lanBytesPerSecond: Long,
    )

    private class Pair2(
        val sender: LadderRunner,
        val receiver: LadderRunner,
    )

    private fun TestScope.pair(
        senderTiming: Timing,
        receiverTiming: Timing,
    ): Pair2 {
        val now = { testScheduler.currentTime }
        val clock = MonotonicClock { testScheduler.currentTime }
        val config = LadderConfig(p2pCredentials = TEST_CREDENTIALS)

        fun device(
            plan: LadderPlan,
            timing: Timing,
        ): Pair<LadderRunner, PairSession> {
            val session = PairSession(backgroundScope, timing.latency, timing.streamMillis)
            val lan = FakeProvider(LinkKind.LAN, now, listOf(Step(millis = timing.lanMillis)))
            val p2p = FakeProvider(LinkKind.P2P, now, listOf(Step(millis = timing.p2pMillis, freqMhz = 5180)))
            val hotspot = FakeProvider(LinkKind.HOTSPOT, now, listOf(Step(never = true)))
            for (provider in listOf(lan, p2p, hotspot)) provider.onTeardown = session::lost
            val runner = LadderRunner(plan, listOf(lan, p2p, hotspot), session, backgroundScope, clock, config)
            session.runner = runner
            // The engine's 250 ms throughput samples while the LAN is being measured.
            backgroundScope.launch {
                while (true) {
                    delay(250)
                    if (runner.state.value.attempts.any { it.candidate.mode == LinkMode.LAN && it.status == AttemptStatus.MEASURING }) {
                        runner.onThroughputSample(LinkKind.LAN, timing.lanBytesPerSecond / 4)
                    }
                }
            }
            return runner to session
        }
        val (sender, senderSession) = device(plan(sameRouterFacts, sameRouterFacts, SENDER), senderTiming)
        val (receiver, receiverSession) = device(plan(sameRouterFacts, sameRouterFacts, RECEIVER), receiverTiming)
        senderSession.peer = receiverSession
        receiverSession.peer = senderSession
        return Pair2(sender, receiver)
    }

    private fun TestScope.assertSameLink(
        pair: Pair2,
        expected: LinkMode? = null,
        context: String = "",
    ) {
        pair.sender.start()
        pair.receiver.start()
        at(10_000)
        val sender = pair.sender.state.value
        val receiver = pair.receiver.state.value
        assertEquals(LadderPhase.ACTIVE, receiver.phase, "receiver $context")
        assertEquals(LadderPhase.ACTIVE, sender.phase, "sender $context")
        assertEquals(receiver.dataCandidate?.mode, sender.dataCandidate?.mode, "data link $context")
        if (expected != null) assertEquals(expected, receiver.dataCandidate?.mode, context)
        assertNotNull(sender.dataLink, context)
        assertNotNull(receiver.dataLink, context)
    }

    @Test
    fun n9_theSenderFollowsTheReceiversSlowLanVerdict() =
        runTest {
            // The sender's meter (bytes sent) reaches 10 MB at 750 ms, the receiver's (bytes received) never does, and
            // Wi-Fi Direct connects at 940 ms, inside the receiver's window: both must end on Wi-Fi Direct.
            val pair =
                pair(
                    Timing(lanMillis = 80, p2pMillis = 910, latency = 20, streamMillis = 10, lanBytesPerSecond = 14_000_000),
                    Timing(lanMillis = 100, p2pMillis = 900, latency = 20, streamMillis = 10, lanBytesPerSecond = 8_400_000),
                )
            assertSameLink(pair, LinkMode.P2P)
        }

    @Test
    fun n9_theSenderFollowsTheReceiversFastLanVerdict() =
        runTest {
            // The other way round: the receiver sees a fast LAN, the sender a slow one, and Wi-Fi Direct is quick.
            val pair =
                pair(
                    Timing(lanMillis = 0, p2pMillis = 700, latency = 30, streamMillis = 10, lanBytesPerSecond = 6_000_000),
                    Timing(lanMillis = 20, p2pMillis = 1_200, latency = 30, streamMillis = 10, lanBytesPerSecond = 14_000_000),
                )
            assertSameLink(pair, LinkMode.LAN)
        }

    @Test
    fun n9_bothDevicesEndOnTheSameLinkUnderJitter() {
        val random = Random(9)
        repeat(40) { run ->
            fun timing() =
                Timing(
                    lanMillis = random.nextLong(0, 300),
                    p2pMillis = random.nextLong(300, 3_000),
                    latency = random.nextLong(5, 60),
                    streamMillis = random.nextLong(5, 40),
                    lanBytesPerSecond = random.nextLong(6_000_000, 14_000_000),
                )
            val senderTiming = timing()
            val receiverTiming = timing()
            runTest { assertSameLink(pair(senderTiming, receiverTiming), context = "run $run: $senderTiming / $receiverTiming") }
        }
    }
}
