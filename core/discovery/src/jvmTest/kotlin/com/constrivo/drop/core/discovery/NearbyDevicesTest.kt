package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.Secrets.EPOCH_START
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The coroutine wrapper around the tracker, on virtual time (F‑A2, F‑A3, design §3.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyDevicesTest {
    private val crypto = JcaCryptoProvider()

    /** Virtual time zero is 250 ms-aligned, a minute into the test epoch. */
    private val base = EPOCH_START + 60_000
    private val ownSecret = Secrets.secret(0)
    private val state =
        LocalBeaconState(Visibility.EVERYONE, DevicePlatform.PHONE, Capabilities.NONE, NetworkHint.NONE, "Nearby phone")

    private class Harness(
        val nearby: NearbyDevices,
        val sightings: Channel<BeaconSighting>,
        val lan: Channel<LanEvent>,
        val peers: MutableStateFlow<Collection<TrustedPeer>>,
        val clockReads: () -> Int,
    )

    private fun TestScope.start(): Harness {
        var reads = 0
        val clock =
            WallClock {
                reads++
                base + testScheduler.currentTime
            }
        val nearby = NearbyDevices(crypto, clock, ownSecret)
        val sightings = Channel<BeaconSighting>(Channel.UNLIMITED)
        val lan = Channel<LanEvent>(Channel.UNLIMITED)
        val peers = MutableStateFlow<Collection<TrustedPeer>>(emptyList())
        nearby.launchIn(backgroundScope, sightings.receiveAsFlow(), lan.receiveAsFlow(), peers)
        runCurrent()
        return Harness(nearby, sightings, lan, peers) { reads }
    }

    private fun TestScope.beacon(
        secret: ByteArray,
        rssi: Int = -50,
    ): BeaconSighting {
        val now = base + testScheduler.currentTime
        val ad = BeaconAdvertisement.create(crypto, secret, state, BeaconCarrier.SERVICE_DATA, now)
        return BeaconSighting.fromAdvertisingData(ad.advertisingData() + ad.scanResponseData()!!, rssi, null, now)!!
    }

    @Test
    fun designSection33_bubbleLeavesFiveSecondsAfterItsLastBeacon() =
        runTest {
            val h = start()
            val secret = Secrets.secret(1)
            h.sightings.send(beacon(secret))
            runCurrent()
            assertEquals("Nearby phone", h.nearby.devices.value.single().nickname, "on the radar at once (F-A2: ≤ 1 s)")
            advanceTimeBy(2_000)
            h.sightings.send(beacon(secret))
            runCurrent()
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(1, h.nearby.devices.value.size, "4.999 s after the last beacon")
            advanceTimeBy(1)
            runCurrent()
            assertTrue(h.nearby.devices.value.isEmpty(), "gone at 5 s")
        }

    @Test
    fun noPeriodicWorkWhileTheRadarIsEmpty() =
        runTest {
            val h = start()
            h.sightings.send(beacon(Secrets.secret(1)))
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()
            assertTrue(h.nearby.devices.value.isEmpty())
            val reads = h.clockReads()
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(reads, h.clockReads(), "the aggregator slept for ten idle minutes")
        }

    @Test
    fun fA3_bluetoothAndLanMergeThroughTheFlows() =
        runTest {
            val h = start()
            val secret = Secrets.secret(2)
            h.sightings.send(beacon(secret, rssi = -60))
            val record = MdnsRecord.create(crypto, secret, state, 40404, base + testScheduler.currentTime)
            h.lan.send(LanEvent.Found(record.toLanService("10.0.0.7")))
            runCurrent()
            val device = h.nearby.devices.value.single()
            assertEquals(setOf(DiscoverySource.BLUETOOTH, DiscoverySource.LAN), device.sources)
            assertEquals(LanEndpoint(record.instanceName, "10.0.0.7", 40404), device.lanEndpoint)
            // Bluetooth stops; the LAN keeps the device until it is lost.
            advanceTimeBy(6_000)
            runCurrent()
            assertTrue(h.nearby.devices.value.single().lanOnly)
            h.lan.send(LanEvent.Lost(record.instanceName))
            runCurrent()
            assertTrue(h.nearby.devices.value.isEmpty())
        }

    @Test
    fun s3_trustChangesRefileDevicesLive() =
        runTest {
            val h = start()
            val secret = Secrets.secret(3)
            h.sightings.send(beacon(secret))
            runCurrent()
            assertTrue(h.nearby.devices.value.single().key.startsWith("e:"))
            h.peers.value = listOf(TrustedPeer("peer-3", secret, "Priya"))
            runCurrent()
            val device = h.nearby.devices.value.single()
            assertEquals("t:peer-3", device.key)
            assertEquals("Priya", device.nickname)
        }

    @Test
    fun fJ1_trustedBubbleSurvivesAnIdRotation() =
        runTest {
            val h = start()
            val secret = Secrets.secret(4)
            h.peers.value = listOf(TrustedPeer("peer-4", secret))
            runCurrent()
            // Beacon every second across the next epoch boundary.
            val untilBoundary = EphemeralIds.millisUntilNextEpoch(base + testScheduler.currentTime)
            advanceTimeBy(untilBoundary - 3_000)
            val keys = HashSet<String>()
            val ids = HashSet<EphemeralId>()
            repeat(6) {
                h.sightings.send(beacon(secret))
                runCurrent()
                val device = h.nearby.devices.value.single()
                keys += device.key
                ids += device.ephemeralId
                advanceTimeBy(1_000)
            }
            assertEquals(setOf("t:peer-4"), keys)
            assertEquals(2, ids.size, "the ID rotated once")
        }

    @Test
    fun steadyBeaconsDoNotRepublishOnEveryPacket() =
        runTest {
            val h = start()
            val secret = Secrets.secret(5)
            h.sightings.send(beacon(secret, rssi = -60))
            runCurrent()
            advanceTimeBy(250)
            runCurrent()
            val published = h.nearby.devices.value
            // Same signal again within the next window: nothing visible changed, so the same list stays published.
            advanceTimeBy(10)
            h.sightings.send(beacon(secret, rssi = -60))
            runCurrent()
            assertSame(published, h.nearby.devices.value)
            // A stronger signal is published when its window closes.
            h.sightings.send(beacon(secret, rssi = -40))
            runCurrent()
            assertSame(published, h.nearby.devices.value)
            advanceTimeBy(250)
            runCurrent()
            assertNotSame(published, h.nearby.devices.value)
            assertTrue(h.nearby.devices.value.single().smoothedRssiDbm!! > -60.0)
        }

    @Test
    fun stoppingClearsTheRadarAndASecondRunIsRefused() =
        runTest {
            val clock = WallClock { base + testScheduler.currentTime }
            val nearby = NearbyDevices(crypto, clock, ownSecret)
            val sightings = Channel<BeaconSighting>(Channel.UNLIMITED)
            val job = nearby.launchIn(backgroundScope, sightings.receiveAsFlow())
            runCurrent()
            sightings.send(beacon(Secrets.secret(6)))
            runCurrent()
            assertEquals(1, nearby.devices.value.size)
            val second = Channel<BeaconSighting>()
            val failure = backgroundScope.launch { assertFailsWith<IllegalStateException> { nearby.run(second.receiveAsFlow()) } }
            runCurrent()
            assertTrue(failure.isCompleted)
            job.cancel()
            runCurrent()
            assertTrue(nearby.devices.value.isEmpty())
            // It can run again after it stopped.
            nearby.launchIn(backgroundScope, sightings.receiveAsFlow())
            runCurrent()
            sightings.send(beacon(Secrets.secret(6)))
            runCurrent()
            assertEquals(1, nearby.devices.value.size)
        }
}
