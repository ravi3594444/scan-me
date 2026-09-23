package com.constrivo.drop.core.protocol

/**
 * The only exception the protocol decoders throw for bad input (architecture §7.1–7.3).
 *
 * Every decoder in this module ([FrameCodec], [FrameReader], [ControlCodec], [ChunkHeader], [BundleIndex], the
 * `FileList` assembler and the plan validators) turns malformed, truncated, oversized or out-of-range input into a
 * [ProtocolException] (or one of its subclasses), never an `IndexOutOfBoundsException`, a serialization exception
 * or an allocation beyond the limits in [ProtocolConstants]. `tools/fuzz` checks this property.
 *
 * The engine answers a [ProtocolException] on an authenticated stream with `Cancel(reason = protocol)`.
 */
open class ProtocolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The stream ended inside a frame: the peer closed (or the link dropped) after sending part of a header or payload.
 * A clean end of stream at a frame boundary is not an error; [FrameReader.readFrame] returns `null` instead.
 */
class TruncatedFrameException(
    message: String,
) : ProtocolException(message)

/**
 * A well-formed control envelope with a message type this version does not know.
 *
 * Forward-compatibility rule (§7.2): after the frame authenticated, a receiver **ignores** control messages of an
 * unknown [code] rather than tearing the session down, so a newer peer can add message types without a version bump.
 */
class UnknownControlMessageException(
    val code: Int,
) : ProtocolException("unknown control message type $code")
