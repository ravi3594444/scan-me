package com.constrivo.drop.core.crypto.qr

import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.TestFixtures
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_A
import com.constrivo.drop.core.crypto.TestFixtures.IDENTITY_B
import com.constrivo.drop.core.crypto.cbor.DeterministicCbor
import com.constrivo.drop.core.crypto.cbor.RawCbor
import com.constrivo.drop.core.crypto.deviceId
import com.constrivo.drop.core.crypto.hexToBytes
import com.constrivo.drop.core.crypto.toHex
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** F-B5 scan-to-send QR payload (architecture §6.3), T-12 expired code, F-B6 static code. */
class QrPayloadTest {
    private val crypto = TestFixtures.crypto
    private val codec = QrPayloadCodec(crypto)
    private val ephemeralId = byteArrayOf(1, 2, 3, 4, 5, 6)
    private val now = 1_790_000_000L
    private val p2p = QrLink(QrLinkKind.P2P, "DIRECT-7F-Ananya", "k3y-Pass-2026", "192.168.49.1", 47000)

    @Test
    fun fB5_oneTimeCodeRoundTrips() {
        for (link in listOf(p2p, QrLink(QrLinkKind.HOTSPOT, "AndroidShare_4821", "12345678", "192.168.43.1", 1), lan(), null)) {
            val text = codec.createOneTime(IDENTITY_A, ephemeralId, link, now)
            val payload = codec.parse(text, now)
            assertContentEquals(IDENTITY_A.publicKey, payload.identityKey)
            assertContentEquals(crypto.deviceId(IDENTITY_A.publicKey), payload.deviceId)
            assertContentEquals(ephemeralId, payload.ephemeralId)
            assertEquals(now + QrPayload.ONE_TIME_VALIDITY_SECONDS, payload.expiresAtEpochSeconds)
            assertFalse(payload.isStatic)
            assertEquals(link?.kind, payload.link?.kind)
            assertEquals(link?.ssid, payload.link?.ssid)
            assertEquals(link?.passphrase, payload.link?.passphrase)
            assertEquals(link?.address, payload.link?.address)
            assertEquals(link?.port, payload.link?.port)
            assertTrue(text.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "base64url without padding")
        }
    }

    @Test
    fun fB6_staticCodeHasNoLinkAndNoExpiry() {
        val text = codec.createStatic(IDENTITY_A, ephemeralId)
        val bytes = Base64.getUrlDecoder().decode(text)
        assertEquals(0xA5, bytes[0].toInt() and 0xFF, "five keys: v, id, identity_pk, eph_id, sig")
        val payload = codec.parse(text, now)
        assertTrue(payload.isStatic)
        assertNull(payload.link)
        assertNull(payload.expiresAtEpochSeconds)
        // Static codes never expire.
        codec.parse(text, 4_000_000_000L)
    }

    @Test
    fun goldenBytes_oneTimeAndStatic() {
        assertEquals(GOLDEN_ONE_TIME, codec.signBytes(IDENTITY_A, ephemeralId, p2p, now + 300).toHex())
        assertEquals(GOLDEN_ONE_TIME_TEXT, codec.createOneTime(IDENTITY_A, ephemeralId, p2p, now))
        assertEquals(GOLDEN_STATIC, codec.signBytes(IDENTITY_A, ephemeralId, null, null).toHex())
        assertEquals(GOLDEN_STATIC_TEXT, codec.createStatic(IDENTITY_A, ephemeralId))
        assertContentEquals(GOLDEN_ONE_TIME.hexToBytes(), Base64.getUrlDecoder().decode(GOLDEN_ONE_TIME_TEXT))
    }

    @Test
    fun goldenSignatureCoversAllPrecedingFields() {
        val bytes = GOLDEN_STATIC.hexToBytes()
        // Without key 7: header 0xa5 → 0xa4 and drop "07 58 40" ‖ 64-byte signature.
        val unsigned = byteArrayOf(0xA4.toByte()) + bytes.copyOfRange(1, bytes.size - 67)
        val signature = bytes.copyOfRange(bytes.size - 64, bytes.size)
        assertTrue(crypto.ed25519Verify(IDENTITY_A.publicKey, "drop-qr-v1".encodeToByteArray() + unsigned, signature))
    }

    @Test
    fun t12_expiredCodeIsRefused() {
        val text = codec.createOneTime(IDENTITY_A, ephemeralId, p2p, now)
        codec.parse(text, now + 300) // the last valid second
        val e = assertFailsWith<QrPayloadException> { codec.parse(text, now + 301) }
        assertEquals(QrFailure.EXPIRED, e.reason)
        assertEquals(QrFailure.EXPIRED, assertFailsWith<QrPayloadException> { codec.parse(text, now + 86_400) }.reason)
    }

    @Test
    fun expiryTooFarAheadIsRefused() {
        val allowed = now + QrPayload.ONE_TIME_VALIDITY_SECONDS + QrPayload.MAX_CLOCK_SKEW_SECONDS
        codec.verify(codec.signBytes(IDENTITY_A, ephemeralId, p2p, allowed), now)
        val e = assertFailsWith<QrPayloadException> { codec.verify(codec.signBytes(IDENTITY_A, ephemeralId, p2p, allowed + 1), now) }
        assertEquals(QrFailure.INVALID_EXPIRY, e.reason)
        // A scanner whose clock runs up to two minutes behind still accepts a fresh code.
        codec.parse(codec.createOneTime(IDENTITY_A, ephemeralId, p2p, now), now - QrPayload.MAX_CLOCK_SKEW_SECONDS)
    }

    @Test
    fun everyBitFlipIsRejected() {
        val bytes = GOLDEN_ONE_TIME.hexToBytes()
        for (bit in 0 until bytes.size * 8) {
            val flipped = bytes.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }
            assertFailsWith<QrPayloadException>("bit $bit") { codec.verify(flipped, now) }
        }
    }

    @Test
    fun everyCharacterSubstitutionIsRejected() {
        val text = GOLDEN_STATIC_TEXT
        for (i in text.indices) {
            val replacement = if (text[i] == 'A') 'B' else 'A'
            val tampered = text.substring(0, i) + replacement + text.substring(i + 1)
            assertFailsWith<QrPayloadException>("char $i") { codec.parse(tampered, now) }
        }
    }

    @Test
    fun codeSignedByAnotherKeyIsRefused() {
        // Replace identity_pk and id by B's but keep A's signature.
        val genuine = DeterministicCbor.decode(QrPayloadWire.serializer(), GOLDEN_STATIC.hexToBytes(), 1024)
        val swapped =
            QrPayloadWire(
                genuine.version,
                crypto.deviceId(IDENTITY_B.publicKey),
                IDENTITY_B.publicKey,
                genuine.ephemeralId,
                signature = genuine.signature,
            )
        val e = assertFailsWith<QrPayloadException> { codec.verify(DeterministicCbor.encode(QrPayloadWire.serializer(), swapped), now) }
        assertEquals(QrFailure.BAD_SIGNATURE, e.reason)
    }

    @Test
    fun smallOrderIdentityKeyIsRefused() {
        // Identity W = 01 00…00 with the signature anyone can make for it: a "verified" code without a private key.
        val w = TestFixtures.NEUTRAL_POINT
        val unsigned = RawCbor.map(1 to 1, 2 to crypto.deviceId(w), 3 to w, 4 to ephemeralId)
        val input = "drop-qr-v1".encodeToByteArray() + RawCbor.encode(unsigned)
        assertTrue(TestFixtures.rawJcaEd25519Verify(w, input, TestFixtures.FORGED_NEUTRAL_SIGNATURE), "the forgery is valid for plain JCA")
        val forged =
            RawCbor.encode(
                RawCbor.map(1 to 1, 2 to crypto.deviceId(w), 3 to w, 4 to ephemeralId, 7 to TestFixtures.FORGED_NEUTRAL_SIGNATURE),
            )
        assertEquals(QrFailure.MALFORMED, assertFailsWith<QrPayloadException> { codec.verify(forged, now) }.reason)
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(forged)
        assertEquals(QrFailure.MALFORMED, assertFailsWith<QrPayloadException> { codec.parse(text, now) }.reason)
        for (key in TestFixtures.SMALL_ORDER_KEYS) {
            val bytes = RawCbor.encode(RawCbor.map(1 to 1, 2 to crypto.deviceId(key), 3 to key, 4 to ephemeralId, 7 to ByteArray(64)))
            assertEquals(QrFailure.MALFORMED, assertFailsWith<QrPayloadException>(key.toHex()) { codec.verify(bytes, now) }.reason)
        }
    }

    @Test
    fun deviceIdMustMatchTheIdentityKey() {
        val unsigned = QrPayloadWire(QrPayload.VERSION, ByteArray(16) { 7 }, IDENTITY_A.publicKey, ephemeralId)
        val e = assertFailsWith<QrPayloadException> { codec.verify(sign(unsigned), now) }
        assertEquals(QrFailure.DEVICE_ID_MISMATCH, e.reason)
    }

    @Test
    fun unknownVersionIsRefused() {
        val unsigned = QrPayloadWire(2, crypto.deviceId(IDENTITY_A.publicKey), IDENTITY_A.publicKey, ephemeralId)
        assertEquals(QrFailure.UNSUPPORTED_VERSION, assertFailsWith<QrPayloadException> { codec.verify(sign(unsigned), now) }.reason)
    }

    @Test
    fun malformedTextIsRefused() {
        val cases =
            listOf(
                "",
                "$GOLDEN_STATIC_TEXT=",
                "$GOLDEN_STATIC_TEXT==",
                GOLDEN_STATIC_TEXT.replace('_', '/'),
                GOLDEN_STATIC_TEXT.replace('-', '+'),
                " $GOLDEN_STATIC_TEXT",
                GOLDEN_STATIC_TEXT.dropLast(1),
                GOLDEN_STATIC_TEXT + "A",
                "*".repeat(40),
                "A".repeat(QrPayload.MAX_TEXT_LENGTH + 4),
                "https://example.invalid/qr",
            )
        for (text in cases) {
            val e = assertFailsWith<QrPayloadException>(text.take(40)) { codec.parse(text, now) }
            assertEquals(QrFailure.MALFORMED, e.reason, text.take(40))
        }
    }

    @Test
    fun malformedPayloadsAreRefused() {
        val id = crypto.deviceId(IDENTITY_A.publicKey)
        val pk = IDENTITY_A.publicKey
        val sig = ByteArray(64)
        val link = RawCbor.map(1 to "lan", 4 to "10.0.0.2", 5 to 1234)
        val cases =
            mapOf(
                "link without expiry" to RawCbor.map(1 to 1, 2 to id, 3 to pk, 4 to ephemeralId, 5 to link, 7 to sig),
                "unknown link kind" to
                    RawCbor.map(
                        1 to 1,
                        2 to id,
                        3 to pk,
                        4 to ephemeralId,
                        5 to RawCbor.map(1 to "wifi", 4 to "10.0.0.2", 5 to 1),
                        6 to now,
                        7 to sig,
                    ),
                "lan link with ssid" to
                    RawCbor.map(
                        1 to 1,
                        2 to id,
                        3 to pk,
                        4 to ephemeralId,
                        5 to RawCbor.map(1 to "lan", 2 to "net", 4 to "10.0.0.2", 5 to 1),
                        6 to now,
                        7 to sig,
                    ),
                "port zero" to
                    RawCbor.map(
                        1 to 1,
                        2 to id,
                        3 to pk,
                        4 to ephemeralId,
                        5 to RawCbor.map(1 to "lan", 4 to "10.0.0.2", 5 to 0),
                        6 to now,
                        7 to sig,
                    ),
                "short eph_id" to RawCbor.map(1 to 1, 2 to id, 3 to pk, 4 to ByteArray(5), 7 to sig),
                "short id" to RawCbor.map(1 to 1, 2 to ByteArray(15), 3 to pk, 4 to ephemeralId, 7 to sig),
                "short signature" to RawCbor.map(1 to 1, 2 to id, 3 to pk, 4 to ephemeralId, 7 to ByteArray(63)),
                "no signature" to RawCbor.map(1 to 1, 2 to id, 3 to pk, 4 to ephemeralId),
                "zero expiry" to RawCbor.map(1 to 1, 2 to id, 3 to pk, 4 to ephemeralId, 6 to 0, 7 to sig),
                "negative expiry" to RawCbor.map(1 to 1, 2 to id, 3 to pk, 4 to ephemeralId, 6 to -5, 7 to sig),
                "unknown key" to RawCbor.map(1 to 1, 2 to id, 3 to pk, 4 to ephemeralId, 7 to sig, 8 to 0),
                "explicit null link" to RawCbor.map(1 to 1, 2 to id, 3 to pk, 4 to ephemeralId, 5 to null, 7 to sig),
            )
        for ((name, map) in cases) {
            val e = assertFailsWith<QrPayloadException>(name) { codec.verify(RawCbor.encode(map), now) }
            assertEquals(QrFailure.MALFORMED, e.reason, name)
        }
    }

    @Test
    fun randomInputOnlyRaisesQrPayloadException() {
        val random = Random(6_3)
        val golden = GOLDEN_ONE_TIME.hexToBytes()
        repeat(2_000) {
            val sample =
                if (it % 2 == 0) {
                    random.nextBytes(random.nextInt(0, 400))
                } else {
                    golden.copyOf().also { b -> repeat(random.nextInt(1, 4)) { b[random.nextInt(b.size)] = random.nextInt(256).toByte() } }
                }
            try {
                codec.verify(sample, now)
            } catch (e: QrPayloadException) {
                // expected
            } catch (e: Exception) {
                fail("expected QrPayloadException, got $e")
            }
            try {
                codec.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(sample), now)
            } catch (e: QrPayloadException) {
                // expected
            } catch (e: Exception) {
                fail("expected QrPayloadException, got $e")
            }
        }
    }

    @Test
    fun linkValidation() {
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.P2P, null, "12345678", "1.2.3.4", 1) }
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.HOTSPOT, "ssid", null, "1.2.3.4", 1) }
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.P2P, "", "12345678", "1.2.3.4", 1) }
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.P2P, "x".repeat(33), "12345678", "1.2.3.4", 1) }
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.P2P, "ssid", "1234567", "1.2.3.4", 1) }
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.P2P, "ssid", "x".repeat(64), "1.2.3.4", 1) }
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.P2P, "ssid", "passéword", "1.2.3.4", 1) }
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.LAN, "ssid", null, "1.2.3.4", 1) }
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.LAN, null, null, "", 1) }
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.LAN, null, null, "1.2.3.4 ", 1) }
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.LAN, null, null, "1.2.3.4", 0) }
        assertFailsWith<IllegalArgumentException> { QrLink(QrLinkKind.LAN, null, null, "1.2.3.4", 65536) }
        QrLink(QrLinkKind.LAN, null, null, "fe80::1%wlan0", 65535)
        assertFalse(p2p.toString().contains(p2p.passphrase!!), "toString must not print the passphrase")
        assertFailsWith<IllegalArgumentException> { codec.createOneTime(IDENTITY_A, ByteArray(5), null, now) }
    }

    @Test
    fun refusalIsACryptoException() {
        val e: CryptoException = assertFailsWith<QrPayloadException> { codec.parse("", now) }
        assertTrue(e.message!!.startsWith("MALFORMED"))
    }

    private fun lan(): QrLink = QrLink(QrLinkKind.LAN, null, null, "192.168.1.20", 47000)

    private fun sign(unsigned: QrPayloadWire): ByteArray {
        val input = "drop-qr-v1".encodeToByteArray() + DeterministicCbor.encode(QrPayloadWire.serializer(), unsigned)
        return DeterministicCbor.encode(QrPayloadWire.serializer(), unsigned.withSignature(IDENTITY_A.sign(input)))
    }

    private companion object {
        const val GOLDEN_ONE_TIME =
            "a70101025021fe31dfa154a261626bf854046fd227035820d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a04" +
                "4601020304050605a5016370327002704449524543542d37462d416e616e7961036d6b33792d506173732d32303236046c3139322e3136" +
                "382e34392e310519b798061a6ab13cac07584077b2b4aaca3fd18232accc77548e7f01ec511307ffc378c82ba535b417636a0bf883426c" +
                "a8ee43f4c9021113a516ed59172b569cf85d56edd26193e922923702"
        const val GOLDEN_ONE_TIME_TEXT =
            "pwEBAlAh_jHfoVSiYWJr-FQEb9InA1gg11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURoERgECAwQFBgWlAWNwMnACcERJUkVDVC03Ri1B" +
                "bmFueWEDbWszeS1QYXNzLTIwMjYEbDE5Mi4xNjguNDkuMQUZt5gGGmqxPKwHWEB3srSqyj_RgjKszHdUjn8B7FETB__DeMgrpTW0F2NqC_iD" +
                "Qmyo7kP0yQIRE6UW7VkXK1ac-F1W7dJhk-kikjcC"
        const val GOLDEN_STATIC =
            "a50101025021fe31dfa154a261626bf854046fd227035820d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a04" +
                "46010203040506075840e8ee5e118a295cb0ada9c3e610e895f3f80cd826ada0a0eddac2e00f19b3e8ad1f6e6c68d8f0be98ede3c58060" +
                "6b6affdc4e21a81021a28391ac42f41c81c309"
        const val GOLDEN_STATIC_TEXT =
            "pQEBAlAh_jHfoVSiYWJr-FQEb9InA1gg11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURoERgECAwQFBgdYQOjuXhGKKVywranD5hDolfP4" +
                "DNgmraCg7drC4A8Zs-itH25saNjwvpjt48WAYGtq_9xOIagQIaKDkaxC9ByBwwk"
    }
}
