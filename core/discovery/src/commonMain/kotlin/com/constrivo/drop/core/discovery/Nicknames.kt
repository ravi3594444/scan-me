package com.constrivo.drop.core.discovery

/**
 * Nickname rules shared by the scan response (architecture §5.1) and the mDNS `nick` key (§5.4).
 *
 * A nickname is display text chosen by another device, so everything received is passed through [sanitize] before it
 * reaches the radar, which removes whatever could make a label blank, invisible or spoofed:
 * - control characters (Cc), line and paragraph separators;
 * - every format character (Cf): bidirectional controls, zero-width space, word joiner, invisible operators, soft
 *   hyphen, BOM, Mongolian vowel separator, interlinear annotation controls and the like;
 * - characters that render blank although they are letters or symbols: the Hangul fillers U+115F, U+1160, U+3164
 *   and U+FFA0, the braille blank U+2800, the Khmer inherent vowels U+17B4–17B5, the combining grapheme joiner
 *   U+034F, the Mongolian free variation selectors, U+FFF0–FFF8 and noncharacters;
 * - ZWJ and ZWNJ unless they stand between two visible characters (emoji sequences and Indic or Persian words keep
 *   them), variation selectors unless they follow a visible character, and tag characters unless they continue an
 *   emoji tag sequence (the flags of England, Scotland and Wales).
 *
 * Lone UTF-16 surrogates become U+FFFD and surrounding whitespace is trimmed. What is left always contains a visible
 * character (a letter, mark, number, punctuation or symbol) or is empty. Lengths are counted in UTF-8 bytes and cut
 * only at code point boundaries.
 */
object Nicknames {
    /** Longest nickname carried anywhere in discovery, in UTF-8 bytes (the handshake `Hello` uses the same limit). */
    const val MAX_BYTES: Int = 64

    /** Returns [raw] without the invisible or spoofing characters listed above, trimmed; may be empty. Idempotent. */
    fun sanitize(raw: String): String {
        // Pass 1: decode code points and drop what is never shown; selectors and tags only after what they modify.
        val kept = ArrayList<Int>(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val cp: Int
            if (c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()) {
                cp = 0x10000 + ((c.code - 0xD800) shl 10) + (raw[i + 1].code - 0xDC00)
                i += 2
            } else {
                cp = if (c.isSurrogate()) REPLACEMENT else c.code
                i++
            }
            val previous = kept.lastOrNull()
            when {
                isStripped(cp) -> Unit
                isVariationSelector(cp) -> if (previous != null && isVisible(previous)) kept += cp
                isTag(cp) -> if (previous == BLACK_FLAG || (previous != null && isTag(previous) && previous != CANCEL_TAG)) kept += cp
                else -> kept += cp
            }
        }
        // Pass 2: joiners only between visible characters (a selector or tag continues the character before it).
        val out = StringBuilder(raw.length)
        var last: Int? = null
        for (k in kept.indices) {
            val cp = kept[k]
            if (isJoiner(cp)) {
                val before = last
                val after = kept.getOrNull(k + 1)
                val joins = before != null && (isVisible(before) || isVariationSelector(before) || isTag(before))
                if (!joins || after == null || !isVisible(after)) continue
            }
            appendCodePoint(out, cp)
            last = cp
        }
        return out.toString().trim()
    }

    /** Whether [text] contains a visible character (a letter, mark, number, punctuation or symbol). */
    fun hasVisibleCharacter(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val cp =
                if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                } else {
                    c.code
                }
            if (!isStripped(cp) && isVisible(cp)) return true
            i += if (cp >= 0x10000) 2 else 1
        }
        return false
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
     * Sanitises [raw] and truncates it to [maxBytes]; returns null when no visible character is left.
     * The cut is sanitised again, so it never ends in a dangling joiner or whitespace. The flag of the result says
     * whether truncation removed anything. `normalize(normalize(x).text)` is `normalize(x)` with the flag cleared.
     */
    fun normalize(
        raw: String,
        maxBytes: Int = MAX_BYTES,
    ): Normalized? {
        val clean = sanitize(raw)
        val cut = sanitize(truncateUtf8(clean, maxBytes))
        if (cut.isEmpty() || !hasVisibleCharacter(cut)) return null
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

    private const val REPLACEMENT = 0xFFFD
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D
    private const val BLACK_FLAG = 0x1F3F4
    private const val CANCEL_TAG = 0xE007F

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

    private fun appendCodePoint(
        out: StringBuilder,
        cp: Int,
    ) {
        if (cp < 0x10000) {
            out.append(cp.toChar())
        } else {
            val v = cp - 0x10000
            out.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }

    private fun isJoiner(cp: Int): Boolean = cp == ZWJ || cp == ZWNJ

    private fun isVariationSelector(cp: Int): Boolean = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

    /** Tag characters U+E0020–E007F: kept only inside an emoji tag sequence. */
    private fun isTag(cp: Int): Boolean = cp in 0xE0020..CANCEL_TAG

    /** Something that draws ink: not whitespace, not a joiner, selector or tag. Stripped code points never get here. */
    private fun isVisible(cp: Int): Boolean =
        !isJoiner(cp) && !isVariationSelector(cp) && !isTag(cp) && !(cp < 0x10000 && cp.toChar().isWhitespace())

    /** Code points removed wherever they appear (see the class documentation). ZWJ and ZWNJ are handled separately. */
    private fun isStripped(cp: Int): Boolean {
        if (isJoiner(cp)) return false
        if (cp < 0x10000) {
            val category = cp.toChar().category
            return category == CharCategory.CONTROL ||
                category == CharCategory.FORMAT ||
                category == CharCategory.LINE_SEPARATOR ||
                category == CharCategory.PARAGRAPH_SEPARATOR ||
                cp == 0x034F ||
                cp == 0x115F ||
                cp == 0x1160 ||
                cp in 0x17B4..0x17B5 ||
                cp in 0x180B..0x180F ||
                cp == 0x2800 ||
                cp == 0x3164 ||
                cp == 0xFFA0 ||
                cp in 0xFDD0..0xFDEF ||
                cp in 0xFFF0..0xFFF8 ||
                cp >= 0xFFFE
        }
        // Supplementary planes: Kotlin common has no category lookup, so the format characters are listed.
        return (cp and 0xFFFE) == 0xFFFE ||
            cp == 0x110BD ||
            cp == 0x110CD ||
            cp in 0x13430..0x1343F ||
            cp in 0x1BCA0..0x1BCA3 ||
            cp in 0x1D173..0x1D17A ||
            (cp in 0xE0000..0xE0FFF && !isTag(cp) && !isVariationSelector(cp))
    }
}
