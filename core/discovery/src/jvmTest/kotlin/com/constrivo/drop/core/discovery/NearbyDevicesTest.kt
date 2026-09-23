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
import kotlin.test.assertFalse
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
        val trust: MutableStateFlow<TrustState>,
        val clockReads: () -> Int,
        /** Added to our wall clock only: models an NTP or user correction. */
        var wallOffset: Long = 0,
    )

    private fun TestScope.start(): Harness {
        var reads = 0
        lateinit var harness: Harness
        val wall =
            WallClock {
                reads++
                base + testScheduler.currentTime + harness.wallOffset
            }
        val monotonic =
            MonotonicClock {
                reads++
                testScheduler.currentTime
            }
        val nearby = NearbyDevices(crypto, wall, monotonic)
        val sightings = Channel<BeaconSighting>(Channel.UNLIMITED)
        val lan = Channel<LanEvent>(Channel.UNLIMITED)
        val trust = MutableStateFlow(TrustState(listOf(ownSecret)))
        harness = Harness(nearby, sightings, lan, trust, { reads })
        nearby.launchIn(backgroundScope, sightings.receiveAsFlow(), lan.receiveAsFlow(), trust)
        runCurrent()
        return harness
    }

    /** A beacon of [secret] stamped with the true time (the peers' clocks are right). */
    private fun TestScope.beacon(
        secret: ByteArray,
        rssi: Int = -50,
        address: String? = null,
    ): BeaconSighting {
        val now = base + testScheduler.currentTime
        val ad = BeaconAdvertisement.create(crypto, secret, state, BeaconCarrier.SERVICE_DATA, now)
        return BeaconSighting.fromAdvertisingData(ad.advertisingData() + ad.scanResponseData()!!, rssi, address, now)!!
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
            assertEquals(listOf(LanEndpoint(record.instanceName, "10.0.0.7", 40404)), device.lanEndpoints)
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
            h.trust.value = TrustState(listOf(ownSecret), listOf(TrustedPeer("peer-3", secret, "Priya")))
            runCurrent()
            // Published within one 100 ms throttle interval.
            advanceTimeBy(100)
            runCurrent()
            val device = h.nearby.devices.value.single()
            assertEquals("t:peer-3", device.key)
            assertEquals("Priya", device.nickname)
        }

    @Test
    fun s3_rotatingTheOwnSecretMidRunKeepsTheOwnRecordOffTheRadar() =
        runTest {
            val h = start()
            val rotated = Secrets.secret(300)
            val record = { secret: ByteArray -> MdnsRecord.create(crypto, secret, state, 40404, base + testScheduler.currentTime) }
            // "Forget" rotates k_adv; the same run carries on with the new trust state, no restart.
            h.trust.value = TrustState(listOf(rotated), emptyList())
            runCurrent()
            // NsdManager reports our own services too; the old record lingers until it is withdrawn.
            h.lan.send(LanEvent.Found(record(rotated).toLanService("10.0.0.2")))
            h.lan.send(LanEvent.Found(record(ownSecret).toLanService("10.0.0.2")))
            h.sightings.send(beacon(rotated))
            h.sightings.send(beacon(ownSecret))
            runCurrent()
            assertTrue(h.nearby.devices.value.isEmpty(), "${h.nearby.devices.value}")
            assertEquals(4, h.nearby.counters.value.ownEchoes)
            // A stranger still shows up.
            h.sightings.send(beacon(Secrets.secret(4)))
            runCurrent()
            assertEquals(1, h.nearby.devices.value.size)
        }

    @Test
    fun wallClockStepsNeitherKeepDepartedDevicesNorDropPresentOnes() =
        runTest {
            val h = start()
            val a = Secrets.secret(5)
            val b = Secrets.secret(6)
            h.sightings.send(beacon(a))
            h.sightings.send(beacon(b))
            val record = MdnsRecord.create(crypto, Secrets.secret(7), state, 40404, base + testScheduler.currentTime)
            h.lan.send(LanEvent.Found(record.toLanService("10.0.0.9")))
            runCurrent()
            assertEquals(3, h.nearby.devices.value.size)
            // Our wall clock steps back an hour; A keeps beaconing, B has left.
            h.wallOffset = -3_600_000
            repeat(10) {
                advanceTimeBy(1_000)
                h.sightings.send(beacon(a))
                runCurrent()
            }
            val keys = h.nearby.devices.value.map { it.key }.toSet()
            assertEquals(2, keys.size, "A once and the LAN device; B left 5 s after its last beacon: $keys")
            assertFalse("e:${EphemeralIds.at(crypto, b, base).toHex()}" in keys)
            // Then forward by 31 minutes: the LAN record, 10 s old on the monotonic clock, stays.
            h.wallOffset = 31 * 60_000L
            advanceTimeBy(1_000)
            h.sightings.send(beacon(a))
            runCurrent()
            assertEquals(keys, h.nearby.devices.value.map { it.key }.toSet())
            // And A still leaves on time.
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf(record.instanceName), h.nearby.devices.value.single().lanEndpoints.map { it.instanceName })
        }

    @Test
    fun fJ1_trustedBubbleSurvivesAnIdRotation() =
        runTest {
            val h = start()
            val secret = Secrets.secret(4)
            h.trust.value = TrustState(listOf(ownSecret), listOf(TrustedPeer("peer-4", secret)))
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
    fun fJ1_aSessionKeepsAStrangersBubbleAcrossItsRotation() =
        runTest {
            val h = start()
            val secret = Secrets.secret(8)
            h.sightings.send(beacon(secret, address = "5A:00:00:00:00:01"))
            runCurrent()
            val key = h.nearby.devices.value.single().key
            val next = EphemeralIds.at(crypto, secret, base + EphemeralIds.EPOCH_MILLIS)
            assertTrue(h.nearby.link(key, next))
            runCurrent()
            // Heard next epoch from a fresh address, far from the boundary: without the link this would be a new bubble.
            val later = base + EphemeralIds.EPOCH_MILLIS + 60_000
            val ad = BeaconAdvertisement.create(crypto, secret, state, BeaconCarrier.SERVICE_DATA, later)
            h.sightings.send(BeaconSighting.fromAdvertisingData(ad.advertisingData(), -50, "5A:00:00:00:00:02", later)!!)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(key, h.nearby.devices.value.single().key)
            assertEquals(next, h.nearby.devices.value.single().ephemeralId)
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
    fun aFloodIsPublishedAtMostTenTimesASecond() =
        runTest {
            val h = start()
            val published = ArrayList<List<NearbyDevice>>()
            backgroundScope.launch { h.nearby.devices.collect { published += it } }
            runCurrent()
            // A flooder sends a new random ID every 5 ms for a second (seeds 1–200: none is our own secret 0).
            repeat(200) { i ->
                h.sightings.send(beacon(Secrets.secret(1 + i)))
                runCurrent()
                advanceTimeBy(5)
            }
            advanceTimeBy(100)
            runCurrent()
            assertEquals(200, h.nearby.devices.value.size, "the last change is published too")
            assertTrue(published.size in 2..15, "${published.size} lists published for 200 changes")
        }

    @Test
    fun stoppingClearsTheRadarAndASecondRunIsRefused() =
        runTest {
            val wall = WallClock { base + testScheduler.currentTime }
            val monotonic = MonotonicClock { testScheduler.currentTime }
            val nearby = NearbyDevices(crypto, wall, monotonic)
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
            assertFalse(nearby.link("e:000000000000", EphemeralId(1)), "no run, no link")
            // It can run again after it stopped.
            nearby.launchIn(backgroundScope, sightings.receiveAsFlow())
            runCurrent()
            sightings.send(beacon(Secrets.secret(6)))
            runCurrent()
            assertEquals(1, nearby.devices.value.size)
        }
}
