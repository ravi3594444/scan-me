package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.ladder.LadderInput
import com.constrivo.drop.core.ladder.LadderPlanner
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.RadioState
import com.constrivo.drop.core.ladder.Side
import com.constrivo.drop.core.ladder.StationBand
import com.constrivo.drop.core.protocol.TransferRole
import com.constrivo.drop.platform.android.capability.LocalRadioFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phone's ladder facts (§4 inputs; N6, N9), the capability-bit-4 recorder (§5.2, F-A4) and the multicast lock
 * holder (WP9's mDNS responder, NSD before T extension 7).
 */
class HostFactsTest {
    private val phoneCaps = Capabilities.of(Flag.WIFI_5GHZ, Flag.WIFI_DIRECT, Flag.CAN_HOST_LOCAL_HOTSPOT, Flag.WIFI_6_OR_NEWER)
    private val hint = NetworkHint(0x01020304)

    private fun radio(
        capabilities: Capabilities = phoneCaps,
        stationBand: StationBand = StationBand.NONE,
        staAp: Boolean = false,
        wifiOn: Boolean = true,
        bluetoothOn: Boolean = true,
    ) = LocalRadioFacts(capabilities, hint, null, stationBand, wifiOn, true, bluetoothOn, staAp, false, true)

    // ---- Link facts ----

    @Test
    fun thePhonesFactsComeFromWhatItPublished() {
        val facts = AndroidLinkFacts.local(phoneCaps, radio(), batteryPercent = 64)
        assertEquals(phoneCaps, facts.capabilities)
        assertEquals(DevicePlatform.PHONE, facts.platform)
        assertEquals(hint, facts.networkHint)
        assertEquals(64, facts.batteryPercent)
        assertTrue(facts.hostingAllowed)
        assertEquals(StationBand.NONE, facts.stationBand)
    }

    @Test
    fun theStationBandIsReadFromThePublishedBitsLikeThePeerDoes() {
        val on5 = phoneCaps + Flag.CONNECTED_TO_WIFI + Flag.STATION_ON_5GHZ
        val on24 = phoneCaps + Flag.CONNECTED_TO_WIFI
        assertEquals(StationBand.BAND_5_GHZ_OR_ABOVE, AndroidLinkFacts.local(on5, radio(on5)).stationBand)
        assertEquals(StationBand.BAND_2_4_GHZ, AndroidLinkFacts.local(on24, radio(on24)).stationBand)
        // Trusted-only beacons cleared bits 11 and 13: the peer sees no station, so neither does the plan.
        assertEquals(StationBand.NONE, AndroidLinkFacts.local(phoneCaps, radio(on24, StationBand.BAND_2_4_GHZ)).stationBand)
    }

    @Test
    fun hostingIsAllowedUnlessSomethingHoldsTheRadio() {
        assertFalse(AndroidLinkFacts.local(phoneCaps, radio(), hosting = HostingState(hotspotInUse = true)).hostingAllowed)
        assertFalse(AndroidLinkFacts.local(phoneCaps, radio(), hosting = HostingState(groupInUse = true)).hostingAllowed)
        assertFalse(AndroidLinkFacts.local(phoneCaps, radio(), hosting = HostingState(tetheringOn = true)).hostingAllowed)
        assertTrue(AndroidLinkFacts.local(phoneCaps, radio(), hosting = HostingState()).hostingAllowed)
        assertEquals(100, AndroidLinkFacts.local(phoneCaps, radio(), batteryPercent = 140).batteryPercent)
    }

    @Test
    fun theRadioStateIsTheDevicesOwn() {
        assertEquals(RadioState(wifiEnabled = true, bluetoothEnabled = false), AndroidLinkFacts.radioState(radio(bluetoothOn = false)))
        assertEquals(RadioState(wifiEnabled = false, bluetoothEnabled = true), AndroidLinkFacts.radioState(radio(wifiOn = false)))
    }

    @Test
    fun staApConcurrencyTellsWhetherAHotspotDisplacesTheStation() {
        val connected = phoneCaps + Flag.CONNECTED_TO_WIFI
        assertEquals(HotspotHosting.NO_STATION, AndroidLinkFacts.hotspotHosting(radio(phoneCaps)))
        assertEquals(HotspotHosting.CONCURRENT, AndroidLinkFacts.hotspotHosting(radio(connected, staAp = true)))
        assertEquals(HotspotHosting.DISPLACES_STATION, AndroidLinkFacts.hotspotHosting(radio(connected, staAp = false)))
    }

    @Test
    fun twoPhonesOnMobileDataPlanWifiDirect() {
        // T-15: different (here: no) network hints, both phones with Wi-Fi Direct and 5 GHz → straight to Wi-Fi Direct.
        val local = AndroidLinkFacts.local(phoneCaps, radio(), networkHint = NetworkHint.NONE)
        val peer = AndroidLinkFacts.local(phoneCaps, radio(), networkHint = NetworkHint.NONE)
        val plan = LadderPlanner.plan(LadderInput(local, peer, TransferRole.SENDER, AndroidLinkFacts.radioState(radio())))
        assertEquals(LinkMode.P2P, plan.candidates.first().mode)
        assertTrue(plan.candidates.first().requestFiveGhz)
        assertEquals(Side.PEER, plan.groupOwner, "the receiver wins the tie")
    }

    // ---- Capability bit 4 ----

    @Test
    fun aFiveGhzGroupVerifiesBit4Once() {
        val store = InMemoryVerifiedHostStore()
        val published = mutableListOf<Boolean>()
        val recorder = FiveGhzHostRecorder(store, { published += it })
        assertFalse(recorder.recordHosted(2437))
        assertFalse(recorder.recordHosted(null))
        assertTrue(recorder.recordHosted(5180))
        assertFalse(recorder.recordHosted(5745), "already verified")
        assertFalse(recorder.recordHosted(2412))
        assertTrue(store.isVerified(), "a later 2.4 GHz group never clears it")
        assertEquals(listOf(true), published)
    }

    @Test
    fun theRecorderPublishesThePersistedFactAndSurvivesStoreFailures() {
        val published = mutableListOf<Boolean>()
        FiveGhzHostRecorder(InMemoryVerifiedHostStore(initial = true), { published += it }).restore()
        assertEquals(listOf(true), published)

        val errors = mutableListOf<Throwable>()
        val broken =
            object : VerifiedHostStore {
                override fun isVerified(): Boolean = throw IllegalStateException("disk")

                override fun setVerified(verified: Boolean) = throw IllegalStateException("disk")
            }
        val recorder = FiveGhzHostRecorder(broken, { published += it }, { errors += it })
        assertFalse(recorder.recordHosted(5180))
        recorder.restore()
        assertEquals(2, errors.size)
        assertEquals(listOf(true, false), published)
    }

    // ---- Multicast lock ----

    private class FakeLock : MulticastLockApi {
        var acquires = 0
        var releases = 0
        var failAcquire = false
        override var isHeld: Boolean = false

        override fun acquire() {
            acquires++
            if (failAcquire) throw SecurityException("no CHANGE_WIFI_MULTICAST_STATE")
            isHeld = true
        }

        override fun release() {
            releases++
            isHeld = false
        }
    }

    @Test
    fun theLockIsHeldFromTheFirstHoldToTheLast() {
        val lock = FakeLock()
        val holder = MulticastLockHolder(lock)
        val responder = holder.hold("mdns-responder")
        val browse = holder.hold("nsd-browse")
        assertEquals(1, lock.acquires)
        assertEquals(listOf("mdns-responder", "nsd-browse"), holder.purposes)
        responder.close()
        responder.close()
        assertTrue(holder.isHeld, "the other user still needs it")
        assertEquals(0, lock.releases)
        browse.close()
        assertEquals(1, lock.releases)
        assertFalse(holder.isHeld)
        holder.hold("again").close()
        assertEquals(2, lock.acquires)
        assertEquals(2, lock.releases)
    }

    @Test
    fun aLockThatCannotBeAcquiredIsReportedAndTheHoldStillWorks() {
        val lock = FakeLock().apply { failAcquire = true }
        val errors = mutableListOf<Throwable>()
        val hold = MulticastLockHolder(lock) { errors += it }.hold("nsd-browse")
        assertEquals(1, errors.size)
        hold.close()
        assertEquals(0, lock.releases, "nothing was held")
    }
}
