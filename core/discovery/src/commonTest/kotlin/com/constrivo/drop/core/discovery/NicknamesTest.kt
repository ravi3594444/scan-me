package com.constrivo.drop.core.discovery

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NicknamesTest {
    @Test
    fun utf8LengthCountsCodePoints() {
        assertEquals(0, Nicknames.utf8Length(""))
        assertEquals(1, Nicknames.utf8Length("a"))
        assertEquals(2, Nicknames.utf8Length("é"))
        assertEquals(3, Nicknames.utf8Length("€"))
        assertEquals(4, Nicknames.utf8Length("😀"))
        assertEquals(3, Nicknames.utf8Length("\uD800"), "a lone surrogate counts as U+FFFD")
    }

    @Test
    fun truncationNeverSplitsACodePoint() {
        val text = "ab€😀c"
        assertEquals("ab", Nicknames.truncateUtf8(text, 2))
        assertEquals("ab", Nicknames.truncateUtf8(text, 4))
        assertEquals("ab€", Nicknames.truncateUtf8(text, 5))
        assertEquals("ab€", Nicknames.truncateUtf8(text, 8))
        assertEquals("ab€😀", Nicknames.truncateUtf8(text, 9))
        assertEquals(text, Nicknames.truncateUtf8(text, 10))
        assertEquals("", Nicknames.truncateUtf8(text, 0))
    }

    @Test
    fun truncationOfRandomTextIsAPrefixWithinBudget() {
        val random = Random(1)
        repeat(5_000) {
            val text = Nicknames.sanitize(Fixtures.randomNickname(random))
            val max = random.nextInt(0, 70)
            val cut = Nicknames.truncateUtf8(text, max)
            assertTrue(text.startsWith(cut))
            assertTrue(cut.encodeToByteArray().size <= max)
            assertEquals(Nicknames.utf8Length(cut), cut.encodeToByteArray().size)
            if (cut.isNotEmpty()) assertTrue(!cut.last().isHighSurrogate(), "split surrogate pair")
            if (cut.length < text.length) {
                val nextBytes = Nicknames.utf8Length(text.substring(0, cut.length + if (text[cut.length].isHighSurrogate()) 2 else 1))
                assertTrue(nextBytes > max, "the cut is not the longest prefix")
            }
        }
    }

    @Test
    fun sanitizeStripsControlsAndBidiOverrides() {
        assertEquals("Anna", Nicknames.sanitize(" \u202EAn\u0000n\u200Fa\n"))
        assertEquals("a\uFFFDb", Nicknames.sanitize("a\uDC00b"))
        assertEquals("👩\u200D💻", Nicknames.sanitize("👩\u200D💻"), "ZWJ emoji survive")
        assertEquals("", Nicknames.sanitize("\u2066\u2069\uFEFF"))
    }

    @Test
    fun normalizeReportsTruncation() {
        assertNull(Nicknames.normalize("  "))
        assertEquals(Nicknames.Normalized("Anna", false), Nicknames.normalize("Anna"))
        val long = "x".repeat(70)
        assertEquals(Nicknames.Normalized("x".repeat(64), true), Nicknames.normalize(long))
        assertEquals(Nicknames.Normalized("ab", true), Nicknames.normalize("ab cd", maxBytes = 3))
    }
}
