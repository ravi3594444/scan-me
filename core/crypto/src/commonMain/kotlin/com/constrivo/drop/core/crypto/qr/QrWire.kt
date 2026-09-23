@file:OptIn(ExperimentalSerializationApi::class)

package com.constrivo.drop.core.crypto.qr

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel

/*
 * Wire format of the QR payload (architecture §6.3): a deterministic CBOR map with integer keys, declared in
 * increasing key order. `sig` is the last key, so "all preceding fields" is the same map with `sig` left out.
 */

@Serializable
internal class QrPayloadWire(
    @CborLabel(1) val version: Int,
    @CborLabel(2) val deviceId: ByteArray,
    @CborLabel(3) val identityKey: ByteArray,
    @CborLabel(4) val ephemeralId: ByteArray,
    @CborLabel(5) val link: QrLinkWire? = null,
    @CborLabel(6) val expiresAt: Long? = null,
    @CborLabel(7) val signature: ByteArray? = null,
) {
    init {
        require(version >= 0) { "version must be non-negative" }
        require(deviceId.size == QrPayload.DEVICE_ID_SIZE) { "id must be ${QrPayload.DEVICE_ID_SIZE} bytes" }
        require(identityKey.size == QrPayload.KEY_SIZE) { "identity_pk must be ${QrPayload.KEY_SIZE} bytes" }
        require(ephemeralId.size == QrPayload.EPHEMERAL_ID_SIZE) { "eph_id must be ${QrPayload.EPHEMERAL_ID_SIZE} bytes" }
        require(expiresAt == null || expiresAt > 0) { "exp must be a positive unix time" }
        require(link == null || expiresAt != null) { "a code with link details must expire" }
        require(signature == null || signature.size == QrPayload.SIGNATURE_SIZE) {
            "sig must be ${QrPayload.SIGNATURE_SIZE} bytes"
        }
    }

    fun withoutSignature(): QrPayloadWire = QrPayloadWire(version, deviceId, identityKey, ephemeralId, link, expiresAt, null)

    fun withSignature(signature: ByteArray): QrPayloadWire =
        QrPayloadWire(version, deviceId, identityKey, ephemeralId, link, expiresAt, signature)
}

@Serializable
internal class QrLinkWire(
    @CborLabel(1) val kind: String,
    @CborLabel(2) val ssid: String? = null,
    @CborLabel(3) val passphrase: String? = null,
    @CborLabel(4) val address: String,
    @CborLabel(5) val port: Int,
) {
    init {
        // Construction validates the fields; decoding reports the same errors as malformed input.
        toLink()
    }

    fun toLink(): QrLink {
        val linkKind = requireNotNull(QrLinkKind.fromWire(kind)) { "unknown link kind" }
        return QrLink(linkKind, ssid, passphrase, address, port)
    }

    companion object {
        fun from(link: QrLink): QrLinkWire = QrLinkWire(link.kind.wireName, link.ssid, link.passphrase, link.address, link.port)
    }
}
