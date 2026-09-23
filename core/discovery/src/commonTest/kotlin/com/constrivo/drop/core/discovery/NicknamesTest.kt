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
    fun blankAndInvisibleNamesAreEmpty() {
        val invisible =
            listOf(
                "\u3164\u3164", // Hangul filler
                "\u115F\u1160", // Hangul choseong and jungseong fillers
                "\uFFA0", // halfwidth Hangul filler
                "\u2060\u2060", // word joiner
                "\u2061\u2062\u2063\u2064", // invisible operators
                "\u00AD", // soft hyphen
                "\u180E", // Mongolian vowel separator
                "\u2800", // braille blank
                "\u034F", // combining grapheme joiner
                "\uDB40\uDC41\uDB40\uDC7F", // tag characters without a flag
                "\uDB40\uDC01", // language tag
                "\uFE0F", // a variation selector with nothing to modify
                " \u200D ", // a joiner between spaces
                "\u17B4\u17B5", // Khmer inherent vowels
                "\uFFF0\uFFFF", // unassigned specials and a noncharacter
                "\u2000\u3000\u00A0", // spaces only
            )
        for (text in invisible) {
            assertEquals("", Nicknames.sanitize(text), text.map { it.code.toString(16) }.toString())
            assertNull(Nicknames.normalize(text), text.map { it.code.toString(16) }.toString())
            assertNull(Nicknames.decodeReceived(text.encodeToByteArray()))
        }
    }

    @Test
    fun formatCharactersAreStrippedInsideNames() {
        assertEquals("Anna", Nicknames.sanitize("\u00ADAn\u2060na\u3164"))
        assertEquals("Priya", Nicknames.sanitize("Pri\u200Bya\uFEFF"))
        assertEquals("a\u200Db", Nicknames.sanitize("a\u200D\u200D\u200Db"), "a run of joiners collapses to one")
        assertEquals("a b", Nicknames.sanitize("a\u200D b"))
    }

    @Test
    fun joinersSelectorsAndTagSequencesSurviveWhereTheyMeanSomething() {
        assertEquals("👩\u200D💻", Nicknames.sanitize("\u200D👩\u200D💻\u200D"))
        // Persian: ZWNJ between letters.
        val persian = "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645"
        assertEquals(persian, Nicknames.sanitize(persian))
        assertEquals("\u2764\uFE0F", Nicknames.sanitize("\u2764\uFE0F"), "emoji presentation selector")
        val england = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
        assertEquals("Sam $england", Nicknames.sanitize("Sam $england"), "emoji tag sequence (flag of England)")
        assertEquals("a", Nicknames.sanitize("a\uDB40\uDC67"), "a tag character outside a flag sequence")
    }

    @Test
    fun truncationNeverLeavesADanglingJoiner() {
        // "👩" is 4 bytes and ZWJ 3: a 7-byte cut ends in the joiner, which normalisation removes.
        assertEquals(Nicknames.Normalized("👩", true), Nicknames.normalize("👩\u200D💻", maxBytes = 7))
        assertEquals(Nicknames.Normalized("ab", true), Nicknames.normalize("ab \u00A0cd", maxBytes = 4))
    }

    @Test
    fun sanitiseIsIdempotentAndNormalisedNamesAreVisible() {
        val random = Random(77)
        repeat(20_000) {
            val raw = Fixtures.randomNickname(random)
            val once = Nicknames.sanitize(raw)
            assertEquals(once, Nicknames.sanitize(once))
            assertTrue(once.isEmpty() || Nicknames.hasVisibleCharacter(once), "'$once' has nothing visible")
            val max = random.nextInt(1, 70)
            val normalized = Nicknames.normalize(raw, max) ?: return@repeat
            assertEquals(normalized.text, Nicknames.sanitize(normalized.text), "a cut is sanitised again")
            assertEquals(Nicknames.Normalized(normalized.text, false), Nicknames.normalize(normalized.text, max))
            assertTrue(Nicknames.utf8Length(normalized.text) <= max)
        }
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
