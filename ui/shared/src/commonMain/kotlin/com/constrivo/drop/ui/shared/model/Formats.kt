package com.constrivo.drop.ui.shared.model

import kotlin.math.abs
import kotlin.math.roundToLong

/** Units of a byte count as the UI shows it: decimal, as in every UI speed readout (spec change S6). */
enum class SizeUnit(
    val bytes: Long,
) {
    B(1),
    KB(1_000),
    MB(1_000_000),
    GB(1_000_000_000),
    TB(1_000_000_000_000),
}

/** A number ready for a unit string: [value] is already formatted ("4.2", "48"), [unit] picks the resource. */
data class SizeParts(
    val value: String,
    val unit: SizeUnit,
)

/** A duration split for the "45 s" / "3 min" / "1 h 20 min" strings. */
sealed interface DurationParts {
    data class Seconds(
        val seconds: Int,
    ) : DurationParts

    data class Minutes(
        val minutes: Int,
    ) : DurationParts

    data class HoursMinutes(
        val hours: Int,
        val minutes: Int,
    ) : DurationParts
}

/**
 * Locale-neutral number formatting for the UI (design §4.2, §6). Decimal MB (S6); digits and the decimal point are
 * Latin in both launch languages (decision 9), so no locale lookup is needed here; unit words come from resources.
 */
object Formats {
    /**
     * [bytes] with the largest unit that keeps the value at least 1: one decimal below 10 ("4.2 MB"), none from 10
     * ("48 MB"). A value that rounds up to 1000 moves to the next unit ("999.6 KB" → "1.0 MB").
     */
    fun size(bytes: Long): SizeParts {
        val b = bytes.coerceAtLeast(0)
        if (b < SizeUnit.KB.bytes) return SizeParts(b.toString(), SizeUnit.B)
        val units = SizeUnit.entries.drop(1)
        for ((i, unit) in units.withIndex()) {
            val v = b.toDouble() / unit.bytes
            val text = decimal(v, if (v < 10) 1 else 0)
            val next = units.getOrNull(i + 1)
            if (next == null || text.toDouble() < 1000.0) return SizeParts(text, unit)
        }
        error("unreachable")
    }

    /**
     * Speed in decimal MB/s for "{speed} MB/s" (design §4.2, §8.3): one decimal below 10, none from 10; a positive
     * speed never shows as 0 (at least "0.1").
     */
    fun megabytesPerSecond(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0"
        val mb = bytesPerSecond / 1_000_000.0
        return if (mb < 10) decimal(maxOf(mb, 0.1), 1) else decimal(mb, 0)
    }

    /**
     * A time left or a duration: under a minute in seconds (at least 1), under an hour in whole minutes rounded up, else
     * hours and minutes.
     */
    fun duration(millis: Long): DurationParts {
        val ms = millis.coerceAtLeast(0)
        val seconds = (ms + 999) / 1000
        if (seconds < 60) return DurationParts.Seconds(seconds.toInt().coerceAtLeast(1))
        val minutes = (seconds + 59) / 60
        if (minutes < 60) return DurationParts.Minutes(minutes.toInt())
        val hours = minutes / 60
        return DurationParts.HoursMinutes(hours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), (minutes % 60).toInt())
    }

    /** Hours for the "{hours} h saved vs Bluetooth" tile: one decimal below 10, none from 10. */
    fun hours(hours: Double): String {
        val h = if (hours.isNaN() || hours < 0) 0.0 else hours
        return if (h < 10) decimal(h, 1) else decimal(h, 0)
    }

    /** The six-digit SAS (F‑B3) in two groups of three joined by a narrow no-break space, "042 917", easy to compare aloud. */
    fun sas(code: String): String {
        val digits = code.filter { it in '0'..'9' }
        return if (digits.length == 6) digits.substring(0, 3) + "\u202F" + digits.substring(3) else code
    }

    /** Integer percent `0..100` of [fraction]. */
    fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f).toInt()

    /** Two digits for clock times ("09"). */
    fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

    /** [value] rounded half-up to [decimals] (0 or 1) places, "." as the separator, no grouping. */
    fun decimal(
        value: Double,
        decimals: Int,
    ): String {
        require(decimals in 0..3) { "decimals must be 0..3" }
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = (abs(value) * scale).roundToLong()
        val whole = scaled / scale
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        if (decimals == 0) return sign + whole
        val fraction = (scaled % scale).toString().padStart(decimals, '0')
        return "$sign$whole.$fraction"
    }
}

/** Initials and colours of avatars (design §12: initials on 8 muted colours derived from the device ID hash). */
object Avatars {
    /**
     * Up to two initials of [name]: the first letter or digit of the first two words, upper-cased, whole code points (so
     * an emoji or a Devanagari letter is never split). Null when the name has no letter or digit.
     */
    fun initials(name: String?): String? {
        if (name == null) return null
        val words = name.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val out = StringBuilder()
        for (word in words) {
            val first = firstLetterOrDigit(word) ?: continue
            out.append(first.uppercase())
            if (out.codePointCount() >= 2) break
        }
        return out.toString().ifEmpty { null }
    }

    /** A stable hash of a device key for the palette ([com.constrivo.drop.ui.shared.theme.DropColors.avatar]). */
    fun hash(key: String): Int {
        var h = FNV_OFFSET
        for (b in key.encodeToByteArray()) {
            h = h xor (b.toInt() and 0xFF)
            h *= FNV_PRIME
        }
        return h and Int.MAX_VALUE
    }

    private fun firstLetterOrDigit(word: String): String? {
        var i = 0
        while (i < word.length) {
            val c = word[i]
            if (c.isHighSurrogate() && i + 1 < word.length && word[i + 1].isLowSurrogate()) {
                i += 2
                continue
            }
            if (c.isLetterOrDigit()) {
                // Keep combining marks that follow (Devanagari vowel signs, accents) with the letter.
                var end = i + 1
                while (end < word.length && isCombining(word[end])) end++
                return word.substring(i, end)
            }
            i++
        }
        return null
    }

    private fun isCombining(c: Char): Boolean =
        c.category == CharCategory.NON_SPACING_MARK ||
            c.category == CharCategory.COMBINING_SPACING_MARK ||
            c.category == CharCategory.ENCLOSING_MARK

    private fun StringBuilder.codePointCount(): Int {
        var n = 0
        var i = 0
        while (i < length) {
            if (!isCombining(this[i])) n++
            i++
        }
        return n
    }

    private val WHITESPACE = Regex("\\s+")
    private const val FNV_OFFSET = -0x7ee3623b // 0x811C9DC5
    private const val FNV_PRIME = 0x01000193
}

/**
 * A short, safe description of a browser's `User-Agent` for "Allow this computer?" (N15). The raw text is chosen by
 * whoever sends the request, so none of it is shown: only browser and OS names recognised in it, from a fixed list.
 * An agent with neither is described as unknown ([BrowserDescription.isUnknown]), never quoted, so a crafted agent
 * cannot put words such as "(this is your computer)" or bidi-reordered text into the prompt.
 */
object BrowserNames {
    /** "Chrome on Windows" style parts, or an unknown description; null when there is no agent at all. */
    fun describe(userAgent: String?): BrowserDescription? {
        val ua =
            userAgent
                ?.filter { it.code >= 0x20 && it.code != 0x7F && !it.isSurrogate() && it.category != CharCategory.FORMAT }
                ?.trim()
        if (ua.isNullOrEmpty()) return null
        val browser =
            when {
                "Edg/" in ua || "Edge/" in ua -> "Edge"
                "OPR/" in ua -> "Opera"
                "Firefox/" in ua -> "Firefox"
                "Chrome/" in ua || "Chromium/" in ua -> "Chrome"
                "Safari/" in ua -> "Safari"
                else -> null
            }
        val os =
            when {
                "Windows" in ua -> "Windows"
                "iPhone" in ua || "iPad" in ua -> "iOS"
                "Mac OS X" in ua || "Macintosh" in ua -> "macOS"
                "Android" in ua -> "Android"
                "CrOS" in ua -> "ChromeOS"
                "Linux" in ua -> "Linux"
                else -> null
            }
        return BrowserDescription(browser, os)
    }
}

/** [browser] and [os] are product names from a fixed list (not translated); both null means "unknown browser". */
data class BrowserDescription(
    val browser: String?,
    val os: String?,
) {
    val isUnknown: Boolean get() = browser == null && os == null
}
