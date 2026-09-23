package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.Secrets.EPOCH_START
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The nearby-device state machine (F‑A2, F‑A3, F‑A5, design §3.2–3.3). */
class NearbyDeviceTrackerTest {
    private val crypto = JcaCryptoProvider()
    private val t0 = EPOCH_START + 60_000
    private val ownSecret = Secrets.secret(0)
    private val bobSecret = Secrets.secret(1)
    private val bob = TrustedPeer("bob-device-id", bobSecret, "Bob's Pixel")

    private fun tracker(config: NearbyConfig = NearbyConfig()) = NearbyDeviceTracker(crypto, ownSecret, config)

    private fun state(
        visibility: Visibility = Visibility.EVERYONE,
        nickname: String = "Stranger",
        platform: DevicePlatform = DevicePlatform.PHONE,
    ) = LocalBeaconState(
        visibility,
        platform,
        Capabilities.of(Capabilities.Flag.WIFI_5GHZ, Capabilities.Flag.WIFI_DIRECT),
        NetworkHint(0x01020304),
        nickname,
    )

    private fun sighting(
        secret: ByteArray,
        at: Long,
        rssi: Int = -50,
        s: LocalBeaconState = state(),
        carrier: BeaconCarrier = BeaconCarrier.SERVICE_DATA,
        address: String? = "5A:00:00:00:00:01",
    ): BeaconSighting {
        val ad = BeaconAdvertisement.create(crypto, secret, s, carrier, at)
        return BeaconSighting.fromAdvertisingData(ad.advertisingData() + (ad.scanResponseData() ?: ByteArray(0)), rssi, address, at)!!
    }

    private fun lanFound(
        secret: ByteArray,
        at: Long,
        s: LocalBeaconState = state(),
        host: String = "192.168.1.30",
    ): LanEvent.Found = LanEvent.Found(MdnsRecord.create(crypto, secret, s, 49152, at).toLanService(host))

    @Test
    fun fA2_deviceAppearsOnItsFirstBeacon() {
        val t = tracker()
        assertTrue(t.onSighting(sighting(Secrets.secret(7), t0, rssi = -50), t0))
        val device = t.snapshot().single()
        assertEquals("Stranger", device.nickname)
        assertFalse(device.trusted)
        assertEquals(Ring.INNER, device.ring)
        assertEquals(setOf(DiscoverySource.BLUETOOTH), device.sources)
        assertEquals(DevicePlatform.PHONE, device.platform)
        assertEquals("5A:00:00:00:00:01", device.radioAddress)
        assertEquals(NetworkHint(0x01020304), device.networkHint)
        assertEquals(RadarPlacement.stableAngleDegrees(device.key), device.stableAngleDegrees)
        assertEquals("e:${EphemeralIds.at(crypto, Secrets.secret(7), t0).toHex()}", device.key)
    }

    @Test
    fun designSection33_deviceLeavesFiveSecondsAfterItsLastBeacon() {
        val t = tracker()
        t.onSighting(sighting(Secrets.secret(7), t0), t0)
        t.onSighting(sighting(Secrets.secret(7), t0 + 1_000), t0 + 1_000)
        t.advanceTo(t0 + 5_999)
        assertEquals(1, t.snapshot().size, "still there 4.999 s after the last beacon")
        assertEquals(t0 + 6_000, t.nextDeadlineMillis())
        assertTrue(t.advanceTo(t0 + 6_000))
        assertTrue(t.snapshot().isEmpty())
        assertNull(t.nextDeadlineMillis())
        assertEquals(0, t.size)
    }

    @Test
    fun fA3_bluetoothAndMdnsMergeWhenTheIdsMatch() {
        val t = tracker()
        val secret = Secrets.secret(7)
        t.onSighting(sighting(secret, t0, rssi = -65), t0)
        assertTrue(t.onLanEvent(lanFound(secret, t0 + 100, state(nickname = "Stranger's full nickname")), t0 + 100))
        val device = t.snapshot().single()
        assertEquals(setOf(DiscoverySource.BLUETOOTH, DiscoverySource.LAN), device.sources)
        assertEquals("Stranger's full nickname", device.nickname, "the mDNS name is not cut to 26 bytes")
        assertEquals(LanEndpoint("drop-${device.ephemeralId.toHex()}", "192.168.1.30", 49152), device.lanEndpoint)
        assertEquals(Ring.MIDDLE, device.ring)
        assertEquals(t0 + 100, device.lastSeenMillis)
        // When Bluetooth goes quiet the device stays, found over the LAN only, on the middle ring.
        t.advanceTo(t0 + 10_000)
        val lanOnly = t.snapshot().single()
        assertTrue(lanOnly.lanOnly)
        assertEquals(Ring.MIDDLE, lanOnly.ring)
        assertNull(lanOnly.smoothedRssiDbm)
        // "Lost" removes it.
        assertTrue(t.onLanEvent(LanEvent.Lost(lanOnly.lanEndpoint!!.instanceName), t0 + 11_000))
        assertTrue(t.snapshot().isEmpty())
    }

    @Test
    fun fA3_mdnsOnlyDevicesSitOnTheMiddleRing() {
        val t = tracker()
        t.onLanEvent(lanFound(Secrets.secret(8), t0, state(platform = DevicePlatform.DESKTOP)), t0)
        val device = t.snapshot().single()
        assertEquals(Ring.MIDDLE, device.ring)
        assertEquals(DevicePlatform.DESKTOP, device.platform)
        assertTrue(device.networkHint.isNone)
        assertNull(device.carrier)
    }

    @Test
    fun s3_trustedPeersResolveAndMergeAcrossEpochs() {
        val t = tracker()
        t.setTrustedPeers(listOf(bob))
        // mDNS record from the previous epoch, beacon from the current one: still one device.
        t.onLanEvent(lanFound(bobSecret, t0 - EphemeralIds.EPOCH_MILLIS), t0)
        t.onSighting(sighting(bobSecret, t0, rssi = -52, s = state(nickname = "spoofed name")), t0)
        val device = t.snapshot().single()
        assertEquals("t:bob-device-id", device.key)
        assertEquals("bob-device-id", device.trustedDeviceId)
        assertTrue(device.trusted)
        assertEquals("Bob's Pixel", device.nickname, "the stored name wins over unauthenticated air")
        assertEquals(setOf(DiscoverySource.BLUETOOTH, DiscoverySource.LAN), device.sources)
        // Bob's ID rotates: same bubble, same key, same angle.
        val next = t0 + EphemeralIds.EPOCH_MILLIS
        t.onSighting(sighting(bobSecret, next, rssi = -52), next)
        val rotated = t.snapshot().single()
        assertEquals(device.key, rotated.key)
        assertEquals(device.stableAngleDegrees, rotated.stableAngleDegrees)
        assertEquals(EphemeralIds.at(crypto, bobSecret, next), rotated.ephemeralId)
    }

    @Test
    fun fA5_trustedOnlyDeviceIsVisibleOnlyToHoldersOfItsSecret() {
        val stranger = tracker()
        val trustedOnly = state(visibility = Visibility.TRUSTED_ONLY)
        assertFalse(stranger.onSighting(sighting(bobSecret, t0, s = trustedOnly), t0))
        assertFalse(stranger.onLanEvent(lanFound(bobSecret, t0, trustedOnly), t0))
        assertTrue(stranger.snapshot().isEmpty())
        assertEquals(2, stranger.counters.trustedOnlyStrangers)

        val friend = tracker()
        friend.setTrustedPeers(listOf(bob))
        assertTrue(friend.onSighting(sighting(bobSecret, t0, s = trustedOnly), t0))
        val device = friend.snapshot().single()
        assertTrue(device.trusted)
        assertEquals("Bob's Pixel", device.nickname)
        assertEquals(Visibility.TRUSTED_ONLY, device.visibility)
        assertTrue(device.networkHint.isNone, "N4: no hint in Trusted-only mode")

        // "Forget": the device disappears from the radar at once.
        assertTrue(friend.setTrustedPeers(emptyList()))
        assertTrue(friend.snapshot().isEmpty())
    }

    @Test
    fun newlyTrustedDevicesAreRefiled() {
        val t = tracker()
        t.onSighting(sighting(bobSecret, t0), t0)
        t.onLanEvent(lanFound(bobSecret, t0), t0)
        val before = t.snapshot().single()
        assertFalse(before.trusted)
        assertTrue(t.setTrustedPeers(listOf(bob)))
        val after = t.snapshot().single()
        assertEquals("t:bob-device-id", after.key)
        assertEquals(setOf(DiscoverySource.BLUETOOTH, DiscoverySource.LAN), after.sources)
        assertFalse(t.setTrustedPeers(listOf(bob)), "no change the second time")
    }

    @Test
    fun duplicateTrustedPeersDoNotStopTheRadar() {
        val t = tracker()
        t.setTrustedPeers(listOf(bob, TrustedPeer(bob.deviceId, Secrets.secret(99), "Impostor")))
        t.onSighting(sighting(bobSecret, t0), t0)
        assertEquals("Bob's Pixel", t.snapshot().single().nickname, "the first entry wins")
    }

    @Test
    fun ownBeaconAndRecordAreIgnored() {
        val t = tracker()
        assertFalse(t.onSighting(sighting(ownSecret, t0), t0))
        assertFalse(t.onLanEvent(lanFound(ownSecret, t0), t0))
        assertTrue(t.snapshot().isEmpty())
        assertEquals(2, t.counters.ownEchoes)
    }

    @Test
    fun malformedInputIsCountedAndDropped() {
        val t = tracker()
        assertFalse(t.onSighting(BeaconSighting(byteArrayOf(1, 2, 3), BeaconCarrier.SERVICE_DATA, null, -50, null, t0), t0))
        val future = Fixtures.BODY.encode().also { it[0] = 0x20 }
        assertFalse(t.onSighting(BeaconSighting(future, BeaconCarrier.SERVICE_DATA, null, -50, null, t0), t0))
        assertFalse(t.onLanEvent(LanEvent.Found(LanService("x", "h", 1, mapOf("v" to "1"))), t0))
        assertEquals(DiscoveryCounters(malformedBeacons = 1, unsupportedBeacons = 1, malformedLanRecords = 1), t.counters)
        assertTrue(t.snapshot().isEmpty())
    }

    @Test
    fun fA2_ringsFollowTheSmoothedSignalWithHysteresis() {
        val t = tracker()
        val secret = Secrets.secret(9)
        val w0 = t0 - t0 % 250
        // Two seconds at −50 dBm settle the bubble on the inner ring.
        for (j in 0 until 20) t.onSighting(sighting(secret, w0 + 100L * j, rssi = -50), w0 + 100L * j)
        assertEquals(Ring.INNER, t.snapshot().single().ring)
        // Then the device walks away to −90 dBm; readings every 100 ms.
        val rings = ArrayList<Ring>()
        for (k in 0 until 60) {
            val time = w0 + 2_000 + 100L * k
            t.onSighting(sighting(secret, time, rssi = -90), time)
            t.advanceTo(time)
            rings += t.snapshot().single().ring
        }
        assertEquals(listOf(Ring.INNER, Ring.MIDDLE, Ring.OUTER), rings.distinct())
        // Windows close at readings 3, 5, 8, 10, 13: −58 (still inner, above −60), −64.4 (middle), −69.5, −73.6
        // (middle down to −75), −76.9 (outer).
        assertEquals(5, rings.indexOf(Ring.MIDDLE))
        assertEquals(13, rings.indexOf(Ring.OUTER))
    }

    @Test
    fun republishesOnlyForVisibleChanges() {
        val t = tracker()
        val secret = Secrets.secret(10)
        val start = t0 - t0 % 250
        assertTrue(t.onSighting(sighting(secret, start, rssi = -60), start))
        // Same RSSI within the first window: only lastSeen changes, which alone is not worth republishing.
        assertFalse(t.onSighting(sighting(secret, start + 100, rssi = -60), start + 100))
        assertEquals(start + 100, t.snapshot().single().lastSeenMillis)
        // Closing the window with the same mean changes nothing visible either.
        assertFalse(t.onSighting(sighting(secret, start + 300, rssi = -60), start + 300))
        // A different signal changes the smoothed value once its window closes.
        assertFalse(t.onSighting(sighting(secret, start + 400, rssi = -70), start + 400))
        assertTrue(t.advanceTo(start + 500))
        assertEquals(start + 400, t.snapshot().single().lastSeenMillis)
    }

    @Test
    fun deadlinesCoverWindowClosesAndExpiry() {
        val t = tracker()
        val start = t0 - t0 % 250
        t.onSighting(sighting(Secrets.secret(11), start + 10), start + 10)
        assertEquals(start + 250, t.nextDeadlineMillis())
        t.advanceTo(start + 250)
        assertEquals(start + 10 + 5_000, t.nextDeadlineMillis())
        t.onLanEvent(lanFound(Secrets.secret(12), start + 300), start + 300)
        assertEquals(start + 10 + 5_000, t.nextDeadlineMillis())
        t.advanceTo(start + 10 + 5_000)
        assertEquals(start + 300 + NearbyConfig().lanRecordMaxAgeMillis, t.nextDeadlineMillis())
        t.advanceTo(start + 300 + NearbyConfig().lanRecordMaxAgeMillis)
        assertTrue(t.snapshot().isEmpty(), "unrefreshed mDNS records age out")
    }

    @Test
    fun missingScanResponsesKeepTheLastName() {
        val t = tracker()
        val secret = Secrets.secret(13)
        t.onSighting(sighting(secret, t0), t0)
        val ad = BeaconAdvertisement.create(crypto, secret, state(), BeaconCarrier.SERVICE_DATA, t0)
        val bare = BeaconSighting.fromAdvertisingData(ad.advertisingData(), 127, null, t0 + 100)!!
        t.onSighting(bare, t0 + 100)
        val device = t.snapshot().single()
        assertEquals("Stranger", device.nickname)
        assertEquals("5A:00:00:00:00:01", device.radioAddress)
        assertEquals(t0 + 100, device.lastSeenMillis)
    }

    @Test
    fun unknownRssiCountsAsPresenceOnly() {
        val t = tracker()
        t.onSighting(sighting(Secrets.secret(14), t0, rssi = 127), t0)
        val device = t.snapshot().single()
        assertNull(device.smoothedRssiDbm)
        assertEquals(Ring.MIDDLE, device.ring)
    }

    @Test
    fun floodingIsBoundedButTrustedPeersAlwaysGetIn() {
        val t = tracker(NearbyConfig(maxDevices = 5))
        t.setTrustedPeers(listOf(bob))
        for (i in 100 until 110) t.onSighting(sighting(Secrets.secret(i), t0), t0)
        assertEquals(5, t.size)
        assertEquals(5, t.counters.flooded)
        assertTrue(t.onSighting(sighting(bobSecret, t0), t0))
        assertEquals(6, t.size)
    }

    @Test
    fun snapshotOrderIsNearestRingThenTrustedThenKey() {
        val t = tracker()
        t.setTrustedPeers(listOf(bob))
        t.onSighting(sighting(Secrets.secret(20), t0, rssi = -80), t0)
        t.onSighting(sighting(Secrets.secret(21), t0, rssi = -50), t0)
        t.onSighting(sighting(bobSecret, t0, rssi = -60), t0)
        t.onSighting(sighting(Secrets.secret(22), t0, rssi = -60), t0)
        val order = t.snapshot().map { it.ring to it.trusted }
        assertEquals(listOf(Ring.INNER to false, Ring.MIDDLE to true, Ring.MIDDLE to false, Ring.OUTER to false), order)
    }
}
