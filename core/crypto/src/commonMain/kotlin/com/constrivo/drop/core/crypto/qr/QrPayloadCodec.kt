package com.constrivo.drop.core.crypto.qr

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.cbor.DeterministicCbor
import com.constrivo.drop.core.crypto.cbor.MalformedCborException
import com.constrivo.drop.core.crypto.constantTimeEquals
import com.constrivo.drop.core.crypto.deviceId
import kotlin.io.encoding.Base64

/**
 * Makes and checks the signed "scan to send" QR payload (architecture §6.3; F-B5, F-B6, T-12).
 *
 * Payload: deterministic CBOR `{1: v, 2: id, 3: identity_pk, 4: eph_id, 5: link {1: kind, 2: ssid, 3: pass,
 * 4: addr, 5: port}?, 6: exp?, 7: sig}`, where `sig = Ed25519("drop-qr-v1" ‖ encoding of the map without key 7)`.
 * The QR text is the payload in base64url without padding.
 *
 * - One-time codes carry `exp` = creation time + [QrPayload.ONE_TIME_VALIDITY_SECONDS] and usually `link`.
 * - Static device codes (desktop "Show my QR", F-B6) carry neither.
 * - A code with `link` must carry `exp`: the link holds a Wi-Fi passphrase.
 *
 * Times are unix seconds from an injected clock, so tests are deterministic.
 */
class QrPayloadCodec(
    private val crypto: CryptoProvider,
) {
    /**
     * A one-time code valid for five minutes from [nowEpochSeconds].
     *
     * @param ephemeralId the device's current 6-byte beacon ID.
     * @param link how the scanner reaches this device, or null to connect over Bluetooth / mDNS.
     */
    fun createOneTime(
        identity: IdentityKey,
        ephemeralId: ByteArray,
        link: QrLink?,
        nowEpochSeconds: Long,
    ): String {
        require(nowEpochSeconds > 0) { "now must be a positive unix time" }
        return toText(sign(identity, ephemeralId, link, nowEpochSeconds + QrPayload.ONE_TIME_VALIDITY_SECONDS))
    }

    /** A static device code: identity and beacon ID only, no link and no expiry (F-B6). */
    fun createStatic(
        identity: IdentityKey,
        ephemeralId: ByteArray,
    ): String = toText(sign(identity, ephemeralId, link = null, expiresAt = null))

    /**
     * Decodes and verifies scanned [text] at [nowEpochSeconds].
     *
     * Checks, in order: encoding, version, signature, `id == device_id(identity_pk)`, expiry (`now ≤ exp`, and
     * `exp ≤ now + 5 min + [QrPayload.MAX_CLOCK_SKEW_SECONDS]`).
     *
     * @throws QrPayloadException with the [QrFailure] that applies; never another exception type for bad input.
     */
    fun parse(
        text: String,
        nowEpochSeconds: Long,
    ): QrPayload {
        if (text.length > QrPayload.MAX_TEXT_LENGTH) {
            throw QrPayloadException(QrFailure.MALFORMED, "QR text is ${text.length} characters, limit ${QrPayload.MAX_TEXT_LENGTH}")
        }
        val bytes =
            try {
                BASE64.decode(text)
            } catch (e: IllegalArgumentException) {
                throw QrPayloadException(QrFailure.MALFORMED, "QR text is not base64url without padding", e)
            }
        if (BASE64.encode(bytes) != text) throw QrPayloadException(QrFailure.MALFORMED, "QR text is not canonical base64url")
        return verify(bytes, nowEpochSeconds)
    }

    /** The CBOR bytes of a signed payload (for golden tests and for transports that skip base64). */
    internal fun signBytes(
        identity: IdentityKey,
        ephemeralId: ByteArray,
        link: QrLink?,
        expiresAt: Long?,
    ): ByteArray = DeterministicCbor.encode(QrPayloadWire.serializer(), sign(identity, ephemeralId, link, expiresAt))

    /** Verifies CBOR payload [bytes]; see [parse]. */
    internal fun verify(
        bytes: ByteArray,
        nowEpochSeconds: Long,
    ): QrPayload {
        val wire =
            try {
                DeterministicCbor.decode(QrPayloadWire.serializer(), bytes, MAX_PAYLOAD_BYTES, maxDepth = 2)
            } catch (e: MalformedCborException) {
                throw QrPayloadException(QrFailure.MALFORMED, "payload is malformed: ${e.message}", e)
            }
        val signature = wire.signature ?: throw QrPayloadException(QrFailure.MALFORMED, "payload has no signature")
        if (wire.version != QrPayload.VERSION) {
            throw QrPayloadException(QrFailure.UNSUPPORTED_VERSION, "payload version ${wire.version}")
        }
        if (!crypto.ed25519Verify(wire.identityKey, signatureInput(wire.withoutSignature()), signature)) {
            throw QrPayloadException(QrFailure.BAD_SIGNATURE, "signature does not verify")
        }
        if (!constantTimeEquals(wire.deviceId, crypto.deviceId(wire.identityKey))) {
            throw QrPayloadException(QrFailure.DEVICE_ID_MISMATCH, "id does not match identity_pk")
        }
        val expiresAt = wire.expiresAt
        if (expiresAt != null) {
            if (nowEpochSeconds > expiresAt) {
                throw QrPayloadException(QrFailure.EXPIRED, "code expired ${nowEpochSeconds - expiresAt} s ago")
            }
            if (expiresAt - nowEpochSeconds > QrPayload.ONE_TIME_VALIDITY_SECONDS + QrPayload.MAX_CLOCK_SKEW_SECONDS) {
                throw QrPayloadException(QrFailure.INVALID_EXPIRY, "code claims to be valid for ${expiresAt - nowEpochSeconds} s")
            }
        }
        return QrPayload(wire.deviceId, wire.identityKey, wire.ephemeralId, wire.link?.toLink(), expiresAt)
    }

    private fun sign(
        identity: IdentityKey,
        ephemeralId: ByteArray,
        link: QrLink?,
        expiresAt: Long?,
    ): QrPayloadWire {
        val identityKey = identity.publicKey
        val unsigned =
            QrPayloadWire(
                version = QrPayload.VERSION,
                deviceId = crypto.deviceId(identityKey),
                identityKey = identityKey,
                ephemeralId = ephemeralId.copyOf(),
                link = link?.let(QrLinkWire::from),
                expiresAt = expiresAt,
            )
        return unsigned.withSignature(identity.sign(signatureInput(unsigned)))
    }

    private fun signatureInput(unsigned: QrPayloadWire): ByteArray =
        SIGNATURE_LABEL + DeterministicCbor.encode(QrPayloadWire.serializer(), unsigned)

    private fun toText(wire: QrPayloadWire): String = BASE64.encode(DeterministicCbor.encode(QrPayloadWire.serializer(), wire))

    private companion object {
        val BASE64: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val SIGNATURE_LABEL = "drop-qr-v1".encodeToByteArray()
        const val MAX_PAYLOAD_BYTES = 768
    }
}
