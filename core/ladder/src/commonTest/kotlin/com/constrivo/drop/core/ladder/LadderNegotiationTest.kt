package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.ladder.Caps.onWifi
import com.constrivo.drop.core.ladder.Side.LOCAL
import com.constrivo.drop.core.ladder.Side.PEER
import com.constrivo.drop.core.protocol.LinkIntent
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.LinkOption
import com.constrivo.drop.core.protocol.TransferRole.RECEIVER
import com.constrivo.drop.core.protocol.TransferRole.SENDER
import com.constrivo.drop.core.protocol.WifiCredentials
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
    ): Triple<LinkAgreement, LinkIntent?, LinkAgreement> {
        val senderPlan = plan(senderFacts, senderSees, SENDER)
        val offered = if (senderPlan.groupOwner == LOCAL) TEST_CREDENTIALS else null
        val options = LadderNegotiation.offerOptions(senderPlan, offered)
        val decision = LadderNegotiation.accept(plan(receiverFacts, receiverSees, RECEIVER), options) { receiverCredentials }
        val sender = LadderNegotiation.adopt(senderPlan, offered, decision.intent) { error("the sender generated early") }
        return Triple(sender, decision.intent, decision.agreement)
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
