package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.ladder.Caps.onWifi
import com.constrivo.drop.core.ladder.ElectionReason.BATTERY
import com.constrivo.drop.core.ladder.ElectionReason.FIVE_GHZ_HOST
import com.constrivo.drop.core.ladder.ElectionReason.ONLY_HOST
import com.constrivo.drop.core.ladder.ElectionReason.RECEIVER_TIE_BREAK
import com.constrivo.drop.core.ladder.ElectionReason.STATION_BAND
import com.constrivo.drop.core.ladder.ElectionReason.WIFI_6
import com.constrivo.drop.core.ladder.Side.LOCAL
import com.constrivo.drop.core.ladder.Side.PEER
import com.constrivo.drop.core.protocol.TransferRole
import com.constrivo.drop.core.protocol.TransferRole.RECEIVER
import com.constrivo.drop.core.protocol.TransferRole.SENDER
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Group owner election (architecture §4: 5 GHz host > Wi-Fi 6 > battery > receiver, with N9 in front). */
class GroupOwnerElectionTest {
    private fun owner(
        local: LinkFacts,
        peer: LinkFacts,
        role: TransferRole = SENDER,
    ): Election? = plan(local, peer, role).groupOwnerElection

    @Test
    fun fE2_verifiedFiveGhzHostBeatsEverythingBelowIt() {
        // The peer has Wi-Fi 6 and more battery, but only this phone has hosted a 5 GHz group before.
        val local = phone(Caps.FLAGSHIP - Flag.WIFI_6_OR_NEWER, battery = 10)
        val peer = phone(Caps.MIDRANGE + Flag.WIFI_6_OR_NEWER, battery = 90)
        assertEquals(Election(LOCAL, FIVE_GHZ_HOST), owner(local, peer))
        assertEquals(Election(PEER, FIVE_GHZ_HOST), owner(peer, local))
    }

    @Test
    fun fE2_fiveGhzCapableBeatsTwoPointFourOnly() {
        assertEquals(Election(PEER, FIVE_GHZ_HOST), owner(phone(Caps.BAND24_ONLY, battery = 100), phone(Caps.MIDRANGE, battery = 1)))
    }

    @Test
    fun fE2_wifi6DecidesBetweenEqualFiveGhzHosts() {
        val six = phone(Caps.MIDRANGE + Flag.WIFI_6_OR_NEWER, battery = 5)
        val five = phone(Caps.MIDRANGE, battery = 95)
        assertEquals(Election(LOCAL, WIFI_6), owner(six, five))
        assertEquals(Election(PEER, WIFI_6), owner(five, six, RECEIVER))
    }

    @Test
    fun fE2_moreBatteryDecidesNext() {
        assertEquals(Election(PEER, BATTERY), owner(phone(battery = 40), phone(battery = 41)))
        assertEquals(Election(LOCAL, BATTERY), owner(phone(battery = 80), phone(battery = 20), RECEIVER))
    }

    @Test
    fun fE2_tieGoesToTheReceiver() {
        assertEquals(Election(PEER, RECEIVER_TIE_BREAK), owner(phone(battery = 50), phone(battery = 50), SENDER))
        assertEquals(Election(LOCAL, RECEIVER_TIE_BREAK), owner(phone(battery = 50), phone(battery = 50), RECEIVER))
    }

    @Test
    fun fE2_batteryKnownOnOneSideOnlyIsSkipped() {
        // Only this device knows its own level: comparing would let the two sides disagree.
        assertEquals(Election(PEER, RECEIVER_TIE_BREAK), owner(phone(battery = 99), phone(battery = null)))
        assertEquals(Election(LOCAL, RECEIVER_TIE_BREAK), owner(phone(battery = null), phone(battery = 99), RECEIVER))
    }

    @Test
    fun n9_neverElectAStationOn24WhenTheOtherCanHost() {
        // The local phone is the better host on paper, but its station would pin the group to 2.4 GHz.
        val local = phone(Caps.FLAGSHIP.onWifi(fiveGhz = false), battery = 100)
        val peer = phone(Caps.MIDRANGE, battery = 1)
        assertEquals(Election(PEER, STATION_BAND), owner(local, peer))
        assertEquals(Election(LOCAL, STATION_BAND), owner(peer, local, RECEIVER))
        // A 5 GHz station is no reason to avoid a device.
        assertEquals(Election(LOCAL, FIVE_GHZ_HOST), owner(phone(Caps.FLAGSHIP.onWifi(true)), phone(Caps.MIDRANGE)))
    }

    @Test
    fun n9_bothStationsOn24FallBackToTheNormalRules() {
        val local = phone(Caps.FLAGSHIP.onWifi(false))
        val peer = phone(Caps.MIDRANGE.onWifi(false))
        val election = owner(local, peer)
        assertEquals(Election(LOCAL, FIVE_GHZ_HOST), election)
        assertEquals(true, plan(local, peer).p2pCandidate?.hostStationOn24)
    }

    @Test
    fun n8_onlyThePhoneCanHostForADesktop() {
        assertEquals(Election(LOCAL, ONLY_HOST), owner(phone(Caps.BAND24_ONLY), laptop(Caps.WINDOWS)))
        assertEquals(Election(PEER, ONLY_HOST), owner(laptop(Caps.MAC), phone(Caps.BAND24_ONLY), RECEIVER))
        assertNull(owner(laptop(Caps.MAC), laptop(Caps.WINDOWS)))
    }

    @Test
    fun fE2_bothDevicesElectTheSameHost() {
        // Symmetry: planning from either device's point of view elects the same physical device.
        val random = Random(20260923)
        val flags = listOf(Flag.WIFI_5GHZ, Flag.WIFI_6_OR_NEWER, Flag.WIFI_DIRECT, Flag.CAN_HOST_P2P_5GHZ, Flag.CAN_HOST_LOCAL_HOTSPOT)
        repeat(2_000) {
            fun device(): LinkFacts {
                var caps = Capabilities.NONE
                for (flag in flags) if (random.nextBoolean()) caps += flag
                if (random.nextInt(3) == 0) caps = caps.onWifi(random.nextBoolean())
                val battery = if (random.nextBoolean()) random.nextInt(0, 101) else null
                return phone(caps, battery = battery)
            }
            val a = device()
            val b = device()
            val aView = plan(a, b, SENDER)
            val bView = plan(b, a, RECEIVER)
            assertEquals(aView.groupOwner, bView.groupOwner?.other, "group owner of $a and $b")
            assertEquals(aView.hotspotHost, bView.hotspotHost?.other, "hotspot host of $a and $b")
            assertEquals(aView.candidates.map { it.mode }, bView.candidates.map { it.mode }, "rungs of $a and $b")
        }
    }
}
