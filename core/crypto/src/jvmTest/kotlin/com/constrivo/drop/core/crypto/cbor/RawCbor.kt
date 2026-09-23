package com.constrivo.drop.core.crypto.cbor

import java.io.ByteArrayOutputStream

/**
 * A tiny independent CBOR writer for tests: builds maps with integer keys in the order given, so tests can craft
 * messages the production encoder would never emit (wrong sizes, unknown keys, explicit nulls, bad order).
 * Values: Int/Long (unsigned or negative), ByteArray, String, Boolean, null, List, and [RawCbor.Map].
 */
object RawCbor {
    class Map(
        val entries: List<Pair<Long, Any?>>,
    )

    fun map(vararg entries: Pair<Int, Any?>): Map = Map(entries.map { (k, v) -> k.toLong() to v })

    fun encode(value: Any?): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(
        out: ByteArrayOutputStream,
        value: Any?,
    ) {
        when (value) {
            null -> {
                out.write(0xF6)
            }

            is Boolean -> {
                out.write(if (value) 0xF5 else 0xF4)
            }

            is Int -> {
                write(out, value.toLong())
            }

            is Long -> {
                if (value >= 0) head(out, 0, value) else head(out, 1, -1 - value)
            }

            is ByteArray -> {
                head(out, 2, value.size.toLong())
                out.write(value)
            }

            is String -> {
                val bytes = value.encodeToByteArray()
                head(out, 3, bytes.size.toLong())
                out.write(bytes)
            }

            is List<*> -> {
                head(out, 4, value.size.toLong())
                value.forEach { write(out, it) }
            }

            is Map -> {
                head(out, 5, value.entries.size.toLong())
                value.entries.forEach { (k, v) ->
                    write(out, k)
                    write(out, v)
                }
            }

            else -> {
                error("unsupported ${value::class}")
            }
        }
    }

    private fun head(
        out: ByteArrayOutputStream,
        major: Int,
        argument: Long,
    ) {
        val m = major shl 5
        when {
            argument < 24 -> {
                out.write(m or argument.toInt())
            }

            argument < 0x100 -> {
                out.write(m or 24)
                out.write(argument.toInt())
            }

            argument < 0x10000 -> {
                out.write(m or 25)
                for (shift in intArrayOf(8, 0)) out.write((argument ushr shift).toInt() and 0xFF)
            }

            argument < 0x1_0000_0000L -> {
                out.write(m or 26)
                for (shift in intArrayOf(24, 16, 8, 0)) out.write((argument ushr shift).toInt() and 0xFF)
            }

            else -> {
                out.write(m or 27)
                for (shift in 56 downTo 0 step 8) out.write((argument ushr shift).toInt() and 0xFF)
            }
        }
    }
}
