package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.CryptoProvider
import kotlin.jvm.JvmInline

/**
 * The 6-byte rotating device ID carried in the beacon and the mDNS `eph` key (architecture §5.3, F‑J1).
 *
 * Held as the 48-bit big-endian value of the six bytes, so it is cheap to compare and to use as a map key.
 * Byte `i` of [toByteArray] is byte `i` of the HMAC output it was cut from.
 */
@JvmInline
value class EphemeralId(
    val value: Long,
) {
    init {
        require(value in 0..MAX_VALUE) { "an ephemeral id is 48 bits" }
    }

    fun toByteArray(): ByteArray = ByteArray(SIZE).also { Bytes.writeBigEndian(value, it, 0, SIZE) }

    /** 12 lower-case hex digits, as published in the mDNS TXT record. */
    fun toHex(): String = Bytes.hex(value, SIZE * 2)

    override fun toString(): String = "EphemeralId(${toHex()})"

    companion object {
        const val SIZE: Int = 6
        const val MAX_VALUE: Long = 0xFFFF_FFFF_FFFFL

        /** Reads six bytes at [offset]. The caller guarantees `offset + 6 <= bytes.size`. */
        fun fromBytes(
            bytes: ByteArray,
            offset: Int = 0,
        ): EphemeralId {
            require(offset >= 0 && offset + SIZE <= bytes.size) { "need $SIZE bytes at offset $offset" }
            return EphemeralId(Bytes.readBigEndian(bytes, offset, SIZE))
        }

        /**
         * Parses exactly 12 hex digits (either case).
         *
         * @throws DiscoveryFormatException for any other text.
         */
        fun parseHex(text: String): EphemeralId =
            Bytes.parseHex(text, SIZE * 2)?.let(::EphemeralId)
                ?: throw DiscoveryFormatException("ephemeral id must be 12 hex digits")
    }
}

/**
 * Epoch arithmetic and derivation of [EphemeralId]s (architecture §5.3 as changed by spec change S3).
 *
 * - `epoch = floor(unix_seconds / 900)`, so IDs rotate exactly at multiples of 15 minutes of unix time.
 * - `eph_id = HMAC‑SHA256(k_adv, "drop-eph-v1" ‖ u64be(epoch))[0..6]`.
 *
 * `k_adv` is the device's 32-byte advertising secret. S3: the same `k_adv` is shared with every trusted peer inside
 * the encrypted handshake (WP2, `AdvertisingSecret`), so all of them resolve the same rotating ID; strangers see an
 * unlinkable value. Advertisers restart their advertising set at every epoch boundary (N4) so the OS address rotates
 * together with the ID; [millisUntilNextEpoch] tells them when.
 */
object EphemeralIds {
    const val EPOCH_SECONDS: Long = 900
    const val EPOCH_MILLIS: Long = EPOCH_SECONDS * 1000

    /** Size of `k_adv` in bytes. */
    const val ADVERTISING_SECRET_SIZE: Int = 32

    private val DOMAIN = "drop-eph-v1".encodeToByteArray()

    /** The epoch containing [unixMillis] (which must not be before 1970). */
    fun epochAt(unixMillis: Long): Long {
        require(unixMillis >= 0) { "time before the unix epoch: $unixMillis" }
        return unixMillis / EPOCH_MILLIS
    }

    /** First millisecond of [epoch]. */
    fun epochStartMillis(epoch: Long): Long {
        require(epoch in 0..Long.MAX_VALUE / EPOCH_MILLIS) { "epoch out of range: $epoch" }
        return epoch * EPOCH_MILLIS
    }

    /** Milliseconds from [unixMillis] until the next rotation, in `1..900_000`. */
    fun millisUntilNextEpoch(unixMillis: Long): Long = epochStartMillis(epochAt(unixMillis) + 1) - unixMillis

    /** The HMAC input for [epoch]: `"drop-eph-v1" ‖ u64be(epoch)`. */
    internal fun message(epoch: Long): ByteArray = DOMAIN + Bytes.u64BigEndian(epoch)

    /** `eph_id` for [epoch] under [advertisingSecret] (`k_adv`, 32 bytes). */
    fun derive(
        crypto: CryptoProvider,
        advertisingSecret: ByteArray,
        epoch: Long,
    ): EphemeralId {
        require(advertisingSecret.size == ADVERTISING_SECRET_SIZE) {
            "k_adv must be $ADVERTISING_SECRET_SIZE bytes, was ${advertisingSecret.size}"
        }
        require(epoch >= 0) { "epoch must not be negative" }
        val mac = crypto.hmacSha256(advertisingSecret, message(epoch))
        return EphemeralId.fromBytes(mac, 0)
    }

    /** `eph_id` for the epoch containing [unixMillis]. */
    fun at(
        crypto: CryptoProvider,
        advertisingSecret: ByteArray,
        unixMillis: Long,
    ): EphemeralId = derive(crypto, advertisingSecret, epochAt(unixMillis))
}
