package com.constrivo.drop.web

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReceiveTokenTest {
    @Test
    fun generatedTokensUseTheAlphabetAndLength() {
        val token = ReceiveToken.generate(Random(1))
        assertEquals(ReceiveToken.DEFAULT_LENGTH, token.value.length)
        assertTrue(token.value.all { it in ReceiveToken.ALPHABET })
        assertEquals(20, ReceiveToken.generate(Random(1), 20).value.length)
    }

    @Test
    fun generationIsDeterministicForASeedAndVariesOtherwise() {
        assertEquals(ReceiveToken.generate(Random(5)).value, ReceiveToken.generate(Random(5)).value)
        assertNotEquals(ReceiveToken.generate(Random(5)).value, ReceiveToken.generate(Random(6)).value)
        val many = (1..1000).map { ReceiveToken.generate(Random(it)).value }.toSet()
        assertEquals(1000, many.size)
    }

    @Test
    fun alphabetAvoidsAmbiguousLetters() {
        assertEquals(32, ReceiveToken.ALPHABET.length)
        "ilou".forEach { assertFalse(it in ReceiveToken.ALPHABET) }
    }

    @Test
    fun matchesIsCaseInsensitiveAndExact() {
        val token = ReceiveToken.parse("k7m2x9qa4v1c")
        assertTrue(token.matches("k7m2x9qa4v1c"))
        assertTrue(token.matches("K7M2X9QA4V1C"))
        assertFalse(token.matches("k7m2x9qa4v1"))
        assertFalse(token.matches("k7m2x9qa4v1cc"))
        assertFalse(token.matches("k7m2x9qa4v1d"))
        assertFalse(token.matches(""))
        assertFalse(token.matches("k7m2x9qa4v1ç"))
    }

    @Test
    fun parseValidates() {
        assertEquals("abcdefgh", ReceiveToken.parse("ABCDEFGH").value)
        assertFailsWith<IllegalArgumentException> { ReceiveToken.parse("short") }
        assertFailsWith<IllegalArgumentException> { ReceiveToken.parse("abcdefgi") }
        assertFailsWith<IllegalArgumentException> { ReceiveToken.parse("abcd/efgh") }
        assertFailsWith<IllegalArgumentException> { ReceiveToken.parse("a".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { ReceiveToken.generate(Random(1), 4) }
    }

    @Test
    fun toStringHidesTheValue() {
        val token = ReceiveToken.parse("k7m2x9qa4v1c")
        assertFalse(token.toString().contains("k7m2"))
        assertEquals(token, ReceiveToken.parse("K7M2X9QA4V1C"))
    }

    @Test
    fun prefixIsTheTokenPath() {
        assertEquals("/t/k7m2x9qa4v1c/", ReceiveRoutes.prefix(ReceiveToken.parse("k7m2x9qa4v1c")))
        assertEquals("file/3", ReceiveRoutes.file(3))
    }
}
