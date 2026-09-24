package com.constrivo.drop.core.transfer.receive

/**
 * Turns a peer-supplied `FileEntry.name` into a name that is safe to create in the receive folder (F-D5, "sanitised
 * names"; architecture §13). The result is a single path component that every supported file system accepts and that
 * cannot escape or hide in the destination:
 *
 * 1. **No path.** Only the last non-empty component survives: `/` and `\` separate components, so `../../etc/passwd`
 *    becomes `passwd` and `C:\Windows\x.dll` becomes `x.dll`.
 * 2. **No invisible or control characters.** C0 and C1 controls, DEL, the bidirectional controls (so
 *    `photo\u202Egpj.exe` cannot pose as a `.jpg`), zero-width and other invisible format characters, line and paragraph
 *    separators, non-characters and unpaired surrogates are removed.
 * 3. **No reserved characters.** `< > : " | ? *` become `_` (Windows forbids them; `:` also names NTFS streams).
 * 4. **No hidden-file or traversal tricks.** Leading dots are removed (`.bashrc` → `bashrc`, `..` → nothing); leading
 *    and trailing spaces and trailing dots are removed (Windows drops them silently).
 * 5. **No device names.** A name whose stem is a reserved Windows device (`CON`, `PRN`, `AUX`, `NUL`, `COM0`–`COM9`,
 *    `LPT0`–`LPT9`, their superscript-digit forms, `CONIN$`, `CONOUT$`), in any case and with any extension, gets a `_`
 *    prefix.
 * 6. **Length.** At most [MAX_NAME_BYTES] UTF-8 bytes (ext4, APFS and NTFS limits), cut at a code-point boundary in the
 *    stem so that the extension is kept.
 * 7. **Never empty.** An empty result becomes [FALLBACK_NAME].
 *
 * [withCollisionSuffix] builds the ` (1)`, ` (2)`, … variants a store uses when the name is taken, within the same
 * length limit. Pure functions; the output of [sanitize] is a fixed point of [sanitize].
 */
object FileNameSanitizer {
    /** Longest file name in UTF-8 bytes. */
    const val MAX_NAME_BYTES: Int = 255

    /** An extension longer than this (in UTF-8 bytes) is treated as part of the stem when shortening. */
    const val MAX_EXTENSION_BYTES: Int = 32

    /** The name used when nothing printable is left. */
    const val FALLBACK_NAME: String = "file"

    private const val RESERVED_CHARS = "<>:\"|?*"

    private val DEVICE_NAMES: Set<String> =
        buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"))
            for (d in "0123456789¹²³") {
                add("COM$d")
                add("LPT$d")
            }
        }

    /** The safe form of [name]; see the class comment. */
    fun sanitize(name: String): String {
        val component = lastComponent(name)
        val cleaned = StringBuilder(component.length)
        var i = 0
        while (i < component.length) {
            val c = component[i]
            if (c.isHighSurrogate()) {
                if (i + 1 < component.length && component[i + 1].isLowSurrogate()) {
                    val codePoint = 0x10000 + ((c.code - 0xD800) shl 10) + (component[i + 1].code - 0xDC00)
                    if (!isNonCharacter(codePoint) && !isInvisibleSupplementary(codePoint)) {
                        cleaned.append(c).append(component[i + 1])
                    }
                    i += 2
                    continue
                }
                i++ // unpaired high surrogate
                continue
            }
            when {
                c.isLowSurrogate() -> Unit

                // unpaired
                isRemoved(c) -> Unit

                c in RESERVED_CHARS -> cleaned.append('_')

                else -> cleaned.append(c)
            }
            i++
        }
        var result = trimName(cleaned.toString())
        if (result.isEmpty()) return FALLBACK_NAME
        if (isDeviceName(result)) result = "_$result"
        result = fitLength(result, MAX_NAME_BYTES)
        return result.ifEmpty { FALLBACK_NAME }
    }

    /**
     * [name] (a [sanitize] output) with ` (n)` before its extension, shortened in the stem to stay within
     * [MAX_NAME_BYTES]: `photo.jpg` → `photo (1).jpg`, `archive` → `archive (2)`.
     */
    fun withCollisionSuffix(
        name: String,
        n: Int,
    ): String {
        require(n >= 1) { "collision counter starts at 1" }
        val (stem, extension) = split(name)
        val suffix = " ($n)"
        val budget = MAX_NAME_BYTES - utf8Length(suffix) - utf8Length(extension)
        val shortStem = trimTrailing(truncateUtf8(stem, maxOf(budget, 1)))
        return shortStem.ifEmpty { "_" } + suffix + extension
    }

    /** The extension of [name] in lower case without the dot, or null when it has none. */
    fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }

    private fun lastComponent(name: String): String {
        var end = name.length
        while (end > 0) {
            while (end > 0 && isSeparator(name[end - 1])) end--
            var start = end
            while (start > 0 && !isSeparator(name[start - 1])) start--
            val part = name.substring(start, end)
            if (part.isNotEmpty()) return part
            end = start
        }
        return ""
    }

    private fun isSeparator(c: Char): Boolean = c == '/' || c == '\\'

    private fun isRemoved(c: Char): Boolean {
        val code = c.code
        return code < 0x20 ||
            code in 0x7F..0x9F ||
            code == 0x00AD || // soft hyphen
            code == 0x061C || // Arabic letter mark
            code == 0x180E || // Mongolian vowel separator
            code in 0x200B..0x200F || // zero-width space, joiners, LRM, RLM
            code in 0x2028..0x202E || // line/paragraph separators, bidi embeddings and overrides
            code in 0x2060..0x2064 || // word joiner, invisible operators
            code in 0x2066..0x206F || // bidi isolates, deprecated format characters
            code == 0xFEFF || // byte order mark
            code in 0xFFF9..0xFFFB || // interlinear annotation
            isNonCharacter(code)
    }

    private fun isNonCharacter(codePoint: Int): Boolean = codePoint in 0xFDD0..0xFDEF || (codePoint and 0xFFFE) == 0xFFFE

    /** Tag characters and the supplementary format characters, invisible in names. */
    private fun isInvisibleSupplementary(codePoint: Int): Boolean =
        codePoint in 0xE0000..0xE007F || codePoint in 0x1D173..0x1D17A || codePoint in 0xE0100..0xE01EF

    /** Removes leading spaces and dots, trailing spaces and dots. */
    private fun trimName(value: String): String {
        var start = 0
        while (start < value.length && (value[start] == '.' || value[start].isWhitespace())) start++
        return trimTrailing(value.substring(start))
    }

    private fun trimTrailing(value: String): String {
        var end = value.length
        while (end > 0 && (value[end - 1] == '.' || value[end - 1].isWhitespace())) end--
        return value.substring(0, end)
    }

    private fun isDeviceName(name: String): Boolean {
        val stem = name.substringBefore('.').trimEnd(' ')
        return stem.uppercase() in DEVICE_NAMES
    }

    /** [name] shortened to [maxBytes], cutting the stem and keeping the extension when it is short enough. */
    private fun fitLength(
        name: String,
        maxBytes: Int,
    ): String {
        if (utf8Length(name) <= maxBytes) return name
        val (stem, extension) = split(name)
        if (extension.isNotEmpty() && utf8Length(extension) < maxBytes) {
            val shortStem = trimTrailing(truncateUtf8(stem, maxBytes - utf8Length(extension)))
            if (shortStem.isNotEmpty()) return shortStem + extension
        }
        return trimTrailing(truncateUtf8(name, maxBytes))
    }

    /** Stem and extension (with its dot); the extension is empty when absent or longer than [MAX_EXTENSION_BYTES]. */
    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return name to ""
        val extension = name.substring(dot)
        if (utf8Length(extension) > MAX_EXTENSION_BYTES + 1) return name to ""
        return name.substring(0, dot) to extension
    }

    private fun truncateUtf8(
        value: String,
        maxBytes: Int,
    ): String {
        var bytes = 0
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val step = if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) 2 else 1
            val size =
                when {
                    step == 2 -> 4
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
            if (bytes + size > maxBytes) break
            bytes += size
            i += step
        }
        return value.substring(0, i)
    }

    internal fun utf8Length(value: String): Int {
        var bytes = 0
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) {
                bytes += 4
                i += 2
                continue
            }
            bytes +=
                when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
            i++
        }
        return bytes
    }
}

/**
 * Executable and package detection for the "this file can run code" warning (F-D5, design §5): the UI never opens
 * received files automatically and warns before opening one of these.
 */
object FileTypes {
    /** Android packages and bundles (Play review risk, PRD §9; decision 8). */
    private val ANDROID_PACKAGES = setOf("apk", "apks", "apkm", "xapk", "aab")

    private val EXECUTABLE_EXTENSIONS =
        ANDROID_PACKAGES +
            setOf(
                // Windows
                "exe",
                "com",
                "scr",
                "pif",
                "msi",
                "msp",
                "msix",
                "msixbundle",
                "appx",
                "appxbundle",
                "bat",
                "cmd",
                "ps1",
                "psm1",
                "vbs",
                "vbe",
                "js",
                "jse",
                "wsf",
                "wsh",
                "hta",
                "cpl",
                "lnk",
                "reg",
                "dll",
                "sys",
                "inf",
                "scf",
                "application",
                "gadget",
                "msc",
                "jar",
                // macOS
                "app",
                "dmg",
                "pkg",
                "mpkg",
                "command",
                "workflow",
                "action",
                "terminal",
                // Linux and Unix
                "sh",
                "bash",
                "zsh",
                "csh",
                "ksh",
                "run",
                "bin",
                "elf",
                "appimage",
                "deb",
                "rpm",
                "flatpakref",
                "snap",
                "desktop",
            )

    private val EXECUTABLE_MIME_TYPES =
        setOf(
            "application/vnd.android.package-archive",
            "application/x-msdownload",
            "application/x-dosexec",
            "application/x-msi",
            "application/x-ms-installer",
            "application/vnd.microsoft.portable-executable",
            "application/x-executable",
            "application/x-elf",
            "application/x-sharedlib",
            "application/x-sh",
            "application/x-shellscript",
            "application/x-bat",
            "application/java-archive",
            "application/x-java-archive",
            "application/x-apple-diskimage",
            "application/x-debian-package",
            "application/vnd.debian.binary-package",
            "application/x-rpm",
            "application/x-redhat-package-manager",
        )

    /** True when [name] (sanitised) or [mimeType] says the file can run code: show the warning before opening it. */
    fun isExecutable(
        name: String,
        mimeType: String?,
    ): Boolean {
        val extension = FileNameSanitizer.extensionOf(name)
        if (extension != null && extension in EXECUTABLE_EXTENSIONS) return true
        return mimeType?.trim()?.lowercase()?.substringBefore(';')?.trim() in EXECUTABLE_MIME_TYPES
    }

    /** True for an Android package ([isExecutable] holds too). */
    fun isAndroidPackage(
        name: String,
        mimeType: String?,
    ): Boolean {
        val extension = FileNameSanitizer.extensionOf(name)
        if (extension != null && extension in ANDROID_PACKAGES) return true
        return mimeType?.trim()?.lowercase() == "application/vnd.android.package-archive"
    }
}
