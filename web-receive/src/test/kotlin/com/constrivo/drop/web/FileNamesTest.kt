package com.constrivo.drop.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileNamesTest {
    @Test
    fun keepsOrdinaryNames() {
        assertEquals("photo.jpg", FileNames.sanitize("photo.jpg"))
        assertEquals("Fotó 日本 📷.jpeg", FileNames.sanitize("Fotó 日本 📷.jpeg"))
        assertEquals(".bashrc", FileNames.sanitize(".bashrc"))
    }

    @Test
    fun keepsOnlyTheLastPathSegment() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("boot.ini", FileNames.sanitize("C:\\Windows\\..\\boot.ini"))
        assertEquals("b", FileNames.sanitize("a/b/"))
        assertEquals("file", FileNames.sanitize("../.."))
        assertEquals("file", FileNames.sanitize("/"))
    }

    @Test
    fun replacesWindowsForbiddenCharactersAndControls() {
        assertEquals("a_b_c_d_e_f_g_h.txt", FileNames.sanitize("a<b>c:d\"e|f?g*h.txt"))
        assertEquals("tab.txt", FileNames.sanitize("t\u0000a\u0007b\u001F.txt"))
        assertEquals("star_.txt", FileNames.sanitize("star*.txt"))
    }

    @Test
    fun removesBidiOverridesThatFakeExtensions() {
        // "photo\u202Egpj.exe" renders as "photoexe.jpg" in some viewers.
        assertEquals("photogpj.exe", FileNames.sanitize("photo\u202Egpj.exe"))
        assertEquals("ab.txt", FileNames.sanitize("a\u200Bb.txt"))
    }

    @Test
    fun trimsSpacesAndTrailingDots() {
        assertEquals("name", FileNames.sanitize("  name. . "))
        assertEquals("file", FileNames.sanitize("   "))
        assertEquals("file", FileNames.sanitize("..."))
        assertEquals("a", FileNames.sanitize("a\u3000."))
    }

    @Test
    fun prefixesWindowsDeviceNames() {
        assertEquals("_CON", FileNames.sanitize("CON"))
        assertEquals("_nul.txt", FileNames.sanitize("nul.txt"))
        assertEquals("_com1.tar.gz", FileNames.sanitize("com1.tar.gz"))
        assertEquals("_LPT9", FileNames.sanitize("LPT9"))
        assertEquals("CONSOLE.txt", FileNames.sanitize("CONSOLE.txt"))
        assertEquals("COM10", FileNames.sanitize("COM10"))
    }

    @Test
    fun normalisesToNfc() {
        val decomposed = "Fo\u0074o\u0301.jpg" // "Fotó" with a combining acute accent
        assertEquals("Fotó.jpg", FileNames.sanitize(decomposed))
    }

    @Test
    fun truncatesTo255BytesKeepingTheExtension() {
        val long = "é".repeat(200) + ".jpeg"
        val result = FileNames.sanitize(long)
        assertTrue(result.toByteArray().size <= FileNames.MAX_NAME_BYTES)
        assertTrue(result.endsWith(".jpeg"))
        assertTrue(result.removeSuffix(".jpeg").all { it == 'é' })
        // An emoji is never split.
        val emoji = "😀".repeat(100)
        val cut = FileNames.sanitize(emoji)
        assertTrue(cut.toByteArray().size <= 255)
        assertEquals(cut.length % 2, 0)
        assertEquals(63, cut.length / 2)
    }

    @Test
    fun sanitizeIsIdempotent() {
        val inputs =
            listOf(
                "a<b>.txt",
                "../x",
                "CON",
                "  x. ",
                "é".repeat(300) + ".png",
                "a\u3000.",
                "Fo\u0301",
                "..",
                "x" + "\u202E",
                "😀".repeat(80) + ".tar.gz",
            )
        for (input in inputs) {
            val once = FileNames.sanitize(input)
            assertEquals(once, FileNames.sanitize(once), "not idempotent for '$input'")
        }
    }

    @Test
    fun customFallbackIsSanitisedToo() {
        assertEquals("upload", FileNames.sanitize("", fallback = "upload"))
        assertEquals("file", FileNames.sanitize("", fallback = "///"))
    }

    @Test
    fun deduplicatesCaseInsensitively() {
        assertEquals(
            listOf("a.txt", "A (2).txt", "a (3).txt", "b"),
            FileNames.deduplicate(listOf("a.txt", "A.txt", "a.txt", "b")),
        )
        // A generated name never takes a name that appears later in the list.
        assertEquals(
            listOf("a.txt", "a (3).txt", "a (2).txt"),
            FileNames.deduplicate(listOf("a.txt", "a.txt", "a (2).txt")),
        )
        assertEquals(listOf("x", "x (2)"), FileNames.deduplicate(listOf("x", "x")))
        assertEquals(listOf(".env", ".env (2)"), FileNames.deduplicate(listOf(".env", ".env")))
    }

    @Test
    fun deduplicatedLongNamesStayWithinTheLimit() {
        val long = FileNames.sanitize("a".repeat(300) + ".jpg")
        val names = FileNames.deduplicate(listOf(long, long, long))
        assertEquals(3, names.toSet().size)
        names.forEach { assertTrue(it.toByteArray().size <= 255 && it.endsWith(".jpg"), it) }
        assertTrue(names[1].endsWith(" (2).jpg"))
    }

    @Test
    fun contentDispositionFollowsRfc6266() {
        assertEquals(
            "attachment; filename=\"report.pdf\"; filename*=UTF-8''report.pdf",
            FileNames.contentDisposition("report.pdf"),
        )
        assertEquals(
            "attachment; filename=\"Fot_ _.jpg\"; filename*=UTF-8''Fot%C3%B3%20%E6%97%A5.jpg",
            FileNames.contentDisposition("Fotó 日.jpg"),
        )
        assertEquals(
            "attachment; filename=\"100_ 'a'b;.txt\"; filename*=UTF-8''100%25%20%27a%27b%3B.txt",
            FileNames.contentDisposition("100% 'a'b;.txt"),
        )
        assertEquals(
            "attachment; filename=\"_x_\"; filename*=UTF-8''%22x%5C",
            FileNames.contentDisposition("\"x\\"),
        )
        assertEquals(
            "attachment; filename=\"_.bin\"; filename*=UTF-8''%F0%9F%98%80.bin",
            FileNames.contentDisposition("😀.bin"),
        )
    }

    @Test
    fun percentEncodingKeepsAttrChars() {
        assertEquals("AZaz09!#\$&+-.^_`|~", FileNames.percentEncode("AZaz09!#\$&+-.^_`|~"))
        assertEquals("%20%28%29%2A%2C%2F%3A%3B%3C%3D%3E%3F%40%5B%5D%7B%7D", FileNames.percentEncode(" ()*,/:;<=>?@[]{}"))
    }

    @Test
    fun mimeTypesAreSanitised() {
        assertEquals("image/jpeg", MimeTypes.sanitize("image/jpeg"))
        assertEquals("text/plain", MimeTypes.sanitize("Text/Plain; charset=utf-8"))
        assertEquals("application/vnd.android.package-archive", MimeTypes.sanitize("application/vnd.android.package-archive"))
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.sanitize(null))
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.sanitize(""))
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.sanitize("image"))
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.sanitize("text/html\r\nX-Evil: 1"))
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.sanitize("a/b/c"))
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.sanitize("ima ge/png"))
    }
}
