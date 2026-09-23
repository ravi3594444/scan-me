package com.constrivo.drop.core.protocol

/** The link kinds of the transport ladder (architecture §4). [wireName] is the value used in CBOR and the database. */
enum class LinkKind(
    val wireName: String,
) {
    LAN("lan"),
    P2P("p2p"),
    HOTSPOT("hotspot"),
    BLUETOOTH("bluetooth"),
    ;

    companion object {
        fun fromWire(name: String): LinkKind? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * A reliable, ordered byte stream to the peer: a TCP socket on LAN / Wi-Fi Direct / hotspot, an RFCOMM socket,
 * or a GATT characteristic pair adapted to a stream. Framing (architecture §7.1) is layered on top.
 *
 * Platform modules implement it; the engine never touches radios directly.
 */
interface DataChannel {
    val kind: LinkKind

    /** Reads up to [length] bytes into [buffer]; returns the count, or -1 at end of stream. Suspends until data arrives. */
    suspend fun read(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    ): Int

    /** Writes all [length] bytes, suspending on backpressure. */
    suspend fun write(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    )

    suspend fun flush()

    /** Closes both directions. Idempotent. */
    suspend fun close()
}
