package com.constrivo.drop.web

import com.constrivo.drop.core.discovery.Nicknames
import java.text.Normalizer

/**
 * File names as the browser sees them: in the file list, in `Content-Disposition` and as zip entry names
 * (architecture §10.3 and §13 "Sanitised names"). The same rules apply to names uploaded back to the phone.
 *
 * [sanitize] turns any string into a single, safe path segment:
 * - only the last `/`- or `\`-separated segment is kept, so `../../x` and `C:\x` cannot escape a folder;
 * - Unicode is NFC-normalised (macOS sends decomposed names), then control and invisible characters (bidirectional
 *   overrides that fake an extension, zero-width characters and the like, see [Nicknames.sanitize]) are removed;
 * - the characters Windows forbids, `< > : " | ? *`, become `_`;
 * - leading and trailing spaces and trailing dots (which Windows drops) are trimmed;
 * - Windows device names (`CON`, `NUL`, `COM1`, `LPT9`, … with any extension) get a `_` prefix;
 * - the result is at most [MAX_NAME_BYTES] UTF-8 bytes, cut at a code point boundary with the extension kept;
 * - an empty result, `.` or `..` becomes the fallback name.
 *
 * [deduplicate] makes names unique case-insensitively (`photo.jpg`, `photo (2).jpg`), because the zip is usually
 * extracted onto a case-insensitive file system.
 */
object FileNames {
    /** Longest name produced, in UTF-8 bytes (the limit of common file systems). */
    const val MAX_NAME_BYTES = 255

    /** Fallback when nothing usable is left of a name. */
    const val FALLBACK = "file"

    private const val MAX_EXTENSION_BYTES = 32
    private const val FORBIDDEN = "<>:\"|?*"
    private val DEVICE_NAMES =
        setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") } +
            listOf("COM¹", "COM²", "COM³", "LPT¹", "LPT²", "LPT³")

    /** Returns [raw] as one safe path segment (rules above); never empty. Idempotent. */
    fun sanitize(
        raw: String,
        fallback: String = FALLBACK,
    ): String {
        val segment = raw.split('/', '\\').lastOrNull { it.isNotBlank() } ?: ""
        val normalized = Normalizer.normalize(segment, Normalizer.Form.NFC)
        val visible = Nicknames.sanitize(normalized)
        val replaced = buildString(visible.length) { visible.forEach { append(if (it in FORBIDDEN) '_' else it) } }
        var name = replaced.trimStart { it.isWhitespace() }.trimEnd(::isTrailingJunk)
        if (name.isEmpty() || name.all { it == '.' }) {
            return if (fallback == FALLBACK) FALLBACK else sanitize(fallback)
        }
        val base = name.substringBefore('.').trimEnd(' ')
        if (base.uppercase() in DEVICE_NAMES) name = "_$name"
        return truncateKeepingExtension(name, MAX_NAME_BYTES)
    }

    /**
     * Makes [names] unique, ignoring case: later duplicates get ` (2)`, ` (3)`, … before the extension, still within
     * [MAX_NAME_BYTES]. The input names should already be [sanitize]d; the output keeps their order.
     */
    fun deduplicate(names: List<String>): List<String> {
        val taken = HashSet<String>(names.size * 2)
        val result = ArrayList<String>(names.size)
        // Reserve the original names first so that "a.txt", "a (2).txt", "a.txt" keeps the given "a (2).txt" as is.
        val originals = names.groupingBy { it.lowercase() }.eachCount()
        val firstSeen = HashSet<String>()
        for (name in names) {
            val key = name.lowercase()
            if (firstSeen.add(key)) {
                taken += key
                result += name
                continue
            }
            var n = 2
            while (true) {
                val candidate = withSuffix(name, " ($n)")
                val candidateKey = candidate.lowercase()
                if (candidateKey !in taken && candidateKey !in originals) {
                    taken += candidateKey
                    result += candidate
                    break
                }
                n++
            }
        }
        return result
    }

    /**
     * An RFC 6266 `Content-Disposition: attachment` value for [fileName]: an ASCII `filename` fallback (non-ASCII,
     * quotes, backslashes, `%` and control characters become `_`) and the exact UTF-8 name in `filename*`
     * (RFC 8187 percent-encoding).
     */
    fun contentDisposition(fileName: String): String {
        val ascii =
            buildString {
                var i = 0
                while (i < fileName.length) {
                    val cp = fileName.codePointAt(i)
                    append(if (cp in 0x20..0x7E && cp != '"'.code && cp != '\\'.code && cp != '%'.code) cp.toChar() else '_')
                    i += Character.charCount(cp)
                }
            }
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''${percentEncode(fileName)}"
    }

    /** RFC 8187 `value-chars`: `attr-char` kept, every other UTF-8 byte as `%XX`. */
    internal fun percentEncode(value: String): String {
        val out = StringBuilder(value.length * 3)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 0x80 && isAttrChar(c.toChar())) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private fun isAttrChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$&+-.^_`|~"

    private const val HEX = "0123456789ABCDEF"

    /** [name] with ` (n)` before its extension, within [MAX_NAME_BYTES] (`photo.jpg`, 2 → `photo (2).jpg`). */
    internal fun numbered(
        name: String,
        n: Int,
    ): String = withSuffix(name, " ($n)")

    private fun withSuffix(
        name: String,
        suffix: String,
    ): String {
        val (base, extension) = split(name)
        val budget = MAX_NAME_BYTES - utf8(suffix) - utf8(extension)
        return Nicknames.truncateUtf8(base, budget).trimEnd(::isTrailingJunk) + suffix + extension
    }

    /** Windows drops trailing dots and spaces, so they are never kept at the end of a name. */
    private fun isTrailingJunk(c: Char): Boolean = c == '.' || c.isWhitespace()

    private fun truncateKeepingExtension(
        name: String,
        maxBytes: Int,
    ): String {
        if (utf8(name) <= maxBytes) return name
        val (base, extension) = split(name)
        return if (extension.isNotEmpty() && utf8(extension) <= MAX_EXTENSION_BYTES && base.isNotEmpty()) {
            Nicknames.truncateUtf8(base, maxBytes - utf8(extension)).trimEnd(::isTrailingJunk) + extension
        } else {
            Nicknames.truncateUtf8(name, maxBytes).trimEnd(::isTrailingJunk)
        }
    }

    /** Splits `photo.final.jpg` into `photo.final` and `.jpg`; a leading dot does not start an extension. */
    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    private fun utf8(text: String): Int = Nicknames.utf8Length(text)
}

/**
 * MIME types as they are echoed in headers and JSON: `type/subtype` made of RFC 9110 token characters, lower case,
 * without parameters; anything else becomes [OCTET_STREAM].
 */
object MimeTypes {
    const val OCTET_STREAM = "application/octet-stream"
    private val TOKEN = Regex("[a-z0-9!#$&^_.+-]{1,64}/[a-z0-9!#$&^_.+-]{1,127}")

    /** The bare, lower-case `type/subtype` of [raw], or [OCTET_STREAM]. */
    fun sanitize(raw: String?): String {
        val bare =
            raw
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase() ?: return OCTET_STREAM
        return if (TOKEN.matches(bare)) bare else OCTET_STREAM
    }
}
