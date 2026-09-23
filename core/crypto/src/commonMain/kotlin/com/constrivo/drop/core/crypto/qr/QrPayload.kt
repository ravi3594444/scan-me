package com.constrivo.drop.core.crypto.qr

import com.constrivo.drop.core.crypto.CryptoException

/** How the scanner reaches the device that shows the code (architecture §6.3 `link.kind`). */
enum class QrLinkKind(
    /** The value on the wire. */
    val wireName: String,
) {
    /** Join the shower's Wi-Fi Direct group (as a peer or a legacy WPA2 client). */
    P2P("p2p"),

    /** Join the shower's local-only hotspot. */
    HOTSPOT("hotspot"),

    /** Both are on the same network; connect to [QrLink.address] directly. */
    LAN("lan"),
    ;

    companion object {
        fun fromWire(name: String): QrLinkKind? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Link details in a one-time QR code (architecture §6.3). They include a Wi-Fi passphrase, which is why a code with
 * link details always expires.
 *
 * @property ssid network name, 1–32 UTF-8 bytes; required for [QrLinkKind.P2P] and [QrLinkKind.HOTSPOT], absent for
 *   [QrLinkKind.LAN].
 * @property passphrase WPA2 passphrase, 8–63 printable ASCII characters; required and absent like [ssid].
 * @property address the shower's IPv4 or IPv6 literal (or host name) on that link, 1–45 characters from
 *   `[0-9A-Za-z.:%-]`.
 * @property port the shower's control port, 1–65535.
 */
class QrLink(
    val kind: QrLinkKind,
    val ssid: String?,
    val passphrase: String?,
    val address: String,
    val port: Int,
) {
    init {
        when (kind) {
            QrLinkKind.P2P, QrLinkKind.HOTSPOT -> {
                requireNotNull(ssid) { "${kind.wireName} link needs an SSID" }
                requireNotNull(passphrase) { "${kind.wireName} link needs a passphrase" }
                require(ssid.encodeToByteArray().size in 1..MAX_SSID_BYTES) { "SSID must be 1–$MAX_SSID_BYTES UTF-8 bytes" }
                require(passphrase.length in MIN_PASSPHRASE..MAX_PASSPHRASE && passphrase.all { it in ' '..'~' }) {
                    "passphrase must be $MIN_PASSPHRASE–$MAX_PASSPHRASE printable ASCII characters"
                }
            }

            QrLinkKind.LAN -> {
                require(ssid == null && passphrase == null) { "a LAN link carries no SSID or passphrase" }
            }
        }
        require(address.length in 1..MAX_ADDRESS && address.all { it.isAddressChar() }) {
            "address must be 1–$MAX_ADDRESS characters of an IP literal or host name"
        }
        require(port in 1..MAX_PORT) { "port must be 1–$MAX_PORT" }
    }

    override fun toString(): String = "QrLink(${kind.wireName}, ssid=$ssid, address=$address, port=$port)"

    companion object {
        const val MAX_SSID_BYTES: Int = 32
        const val MIN_PASSPHRASE: Int = 8
        const val MAX_PASSPHRASE: Int = 63
        const val MAX_ADDRESS: Int = 45
        const val MAX_PORT: Int = 65535

        private fun Char.isAddressChar(): Boolean = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z' || this in ".:%-"
    }
}

/**
 * A verified QR payload (architecture §6.3; F-B5), as returned by [QrPayloadCodec.parse].
 *
 * The signature has been checked under [identityKey], [deviceId] equals `device_id(identity_pk)` and the code has
 * not expired. Scanning such a code verifies the device (no SAS): connect with
 * [com.constrivo.drop.core.crypto.handshake.ExpectedPeer] set to [identityKey], so the handshake fails if anything
 * else answers.
 */
class QrPayload internal constructor(
    deviceId: ByteArray,
    identityKey: ByteArray,
    ephemeralId: ByteArray,
    /** Link details, present only in one-time codes. */
    val link: QrLink?,
    /** Unix time (seconds) after which the code is refused; null for a static device code. */
    val expiresAtEpochSeconds: Long?,
) {
    private val deviceIdBytes = deviceId.copyOf()
    private val identityKeyBytes = identityKey.copyOf()
    private val ephemeralIdBytes = ephemeralId.copyOf()

    /** `device_id` = SHA-256(identity_pk)[0..16]. */
    val deviceId: ByteArray get() = deviceIdBytes.copyOf()

    /** The shower's Ed25519 identity key. */
    val identityKey: ByteArray get() = identityKeyBytes.copyOf()

    /** The shower's beacon ID when the code was made (architecture §5.3), to match it on the radar. */
    val ephemeralId: ByteArray get() = ephemeralIdBytes.copyOf()

    /** True for a static device code (no link, no expiry; F-B6). */
    val isStatic: Boolean get() = link == null && expiresAtEpochSeconds == null

    companion object {
        /** Payload format version. */
        const val VERSION: Int = 1

        /** One-time codes are valid for 5 minutes (F-B5, T-12). */
        const val ONE_TIME_VALIDITY_SECONDS: Long = 300

        /**
         * How far a code's expiry may lie beyond [ONE_TIME_VALIDITY_SECONDS] from the scanner's clock before it is
         * refused as [QrFailure.INVALID_EXPIRY], allowing for clock differences between the two devices.
         */
        const val MAX_CLOCK_SKEW_SECONDS: Long = 120

        const val DEVICE_ID_SIZE: Int = 16
        const val KEY_SIZE: Int = 32
        const val EPHEMERAL_ID_SIZE: Int = 6
        const val SIGNATURE_SIZE: Int = 64

        /** Longest QR text accepted by [QrPayloadCodec.parse]. */
        const val MAX_TEXT_LENGTH: Int = 1024
    }
}

/** Why a scanned code was refused. */
enum class QrFailure {
    /** Not base64url without padding, not canonical CBOR, or a field has the wrong size or range. */
    MALFORMED,

    /** A payload version this build does not understand. */
    UNSUPPORTED_VERSION,

    /** The signature does not verify under the code's identity key. */
    BAD_SIGNATURE,

    /** `id` is not `device_id(identity_pk)`. */
    DEVICE_ID_MISMATCH,

    /** The code's expiry has passed (T-12: "Refused with clear message"). */
    EXPIRED,

    /** The expiry lies further ahead than a one-time code allows. */
    INVALID_EXPIRY,
}

/** A scanned QR code was refused; [reason] picks the message the UI shows. */
class QrPayloadException(
    val reason: QrFailure,
    message: String,
    cause: Throwable? = null,
) : CryptoException("$reason: $message", cause)
