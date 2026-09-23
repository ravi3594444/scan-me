package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.ladder.Caps.onWifi
import com.constrivo.drop.core.ladder.Side.LOCAL
import com.constrivo.drop.core.ladder.Side.PEER
import com.constrivo.drop.core.protocol.LinkIntent
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkOption
import com.constrivo.drop.core.protocol.TransferRole.RECEIVER
import com.constrivo.drop.core.protocol.TransferRole.SENDER
import com.constrivo.drop.core.protocol.WifiCredentials
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S5: the elected group owner generates the credentials; the receiver's choice is final. */
class LadderNegotiationTest {
    private val receiverCredentials = OTHER_CREDENTIALS
    private val never: () -> WifiCredentials = { error("no credentials expected") }

    /** Runs the whole exchange between a sender with [senderFacts] and a receiver with [receiverFacts]. */
    private fun exchange(
        senderFacts: LinkFacts,
        receiverFacts: LinkFacts,
        senderSees: LinkFacts = receiverFacts,
        receiverSees: LinkFacts = senderFacts,
    ): Triple<LinkAgreement, LinkIntent?, LinkAgreement> =
        exchange(
            LadderInput(senderFacts, senderSees, SENDER),
            LadderInput(receiverFacts, receiverSees, RECEIVER),
        ) { error("the sender generated early") }

    /** The whole exchange from each device's own [LadderInput]. */
    private fun exchange(
        senderInput: LadderInput,
        receiverInput: LadderInput,
        senderGenerates: () -> WifiCredentials,
    ): Triple<LinkAgreement, LinkIntent?, LinkAgreement> {
        val senderPlan = LadderPlanner.plan(senderInput)
        val offered = if (senderPlan.groupOwner == LOCAL) TEST_CREDENTIALS else null
        val options = LadderNegotiation.offerOptions(senderPlan, offered, "192.168.1.20", 5000)
        val decision = LadderNegotiation.accept(LadderPlanner.plan(receiverInput), options) { receiverCredentials }
        val sender = LadderNegotiation.adopt(senderPlan, offered, decision.intent, senderGenerates)
        return Triple(sender, decision.intent, decision.agreement)
    }

    /** The Wi-Fi rungs as (mode, physical host), the host named as the sender (`S`) or the receiver (`R`). */
    private fun rungs(
        agreement: LinkAgreement,
        isSender: Boolean,
    ): List<Pair<LinkMode, String>> =
        agreement.plan.candidates.filter { it.mode.isWifi }.map { candidate ->
            val hostIsSender = (candidate.host == LOCAL) == isSender
            candidate.mode to if (hostIsSender) "S" else "R"
        }

    @Test
    fun s5_senderIsGroupOwnerAndSharesItsCredentialsInTheOffer() {
        val senderPlan = plan(phone(Caps.FLAGSHIP), phone(Caps.MIDRANGE), SENDER)
        assertEquals(LOCAL, senderPlan.groupOwner)
        assertEquals(
            listOf(LinkOption(LinkKind.P2P, credentials = TEST_CREDENTIALS), LinkOption(LinkKind.HOTSPOT)),
            LadderNegotiation.offerOptions(senderPlan, TEST_CREDENTIALS),
        )
        val (sender, intent, receiver) = exchange(phone(Caps.FLAGSHIP), phone(Caps.MIDRANGE))
        assertEquals(LinkIntent(LinkKind.P2P), intent)
        assertEquals(LOCAL, sender.plan.groupOwner)
        assertEquals(TEST_CREDENTIALS, sender.p2pCredentials)
        assertFalse(sender.announceCredentials)
        assertEquals(PEER, receiver.plan.groupOwner)
        assertEquals(TEST_CREDENTIALS, receiver.p2pCredentials)
    }

    @Test
    fun s5_receiverIsGroupOwnerAndSharesItsCredentialsInTheAccept() {
        val (sender, intent, receiver) = exchange(phone(Caps.MIDRANGE), phone(Caps.FLAGSHIP))
        assertEquals(LinkIntent(LinkKind.P2P, receiverCredentials), intent)
        assertEquals(LOCAL, receiver.plan.groupOwner)
        assertEquals(receiverCredentials, receiver.p2pCredentials)
        assertEquals(PEER, sender.plan.groupOwner)
        assertEquals(receiverCredentials, sender.p2pCredentials)
        assertFalse(sender.announceCredentials)
    }

    @Test
    fun s5_tieGoesToTheReceiver() {
        val (sender, intent, receiver) = exchange(phone(), phone())
        assertEquals(LinkIntent(LinkKind.P2P, receiverCredentials), intent)
        assertEquals(LOCAL, receiver.plan.groupOwner)
        assertEquals(PEER, sender.plan.groupOwner)
    }

    @Test
    fun s5_whenTheFactsDisagreeTheReceiverHostsIfItCan() {
        // The sender cannot host right now and knows it; the receiver does not, and elects the sender.
        val senderFacts = phone(Caps.FLAGSHIP, hostingAllowed = false)
        val (sender, intent, receiver) = exchange(senderFacts, phone(Caps.MIDRANGE), receiverSees = phone(Caps.FLAGSHIP))
        assertEquals(LinkIntent(LinkKind.P2P, receiverCredentials), intent)
        assertEquals(LOCAL, receiver.plan.groupOwner)
        assertEquals(ElectionReason.AGREED, receiver.plan.groupOwnerElection?.reason)
        assertEquals(PEER, sender.plan.groupOwner)
        assertEquals(receiverCredentials, sender.p2pCredentials)
        // The hotspot goes with the agreed group owner, so the two never both wait to join it.
        assertEquals(LOCAL, receiver.plan.hotspotHost)
        assertEquals(PEER, sender.plan.hotspotHost)
        assertEquals(rungs(sender, isSender = true), rungs(receiver, isSender = false))
    }

    @Test
    fun s5_aHotspotHostThatMayNotHostLeavesItOutOfTheOffer() {
        // Neither phone has Wi-Fi Direct; the sender wins the hotspot election on battery but its hotspot is in use.
        val (sender, intent, receiver) =
            exchange(
                phone(Caps.NO_WIFI_DIRECT, battery = 90, hostingAllowed = false),
                phone(Caps.NO_WIFI_DIRECT, battery = 10),
                receiverSees = phone(Caps.NO_WIFI_DIRECT, battery = 90),
            )
        assertNull(intent)
        assertEquals(listOf(LinkMode.BLUETOOTH), sender.plan.candidates.map { it.mode })
        assertEquals(listOf(LinkMode.BLUETOOTH), receiver.plan.candidates.map { it.mode })
    }

    @Test
    fun n6_theReceiverKeepsTheLanTheSenderFoundOverMdns() {
        // Only the Mac sees the phone's mDNS record (multicast filtered the other way), and their hints differ.
        val (sender, intent, receiver) =
            exchange(
                LadderInput(laptop(Caps.MAC, HOME_ROUTER), phone(Caps.FLAGSHIP, OFFICE_ROUTER), SENDER, lanReachable = true),
                LadderInput(phone(Caps.FLAGSHIP, OFFICE_ROUTER), laptop(Caps.MAC, HOME_ROUTER), RECEIVER, lanReachable = false),
            ) { TEST_CREDENTIALS }
        assertEquals(LinkIntent(LinkKind.P2P, receiverCredentials), intent)
        val expected = listOf(LinkMode.LAN, LinkMode.P2P_LEGACY, LinkMode.HOTSPOT, LinkMode.BLUETOOTH)
        assertEquals(expected, sender.plan.candidates.map { it.mode })
        assertEquals(expected, receiver.plan.candidates.map { it.mode })
        // The other way round the receiver sees a LAN the sender did not offer: nobody probes it.
        val (s2, _, r2) =
            exchange(
                LadderInput(laptop(Caps.MAC, HOME_ROUTER), phone(Caps.FLAGSHIP, OFFICE_ROUTER), SENDER, lanReachable = false),
                LadderInput(phone(Caps.FLAGSHIP, OFFICE_ROUTER), laptop(Caps.MAC, HOME_ROUTER), RECEIVER, lanReachable = true),
            ) { TEST_CREDENTIALS }
        assertEquals(-1, s2.plan.indexOf(LinkMode.LAN))
        assertEquals(-1, r2.plan.indexOf(LinkMode.LAN))
    }

    @Test
    fun fE2_bothDevicesRunTheSameRungsWithTheSameHostsAfterTheExchange() {
        // Each device knows its own hosting permission, radios and mDNS view; the peer's view of them is the default.
        val random = Random(20260923)
        val flags = listOf(Flag.WIFI_5GHZ, Flag.WIFI_6_OR_NEWER, Flag.WIFI_DIRECT, Flag.CAN_HOST_P2P_5GHZ, Flag.CAN_HOST_LOCAL_HOTSPOT)
        val hints = listOf(NetworkHint.NONE, HOME_ROUTER, OFFICE_ROUTER)
        repeat(3_000) {
            fun device(): Pair<LinkFacts, RadioState> {
                var caps = Capabilities.NONE
                for (flag in flags) if (random.nextBoolean()) caps += flag
                if (random.nextInt(3) == 0) caps = caps.onWifi(random.nextBoolean())
                val battery = if (random.nextBoolean()) random.nextInt(0, 101) else null
                val hint = hints[random.nextInt(hints.size)]
                val facts =
                    if (random.nextInt(4) == 0) {
                        laptop(if (random.nextBoolean()) Caps.MAC else Caps.WINDOWS, hint)
                    } else {
                        phone(caps, hint, battery, hostingAllowed = random.nextInt(4) != 0)
                    }
                val radio = RadioState(wifiEnabled = random.nextInt(8) != 0, bluetoothEnabled = random.nextInt(8) != 0)
                return facts to radio
            }
            val (a, aRadio) = device()
            val (b, bRadio) = device()
            // What each device publishes about itself: everything but the private hosting permission.
            val aSeen = a.copy(hostingAllowed = true)
            val bSeen = b.copy(hostingAllowed = true)
            val (sender, _, receiver) =
                exchange(
                    LadderInput(a, bSeen, SENDER, localRadio = aRadio, lanReachable = random.nextBoolean()),
                    LadderInput(b, aSeen, RECEIVER, localRadio = bRadio, lanReachable = random.nextBoolean()),
                ) { TEST_CREDENTIALS }
            val s = rungs(sender, isSender = true)
            val r = rungs(receiver, isSender = false)
            val facts = "sender $a $aRadio, receiver $b $bRadio"
            // The LAN is probed by both or by neither, and a rung both run has the same host on both.
            assertEquals(s.any { it.first == LinkMode.LAN }, r.any { it.first == LinkMode.LAN }, "LAN of $facts")
            for (kind in listOf(LinkKind.P2P, LinkKind.HOTSPOT)) {
                val sHost = s.firstOrNull { it.first.kind == kind }
                val rHost = r.firstOrNull { it.first.kind == kind }
                if (sHost != null && rHost != null) assertEquals(sHost, rHost, "$kind of $facts")
            }
            // Only a device that may not host can leave the other waiting on a rung (it cannot say so in the Accept).
            if (a.hostingAllowed && b.hostingAllowed) assertEquals(s, r, "rungs of $facts")
        }
    }

    @Test
    fun s5_senderHostsAndAnnouncesWhenOnlyTheAcceptSettledIt() {
        // The sender offered Wi-Fi Direct without credentials, but the receiver (a Mac) cannot host.
        val receiverPlan = plan(laptop(Caps.MAC), phone(), RECEIVER)
        val decision = LadderNegotiation.accept(receiverPlan, listOf(LinkOption(LinkKind.P2P), LinkOption(LinkKind.HOTSPOT)), never)
        assertEquals(LinkIntent(LinkKind.P2P), decision.intent)
        assertEquals(PEER, decision.agreement.plan.groupOwner)
        assertNull(decision.agreement.p2pCredentials) // they arrive in the phone's LinkReady

        val senderPlan = plan(phone(), laptop(Caps.MAC), SENDER)
        val sender = LadderNegotiation.adopt(senderPlan, null, decision.intent) { TEST_CREDENTIALS }
        assertEquals(LOCAL, sender.plan.groupOwner)
        assertEquals(TEST_CREDENTIALS, sender.p2pCredentials)
        assertTrue(sender.announceCredentials)
        assertTrue(sender.config().announceCredentials)
    }

    @Test
    fun s5_onlyOfferedRungsAreKept() {
        val receiverPlan = plan(phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), RECEIVER)
        assertEquals(LinkMode.LAN, receiverPlan.candidates.first().mode)
        // The sender offered no LAN and no Wi-Fi Direct.
        val decision = LadderNegotiation.accept(receiverPlan, listOf(LinkOption(LinkKind.HOTSPOT), LinkOption("aware")), never)
        assertEquals(LinkIntent(LinkKind.HOTSPOT), decision.intent)
        assertEquals(listOf(LinkMode.HOTSPOT, LinkMode.BLUETOOTH), decision.agreement.plan.candidates.map { it.mode })
        assertNull(decision.agreement.p2pCredentials)

        val lanOnly = LadderNegotiation.accept(receiverPlan, listOf(LinkOption(LinkKind.LAN, address = "192.168.1.20", port = 5000)), never)
        assertEquals(LinkIntent(LinkKind.LAN), lanOnly.intent)
        assertEquals(listOf(LinkMode.LAN, LinkMode.BLUETOOTH), lanOnly.agreement.plan.candidates.map { it.mode })

        val nothing = LadderNegotiation.accept(receiverPlan, emptyList(), never)
        assertNull(nothing.intent)
        assertEquals(listOf(LinkMode.BLUETOOTH), nothing.agreement.plan.candidates.map { it.mode })
    }

    @Test
    fun s5_sameRouterKeepsTheParallelLanProbe() {
        val sender = plan(phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), SENDER)
        val options = LadderNegotiation.offerOptions(sender, null, "192.168.1.20", 5000)
        assertEquals(LinkOption(LinkKind.LAN, address = "192.168.1.20", port = 5000), options.first())
        val decision =
            LadderNegotiation.accept(
                plan(phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), RECEIVER),
                options,
            ) {
                receiverCredentials
            }
        assertEquals(LinkIntent(LinkKind.P2P, receiverCredentials), decision.intent)
        assertTrue(decision.agreement.plan.parallelLanProbe)
        val adopted = LadderNegotiation.adopt(sender, null, decision.intent, never)
        assertTrue(adopted.plan.parallelLanProbe)
        assertEquals(PEER, adopted.plan.groupOwner)
    }

    @Test
    fun s5_senderDropsWhatTheReceiverDidNotPick() {
        val sender = plan(phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), SENDER)
        assertEquals(
            listOf(LinkMode.LAN, LinkMode.BLUETOOTH),
            LadderNegotiation.adopt(sender, null, null, never).plan.candidates.map {
                it.mode
            },
        )
        assertEquals(
            listOf(LinkMode.LAN, LinkMode.BLUETOOTH),
            LadderNegotiation.adopt(sender, null, LinkIntent(LinkKind.LAN), never).plan.candidates.map {
                it.mode
            },
        )
        assertEquals(
            listOf(LinkMode.LAN, LinkMode.HOTSPOT, LinkMode.BLUETOOTH),
            LadderNegotiation.adopt(sender, null, LinkIntent(LinkKind.HOTSPOT), never).plan.candidates.map { it.mode },
        )
        assertEquals(
            listOf(LinkMode.LAN, LinkMode.BLUETOOTH),
            LadderNegotiation.adopt(sender, null, LinkIntent("aware"), never).plan.candidates.map {
                it.mode
            },
        )
    }

    @Test
    fun s5_invalidCredentialsAreNeverUsed() {
        val bad = WifiCredentials("MyHomeWifi", "abcdefghijkm") // not a DIRECT- name
        // From the sender: treated as absent, so the receiver hosts.
        val decision =
            LadderNegotiation.accept(
                plan(
                    phone(Caps.MIDRANGE),
                    phone(Caps.FLAGSHIP),
                    RECEIVER,
                ),
                listOf(LinkOption(LinkKind.P2P, bad)),
                {
                    receiverCredentials
                },
            )
        assertEquals(LinkIntent(LinkKind.P2P, receiverCredentials), decision.intent)
        // From the receiver: the Wi-Fi Direct rung is dropped.
        val adopted =
            LadderNegotiation.adopt(
                plan(phone(Caps.MIDRANGE), phone(Caps.FLAGSHIP), SENDER),
                null,
                LinkIntent(LinkKind.P2P, bad),
                never,
            )
        assertNull(adopted.plan.p2pCandidate)
        assertNull(adopted.p2pCredentials)
        assertEquals(listOf(LinkMode.HOTSPOT, LinkMode.BLUETOOTH), adopted.plan.candidates.map { it.mode })
        // A generator that returns bad values is refused.
        assertFailsWith<LinkCredentialsException> {
            LadderNegotiation.accept(plan(phone(), phone(), RECEIVER), listOf(LinkOption(LinkKind.P2P)), { bad })
        }
    }

    @Test
    fun agreementsAreConsistent() {
        val goPlan = plan(phone(Caps.FLAGSHIP), phone(Caps.MIDRANGE), SENDER)
        assertFailsWith<IllegalArgumentException> { LadderNegotiation.offerOptions(goPlan, null) }
        assertFailsWith<IllegalArgumentException> { LinkAgreement(goPlan, null) }
        assertFailsWith<IllegalArgumentException> {
            LinkAgreement(plan(phone(Caps.MIDRANGE), phone(Caps.FLAGSHIP)), TEST_CREDENTIALS, announceCredentials = true)
        }
        val config =
            LinkAgreement(
                goPlan,
                TEST_CREDENTIALS,
                announceCredentials = true,
            ).config(generationBase = 6, persistent = true, prewarm = true)
        assertEquals(LadderConfig(6, TEST_CREDENTIALS, announceCredentials = true, persistent = true, prewarm = true), config)
    }
}
