package com.constrivo.drop.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The single encode/decode entry point for control messages (architecture §7.2).
 *
 * **Envelope.** A control payload (the plaintext of a `Control` or `StreamOpen` frame) is the CBOR array
 * `[type, body]`: `type` is the [ControlMessageType.code] as an unsigned integer, `body` is a map from integer
 * labels to fields, as declared by each message class.
 *
 * **Deterministic encoding** (RFC 8949 §4.2.1): definite lengths, shortest-form heads, map keys in bytewise order of
 * their encodings (classes declare fields in ascending label order; string-keyed maps are sorted by the serializer),
 * byte strings for byte fields, and no tags. A field equal to its declared default (null, empty list or map, or
 * `MissingChunks.firstBlockOffset = 0`) is omitted; `Offer.version`, `chunk_size` and `bundle_small` are always
 * written. The encoder checks its own output against these rules.
 *
 * **Forward compatibility.** Labels are never reused or renumbered.
 * - A decoder ignores body keys it does not know, whatever their (well-formed) value, so a newer peer may add
 *   optional fields without a version bump.
 * - An unknown envelope `type` raises [UnknownControlMessageException]; the receiver ignores that message.
 * - Unknown enum wire names decode as the documented fallback (`other` for reasons); link kinds and hint codes stay
 *   strings so unknown ones survive and are skipped.
 * - A change an older peer must not ignore bumps [ProtocolConstants.PROTOCOL_VERSION] in `Offer.version`.
 *
 * **Strict decoding.** Before `kotlinx-serialization` sees the bytes, [CborProfile] rejects indefinite lengths,
 * tags, non-shortest heads, invalid UTF-8, duplicate keys, deep nesting and trailing bytes; missing required fields,
 * wrong types and values that break a message's invariants follow. Every failure is a [ProtocolException].
 * Messages are at most [ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES] encoded.
 */
object ControlCodec {
    private const val ENVELOPE_HEAD: Int = 0x82 // CBOR array of two items
    private const val MAP_MAJOR: Int = 5

    /**
     * Encodes [message] as its envelope.
     * Throws [ProtocolException] if the encoding exceeds [ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES].
     */
    fun encode(message: ControlMessage): ByteArray {
        val body = encodeBody(message)
        val code = message.type.code
        val typeHead = if (code < 24) byteArrayOf(code.toByte()) else byteArrayOf(0x18, code.toByte())
        val out = ByteArray(1 + typeHead.size + body.size)
        out[0] = ENVELOPE_HEAD.toByte()
        typeHead.copyInto(out, 1)
        body.copyInto(out, 1 + typeHead.size)
        if (out.size > ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES) {
            throw ProtocolException("${message.type} encodes to ${out.size} bytes, above ${ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES}")
        }
        try {
            CborProfile.validate(out, canonical = true)
        } catch (e: ProtocolException) {
            throw IllegalStateException("encoder produced non-deterministic CBOR for ${message.type}: ${e.message}", e)
        }
        return out
    }

    /** Decodes one envelope. Throws [ProtocolException] (or [UnknownControlMessageException]) for bad input. */
    fun decode(bytes: ByteArray): ControlMessage = decode(bytes, 0, bytes.size)

    fun decode(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): ControlMessage {
        checkRange(bytes.size, offset, length, "control message")
        if (length > ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES) {
            throw ProtocolException("control message of $length bytes exceeds ${ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES}")
        }
        val end = offset + length
        CborProfile.validate(bytes, offset, end)
        if ((bytes[offset].toInt() and 0xFF) != ENVELOPE_HEAD) throw ProtocolException("control envelope must be a two-element array")
        // The profile check guarantees a well-formed, shortest-form item follows.
        val typeByte = bytes[offset + 1].toInt() and 0xFF
        val code: Int
        val bodyStart: Int
        when {
            typeByte < 24 -> {
                code = typeByte
                bodyStart = offset + 2
            }

            typeByte == 24 -> {
                code = bytes[offset + 2].toInt() and 0xFF
                bodyStart = offset + 3
            }

            else -> {
                throw ProtocolException("control message type must be a small unsigned integer")
            }
        }
        val type = ControlMessageType.fromCode(code) ?: throw UnknownControlMessageException(code)
        if ((bytes[bodyStart].toInt() and 0xFF) ushr 5 != MAP_MAJOR) throw ProtocolException("$type body must be a map")
        val body = bytes.copyOfRange(bodyStart, end)
        return try {
            decodeBody(type, body)
        } catch (e: ProtocolException) {
            throw e
        } catch (e: Exception) {
            // SerializationException (missing or mistyped field), IllegalArgumentException (an invariant), and any
            // other decoder failure: all mean the peer sent a malformed message.
            throw ProtocolException("malformed $type: ${e.message}", e)
        }
    }

    /** Size of [message]'s envelope in bytes. */
    fun encodedSize(message: ControlMessage): Int = encode(message).size

    private fun encodeBody(message: ControlMessage): ByteArray =
        when (message) {
            is Offer -> ProtocolCbor.encodeToByteArray(Offer.serializer(), message)
            is FileList -> ProtocolCbor.encodeToByteArray(FileList.serializer(), message)
            is Accept -> ProtocolCbor.encodeToByteArray(Accept.serializer(), message)
            is Decline -> ProtocolCbor.encodeToByteArray(Decline.serializer(), message)
            is Ack -> ProtocolCbor.encodeToByteArray(Ack.serializer(), message)
            is Resume -> ProtocolCbor.encodeToByteArray(Resume.serializer(), message)
            is Hint -> ProtocolCbor.encodeToByteArray(Hint.serializer(), message)
            is LinkReady -> ProtocolCbor.encodeToByteArray(LinkReady.serializer(), message)
            is Heartbeat -> ProtocolCbor.encodeToByteArray(Heartbeat.serializer(), message)
            is FileDone -> ProtocolCbor.encodeToByteArray(FileDone.serializer(), message)
            is Complete -> ProtocolCbor.encodeToByteArray(Complete.serializer(), message)
            is Cancel -> ProtocolCbor.encodeToByteArray(Cancel.serializer(), message)
            is ControlMoved -> ProtocolCbor.encodeToByteArray(ControlMoved.serializer(), message)
            is TrustShare -> ProtocolCbor.encodeToByteArray(TrustShare.serializer(), message)
            is StreamOpen -> ProtocolCbor.encodeToByteArray(StreamOpen.serializer(), message)
            is Retransmit -> ProtocolCbor.encodeToByteArray(Retransmit.serializer(), message)
        }

    private fun decodeBody(
        type: ControlMessageType,
        body: ByteArray,
    ): ControlMessage =
        when (type) {
            ControlMessageType.OFFER -> ProtocolCbor.decodeFromByteArray(Offer.serializer(), body)
            ControlMessageType.FILE_LIST -> ProtocolCbor.decodeFromByteArray(FileList.serializer(), body)
            ControlMessageType.ACCEPT -> ProtocolCbor.decodeFromByteArray(Accept.serializer(), body)
            ControlMessageType.DECLINE -> ProtocolCbor.decodeFromByteArray(Decline.serializer(), body)
            ControlMessageType.ACK -> ProtocolCbor.decodeFromByteArray(Ack.serializer(), body)
            ControlMessageType.RESUME -> ProtocolCbor.decodeFromByteArray(Resume.serializer(), body)
            ControlMessageType.HINT -> ProtocolCbor.decodeFromByteArray(Hint.serializer(), body)
            ControlMessageType.LINK_READY -> ProtocolCbor.decodeFromByteArray(LinkReady.serializer(), body)
            ControlMessageType.HEARTBEAT -> ProtocolCbor.decodeFromByteArray(Heartbeat.serializer(), body)
            ControlMessageType.FILE_DONE -> ProtocolCbor.decodeFromByteArray(FileDone.serializer(), body)
            ControlMessageType.COMPLETE -> ProtocolCbor.decodeFromByteArray(Complete.serializer(), body)
            ControlMessageType.CANCEL -> ProtocolCbor.decodeFromByteArray(Cancel.serializer(), body)
            ControlMessageType.CONTROL_MOVED -> ProtocolCbor.decodeFromByteArray(ControlMoved.serializer(), body)
            ControlMessageType.TRUST_SHARE -> ProtocolCbor.decodeFromByteArray(TrustShare.serializer(), body)
            ControlMessageType.STREAM_OPEN -> ProtocolCbor.decodeFromByteArray(StreamOpen.serializer(), body)
            ControlMessageType.RETRANSMIT -> ProtocolCbor.decodeFromByteArray(Retransmit.serializer(), body)
        }
}

/** The CBOR configuration of the deterministic profile described on [ControlCodec]. */
internal val ProtocolCbor: Cbor =
    Cbor {
        encodeDefaults = false
        ignoreUnknownKeys = true
        preferCborLabelsOverNames = true
        useDefiniteLengthEncoding = true
        alwaysUseByteString = true
    }

/** Orders [map] by the bytewise order of the CBOR encodings of its keys: shorter UTF-8 first, then bytewise. */
internal fun <V> canonicalOrder(map: Map<String, V>): Map<String, V> {
    if (map.size < 2) return map
    val encoded = map.keys.associateWith { it.encodeToByteArray() }
    val keys =
        map.keys.sortedWith { a, b ->
            val x = encoded.getValue(a)
            val y = encoded.getValue(b)
            if (x.size != y.size) {
                x.size - y.size
            } else {
                var result = 0
                for (i in x.indices) {
                    val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
                    if (d != 0) {
                        result = d
                        break
                    }
                }
                result
            }
        }
    val out = LinkedHashMap<String, V>(map.size)
    for (key in keys) out[key] = map.getValue(key)
    return out
}

/** A string-keyed map written in canonical key order (deterministic encoding). */
internal abstract class CanonicalStringMapSerializer<V>(
    valueSerializer: KSerializer<V>,
) : KSerializer<Map<String, V>> {
    private val delegate = MapSerializer(String.serializer(), valueSerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<String, V>,
    ) = delegate.serialize(encoder, canonicalOrder(value))

    override fun deserialize(decoder: Decoder): Map<String, V> = delegate.deserialize(decoder)
}

internal object MimeHistogramSerializer : CanonicalStringMapSerializer<Int>(Int.serializer())

internal object HintParamsSerializer : CanonicalStringMapSerializer<String>(String.serializer())
