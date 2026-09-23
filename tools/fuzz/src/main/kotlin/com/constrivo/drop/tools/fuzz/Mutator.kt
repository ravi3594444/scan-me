package com.constrivo.drop.tools.fuzz

import com.constrivo.drop.core.protocol.ProtocolConstants
import kotlin.random.Random

/**
 * Structure-unaware byte mutations plus a few protocol-aware ones (u32 lengths and CBOR heads that declare huge
 * sizes), in the style of AFL/libFuzzer. Everything derives from the [Random] passed in, so a run is reproducible
 * from its seed.
 */
class Mutator(
    private val random: Random,
    private val maxSize: Int,
) {
    init {
        require(maxSize >= 1) { "maxSize must be positive" }
    }

    /** Applies 1..[maxRounds] random mutations to a copy of [input]; [other] is a second corpus entry for splicing. */
    fun mutate(
        input: ByteArray,
        other: ByteArray,
        maxRounds: Int = 4,
    ): ByteArray {
        var data = input
        repeat(1 + random.nextInt(maxRounds)) { data = mutateOnce(data, other) }
        return if (data.size > maxSize) data.copyOf(maxSize) else data
    }

    /** Fresh random bytes, mostly short, sometimes long. */
    fun randomInput(): ByteArray {
        val size = if (random.nextInt(8) == 0) random.nextInt(0, minOf(maxSize, 4096) + 1) else random.nextInt(0, minOf(maxSize, 64) + 1)
        return random.nextBytes(size)
    }

    private fun mutateOnce(
        data: ByteArray,
        other: ByteArray,
    ): ByteArray {
        if (data.isEmpty()) return random.nextBytes(1 + random.nextInt(16))
        return when (random.nextInt(12)) {
            0 -> flipBit(data)
            1 -> setByte(data, random.nextInt(256))
            2 -> setByte(data, INTERESTING_BYTES[random.nextInt(INTERESTING_BYTES.size)])
            3 -> setU32(data, INTERESTING_U32[random.nextInt(INTERESTING_U32.size)])
            4 -> insert(data, random.nextBytes(1 + random.nextInt(8)))
            5 -> delete(data)
            6 -> duplicate(data)
            7 -> data.copyOf(random.nextInt(data.size))
            8 -> splice(data, other)
            9 -> insert(data, CBOR_HEADS[random.nextInt(CBOR_HEADS.size)])
            10 -> addToByte(data)
            else -> swap(data)
        }
    }

    private fun flipBit(data: ByteArray): ByteArray =
        data.copyOf().also {
            val i = random.nextInt(it.size)
            it[i] = (it[i].toInt() xor (1 shl random.nextInt(8))).toByte()
        }

    private fun setByte(
        data: ByteArray,
        value: Int,
    ): ByteArray = data.copyOf().also { it[random.nextInt(it.size)] = value.toByte() }

    private fun addToByte(data: ByteArray): ByteArray =
        data.copyOf().also {
            val i = random.nextInt(it.size)
            it[i] = (it[i] + random.nextInt(-8, 9)).toByte()
        }

    private fun setU32(
        data: ByteArray,
        value: Long,
    ): ByteArray {
        if (data.size < 4) return setByte(data, value.toInt())
        val out = data.copyOf()
        val at = random.nextInt(out.size - 3)
        for (k in 0 until 4) out[at + k] = (value ushr (24 - 8 * k)).toByte()
        return out
    }

    private fun insert(
        data: ByteArray,
        bytes: ByteArray,
    ): ByteArray {
        val at = random.nextInt(data.size + 1)
        return data.copyOfRange(0, at) + bytes + data.copyOfRange(at, data.size)
    }

    private fun delete(data: ByteArray): ByteArray {
        val from = random.nextInt(data.size)
        val to = minOf(data.size, from + 1 + random.nextInt(16))
        return data.copyOfRange(0, from) + data.copyOfRange(to, data.size)
    }

    private fun duplicate(data: ByteArray): ByteArray {
        val from = random.nextInt(data.size)
        val to = minOf(data.size, from + 1 + random.nextInt(32))
        return insert(data, data.copyOfRange(from, to))
    }

    private fun splice(
        data: ByteArray,
        other: ByteArray,
    ): ByteArray {
        if (other.isEmpty()) return data
        val cut = random.nextInt(data.size + 1)
        val from = random.nextInt(other.size)
        return data.copyOfRange(0, cut) + other.copyOfRange(from, other.size)
    }

    private fun swap(data: ByteArray): ByteArray =
        data.copyOf().also {
            val i = random.nextInt(it.size)
            val j = random.nextInt(it.size)
            val t = it[i]
            it[i] = it[j]
            it[j] = t
        }

    private companion object {
        val INTERESTING_BYTES =
            intArrayOf(
                0x00,
                0x01,
                0x7F,
                0x80,
                0xFF,
                0x17,
                0x18,
                0x19,
                0x1A,
                0x1B,
                0x1F, // CBOR integer heads
                0x40,
                0x5F,
                0x60,
                0x7F,
                0x80,
                0x9F,
                0xA0,
                0xBF,
                0xC0,
                0xF4,
                0xF5,
                0xF6,
                0xF7,
                0xF9,
                0xFF, // other CBOR heads
                0x10,
                0x11,
                0x12, // frame types
            )

        val INTERESTING_U32 =
            longArrayOf(
                0,
                1,
                47,
                48,
                49,
                0x7F,
                0x80,
                0xFF,
                0x100,
                0x7FFF,
                0x8000,
                0xFFFF,
                0x1_0000,
                ProtocolConstants.CHUNK_SIZE - 1L,
                ProtocolConstants.CHUNK_SIZE.toLong(),
                ProtocolConstants.CHUNK_SIZE + 1L,
                ProtocolConstants.MAX_FRAME_PAYLOAD.toLong(),
                ProtocolConstants.MAX_FRAME_PAYLOAD + 1L,
                ProtocolConstants.MAX_FILES_PER_TRANSFER.toLong(),
                0x7FFF_FFFF,
                0x8000_0000,
                0xFFFF_FFFE,
                0xFFFF_FFFF,
            )

        /** CBOR heads declaring very large strings, arrays and maps, and the 64-bit edge of integers. */
        val CBOR_HEADS =
            listOf(
                byteArrayOf(0x5A, 0x7F, -1, -1, -1),
                byteArrayOf(0x5B, -1, -1, -1, -1, -1, -1, -1, -1),
                byteArrayOf(0x7A, 0x00, 0x10, 0x00, 0x00),
                byteArrayOf(0x9A.toByte(), 0x7F, -1, -1, -1),
                byteArrayOf(0x9B.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00),
                byteArrayOf(0xBA.toByte(), 0x40, 0x00, 0x00, 0x00),
                byteArrayOf(0xBB.toByte(), 0x7F, -1, -1, -1, -1, -1, -1, -1),
                byteArrayOf(0x1B, -1, -1, -1, -1, -1, -1, -1, -1),
                byteArrayOf(0x3B, 0x7F, -1, -1, -1, -1, -1, -1, -1),
                byteArrayOf(0x9F.toByte(), 0x9F.toByte(), 0x9F.toByte()),
                byteArrayOf(0xC1.toByte(), 0x1A, 0x5F, 0x5E, 0x10, 0x00),
                ByteArray(64) { 0x81.toByte() },
            )
    }
}
