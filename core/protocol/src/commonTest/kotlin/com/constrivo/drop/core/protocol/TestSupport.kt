package com.constrivo.drop.core.protocol

import kotlin.test.assertFailsWith
import kotlin.test.fail

internal fun String.unhex(): ByteArray = hexToBytesOrThrow()

internal fun ByteArray.hex(): String = toHexString()

internal val TEST_ID: TransferId = TransferId.fromHex("00112233445566778899aabbccddeeff")

internal val OTHER_ID: TransferId = TransferId.fromHex("ffeeddccbbaa99887766554433221100")

/** Asserts that [block] throws exactly a [ProtocolException] (or subclass), never anything else. */
internal inline fun assertProtocolError(
    what: String = "",
    block: () -> Unit,
): ProtocolException =
    try {
        block()
        fail("expected ProtocolException $what")
    } catch (e: ProtocolException) {
        e
    } catch (e: AssertionError) {
        throw e
    } catch (e: Throwable) {
        fail("expected ProtocolException $what, got ${e::class.simpleName}: ${e.message}")
    }

internal inline fun assertRejectsArgument(block: () -> Unit) {
    assertFailsWith<IllegalArgumentException> { block() }
}

/** Builds CBOR by hand for negative tests. */
internal object RawCbor {
    fun head(
        major: Int,
        value: Long,
    ): ByteArray {
        val m = major shl 5
        return when {
            value < 24 -> {
                byteArrayOf((m or value.toInt()).toByte())
            }

            value < 0x100 -> {
                byteArrayOf((m or 24).toByte(), value.toByte())
            }

            value < 0x10000 -> {
                byteArrayOf((m or 25).toByte(), (value shr 8).toByte(), value.toByte())
            }

            value < 0x1_0000_0000 -> {
                byteArrayOf(
                    (m or 26).toByte(),
                    (value shr 24).toByte(),
                    (value shr 16).toByte(),
                    (value shr 8).toByte(),
                    value.toByte(),
                )
            }

            else -> {
                byteArrayOf((m or 27).toByte()) + ByteArray(8) { (value shr (56 - 8 * it)).toByte() }
            }
        }
    }

    fun uint(value: Long): ByteArray = head(0, value)

    fun text(value: String): ByteArray = value.encodeToByteArray().let { head(3, it.size.toLong()) + it }

    fun bytes(value: ByteArray): ByteArray = head(2, value.size.toLong()) + value

    fun array(vararg items: ByteArray): ByteArray = items.fold(head(4, items.size.toLong())) { acc, b -> acc + b }

    fun map(vararg entries: Pair<ByteArray, ByteArray>): ByteArray =
        entries.fold(head(5, entries.size.toLong())) { acc, (k, v) ->
            acc + k +
                v
        }

    fun envelope(
        type: Int,
        body: ByteArray,
    ): ByteArray = array(uint(type.toLong()), body)

    val TRUE: ByteArray = byteArrayOf(0xF5.toByte())
}
