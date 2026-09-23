@file:OptIn(ExperimentalSerializationApi::class)

package com.constrivo.drop.core.crypto.cbor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.cbor.Cbor

/**
 * Deterministic CBOR for signed and hashed wire messages (architecture §6.2, §6.3; implementation plan N1, N2).
 *
 * Messages are `@Serializable` classes whose properties carry integer `@CborLabel` keys, declared in increasing
 * label order, with optional fields nullable and defaulting to `null`. With the configuration below the encoder
 * then emits exactly the RFC 8949 §4.2.1 core deterministic encoding: definite lengths, shortest integer
 * arguments, map keys in ascending order, byte arrays as byte strings, and absent optionals left out.
 *
 * [decode] accepts only that encoding: it validates the bytes structurally ([CanonicalCborValidator]), decodes, then
 * re-encodes and requires the result to equal the input byte for byte. So every value has exactly one accepted
 * encoding, and a hash or signature over received bytes means the same thing on both sides. Unknown keys, explicit
 * `null`s for optional fields, duplicate keys and non-minimal integers are all rejected.
 *
 * Other modules may reuse this object for their own wire messages.
 */
object DeterministicCbor {
    /** Nesting limit applied by [decode] unless the caller passes another one. */
    const val DEFAULT_MAX_DEPTH: Int = 8

    /** The kotlinx-serialization format; exposed for callers that need to build their own serializers. */
    val format: Cbor =
        Cbor {
            encodeDefaults = false
            ignoreUnknownKeys = false
            useDefiniteLengthEncoding = true
            preferCborLabelsOverNames = true
            alwaysUseByteString = true
            encodeKeyTags = false
            encodeValueTags = false
            encodeObjectTags = false
            verifyKeyTags = false
            verifyValueTags = false
            verifyObjectTags = false
        }

    /** Encodes [value] deterministically. */
    fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
    ): ByteArray = format.encodeToByteArray(serializer, value)

    /**
     * Decodes [bytes] as [serializer], accepting only the deterministic encoding of the result.
     *
     * @param maxSize inputs longer than this are rejected before parsing.
     * @throws MalformedCborException if the input is too long, not well-formed, not canonical, does not match the
     *   schema, or fails the message's own validation (an `init` block that throws [IllegalArgumentException]).
     */
    fun <T> decode(
        serializer: KSerializer<T>,
        bytes: ByteArray,
        maxSize: Int,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): T {
        if (bytes.size > maxSize) throw MalformedCborException("message is ${bytes.size} bytes, limit $maxSize")
        CanonicalCborValidator(bytes, maxDepth).validate()
        val value =
            try {
                format.decodeFromByteArray(serializer, bytes)
            } catch (e: MalformedCborException) {
                throw e
            } catch (e: Exception) {
                // kotlinx-serialization reports schema errors with several exception types; the message's init
                // blocks throw IllegalArgumentException. All of them mean "malformed" to the caller.
                throw MalformedCborException("message does not match its schema: ${e.message}", e)
            }
        val canonical =
            try {
                encode(serializer, value)
            } catch (e: Exception) {
                throw MalformedCborException("decoded message cannot be re-encoded: ${e.message}", e)
            }
        if (!canonical.contentEquals(bytes)) throw MalformedCborException("message is not in deterministic encoding")
        return value
    }

    /**
     * Checks that [bytes] hold exactly one data item in the deterministic subset described on
     * [CanonicalCborValidator], without decoding it against a schema.
     *
     * @throws MalformedCborException if they do not.
     */
    fun checkWellFormed(
        bytes: ByteArray,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ) {
        CanonicalCborValidator(bytes, maxDepth).validate()
    }
}

/** Input is not a well-formed, canonical encoding of the expected message ([DeterministicCbor.decode]). */
class MalformedCborException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
