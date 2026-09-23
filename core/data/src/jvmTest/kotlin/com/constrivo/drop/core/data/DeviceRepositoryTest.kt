package com.constrivo.drop.core.data

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.deviceId
import com.constrivo.drop.core.crypto.toHex
import com.constrivo.drop.core.crypto.trust.AdvertisingSecret
import com.constrivo.drop.core.discovery.BluetoothAddress
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.WallClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DeviceRepository (architecture §12 `device`; F-B4, F-G3; S3). */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRepositoryTest {
    private val adv1 = AdvertisingSecret(secret(11))
    private val adv2 = AdvertisingSecret(secret(12))
    private val adv3 = AdvertisingSecret(secret(13))

    @Test
    fun recordPeerCreatesAnUntrustedRowWithTheDerivedId() =
        runTest {
            val data = openTestData()
            val device = data.devices.recordPeer(identityKey(1), "  Dev's Pixel​ ", DevicePlatform.PHONE, T0)
            assertEquals(JcaCryptoProvider().deviceId(identityKey(1)).toHex(), device.id)
            assertEquals("Dev's Pixel", device.nickname)
            assertEquals("Dev's Pixel", device.displayName)
            assertEquals(DevicePlatform.PHONE, device.platform)
            assertFalse(device.isTrusted)
            assertFalse(device.autoAccept)
            assertFalse(device.isBrowserSession)
            assertContentEquals(identityKey(1), device.identityKey())
            assertEquals(T0, device.firstSeenMillis)
            assertEquals(T0, device.lastSeenMillis)
            assertNull(device.trustedAtMillis)
            assertEquals(device, data.devices.find(device.id))
            assertEquals(device, data.devices.findByIdentityKey(identityKey(1)))
            assertNull(data.devices.findByIdentityKey(identityKey(2)))
            assertNull(data.devices.trustedKeys(device.id))
        }

    @Test
    fun trustedDeviceIsRecognisedByKeyWhenItsNameChanges() =
        runTest {
            val data = openTestData()
            val id = data.peer(1, nickname = "Dev", at = T0)
            assertTrue(data.devices.trust(id, secret(1), adv1, 0, T0 + 10))
            val renamed = data.devices.recordPeer(identityKey(1), "Dev (work)", DevicePlatform.LAPTOP, T0 + 1_000)
            assertEquals(id, renamed.id)
            assertEquals("Dev (work)", renamed.nickname)
            assertEquals(DevicePlatform.LAPTOP, renamed.platform)
            assertTrue(renamed.isTrusted, "trust survives a name change (F-B4)")
            assertEquals(T0, renamed.firstSeenMillis)
            assertEquals(T0 + 1_000, renamed.lastSeenMillis)
            assertEquals(T0 + 10, renamed.trustedAtMillis)
        }

    @Test
    fun anInvisibleNicknameIsStoredEmpty() =
        runTest {
            val data = openTestData()
            val device = data.devices.recordPeer(identityKey(1), "​‍", DevicePlatform.PHONE)
            assertEquals("", device.nickname)
            data.devices.trust(device.id, secret(1), adv1, 0)
            assertNull(data.devices.trustedKeys(device.id)?.toTrustedPeer()?.nickname, "the radar gets no name rather than an empty one")
        }

    @Test
    fun recordPeerRejectsAKeyStoredUnderItsIdWithDifferentBytes() =
        runTest {
            val data = openTestData()
            val id = JcaCryptoProvider().deviceId(identityKey(1)).toHex()
            data.driver.execute(
                null,
                "INSERT INTO device (id, identity_pk, nickname, platform, first_seen, last_seen) " +
                    "VALUES ('$id', X'${identityKey(2).toHex()}', 'x', 'phone', 0, 0)",
                0,
            )
            val error = assertFailsWith<IdentityConflictException> { data.devices.recordPeer(identityKey(1), "Dev", DevicePlatform.PHONE) }
            assertEquals(id, error.deviceId)
            assertEquals("x", data.devices.find(id)?.nickname, "the stored row is untouched")
        }

    @Test
    fun recordPeerValidatesTheKey() =
        runTest {
            val data = openTestData()
            assertFailsWith<IllegalArgumentException> { data.devices.recordPeer(ByteArray(31), "Dev", DevicePlatform.PHONE) }
        }

    @Test
    fun sightingsOnlyMoveLastSeenForward() =
        runTest {
            val data = openTestData()
            val id = data.peer(1, at = T0)
            assertTrue(data.devices.recordSighting(id, T0 + 50_000))
            assertTrue(data.devices.recordSighting(id, T0 + 1_000))
            assertEquals(T0 + 50_000, data.devices.find(id)?.lastSeenMillis)
            assertFalse(data.devices.recordSighting("f".repeat(32), T0))
            assertFailsWith<IllegalArgumentException> { data.devices.recordSighting("not-an-id", T0) }
        }

    @Test
    fun sightingsAreStoredAtMostEvery30SecondsAndNeverTouchTheKeysFlow() =
        runTest {
            val crypto = SeededCrypto(7)
            val opened = mutableListOf<String>()
            val aead = AeadSecretFieldCipher(crypto, ByteArray(32))
            // Counts every opened secret: the keys flow must not re-open secrets for a sighting.
            val counting =
                object : SecretFieldCipher by aead {
                    override fun open(
                        sealed: ByteArray,
                        context: String,
                    ): ByteArray = aead.open(sealed, context).also { opened += context }
                }
            val data = openTestData(cipher = counting, crypto = crypto)
            val a = data.peer(1, at = T0)
            val b = data.peer(2, at = T0)
            data.devices.trust(a, secret(1), adv1, 0, T0)
            data.devices.trust(b, secret(2), adv2, 0, T0)
            val keys = collectInto(data.devices.observeTrustedKeys())
            val trusted = collectInto(data.devices.observeTrusted())
            runCurrent()
            val openedAtStart = opened.size

            // A burst of beacons within 30 s: not one write.
            for (t in 1..29) assertTrue(data.devices.recordSighting(b, T0 + t * 1_000L))
            runCurrent()
            assertEquals(T0, data.devices.find(b)?.lastSeenMillis)
            assertEquals(1, trusted.size, "no write, so not even the Devices tab re-queried")

            // 30 s later: stored, the Devices tab reorders, the keys flow stays silent and opens nothing.
            assertTrue(data.devices.recordSighting(b, T0 + 30_000))
            runCurrent()
            assertEquals(T0 + 30_000, data.devices.find(b)?.lastSeenMillis)
            assertEquals(listOf(b, a), trusted.last().map { it.id })
            assertEquals(1, keys.size, "a sighting does not re-emit the keys")
            assertEquals(openedAtStart, opened.size, "a sighting opens no secret")
            assertEquals(listOf(a, b).sorted(), keys.single().map { it.device.id }, "keys are in id order")

            // A real change re-emits.
            data.devices.rename(a, "Laptop")
            runCurrent()
            assertEquals(2, keys.size)
            assertEquals("Laptop", keys.last().first { it.device.id == a }.device.displayName)
        }

    @Test
    fun trustStoresBothSecretsAndReadsThemBack() =
        runTest {
            val data = openTestData()
            val id = data.peer(1)
            assertTrue(data.devices.trust(id, secret(1), adv1, 3, T0))
            val keys = assertNotNull(data.devices.trustedKeys(id))
            assertContentEquals(secret(1), keys.recognitionSecret())
            assertEquals(adv1, keys.advertisingSecret)
            assertNull(keys.previousAdvertisingSecret)
            assertEquals(3, keys.device.advertisingSecretGeneration)
            assertTrue(keys.device.isTrusted)
            assertEquals(listOf(keys), data.devices.trustedKeys())
            assertFalse(secret(1).toHex() in keys.toString(), "toString must not print secrets")
        }

    @Test
    fun trustWithoutAnAdvertisingSecretLeavesTheDeviceUnresolvable() =
        runTest {
            val data = openTestData()
            val id = data.peer(1)
            assertTrue(data.devices.trust(id, secret(1)))
            val keys = assertNotNull(data.devices.trustedKeys(id))
            assertNull(keys.advertisingSecret)
            assertNull(keys.toTrustedPeer())
            assertEquals(emptyList(), listOf(keys).toTrustedPeers())
        }

    @Test
    fun trustValidatesItsArguments() =
        runTest {
            val data = openTestData()
            val id = data.peer(1)
            assertFailsWith<IllegalArgumentException> { data.devices.trust(id, ByteArray(16)) }
            assertFailsWith<IllegalArgumentException> { data.devices.trust(id, secret(1), adv1, null) }
            assertFailsWith<IllegalArgumentException> { data.devices.trust(id, secret(1), null, 1) }
            assertFailsWith<IllegalArgumentException> { data.devices.trust(id, secret(1), adv1, -1) }
            assertFalse(data.devices.trust("e".repeat(32), secret(1)))
        }

    @Test
    fun advertisingSecretsAdvanceByGenerationAndKeepThePreviousOne() =
        runTest {
            val data = openTestData()
            val id = data.peer(1)
            assertFalse(data.devices.storeAdvertisingSecret(id, adv1, 0), "untrusted devices get no secret")
            data.devices.trust(id, secret(1))
            assertTrue(data.devices.storeAdvertisingSecret(id, adv1, 0))
            assertTrue(data.devices.storeAdvertisingSecret(id, adv2, 1))
            assertFalse(data.devices.storeAdvertisingSecret(id, adv3, 1), "same generation is a replay")
            assertFalse(data.devices.storeAdvertisingSecret(id, adv3, 0), "older generation is a replay")
            val keys = assertNotNull(data.devices.trustedKeys(id))
            assertEquals(adv2, keys.advertisingSecret)
            assertEquals(adv1, keys.previousAdvertisingSecret)
            assertEquals(1, keys.device.advertisingSecretGeneration)

            val peer = assertNotNull(keys.toTrustedPeer())
            assertEquals(id, peer.deviceId)
            assertEquals("Peer 1", peer.nickname)
        }

    @Test
    fun pairingAgainNeverLosesOrRollsBackAKnownAdvertisingSecret() =
        runTest {
            val data = openTestData()
            val id = data.peer(1)
            assertTrue(data.devices.trust(id, secret(1), adv2, 5, T0))
            assertTrue(data.devices.trust(id, secret(2), atMillis = T0 + 1), "re-pair without a TrustShare")
            var keys = assertNotNull(data.devices.trustedKeys(id))
            assertContentEquals(secret(2), keys.recognitionSecret(), "the recognition secret is replaced")
            assertEquals(adv2, keys.advertisingSecret, "the known k_adv stays")
            assertEquals(5, keys.device.advertisingSecretGeneration)

            assertTrue(data.devices.trust(id, secret(3), adv1, 4, T0 + 2), "re-pair with a stale TrustShare")
            keys = assertNotNull(data.devices.trustedKeys(id))
            assertEquals(adv2, keys.advertisingSecret, "an older generation never replaces a newer one")
            assertEquals(5, keys.device.advertisingSecretGeneration)
            assertNull(keys.previousAdvertisingSecret)

            assertTrue(data.devices.trust(id, secret(4), adv3, 6, T0 + 3), "re-pair with a newer TrustShare")
            keys = assertNotNull(data.devices.trustedKeys(id))
            assertEquals(adv3, keys.advertisingSecret)
            assertEquals(adv2, keys.previousAdvertisingSecret, "the replaced secret becomes the previous generation")
            assertEquals(6, keys.device.advertisingSecretGeneration)
            assertEquals(T0, keys.device.trustedAtMillis, "the first trust time stays")
        }

    @Test
    fun thePreviousAdvertisingSecretStopsResolvingAfterItsGracePeriod() =
        runTest {
            val clock = WallClock { T0 + testScheduler.currentTime }
            val data = openTestData(clock)
            val id = data.peer(1)
            data.devices.trust(id, secret(1), adv1, 0)
            val keys = collectInto(data.devices.observeTrustedKeys())
            runCurrent()
            assertTrue(data.devices.storeAdvertisingSecret(id, adv2, 1))
            runCurrent()
            assertEquals(adv1, keys.last().single().previousAdvertisingSecret)

            advanceTimeBy(DeviceRepository.PREVIOUS_SECRET_GRACE_MILLIS - 1)
            runCurrent()
            assertEquals(adv1, keys.last().single().previousAdvertisingSecret, "still inside the grace period")
            advanceTimeBy(1)
            runCurrent()
            val after = keys.last().single()
            assertNull(after.previousAdvertisingSecret, "the forgotten device's copy of the old k_adv no longer resolves")
            assertEquals(adv2, after.advertisingSecret)
            assertNull(data.devices.trustedKeys(id)?.previousAdvertisingSecret)
            assertEquals(30 * 60_000L, DeviceRepository.PREVIOUS_SECRET_GRACE_MILLIS)
        }

    @Test
    fun ownAdvertisingGenerationStartsAtZeroAndOnlyAdvances() =
        runTest {
            val data = openTestData()
            assertEquals(0, data.devices.ownAdvertisingGeneration())
            assertEquals(1, data.devices.advanceOwnAdvertisingGeneration())
            assertEquals(2, data.devices.advanceOwnAdvertisingGeneration())
            assertEquals(2, data.devices.ownAdvertisingGeneration())
            assertEquals(SettingsSnapshot::class, data.settings.snapshot()::class, "not a user setting, and harmless to them")

            // What a peer does with our shares: every advanced generation is accepted.
            val peer = data.peer(1)
            data.devices.trust(peer, secret(1))
            assertTrue(data.devices.storeAdvertisingSecret(peer, adv1, data.devices.ownAdvertisingGeneration()))
            assertTrue(data.devices.storeAdvertisingSecret(peer, adv2, data.devices.advanceOwnAdvertisingGeneration()))

            data.driver.execute(null, "UPDATE settings SET value = '-1' WHERE key = '${DeviceRepository.OWN_GENERATION_KEY}'", 0)
            assertFailsWith<DataCorruptionException>("never silently back to 0") { data.devices.ownAdvertisingGeneration() }
        }

    @Test
    fun trustedPeerLookupAnswersByIdentityKey() =
        runTest {
            val data = openTestData()
            val a = data.peer(1)
            val b = data.peer(2)
            data.peer(3)
            data.devices.trust(a, secret(1), adv1, 0)
            data.devices.trust(b, secret(2))
            val lookup = data.devices.trustedKeys().toTrustedPeerLookup()
            assertContentEquals(secret(1), lookup.recognitionSecretFor(identityKey(1)))
            assertContentEquals(secret(2), lookup.recognitionSecretFor(identityKey(2)))
            assertNull(lookup.recognitionSecretFor(identityKey(3)), "untrusted")
            assertNull(lookup.recognitionSecretFor(identityKey(4)), "unknown")
            assertEquals(listOf(a), data.devices.trustedKeys().toTrustedPeers().map { it.deviceId })
        }

    @Test
    fun renameAndAutoAccept() =
        runTest {
            val data = openTestData()
            val id = data.peer(1, nickname = "Dev")
            assertTrue(data.devices.rename(id, "  Mum's tablet "))
            assertEquals("Mum's tablet", data.devices.find(id)?.displayName)
            assertEquals("Dev", data.devices.find(id)?.nickname)
            assertTrue(data.devices.rename(id, null))
            assertEquals("Dev", data.devices.find(id)?.displayName)
            assertFailsWith<IllegalArgumentException> { data.devices.rename(id, " ​ ") }
            assertFalse(data.devices.rename("d".repeat(32), "x"))

            assertFalse(data.devices.setAutoAccept(id, true), "only trusted devices auto-accept")
            data.devices.trust(id, secret(1))
            assertTrue(data.devices.setAutoAccept(id, true))
            assertTrue(data.devices.find(id)!!.autoAccept)
            assertTrue(data.devices.setAutoAccept(id, false))
            assertFalse(data.devices.find(id)!!.autoAccept)
        }

    @Test
    fun classicAddressIsKeptOnlyForTrustedDevices() =
        runTest {
            val data = openTestData()
            val id = data.peer(1, platform = DevicePlatform.DESKTOP)
            val address = BluetoothAddress.parse("AA:BB:CC:DD:EE:FF")
            assertFalse(data.devices.setClassicAddress(id, address))
            data.devices.trust(id, secret(1))
            assertTrue(data.devices.setClassicAddress(id, address))
            assertEquals(address, data.devices.find(id)?.classicAddress)
            assertFailsWith<IllegalArgumentException> { data.devices.setClassicAddress(id, BluetoothAddress.parse("02:00:00:00:00:00")) }
            assertTrue(data.devices.setClassicAddress(id, null))
            assertNull(data.devices.find(id)?.classicAddress)
        }

    @Test
    fun forgetClearsTrustAndEverySecretSoTheDeviceIsAStrangerNextTime() =
        runTest {
            val data = openTestData()
            val id = data.peer(1, nickname = "Dev")
            data.devices.trust(id, secret(1), adv1, 0)
            data.devices.storeAdvertisingSecret(id, adv2, 1)
            data.devices.setAutoAccept(id, true)
            data.devices.rename(id, "Work laptop")
            data.devices.setClassicAddress(id, BluetoothAddress.parse("AA:BB:CC:DD:EE:FF"))
            val transfer = transferId(1)
            data.transfers.create(NewTransfer(transfer, id, TransferDirection.SEND, 10, 1))

            assertTrue(data.devices.forget(id))

            val forgotten = assertNotNull(data.devices.find(id), "the row stays for History")
            assertFalse(forgotten.isTrusted)
            assertFalse(forgotten.autoAccept)
            assertNull(forgotten.customName)
            assertNull(forgotten.classicAddress)
            assertNull(forgotten.advertisingSecretGeneration)
            assertNull(forgotten.trustedAtMillis)
            assertNull(data.devices.trustedKeys(id))
            assertEquals(emptyList(), data.devices.trustedKeys())
            assertNull(data.devices.trustedKeys().toTrustedPeerLookup().recognitionSecretFor(identityKey(1)))
            val stored =
                data.driver.longs(
                    "SELECT count(*) FROM device WHERE recognition_secret IS NULL AND peer_adv_secret IS NULL AND previous_peer_adv_secret IS NULL",
                )
            assertEquals(listOf(1L), stored)
            assertEquals("Dev", data.transfers.get(transfer)?.peerName, "History keeps the device under its nickname")

            // Next contact: the device is recorded again as a stranger and must pair again to be trusted.
            val again = data.devices.recordPeer(identityKey(1), "Dev", DevicePlatform.PHONE)
            assertFalse(again.isTrusted)
            assertFalse(data.devices.setAutoAccept(id, true))
            assertFalse(data.devices.forget("c".repeat(32)))
        }

    @Test
    fun observeTrustedFollowsTrustChanges() =
        runTest {
            val data = openTestData()
            val a = data.peer(1, at = T0)
            val b = data.peer(2, at = T0 + 1)
            val seen = collectInto(data.devices.observeTrusted())
            runCurrent()
            data.devices.trust(a, secret(1), atMillis = T0 + 2)
            runCurrent()
            data.devices.trust(b, secret(2), atMillis = T0 + 3)
            runCurrent()
            data.devices.recordSighting(a, T0 + 60_000)
            runCurrent()
            data.devices.forget(b)
            runCurrent()
            assertEquals(
                listOf(emptyList(), listOf(a), listOf(b, a), listOf(a, b), listOf(a)),
                seen.map { list -> list.map { it.id } },
            )
        }

    @Test
    fun observeOneDeviceAndItsKeys() =
        runTest {
            val data = openTestData()
            val id = data.peer(1)
            val device = collectInto(data.devices.observe(id))
            val keys = collectInto(data.devices.observeTrustedKeys())
            runCurrent()
            data.devices.trust(id, secret(1), adv1, 0)
            runCurrent()
            data.devices.storeAdvertisingSecret(id, adv2, 1)
            runCurrent()
            assertEquals(listOf(false, true, true), device.map { it!!.isTrusted })
            assertEquals(listOf(null, 0, 1), device.map { it!!.advertisingSecretGeneration })
            assertEquals(listOf(emptyList(), listOf(adv1), listOf(adv2)), keys.map { list -> list.map { it.advertisingSecret } })
        }

    @Test
    fun browserSessionsHaveNoKeyAndCannotBeTrusted() =
        runTest {
            val data = openTestData()
            val id = "b".repeat(32)
            val browser = data.devices.recordBrowserPeer(id, "Browser", T0)
            assertTrue(browser.isBrowserSession)
            assertNull(browser.identityKey())
            assertEquals(DevicePlatform.BROWSER_PROXY, browser.platform)
            assertFalse(data.devices.trust(id, secret(1)))
            assertEquals(T0 + 5, data.devices.recordBrowserPeer(id, "Browser", T0 + 5).lastSeenMillis)
            val peer = data.peer(1)
            assertFailsWith<IllegalArgumentException> { data.devices.recordBrowserPeer(peer, "Browser") }
        }

    @Test
    fun anUnknownPlatformRoundTrips() =
        runTest {
            val data = openTestData()
            assertEquals(DevicePlatform.UNKNOWN, data.devices.recordPeer(identityKey(1), "Tab", DevicePlatform.UNKNOWN).platform)
        }

    @Test
    fun sealedSecretsNeverReachTheDatabaseInClear() =
        runTest {
            val crypto = SeededCrypto(7)
            val cipher = AeadSecretFieldCipher(crypto, ByteArray(32) { it.toByte() })
            val data = openTestData(cipher = cipher, crypto = crypto)
            val id = data.peer(1)
            data.devices.trust(id, secret(1), adv1, 0)
            data.devices.storeAdvertisingSecret(id, adv2, 1)
            val stored =
                listOf("recognition_secret", "peer_adv_secret", "previous_peer_adv_secret").map { column ->
                    data.driver.blob("SELECT $column FROM device")!!
                }
            for ((raw, clear) in stored.zip(listOf(secret(1), adv2.bytes(), adv1.bytes()))) {
                assertFalse(raw.toHex().contains(clear.toHex()), "stored value contains the plaintext")
                assertEquals(1, raw[0].toInt(), "AEAD format byte")
            }
            val keys = assertNotNull(data.devices.trustedKeys(id))
            assertContentEquals(secret(1), keys.recognitionSecret())
            assertEquals(adv2, keys.advertisingSecret)
            assertEquals(adv1, keys.previousAdvertisingSecret)
        }

    @Test
    fun aSecretMovedToAnotherDeviceDoesNotOpen() =
        runTest {
            val crypto = SeededCrypto(7)
            val data = openTestData(cipher = AeadSecretFieldCipher(crypto, ByteArray(32)), crypto = crypto)
            val a = data.peer(1)
            val b = data.peer(2)
            data.devices.trust(a, secret(1))
            data.devices.trust(b, secret(2))
            data.driver.execute(
                null,
                "UPDATE device SET recognition_secret = (SELECT recognition_secret FROM device WHERE id = '$a') WHERE id = '$b'",
                0,
            )
            assertFailsWith<DataCorruptionException> { data.devices.trustedKeys(b) }
            assertNotNull(data.devices.trustedKeys(a))
        }

    @Test
    fun oneUnreadableDeviceLeavesTheOthersTrusted() =
        runTest {
            val data = openTestData()
            val a = data.peer(1)
            val b = data.peer(2)
            val c = data.peer(3)
            for ((n, id) in listOf(a, b, c).withIndex()) data.devices.trust(id, secret(n), adv1, 0)
            val reported = mutableListOf<String>()
            val keys = collectInto(data.devices.observeTrustedKeys { id, _ -> reported += id })
            runCurrent()
            data.driver.execute(null, "UPDATE device SET recognition_secret = X'01' WHERE id = '$b'", 0)
            data.devices.rename(a, "A") // any write re-runs the flow
            runCurrent()
            assertEquals(listOf(a, c).sorted(), keys.last().map { it.device.id }, "the flow keeps going without b")
            assertEquals(listOf(b), reported.distinct())

            val skipped = mutableListOf<String>()
            assertEquals(listOf(a, c).sorted(), data.devices.trustedKeys { id, _ -> skipped += id }.map { it.device.id })
            assertEquals(listOf(b), skipped)
            assertFailsWith<DataCorruptionException>("asking for that one device still says why") { data.devices.trustedKeys(b) }
        }

    @Test
    fun switchingFromPlaintextToAeadKeepsEveryTrustedDevice() =
        runTest {
            val dir = Files.createTempDirectory("drop-reseal").toFile()
            val path = File(dir, "drop.db").path
            val crypto = SeededCrypto(7)
            val aead = AeadSecretFieldCipher(crypto, ByteArray(32) { 3 })
            val first = DropData.open(JdbcSqliteDriverFactory.file(path), Dispatchers.IO, crypto, FakeClock(), LocalCalendar.UTC)
            val id = first.peer(1)
            first.devices.trust(id, secret(1), adv1, 0)
            first.devices.storeAdvertisingSecret(id, adv2, 1)
            val untrusted = first.peer(2)
            first.close()

            // A later release plugs in the AEAD cipher.
            val second = DropData.open(JdbcSqliteDriverFactory.file(path), Dispatchers.IO, crypto, FakeClock(), LocalCalendar.UTC, aead)
            val keys = assertNotNull(second.devices.trustedKeys(id))
            assertContentEquals(secret(1), keys.recognitionSecret())
            assertEquals(adv2, keys.advertisingSecret)
            assertEquals(adv1, keys.previousAdvertisingSecret)
            for (column in listOf("recognition_secret", "peer_adv_secret", "previous_peer_adv_secret")) {
                assertEquals(1, second.driver.blob("SELECT $column FROM device WHERE id = '$id'")!![0].toInt(), "$column re-sealed")
            }
            assertNull(second.driver.blob("SELECT recognition_secret FROM device WHERE id = '$untrusted'"))
            val sealed = second.driver.blob("SELECT recognition_secret FROM device WHERE id = '$id'")!!.toList()
            second.close()

            // Opening again changes nothing, and the re-seal was one-time: a plaintext value written later does not open.
            val third = DropData.open(JdbcSqliteDriverFactory.file(path), Dispatchers.IO, crypto, FakeClock(), LocalCalendar.UTC, aead)
            assertEquals(sealed, third.driver.blob("SELECT recognition_secret FROM device WHERE id = '$id'")!!.toList())
            assertContentEquals(secret(1), third.devices.trustedKeys(id)!!.recognitionSecret())
            val planted = third.peer(3)
            third.devices.trust(planted, secret(3))
            third.driver.execute(null, "UPDATE device SET recognition_secret = X'00${secret(9).toHex()}' WHERE id = '$planted'", 0)
            third.close()
            val fourth = DropData.open(JdbcSqliteDriverFactory.file(path), Dispatchers.IO, crypto, FakeClock(), LocalCalendar.UTC, aead)
            assertEquals(listOf(id), fourth.devices.trustedKeys().map { it.device.id })
            assertFailsWith<DataCorruptionException> { fourth.devices.trustedKeys(planted) }
            fourth.close()
            dir.deleteRecursively()
        }

    @Test
    fun corruptStoredValuesRaiseDataCorruption() =
        runTest {
            val data = openTestData()
            val id = data.peer(1)
            data.devices.trust(id, secret(1))
            data.driver.execute(null, "UPDATE device SET recognition_secret = X'0001' WHERE id = '$id'", 0)
            assertFailsWith<DataCorruptionException> { data.devices.trustedKeys(id) }
            data.driver.execute(null, "UPDATE device SET recognition_secret = X'01' WHERE id = '$id'", 0)
            assertFailsWith<DataCorruptionException> { data.devices.trustedKeys(id) }
        }
}
