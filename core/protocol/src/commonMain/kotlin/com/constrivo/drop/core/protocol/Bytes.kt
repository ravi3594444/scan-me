package com.constrivo.drop.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.random.Random

/**
 * Immutable bytes with content equality, the base of the byte-valued protocol fields (architecture §7.2, §7.3).
 *
 * The constructor copies its argument and [toByteArray] returns a copy, so instances can be shared freely and used
 * as map keys. Control messages carry these instead of raw `ByteArray`s so that data-class equality compares
 * content (golden and round-trip tests rely on it). In CBOR every subclass is a byte string (major type 2).
 */
abstract class ImmutableBytes internal constructor(
    bytes: ByteArray,
) {
    internal val bytes: ByteArray = bytes.copyOf()

    val size: Int get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    fun toHex(): String = bytes.toHexString()

    internal fun copyInto(
        destination: ByteArray,
        offset: Int,
    ) {
        bytes.copyInto(destination, offset)
    }

    final override fun equals(other: Any?): Boolean =
        other is ImmutableBytes && other::class == this::class && other.bytes.contentEquals(bytes)

    final override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "${this::class.simpleName}(${toHex()})"
}

/** `transfer_id` (§7.2): 16 random bytes naming one transfer on both devices. */
@Serializable(with = TransferIdSerializer::class)
class TransferId(
    bytes: ByteArray,
) : ImmutableBytes(bytes) {
    init {
        require(bytes.size == SIZE) { "transfer_id must be $SIZE bytes, got ${bytes.size}" }
    }

    companion object {
        const val SIZE: Int = 16

        /** A fresh id from [random]; production code passes a cryptographically secure source. */
        fun random(random: Random): TransferId = TransferId(random.nextBytes(SIZE))

        fun fromHex(hex: String): TransferId = TransferId(hex.hexToBytesOrThrow())
    }
}

/** Per-chunk XXH3-128 of a `Chunk` frame's plaintext payload (§7.3, decision 6); computed by `core/transfer`. */
class ChunkHash(
    bytes: ByteArray,
) : ImmutableBytes(bytes) {
    init {
        require(bytes.size == SIZE) { "chunk hash must be $SIZE bytes, got ${bytes.size}" }
    }

    companion object {
        const val SIZE: Int = 16

        fun fromHex(hex: String): ChunkHash = ChunkHash(hex.hexToBytesOrThrow())
    }
}

/** Whole-file SHA-256 carried by `FileDone` (spec change S2). */
@Serializable(with = Sha256DigestSerializer::class)
class Sha256Digest(
    bytes: ByteArray,
) : ImmutableBytes(bytes) {
    init {
        require(bytes.size == SIZE) { "SHA-256 digest must be $SIZE bytes, got ${bytes.size}" }
    }

    companion object {
        const val SIZE: Int = 32

        fun fromHex(hex: String): Sha256Digest = Sha256Digest(hex.hexToBytesOrThrow())
    }
}

/**
 * The per-device advertising secret `k_adv` that `TrustShare` hands to a trusted peer (spec change S3).
 * [toString] never prints the value.
 */
@Serializable(with = AdvertisingSecretSerializer::class)
class AdvertisingSecret(
    bytes: ByteArray,
) : ImmutableBytes(bytes) {
    init {
        require(bytes.size == SIZE) { "advertising secret must be $SIZE bytes, got ${bytes.size}" }
    }

    override fun toString(): String = "AdvertisingSecret(<redacted>)"

    companion object {
        const val SIZE: Int = ProtocolConstants.ADVERTISING_SECRET_SIZE
    }
}

/** A variable-length byte string, for example an `Offer` preview image. */
@Serializable(with = BytesSerializer::class)
class Bytes(
    bytes: ByteArray,
) : ImmutableBytes(bytes) {
    override fun toString(): String = "Bytes(${bytes.size} B)"

    companion object {
        val EMPTY: Bytes = Bytes(ByteArray(0))

        fun fromHex(hex: String): Bytes = Bytes(hex.hexToBytesOrThrow())
    }
}

/** CBOR byte-string serializer for an [ImmutableBytes] subtype; decoding checks the length before construction. */
internal open class ImmutableBytesSerializer<T : ImmutableBytes>(
    serialName: String,
    private val requiredSize: Int?,
    private val create: (ByteArray) -> T,
) : KSerializer<T> {
    private val delegate = ByteArraySerializer()

    override val descriptor: SerialDescriptor = SerialDescriptor(serialName, delegate.descriptor)

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        encoder.encodeSerializableValue(delegate, value.bytes)
    }

    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeSerializableValue(delegate)
        if (requiredSize != null && raw.size != requiredSize) {
            throw SerializationException("${descriptor.serialName} must be $requiredSize bytes, got ${raw.size}")
        }
        return create(raw)
    }
}

internal object TransferIdSerializer :
    ImmutableBytesSerializer<TransferId>("drop.TransferId", TransferId.SIZE, ::TransferId)

internal object Sha256DigestSerializer :
    ImmutableBytesSerializer<Sha256Digest>("drop.Sha256Digest", Sha256Digest.SIZE, ::Sha256Digest)

internal object AdvertisingSecretSerializer :
    ImmutableBytesSerializer<AdvertisingSecret>("drop.AdvertisingSecret", AdvertisingSecret.SIZE, ::AdvertisingSecret)

internal object BytesSerializer : ImmutableBytesSerializer<Bytes>("drop.Bytes", null, ::Bytes)

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Lower-case hex, two digits per byte. */
internal fun ByteArray.toHexString(
    offset: Int = 0,
    length: Int = size - offset,
): String {
    val out = CharArray(length * 2)
    for (i in 0 until length) {
        val v = this[offset + i].toInt() and 0xFF
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
    }
    return out.concatToString()
}

/** Parses hex (either case, no separators); throws [IllegalArgumentException] on odd length or a non-hex digit. */
internal fun String.hexToBytesOrThrow(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(length / 2) { i ->
        val hi = this[i * 2].digitToIntOrNull(16)
        val lo = this[i * 2 + 1].digitToIntOrNull(16)
        require(hi != null && lo != null) { "not a hex digit near index ${i * 2}" }
        ((hi shl 4) or lo).toByte()
    }
}
