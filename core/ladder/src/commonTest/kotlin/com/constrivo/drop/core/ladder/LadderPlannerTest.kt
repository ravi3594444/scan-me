package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.ladder.Caps.onWifi
import com.constrivo.drop.core.ladder.LinkMode.BLUETOOTH
import com.constrivo.drop.core.ladder.LinkMode.HOTSPOT
import com.constrivo.drop.core.ladder.LinkMode.LAN
import com.constrivo.drop.core.ladder.LinkMode.P2P
import com.constrivo.drop.core.ladder.LinkMode.P2P_LEGACY
import com.constrivo.drop.core.ladder.Side.LOCAL
import com.constrivo.drop.core.ladder.Side.PEER
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.TransferRole.RECEIVER
import com.constrivo.drop.core.protocol.TransferRole.SENDER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Architecture §4 with N6, N8, N9 and N10: the flag combinations that matter, as a table. */
class LadderPlannerTest {
    private data class Case(
        val name: String,
        val input: LadderInput,
        /** Expected rungs as (mode, host). */
        val rungs: List<Pair<LinkMode, Side?>>,
        val parallel: Boolean = false,
        /** Whether the Wi-Fi Direct rung asks for 5 GHz (null: no such rung). */
        val requestFiveGhz: Boolean? = null,
        val hints: List<HintCode> = emptyList(),
    )

    private val cases =
        listOf(
            Case(
                "T-15: two phones on mobile data skip the LAN and go straight to Wi-Fi Direct",
                input(phone(), phone()),
                listOf(P2P to PEER, HOTSPOT to PEER, BLUETOOTH to null),
                requestFiveGhz = true,
            ),
            Case(
                "T-16: same router: the LAN is probed in parallel with Wi-Fi Direct (N9)",
                input(phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER)),
                listOf(LAN to LOCAL, P2P to PEER, HOTSPOT to PEER, BLUETOOTH to null),
                parallel = true,
                requestFiveGhz = true,
            ),
            Case(
                "different routers: different hints skip the LAN",
                input(phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), phone(Caps.FLAGSHIP.onWifi(true), OFFICE_ROUTER)),
                listOf(P2P to PEER, HOTSPOT to PEER, BLUETOOTH to null),
                requestFiveGhz = true,
            ),
            Case(
                "N6: a live mDNS record means a shared LAN even when the hints disagree",
                input(
                    phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER),
                    phone(Caps.FLAGSHIP.onWifi(true), OFFICE_ROUTER),
                    lanReachable = true,
                ),
                listOf(LAN to LOCAL, P2P to PEER, HOTSPOT to PEER, BLUETOOTH to null),
                parallel = true,
                requestFiveGhz = true,
            ),
            Case(
                "the receiver listens on the LAN when this device receives (S5: the sender offers its address)",
                input(phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), role = RECEIVER),
                listOf(LAN to PEER, P2P to LOCAL, HOTSPOT to LOCAL, BLUETOOTH to null),
                parallel = true,
                requestFiveGhz = true,
            ),
            Case(
                "2.4 GHz-only peer: the 5 GHz device hosts and does not ask for 5 GHz",
                input(phone(Caps.FLAGSHIP), phone(Caps.BAND24_ONLY)),
                listOf(P2P to LOCAL, HOTSPOT to LOCAL, BLUETOOTH to null),
                requestFiveGhz = false,
            ),
            Case(
                "no Wi-Fi Direct on either phone: the local-only hotspot",
                input(phone(Caps.NO_WIFI_DIRECT), phone(Caps.NO_WIFI_DIRECT)),
                listOf(HOTSPOT to PEER, BLUETOOTH to null),
            ),
            Case(
                "N8: a phone without Wi-Fi Direct joins the other's group as a legacy client, hotspot last",
                input(phone(Caps.NOTHING), phone(Caps.FLAGSHIP)),
                listOf(P2P_LEGACY to PEER, HOTSPOT to PEER, BLUETOOTH to null),
                requestFiveGhz = true,
            ),
            Case(
                "nothing in common: Bluetooth only",
                input(phone(Caps.NOTHING), phone(Caps.NOTHING)),
                listOf(BLUETOOTH to null),
            ),
            Case(
                "Wi-Fi off here: Bluetooth only, with the slow-mode hint",
                input(phone(), phone(), localRadio = RadioState(wifiEnabled = false, bluetoothEnabled = true)),
                listOf(BLUETOOTH to null),
                hints = listOf(HintCode.BT_FALLBACK),
            ),
            Case(
                "Wi-Fi off on the peer: Bluetooth only, with the slow-mode hint",
                input(phone(), phone(), peerRadio = RadioState(wifiEnabled = false)),
                listOf(BLUETOOTH to null),
                hints = listOf(HintCode.BT_FALLBACK),
            ),
            Case(
                "Wi-Fi off but both on the same wired LAN: the LAN still works, no slow-mode hint",
                input(phone(Caps.FLAGSHIP, HOME_ROUTER), phone(Caps.FLAGSHIP, HOME_ROUTER), peerRadio = RadioState(wifiEnabled = false)),
                listOf(LAN to LOCAL, BLUETOOTH to null),
            ),
            Case(
                "Bluetooth off here: no Bluetooth rung",
                input(phone(), phone(), localRadio = RadioState(wifiEnabled = true, bluetoothEnabled = false)),
                listOf(P2P to PEER, HOTSPOT to PEER),
                requestFiveGhz = true,
            ),
            Case(
                "N8: a Mac joins the phone's group as a legacy WPA2 client",
                input(phone(), laptop(Caps.MAC)),
                listOf(P2P_LEGACY to LOCAL, HOTSPOT to LOCAL, BLUETOOTH to null),
                requestFiveGhz = true,
            ),
            Case(
                "N8: a Mac sending to a phone joins the phone's group",
                input(laptop(Caps.MAC), phone()),
                listOf(P2P_LEGACY to PEER, HOTSPOT to PEER, BLUETOOTH to null),
                requestFiveGhz = true,
            ),
            Case(
                "N10: Windows publishes Wi-Fi Direct but joins as a legacy client and never hosts",
                input(phone(Caps.MIDRANGE), laptop(Caps.WINDOWS), role = RECEIVER),
                listOf(P2P_LEGACY to LOCAL, HOTSPOT to LOCAL, BLUETOOTH to null),
                requestFiveGhz = true,
            ),
            Case(
                "N9: a Mac seen over mDNS waits for the LAN verdict (joining would leave the LAN)",
                input(phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), laptop(Caps.MAC.onWifi(true), HOME_ROUTER), lanReachable = true),
                listOf(LAN to LOCAL, P2P_LEGACY to LOCAL, HOTSPOT to LOCAL, BLUETOOTH to null),
                parallel = false,
                requestFiveGhz = true,
            ),
            Case(
                "N6: equal hints without mDNS do not hold up a Mac's legacy join (hints collide, 192.168.1.1)",
                input(phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), laptop(Caps.MAC.onWifi(true), HOME_ROUTER)),
                listOf(P2P_LEGACY to LOCAL, HOTSPOT to LOCAL, BLUETOOTH to null),
                requestFiveGhz = true,
            ),
            Case(
                "N6: equal hints without mDNS still race two phones' Wi-Fi Direct formation (it costs nothing)",
                input(phone(Caps.MIDRANGE, HOME_ROUTER), phone(Caps.MIDRANGE, HOME_ROUTER)),
                listOf(LAN to LOCAL, P2P to PEER, HOTSPOT to PEER, BLUETOOTH to null),
                parallel = true,
                requestFiveGhz = true,
            ),
            Case(
                "N6: equal hints without mDNS do not hold up a hotspot join between phones without Wi-Fi Direct",
                input(phone(Caps.NO_WIFI_DIRECT, HOME_ROUTER), phone(Caps.NO_WIFI_DIRECT, HOME_ROUTER)),
                listOf(HOTSPOT to PEER, BLUETOOTH to null),
            ),
            Case(
                "N6: with mDNS the hotspot joiner waits for the LAN verdict",
                input(phone(Caps.NO_WIFI_DIRECT, HOME_ROUTER), phone(Caps.NO_WIFI_DIRECT, HOME_ROUTER), lanReachable = true),
                listOf(LAN to LOCAL, HOTSPOT to PEER, BLUETOOTH to null),
            ),
            Case(
                "N8: a browser joins the phone's group; no Bluetooth towards a browser",
                input(phone(), LinkFacts.browser()),
                listOf(P2P_LEGACY to LOCAL, HOTSPOT to LOCAL),
                requestFiveGhz = true,
            ),
            Case(
                "two laptops on different networks: Bluetooth only (desktops never host)",
                input(laptop(Caps.WINDOWS), laptop(Caps.MAC)),
                listOf(BLUETOOTH to null),
            ),
            Case(
                "two laptops on one network: LAN, then Bluetooth",
                input(laptop(Caps.WINDOWS, HOME_ROUTER), laptop(Caps.MAC, HOME_ROUTER)),
                listOf(LAN to LOCAL, BLUETOOTH to null),
            ),
            Case(
                "F-H4: a wired desktop without Bluetooth on the same LAN: LAN only",
                input(phone(Caps.FLAGSHIP.onWifi(true), HOME_ROUTER), laptop(Caps.WIRED_DESKTOP, HOME_ROUTER)),
                listOf(LAN to LOCAL),
            ),
            Case(
                "a wired desktop on another network is unreachable",
                input(phone(), laptop(Caps.WIRED_DESKTOP, OFFICE_ROUTER)),
                emptyList(),
            ),
            Case(
                "a phone that may not host right now leaves hosting to the peer",
                input(phone(Caps.FLAGSHIP, hostingAllowed = false), phone(Caps.MIDRANGE), role = RECEIVER),
                listOf(P2P to PEER, HOTSPOT to PEER, BLUETOOTH to null),
                requestFiveGhz = true,
            ),
            Case(
                "neither may host: no Wi-Fi Direct and no hotspot",
                input(phone(hostingAllowed = false), laptop(Caps.MAC)),
                listOf(BLUETOOTH to null),
            ),
            Case(
                "a hotspot host that may not host now drops the rung rather than electing the peer, who cannot know",
                input(phone(Caps.NO_WIFI_DIRECT, battery = 90, hostingAllowed = false), phone(Caps.NO_WIFI_DIRECT, battery = 10)),
                listOf(BLUETOOTH to null),
            ),
        )

    @Test
    fun fE1_ladderTable() {
        for (case in cases) {
            val plan = LadderPlanner.plan(case.input)
            assertEquals(case.rungs, plan.candidates.map { it.mode to it.host }, case.name)
            assertEquals(case.parallel, plan.parallelLanProbe, "${case.name}: parallel LAN probe")
            assertEquals(case.requestFiveGhz, plan.p2pCandidate?.requestFiveGhz, "${case.name}: 5 GHz request")
            assertEquals(case.hints, plan.initialHints.map { it.code }, "${case.name}: initial hints")
            assertEquals(plan.p2pCandidate?.host, plan.groupOwner, "${case.name}: group owner")
            assertEquals(plan.candidate(HOTSPOT)?.host, plan.hotspotHost, "${case.name}: hotspot host")
        }
    }

    @Test
    fun fE3_theHotspotFollowsTheGroupOwner() {
        // The peer wins the group owner election on battery; the hotspot goes with it.
        val plan = plan(phone(Caps.MIDRANGE, battery = 20), phone(Caps.MIDRANGE, battery = 80))
        assertEquals(PEER, plan.groupOwner)
        assertEquals(Election(PEER, ElectionReason.GROUP_OWNER), plan.hotspotElection)
        // The group owner cannot host a hotspot: the shared election picks the one that can.
        val noHotspotHere = plan(phone(Caps.MIDRANGE - Flag.CAN_HOST_LOCAL_HOTSPOT, battery = 80), phone(Caps.MIDRANGE, battery = 20))
        assertEquals(LOCAL, noHotspotHere.groupOwner)
        assertEquals(Election(PEER, ElectionReason.ONLY_HOST), noHotspotHere.hotspotElection)
        // Hosting not allowed here (the hotspot is in use): the peer hosts the group, so the hotspot goes there too.
        val busy = plan(phone(Caps.FLAGSHIP, hostingAllowed = false), phone(Caps.MIDRANGE))
        assertEquals(PEER, busy.groupOwner)
        assertEquals(Election(PEER, ElectionReason.GROUP_OWNER), busy.hotspotElection)
    }

    @Test
    fun fE1_zeroHintsNeverMatch() {
        val plan = plan(phone(hint = NetworkHint.NONE), phone(hint = NetworkHint.NONE))
        assertEquals(-1, plan.indexOf(LAN))
        assertFalse(plan.parallelLanProbe)
    }

    @Test
    fun fE1_bluetoothOnlyAndUnreachableFlags() {
        val bt = plan(phone(Caps.NOTHING), phone(Caps.NOTHING))
        assertTrue(bt.isBluetoothOnly)
        assertFalse(bt.isUnreachable)
        val none = plan(phone(), laptop(Caps.WIRED_DESKTOP, OFFICE_ROUTER))
        assertTrue(none.isUnreachable)
        assertFalse(none.isBluetoothOnly)
        assertNull(none.groupOwner)
    }

    @Test
    fun fE2_fiveGhzIsPossibleOnlyWhenBothSupportIt() {
        assertTrue(plan(phone(), phone(Caps.MIDRANGE)).fiveGhzPossible)
        assertFalse(plan(phone(), phone(Caps.BAND24_ONLY)).fiveGhzPossible)
        // A 6 GHz radio implies 5 GHz.
        assertTrue(plan(phone(), phone(Caps.BAND24_ONLY + Flag.WIFI_6GHZ)).fiveGhzPossible)
    }

    @Test
    fun n9_hostStationOn24IsFlaggedOnlyWhenUnavoidable() {
        // Only the local phone can host, and its station is on 2.4 GHz: unavoidable.
        val pinned = plan(phone(Caps.FLAGSHIP.onWifi(false)), laptop(Caps.MAC))
        assertEquals(LOCAL, pinned.groupOwner)
        assertTrue(pinned.p2pCandidate!!.hostStationOn24)
        // Both can host: the other one hosts and nothing is pinned.
        val avoided = plan(phone(Caps.FLAGSHIP.onWifi(false)), phone(Caps.MIDRANGE))
        assertEquals(PEER, avoided.groupOwner)
        assertEquals(ElectionReason.STATION_BAND, avoided.groupOwnerElection?.reason)
        assertFalse(avoided.p2pCandidate!!.hostStationOn24)
    }

    @Test
    fun planInvariantsAreEnforced() {
        val input = input(phone(), phone())
        assertFailsWith<IllegalArgumentException> {
            LadderPlan(input, listOf(LinkCandidate(BLUETOOTH), LinkCandidate(LAN, LOCAL)))
        }
        assertFailsWith<IllegalArgumentException> { LadderPlan(input, listOf(LinkCandidate(P2P, LOCAL))) }
        assertFailsWith<IllegalArgumentException> {
            LadderPlan(
                input,
                listOf(LinkCandidate(HOTSPOT, LOCAL), LinkCandidate(P2P, LOCAL)),
                Election(LOCAL, ElectionReason.ONLY_HOST),
                Election(LOCAL, ElectionReason.ONLY_HOST),
            )
        }
        assertFailsWith<IllegalArgumentException> { LinkCandidate(BLUETOOTH, LOCAL) }
        assertFailsWith<IllegalArgumentException> { LinkCandidate(HOTSPOT, LOCAL, requestFiveGhz = true) }
        assertFailsWith<IllegalArgumentException> {
            LinkFacts(Caps.FLAGSHIP, com.constrivo.drop.core.discovery.DevicePlatform.PHONE, batteryPercent = 101)
        }
    }

    @Test
    fun stationBandComesFromBits11And13() {
        assertEquals(StationBand.NONE, StationBand.fromCapabilities(Caps.FLAGSHIP))
        assertEquals(StationBand.BAND_2_4_GHZ, StationBand.fromCapabilities(Caps.FLAGSHIP.onWifi(false)))
        assertEquals(StationBand.BAND_5_GHZ_OR_ABOVE, StationBand.fromCapabilities(Caps.FLAGSHIP.onWifi(true)))
        assertEquals(SENDER, input(phone(), phone()).localRole)
    }
}
