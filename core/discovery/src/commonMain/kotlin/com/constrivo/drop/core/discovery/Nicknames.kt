package com.constrivo.drop.core.discovery

/**
 * Nickname rules shared by the scan response (architecture §5.1) and the mDNS `nick` key (§5.4).
 *
 * A nickname is display text chosen by another device, so everything received is passed through [sanitize] before it
 * reaches the radar: control characters, bidirectional overrides and invisible separators are removed (they can
 * spoof or garble a label), lone UTF-16 surrogates become U+FFFD, and surrounding whitespace is trimmed.
 * Lengths are counted in UTF-8 bytes and cut only at code point boundaries.
 */
object Nicknames {
    /** Longest nickname carried anywhere in discovery, in UTF-8 bytes (the handshake `Hello` uses the same limit). */
    const val MAX_BYTES: Int = 64

    /** Returns [raw] without control, bidi-override and invisible separator characters, trimmed; may be empty. */
    fun sanitize(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()) {
                out.append(c).append(raw[i + 1])
                i += 2
                continue
            }
            when {
                c.isSurrogate() -> out.append(REPLACEMENT)
                isStripped(c) -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString().trim()
    }

    /** UTF-8 length of [text]; a lone surrogate counts as the 3 bytes of U+FFFD. */
    fun utf8Length(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val step = codePointStep(text, i)
            n += step.utf8Bytes
            i += step.chars
        }
        return n
    }

    /**
     * The longest prefix of [text] whose UTF-8 encoding fits in [maxBytes], cut at a code point boundary (a surrogate
     * pair is never split). Returns [text] itself when it already fits.
     */
    fun truncateUtf8(
        text: String,
        maxBytes: Int,
    ): String {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val step = codePointStep(text, i)
            if (bytes + step.utf8Bytes > maxBytes) return text.substring(0, i)
            bytes += step.utf8Bytes
            i += step.chars
        }
        return text
    }

    /**
     * Sanitises [raw] and truncates it to [maxBytes]; returns null when nothing printable is left.
     * The flag of the result says whether truncation removed anything.
     */
    fun normalize(
        raw: String,
        maxBytes: Int = MAX_BYTES,
    ): Normalized? {
        val clean = sanitize(raw)
        val cut = truncateUtf8(clean, maxBytes).trimEnd()
        if (cut.isEmpty()) return null
        return Normalized(cut, cut.length != clean.length)
    }

    /** Decodes received UTF-8, replacing malformed sequences with U+FFFD (never throws), then [normalize]s it. */
    fun decodeReceived(
        bytes: ByteArray,
        maxBytes: Int = MAX_BYTES,
    ): Normalized? = normalize(bytes.decodeToString(), maxBytes)

    /** A sanitised nickname and whether it was shortened to fit. */
    data class Normalized(
        val text: String,
        val truncated: Boolean,
    )

    private const val REPLACEMENT = '\uFFFD'

    private class Step(
        val chars: Int,
        val utf8Bytes: Int,
    )

    private val ONE = Step(1, 1)
    private val TWO = Step(1, 2)
    private val THREE = Step(1, 3)
    private val PAIR = Step(2, 4)

    private fun codePointStep(
        text: String,
        i: Int,
    ): Step {
        val c = text[i]
        return when {
            c.code < 0x80 -> ONE
            c.code < 0x800 -> TWO
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> PAIR
            else -> THREE
        }
    }

    private fun isStripped(c: Char): Boolean =
        c.isISOControl() ||
            c == '\u061C' ||
            c == '\u200B' ||
            c == '\u200E' ||
            c == '\u200F' ||
            c in '\u202A'..'\u202E' ||
            c in '\u2066'..'\u2069' ||
            c == '\u2028' ||
            c == '\u2029' ||
            c == '\uFEFF'
}
