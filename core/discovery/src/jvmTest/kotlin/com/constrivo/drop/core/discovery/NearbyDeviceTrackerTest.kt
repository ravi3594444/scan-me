package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.Secrets.EPOCH_START
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The nearby-device state machine (F‑A2, F‑A3, F‑A5, F‑J1, design §3.2–3.3). */
class NearbyDeviceTrackerTest {
    private val crypto = JcaCryptoProvider()
    private val t0 = EPOCH_START + 60_000
    private val ownSecret = Secrets.secret(0)
    private val bobSecret = Secrets.secret(1)
    private val bob = TrustedPeer("bob-device-id", bobSecret, "Bob's Pixel")

    /** The next epoch boundary after [t0]. */
    private val boundary = EPOCH_START + EphemeralIds.EPOCH_MILLIS

    /** Both clocks on one timeline, as when the wall clock never steps. */
    private fun at(millis: Long) = ClockReading(millis, millis)

    private fun trust(vararg peers: TrustedPeer) = TrustState(listOf(ownSecret), peers.toList())

    private fun tracker(config: NearbyConfig = NearbyConfig()) = NearbyDeviceTracker(crypto, config).also { it.setTrust(trust(), at(0)) }

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
        instanceName: String? = null,
    ): LanEvent.Found {
        val service = MdnsRecord.create(crypto, secret, s, 49152, at).toLanService(host)
        return LanEvent.Found(if (instanceName == null) service else service.copy(instanceName = instanceName))
    }

    private fun eph(
        secret: ByteArray,
        at: Long,
    ) = EphemeralIds.at(crypto, secret, at)

    @Test
    fun fA2_deviceAppearsOnItsFirstBeacon() {
        val t = tracker()
        assertTrue(t.onSighting(sighting(Secrets.secret(7), t0, rssi = -50), at(t0)))
        val device = t.snapshot().single()
        assertEquals("Stranger", device.nickname)
        assertFalse(device.trusted)
        assertEquals(Ring.INNER, device.ring)
        assertEquals(setOf(DiscoverySource.BLUETOOTH), device.sources)
        assertEquals(DevicePlatform.PHONE, device.platform)
        assertEquals(listOf(RadioAddress("5A:00:00:00:00:01", t0)), device.radioAddresses)
        assertEquals(NetworkHint(0x01020304), device.networkHint)
        assertEquals(RadarPlacement.stableAngleDegrees(device.key), device.stableAngleDegrees)
        assertEquals("e:${eph(Secrets.secret(7), t0).toHex()}", device.key)
    }

    @Test
    fun designSection33_deviceLeavesFiveSecondsAfterItsLastBeacon() {
        val t = tracker()
        t.onSighting(sighting(Secrets.secret(7), t0), at(t0))
        t.onSighting(sighting(Secrets.secret(7), t0 + 1_000), at(t0 + 1_000))
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
        val long = state(nickname = "Stranger with a long full nickname")
        t.onSighting(sighting(secret, t0, rssi = -65, s = long), at(t0))
        assertEquals("Stranger with a long ful", t.snapshot().single().nickname, "24 bytes over Bluetooth")
        assertTrue(t.snapshot().single().nicknameTruncated)
        assertTrue(t.onLanEvent(lanFound(secret, t0 + 100, long), at(t0 + 100)))
        val device = t.snapshot().single()
        assertEquals(setOf(DiscoverySource.BLUETOOTH, DiscoverySource.LAN), device.sources)
        assertEquals("Stranger with a long full nickname", device.nickname, "the mDNS name completes the shortened one")
        assertFalse(device.nicknameTruncated)
        assertEquals(listOf(LanEndpoint("drop-${device.ephemeralId.toHex()}", "192.168.1.30", 49152)), device.lanEndpoints)
        assertEquals(Ring.MIDDLE, device.ring)
        assertEquals(t0 + 100, device.lastSeenElapsedMillis)
        // When Bluetooth goes quiet the device stays, found over the LAN only, on the middle ring.
        t.advanceTo(t0 + 10_000)
        val lanOnly = t.snapshot().single()
        assertTrue(lanOnly.lanOnly)
        assertEquals(Ring.MIDDLE, lanOnly.ring)
        assertNull(lanOnly.smoothedRssiDbm)
        assertTrue(lanOnly.radioAddresses.isEmpty())
        // "Lost" removes it.
        assertTrue(t.onLanEvent(LanEvent.Lost(lanOnly.lanEndpoints.single().instanceName), at(t0 + 11_000)))
        assertTrue(t.snapshot().isEmpty())
    }

    @Test
    fun fA3_mdnsOnlyDevicesSitOnTheMiddleRing() {
        val t = tracker()
        t.onLanEvent(lanFound(Secrets.secret(8), t0, state(platform = DevicePlatform.DESKTOP)), at(t0))
        val device = t.snapshot().single()
        assertEquals(Ring.MIDDLE, device.ring)
        assertEquals(DevicePlatform.DESKTOP, device.platform)
        assertTrue(device.networkHint.isNone)
        assertNull(device.carrier)
    }

    @Test
    fun s3_trustedPeersResolveAndMergeAcrossEpochs() {
        val t = tracker()
        t.setTrust(trust(bob), at(t0))
        // mDNS record from the previous epoch, beacon from the current one: still one device.
        t.onLanEvent(lanFound(bobSecret, t0 - EphemeralIds.EPOCH_MILLIS), at(t0))
        t.onSighting(sighting(bobSecret, t0, rssi = -52, s = state(nickname = "spoofed name")), at(t0))
        val device = t.snapshot().single()
        assertEquals("t:bob-device-id", device.key)
        assertEquals("bob-device-id", device.trustedDeviceId)
        assertTrue(device.trusted)
        assertEquals("Bob's Pixel", device.nickname, "the stored name wins over unauthenticated air")
        assertEquals(setOf(DiscoverySource.BLUETOOTH, DiscoverySource.LAN), device.sources)
        // Bob's ID rotates: same bubble, same key, same angle.
        val next = t0 + EphemeralIds.EPOCH_MILLIS
        t.onSighting(sighting(bobSecret, next, rssi = -52), at(next))
        val rotated = t.snapshot().single()
        assertEquals(device.key, rotated.key)
        assertEquals(device.stableAngleDegrees, rotated.stableAngleDegrees)
        assertEquals(eph(bobSecret, next), rotated.ephemeralId)
    }

    @Test
    fun s3_aPeersPreviousSecretStillResolves() {
        val t = tracker()
        val rotated = Secrets.secret(2)
        t.setTrust(trust(TrustedPeer(bob.deviceId, rotated, "Bob's Pixel", previousAdvertisingSecrets = listOf(bobSecret))), at(t0))
        t.onSighting(sighting(bobSecret, t0), at(t0))
        t.onSighting(sighting(rotated, t0 + 100), at(t0 + 100))
        assertEquals("t:bob-device-id", t.snapshot().single().key, "both generations are Bob, one bubble")
    }

    @Test
    fun fA5_trustedOnlyDeviceIsVisibleOnlyToHoldersOfItsSecret() {
        val stranger = tracker()
        val trustedOnly = state(visibility = Visibility.TRUSTED_ONLY)
        assertFalse(stranger.onSighting(sighting(bobSecret, t0, s = trustedOnly), at(t0)))
        assertFalse(stranger.onLanEvent(lanFound(bobSecret, t0, trustedOnly), at(t0)))
        assertTrue(stranger.snapshot().isEmpty())
        assertEquals(2, stranger.counters.trustedOnlyStrangers)

        val friend = tracker()
        friend.setTrust(trust(bob), at(t0))
        assertTrue(friend.onSighting(sighting(bobSecret, t0, s = trustedOnly), at(t0)))
        val device = friend.snapshot().single()
        assertTrue(device.trusted)
        assertEquals("Bob's Pixel", device.nickname)
        assertEquals(Visibility.TRUSTED_ONLY, device.visibility)
        assertTrue(device.networkHint.isNone, "N4: no hint in Trusted-only mode")

        // "Forget": the device disappears from the radar at once.
        assertTrue(friend.setTrust(trust(), at(t0)))
        assertTrue(friend.snapshot().isEmpty())
    }

    @Test
    fun fA5_aStrangerSwitchingToTrustedOnlyLeavesAtOnce() {
        val t = tracker()
        t.onSighting(sighting(bobSecret, t0), at(t0))
        assertEquals(1, t.size)
        assertTrue(t.onSighting(sighting(bobSecret, t0 + 100, s = state(visibility = Visibility.TRUSTED_ONLY)), at(t0 + 100)))
        assertTrue(t.snapshot().isEmpty(), "not after the 5 s timeout")
    }

    @Test
    fun newlyTrustedDevicesAreRefiled() {
        val t = tracker()
        t.onSighting(sighting(bobSecret, t0), at(t0))
        t.onLanEvent(lanFound(bobSecret, t0), at(t0))
        val before = t.snapshot().single()
        assertFalse(before.trusted)
        assertTrue(t.setTrust(trust(bob), at(t0)))
        val after = t.snapshot().single()
        assertEquals("t:bob-device-id", after.key)
        assertEquals(setOf(DiscoverySource.BLUETOOTH, DiscoverySource.LAN), after.sources)
        assertFalse(t.setTrust(trust(bob), at(t0)), "no change the second time")
    }

    @Test
    fun anUnchangedTrustStateCostsNoCryptography() {
        val counting = CountingCrypto()
        val t = NearbyDeviceTracker(counting)
        val peers = (1..100).map { TrustedPeer("peer-$it", Secrets.secret(it)) }
        t.setTrust(TrustState(listOf(ownSecret), peers), at(t0))
        t.onSighting(sighting(Secrets.secret(500), t0), at(t0))
        val calls = counting.hmacCalls
        // The trust store re-emits an equal value (another copy of the same rows).
        val copy = TrustState(listOf(ownSecret.copyOf()), (1..100).map { TrustedPeer("peer-$it", Secrets.secret(it)) })
        assertFalse(t.setTrust(copy, at(t0 + 10)))
        t.onSighting(sighting(Secrets.secret(500), t0 + 20), at(t0 + 20))
        assertEquals(calls, counting.hmacCalls, "the resolver's tables were kept")
    }

    @Test
    fun duplicateTrustedPeersDoNotStopTheRadar() {
        val t = tracker()
        t.setTrust(trust(bob, TrustedPeer(bob.deviceId, Secrets.secret(99), "Impostor")), at(t0))
        t.onSighting(sighting(bobSecret, t0), at(t0))
        assertEquals("Bob's Pixel", t.snapshot().single().nickname, "the first entry wins")
    }

    @Test
    fun ownBeaconAndRecordAreIgnored() {
        val t = tracker()
        assertFalse(t.onSighting(sighting(ownSecret, t0), at(t0)))
        assertFalse(t.onLanEvent(lanFound(ownSecret, t0), at(t0)))
        assertTrue(t.snapshot().isEmpty())
        assertEquals(2, t.counters.ownEchoes)
    }

    @Test
    fun s3_rotatingTheOwnSecretKeepsOldAndNewEchoesOffTheRadar() {
        val t = tracker()
        val rotated = Secrets.secret(300)
        // "Forget": WP2 rotates k_adv; the radar keeps running with the new trust state.
        t.onSighting(sighting(rotated, t0), at(t0))
        assertEquals(1, t.size, "before the update, the new own ID looks like a stranger")
        t.setTrust(TrustState(listOf(rotated), emptyList()), at(t0 + 10))
        assertTrue(t.snapshot().isEmpty(), "the sighting filed as a stranger is re-filed as our own")
        // Echoes of the last advertisement with the old secret, and of the new one, are both ours.
        assertFalse(t.onSighting(sighting(ownSecret, t0 + 1_000), at(t0 + 1_000)))
        assertFalse(t.onLanEvent(lanFound(ownSecret, t0 + 1_000), at(t0 + 1_000)))
        assertFalse(t.onSighting(sighting(rotated, t0 + 1_000), at(t0 + 1_000)))
        assertTrue(t.snapshot().isEmpty())
        // The old secret is still recognised until two epochs after the rotation, then it is nobody's.
        val end = t0 + 10 + NearbyConfig().ownSecretRetentionMillis
        t.advanceTo(end - 1)
        assertFalse(t.onSighting(sighting(ownSecret, end - 1), at(end - 1)))
        assertEquals(4, t.counters.ownEchoes)
        t.advanceTo(end)
        assertTrue(t.onSighting(sighting(ownSecret, end), at(end)))
    }

    @Test
    fun malformedInputIsCountedAndDropped() {
        val t = tracker()
        assertFalse(t.onSighting(BeaconSighting(byteArrayOf(1, 2, 3), BeaconCarrier.SERVICE_DATA, null, -50, null, t0), at(t0)))
        val future = Fixtures.BODY.encode().also { it[0] = 0x20 }
        assertFalse(t.onSighting(BeaconSighting(future, BeaconCarrier.SERVICE_DATA, null, -50, null, t0), at(t0)))
        assertFalse(t.onLanEvent(LanEvent.Found(LanService("x", "h", 1, mapOf("v" to "1"))), at(t0)))
        assertEquals(DiscoveryCounters(malformedBeacons = 1, unsupportedBeacons = 1, malformedLanRecords = 1), t.counters)
        assertTrue(t.snapshot().isEmpty())
    }

    @Test
    fun fA2_ringsFollowTheSmoothedSignalWithHysteresis() {
        val t = tracker()
        val secret = Secrets.secret(9)
        val w0 = t0 - t0 % 250
        // Two seconds at −50 dBm settle the bubble on the inner ring.
        for (j in 0 until 20) t.onSighting(sighting(secret, w0 + 100L * j, rssi = -50), at(w0 + 100L * j))
        assertEquals(Ring.INNER, t.snapshot().single().ring)
        // Then the device walks away to −90 dBm; readings every 100 ms.
        val rings = ArrayList<Ring>()
        for (k in 0 until 60) {
            val time = w0 + 2_000 + 100L * k
            t.onSighting(sighting(secret, time, rssi = -90), at(time))
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
    fun fA2_aOneSecondAdvertiserSettlesInWallTime() {
        // Background peers advertise once a second (§5.1): a 30 dB step still reaches the inner ring in about 2 s.
        val t = tracker()
        val secret = Secrets.secret(19)
        val w0 = t0 - t0 % 1_000
        for (k in 0 until 5) t.onSighting(sighting(secret, w0 + 1_000L * k, rssi = -80), at(w0 + 1_000L * k))
        val step = w0 + 5_000
        var reached: Long? = null
        var now = step
        var nextBeacon = step
        while (reached == null && now < step + 15_000) {
            if (now == nextBeacon) {
                t.onSighting(sighting(secret, now, rssi = -50), at(now))
                nextBeacon += 1_000
            }
            t.advanceTo(now)
            if (t.snapshot().single().ring == Ring.INNER) reached = now - step
            now += 50
        }
        assertTrue(reached!! <= 2_500, "inner ring after $reached ms (9 s before bridging)")
    }

    @Test
    fun republishesOnlyForVisibleChanges() {
        val t = tracker()
        val secret = Secrets.secret(10)
        val start = t0 - t0 % 250
        assertTrue(t.onSighting(sighting(secret, start, rssi = -60), at(start)))
        // Same RSSI within the first window: only lastSeen changes, which alone is not worth republishing.
        assertFalse(t.onSighting(sighting(secret, start + 100, rssi = -60), at(start + 100)))
        assertEquals(start + 100, t.snapshot().single().lastSeenElapsedMillis)
        // Closing the window with the same mean changes nothing visible either.
        assertFalse(t.onSighting(sighting(secret, start + 300, rssi = -60), at(start + 300)))
        // A different signal changes the smoothed value once its window closes.
        assertFalse(t.onSighting(sighting(secret, start + 400, rssi = -70), at(start + 400)))
        assertTrue(t.advanceTo(start + 500))
        assertEquals(start + 400, t.snapshot().single().lastSeenElapsedMillis)
    }

    @Test
    fun deadlinesCoverWindowClosesAndExpiry() {
        val t = tracker()
        val start = t0 - t0 % 250
        t.onSighting(sighting(Secrets.secret(11), start + 10), at(start + 10))
        assertEquals(start + 250, t.nextDeadlineMillis())
        assertFalse(t.advanceTo(start + 249), "nothing is due yet")
        t.advanceTo(start + 250)
        assertEquals(start + 10 + 5_000, t.nextDeadlineMillis())
        t.onLanEvent(lanFound(Secrets.secret(12), start + 300), at(start + 300))
        assertEquals(start + 10 + 5_000, t.nextDeadlineMillis())
        t.advanceTo(start + 10 + 5_000)
        assertEquals(start + 300 + NearbyConfig().lanRecordMaxAgeMillis, t.nextDeadlineMillis())
        t.advanceTo(start + 300 + NearbyConfig().lanRecordMaxAgeMillis)
        assertTrue(t.snapshot().isEmpty(), "unrefreshed mDNS records age out")
    }

    @Test
    fun wallClockStepsChangeNoDuration() {
        val t = tracker()
        val a = Secrets.secret(30)
        val b = Secrets.secret(31)
        val hour = 3_600_000L

        // Monotonic time starts at 0; the peers' clocks are right, ours steps back an hour after one second.
        fun ours(
            elapsed: Long,
            offset: Long,
        ) = ClockReading(elapsed, t0 + elapsed + offset)
        t.onSighting(sighting(a, t0), ours(0, 0))
        t.onSighting(sighting(b, t0), ours(0, 0))
        t.onLanEvent(lanFound(Secrets.secret(32), t0), ours(0, 0))
        for (k in 1..10) {
            t.onSighting(sighting(a, t0 + 1_000L * k), ours(1_000L * k, -hour))
            t.advanceTo(1_000L * k)
        }
        val keys = t.snapshot().map { it.key }.toSet()
        assertFalse("e:${eph(b, t0).toHex()}" in keys, "B left 5 s after its last beacon, although the wall clock went back")
        assertTrue("e:${eph(a, t0).toHex()}" in keys)
        assertEquals(2, keys.size, "A (one bubble) and the LAN device")
        // Our clock jumps forward 31 minutes: the LAN record seen 12 s ago is not two epochs old.
        t.onSighting(sighting(a, t0 + 12_000), ours(12_000, 31 * 60_000L))
        t.advanceTo(12_250)
        assertEquals(2, t.snapshot().size)
        assertEquals(12_000L + 5_000, t.nextDeadlineMillis(), "A leaves 5 s after its last beacon, on the monotonic clock")
    }

    @Test
    fun missingScanResponsesKeepTheLastName() {
        val t = tracker()
        val secret = Secrets.secret(13)
        t.onSighting(sighting(secret, t0), at(t0))
        val ad = BeaconAdvertisement.create(crypto, secret, state(), BeaconCarrier.SERVICE_DATA, t0)
        val bare = BeaconSighting.fromAdvertisingData(ad.advertisingData(), 127, null, t0 + 100)!!
        t.onSighting(bare, at(t0 + 100))
        val device = t.snapshot().single()
        assertEquals("Stranger", device.nickname)
        assertEquals(listOf("5A:00:00:00:00:01"), device.radioAddresses.map { it.address })
        assertEquals(t0 + 100, device.lastSeenElapsedMillis)
    }

    @Test
    fun unknownRssiCountsAsPresenceOnly() {
        val t = tracker()
        t.onSighting(sighting(Secrets.secret(14), t0, rssi = 127), at(t0))
        val device = t.snapshot().single()
        assertNull(device.smoothedRssiDbm)
        assertEquals(Ring.MIDDLE, device.ring)
    }

    @Test
    fun floodingIsBoundedButTrustedPeersAlwaysGetIn() {
        val t = tracker(NearbyConfig(maxDevices = 5))
        t.setTrust(trust(bob), at(t0))
        for (i in 100 until 110) t.onSighting(sighting(Secrets.secret(i), t0, address = "5A:00:00:00:01:$i"), at(t0))
        assertEquals(5, t.size)
        assertEquals(5, t.counters.flooded)
        assertTrue(t.onSighting(sighting(bobSecret, t0), at(t0)))
        assertEquals(6, t.size)
    }

    @Test
    fun snapshotOrderIsNearestRingThenTrustedThenKey() {
        val t = tracker()
        t.setTrust(trust(bob), at(t0))
        t.onSighting(sighting(Secrets.secret(20), t0, rssi = -80), at(t0))
        t.onSighting(sighting(Secrets.secret(21), t0, rssi = -50), at(t0))
        t.onSighting(sighting(bobSecret, t0, rssi = -60), at(t0))
        t.onSighting(sighting(Secrets.secret(22), t0, rssi = -60), at(t0))
        val order = t.snapshot().map { it.ring to it.trusted }
        assertEquals(listOf(Ring.INNER to false, Ring.MIDDLE to true, Ring.MIDDLE to false, Ring.OUTER to false), order)
    }

    // --- Unauthenticated sources never replace each other (review finding: hijack of a merged bubble) ---

    @Test
    fun aForgedTxtRecordWithAStrangersIdDoesNotTakeOverItsBubble() {
        val t = tracker()
        val priya = Secrets.secret(40)
        val priyaState = state(nickname = "Priya")
        t.onSighting(sighting(priya, t0, s = priyaState), at(t0))
        t.onLanEvent(lanFound(priya, t0 + 50, priyaState, host = "192.168.1.30"), at(t0 + 50))
        val genuine = t.snapshot().single()
        assertEquals(listOf("192.168.1.30"), genuine.lanEndpoints.map { it.host })
        // A LAN host announces Priya's rotating ID under another instance, with a tempting name.
        val id = eph(priya, t0).toHex()
        val forged = lanFound(priya, t0 + 100, state(nickname = "Priya (send here)"), host = "10.0.0.66", instanceName = "drop-$id (2)")
        assertTrue(t.onLanEvent(forged, at(t0 + 100)))
        val contested = t.snapshot().single()
        assertEquals(genuine.key, contested.key)
        assertEquals("Priya", contested.nickname, "the Bluetooth name is kept")
        assertTrue(contested.lanEndpoints.isEmpty(), "two hosts claim one stranger: neither endpoint is offered")
        assertEquals(setOf(DiscoverySource.BLUETOOTH), contested.sources)
        assertEquals(1, t.counters.contestedLanClaims)
        // Once the forged claim is gone, the genuine endpoint is back.
        t.onLanEvent(LanEvent.Lost("drop-$id (2)"), at(t0 + 200))
        assertEquals(listOf("192.168.1.30"), t.snapshot().single().lanEndpoints.map { it.host })
    }

    @Test
    fun aLanNicknameNeverRelabelsADifferentBluetoothName() {
        val t = tracker()
        val priya = Secrets.secret(41)
        t.onSighting(sighting(priya, t0, s = state(nickname = "Priya")), at(t0))
        t.onLanEvent(lanFound(priya, t0 + 100, state(nickname = "Priya (send here)"), host = "10.0.0.66"), at(t0 + 100))
        val device = t.snapshot().single()
        assertEquals("Priya", device.nickname)
        // The lone claim is still listed (nothing contradicts it); it is a candidate, like every endpoint of a stranger.
        assertEquals(listOf("10.0.0.66"), device.lanEndpoints.map { it.host })
    }

    @Test
    fun aForgedTxtRecordForATrustedPeerKeepsEveryCandidate() {
        val t = tracker()
        t.setTrust(trust(bob), at(t0))
        t.onLanEvent(lanFound(bobSecret, t0, host = "192.168.1.30"), at(t0))
        val id = eph(bobSecret, t0).toHex()
        t.onLanEvent(lanFound(bobSecret, t0 + 100, host = "10.0.0.66", instanceName = "drop-$id (2)"), at(t0 + 100))
        val device = t.snapshot().single()
        assertEquals("Bob's Pixel", device.nickname)
        // Newest first; WP5 tries each behind the identity check, so the forged one costs one failed attempt.
        assertEquals(listOf("10.0.0.66", "192.168.1.30"), device.lanEndpoints.map { it.host })
    }

    @Test
    fun aRelayedBeaconAddsACandidateAddressInsteadOfReplacingIt() {
        val t = tracker()
        t.setTrust(trust(bob), at(t0))
        t.onSighting(sighting(bobSecret, t0, address = "5A:B0:B0:B0:B0:01"), at(t0))
        // A relay repeats Bob's beacon from its own address.
        t.onSighting(sighting(bobSecret, t0 + 100, address = "66:66:66:66:66:66"), at(t0 + 100))
        t.onSighting(sighting(bobSecret, t0 + 200, address = "5A:B0:B0:B0:B0:01"), at(t0 + 200))
        val device = t.snapshot().single()
        assertEquals(listOf("5A:B0:B0:B0:B0:01", "66:66:66:66:66:66"), device.radioAddresses.map { it.address }, "first heard first")
        assertEquals(listOf(t0 + 200, t0 + 100), device.radioAddresses.map { it.lastSeenElapsedMillis })
        // The relay stops; its address expires 5 s after it was last heard, Bob's stays while he is heard.
        for (k in 1..6) t.onSighting(sighting(bobSecret, t0 + 200 + 1_000L * k, address = "5A:B0:B0:B0:B0:01"), at(t0 + 200 + 1_000L * k))
        t.advanceTo(t0 + 6_200)
        assertEquals(listOf("5A:B0:B0:B0:B0:01"), t.snapshot().single().radioAddresses.map { it.address })
    }

    @Test
    fun candidateAddressesAreBounded() {
        val t = tracker()
        for (k in 0 until 10) t.onSighting(sighting(bobSecret, t0 + k, address = "66:66:66:66:66:%02X".format(k)), at(t0 + k))
        val addresses = t.snapshot().single().radioAddresses.map { it.address }
        assertEquals(NearbyConfig().maxRadioAddresses, addresses.size)
        assertTrue("66:66:66:66:66:09" in addresses, "the least recently heard ones made room")
    }

    // --- Strangers across an ID rotation (F‑J1 with a stable bubble) ---

    /** A stranger heard every second from [from] to [until] (exclusive). */
    private fun beaconEverySecond(
        t: NearbyDeviceTracker,
        secret: ByteArray,
        from: Long,
        until: Long,
        address: (Long) -> String = { "5A:00:00:00:00:01" },
        s: LocalBeaconState = state(),
    ) {
        var time = from
        while (time < until) {
            t.onSighting(sighting(secret, time, s = s, address = address(time)), at(time))
            t.advanceTo(time)
            time += 1_000
        }
    }

    @Test
    fun fJ1_aStrangerKeepsItsBubbleAcrossTheRotationWhenItsAddressIsShared() {
        val t = tracker()
        val secret = Secrets.secret(50)
        val ana = state(nickname = "Ana")
        beaconEverySecond(t, secret, boundary - 30_000, boundary, s = ana)
        val before = t.snapshot().single()
        t.onSighting(sighting(secret, boundary + 300, s = ana), at(boundary + 300))
        // A late packet of the old advertising set does not bring the old ID back.
        assertFalse(t.onSighting(sighting(secret, boundary - 1, s = ana), at(boundary + 800)))
        beaconEverySecond(t, secret, boundary + 1_300, boundary + 6_300, s = ana)
        val after = t.snapshot()
        assertEquals(1, after.size, "one bubble, not two for 5 s")
        val device = after.single()
        assertEquals(before.key, device.key)
        assertEquals(before.stableAngleDegrees, device.stableAngleDegrees)
        assertEquals(before.ring, device.ring)
        assertEquals(eph(secret, boundary), device.ephemeralId)
        // Nor does a replay of the old ID from another address.
        assertFalse(t.onSighting(sighting(secret, boundary - 1, s = ana, address = "66:66:66:66:66:66"), at(boundary + 6_400)))
        assertEquals(before.key, t.snapshot().single().key)
        assertEquals(2, t.counters.retiredIdBeacons)
        assertEquals(0, t.counters.undoneLinks)
    }

    @Test
    fun fJ1_aRelayCannotKeepAStrangersBubbleByRotatingFirst() {
        val t = tracker()
        val priya = Secrets.secret(62)
        val s = state(nickname = "Priya")
        // Priya is heard directly and through a relay that repeats her beacon from its own address.
        var time = boundary - 30_000
        while (time < boundary) {
            t.onSighting(sighting(priya, time, s = s, address = "5A:00:00:00:00:0A"), at(time))
            t.onSighting(sighting(priya, time, s = s, address = "66:66:66:66:66:66"), at(time + 10))
            time += 1_000
        }
        val oldKey = t.snapshot().single().key
        // Right after the boundary the relay advertises an ID of its own: it shares an address with the bubble.
        val relayOwn =
            sighting(Secrets.secret(63), boundary + 100, s = state(nickname = "Priya (send here)"), address = "66:66:66:66:66:66")
        t.onSighting(relayOwn, at(boundary + 100))
        assertEquals(oldKey, t.snapshot().single().key)
        // Priya's real next ID, with the facts her bubble had: the link is undone, and the old bubble is not the relay's.
        t.onSighting(sighting(priya, boundary + 400, s = s, address = "5A:00:00:00:00:0B"), at(boundary + 400))
        val devices = t.snapshot()
        assertFalse(oldKey in devices.map { it.key })
        assertEquals(setOf("Priya", "Priya (send here)"), devices.map { it.nickname }.toSet())
        assertEquals(1, t.counters.undoneLinks)
    }

    @Test
    fun fJ1_aStrangerWithANewAddressIsLinkedOnWhatItSaysInClear() {
        // N4: the advertising set restarts at the boundary, so the address changes too.
        val t = tracker()
        val secret = Secrets.secret(51)
        val s = state(nickname = "Ana's phone")
        beaconEverySecond(t, secret, boundary - 30_000, boundary, address = { "5A:00:00:00:00:0A" }, s = s)
        val before = t.snapshot().single()
        beaconEverySecond(t, secret, boundary + 400, boundary + 6_400, address = { "5A:00:00:00:00:0B" }, s = s)
        val device = t.snapshot().single()
        assertEquals(before.key, device.key)
        assertEquals(listOf("5A:00:00:00:00:0B"), device.radioAddresses.map { it.address }, "the old address expired")
    }

    @Test
    fun fJ1_aProvisionalLinkIsUndoneWhenALookAlikeAppears() {
        val t = tracker()
        val ana = Secrets.secret(52)
        val twin = Secrets.secret(53)
        val s = state(nickname = "iPhone")
        beaconEverySecond(t, ana, boundary - 30_000, boundary, address = { "5A:00:00:00:00:0A" }, s = s)
        val oldKey = t.snapshot().single().key
        t.onSighting(sighting(ana, boundary + 200, s = s, address = "5A:00:00:00:00:0B"), at(boundary + 200))
        assertEquals(oldKey, t.snapshot().single().key, "linked on the facts alone")
        // A second device with the same facts shows up: the guess is withdrawn, both get bubbles of their own.
        t.onSighting(sighting(twin, boundary + 500, s = s, address = "5A:00:00:00:00:0C"), at(boundary + 500))
        val keys = t.snapshot().map { it.key }.toSet()
        assertEquals(setOf("e:${eph(ana, boundary).toHex()}", "e:${eph(twin, boundary).toHex()}"), keys)
        assertEquals(1, t.counters.undoneLinks)
    }

    @Test
    fun fJ1_aProvisionalLinkIsUndoneWhenTheOldIdIsHeardAgain() {
        val t = tracker()
        val ana = Secrets.secret(54)
        val other = Secrets.secret(55)
        val s = state(nickname = "Galaxy")
        // Ana's clock is 3 s behind: she still advertises her old ID after the boundary.
        var time = boundary - 30_000
        while (time < boundary + 3_000) {
            t.onSighting(sighting(ana, time - 3_000, s = s, address = "5A:00:00:00:00:0A"), at(time))
            t.advanceTo(time)
            time += 1_000
        }
        val oldKey = t.snapshot().single().key
        // A different device with the same facts rotated on time.
        t.onSighting(sighting(other, boundary + 3_100, s = s, address = "5A:00:00:00:00:0C"), at(boundary + 3_100))
        assertEquals(1, t.snapshot().size)
        // Ana's old ID again, after the grace period: two devices after all.
        t.onSighting(sighting(ana, boundary - 1, s = s, address = "5A:00:00:00:00:0A"), at(boundary + 4_500))
        val devices = t.snapshot().associateBy { it.key }
        assertEquals(setOf(oldKey, "e:${eph(other, boundary).toHex()}"), devices.keys)
        assertEquals(eph(ana, boundary - 1), devices.getValue(oldKey).ephemeralId, "the old bubble stays with Ana")
        assertEquals(1, t.counters.undoneLinks)
    }

    @Test
    fun fJ1_noLinkingAwayFromAnEpochBoundary() {
        val t = tracker()
        beaconEverySecond(t, Secrets.secret(56), t0, t0 + 3_000)
        // A device with the same facts appears mid-epoch: a different device, a bubble of its own.
        t.onSighting(sighting(Secrets.secret(57), t0 + 3_000), at(t0 + 3_000))
        assertEquals(2, t.snapshot().size)
    }

    @Test
    fun fJ1_aLanOnlyStrangerKeepsItsBubbleWhenItReRegisters() {
        val t = tracker()
        val secret = Secrets.secret(58)
        t.onLanEvent(lanFound(secret, boundary - 60_000, host = "192.168.1.44"), at(boundary - 60_000))
        val before = t.snapshot().single()
        // At the boundary it withdraws the old instance and registers the new one (N4).
        t.onLanEvent(LanEvent.Lost(before.lanEndpoints.single().instanceName), at(boundary + 100))
        assertTrue(t.snapshot().isEmpty())
        t.onLanEvent(lanFound(secret, boundary + 400, host = "192.168.1.44"), at(boundary + 400))
        val after = t.snapshot().single()
        assertEquals(before.key, after.key)
        assertEquals("drop-${eph(secret, boundary).toHex()}", after.lanEndpoints.single().instanceName)
        // The invisible placeholder does not outlive the window.
        val other = Secrets.secret(59)
        val oldKey = "e:${eph(other, boundary - 60_000).toHex()}"
        t.onLanEvent(lanFound(other, boundary - 60_000, host = "192.168.1.45"), at(boundary - 60_000 + 1))
        t.onLanEvent(LanEvent.Lost("drop-${eph(other, boundary - 60_000).toHex()}"), at(boundary - 9_000))
        t.advanceTo(boundary + 1_000)
        t.onLanEvent(lanFound(other, boundary + 2_000, host = "192.168.1.45"), at(boundary + 2_000))
        assertNotEquals(oldKey, t.snapshot().first { it.lanEndpoints.single().host == "192.168.1.45" }.key)
    }

    @Test
    fun fJ1_aSessionLinksTheNextIdExplicitly() {
        val t = tracker()
        val secret = Secrets.secret(60)
        t.onSighting(sighting(secret, t0), at(t0))
        val key = t.snapshot().single().key
        // Learnt over the encrypted link: the peer's ID of the next epoch.
        val next = eph(secret, boundary)
        assertTrue(t.link(key, next))
        assertFalse(t.link("e:000000000000", next), "no such bubble")
        // Heard far from any automatic-link evidence: new address, new name.
        t.onSighting(sighting(secret, boundary + 30_000, s = state(nickname = "Renamed"), address = "5A:00:00:00:00:77"), at(t0 + 1_000))
        val device = t.snapshot().single()
        assertEquals(key, device.key)
        assertEquals(next, device.ephemeralId)
    }

    @Test
    fun fJ1_linkingMergesABubbleThatAlreadyAppeared() {
        val t = tracker()
        val secret = Secrets.secret(61)
        t.onSighting(sighting(secret, t0), at(t0))
        val key = t.snapshot().single().key
        t.onSighting(sighting(secret, boundary + 60_000, address = "5A:00:00:00:00:78"), at(t0 + 500))
        t.onLanEvent(lanFound(secret, boundary + 60_000), at(t0 + 600))
        assertEquals(2, t.snapshot().size)
        assertTrue(t.link(key, eph(secret, boundary + 60_000)))
        val device = t.snapshot().single()
        assertEquals(key, device.key)
        assertEquals(eph(secret, boundary + 60_000), device.ephemeralId)
        assertEquals(setOf(DiscoverySource.BLUETOOTH, DiscoverySource.LAN), device.sources)
    }
}
