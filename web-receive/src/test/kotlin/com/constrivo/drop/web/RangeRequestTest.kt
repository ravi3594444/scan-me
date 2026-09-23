package com.constrivo.drop.web

import com.constrivo.drop.web.RangeRequest.Part
import com.constrivo.drop.web.RangeRequest.Unsatisfiable
import com.constrivo.drop.web.RangeRequest.Whole
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RangeRequestTest {
    private fun parse(header: String?) = RangeRequest.parse(header, 1000)

    @Test
    fun noHeaderMeansWhole() {
        assertEquals(Whole, parse(null))
    }

    @Test
    fun closedRange() {
        assertEquals(Part(0, 499), parse("bytes=0-499"))
        assertEquals(Part(500, 999), parse("bytes=500-999"))
        assertEquals(Part(7, 7), parse("bytes=7-7"))
        assertEquals(1, Part(7, 7).length)
    }

    @Test
    fun openRange() {
        assertEquals(Part(900, 999), parse("bytes=900-"))
        assertEquals(Part(0, 999), parse("bytes=0-"))
        assertEquals(Part(999, 999), parse("bytes=999-"))
    }

    @Test
    fun suffixRange() {
        assertEquals(Part(900, 999), parse("bytes=-100"))
        assertEquals(Part(0, 999), parse("bytes=-1000"))
        assertEquals(Part(0, 999), parse("bytes=-5000"))
        assertEquals(Part(0, 999), parse("bytes=-99999999999999999999999"))
        assertEquals(Unsatisfiable, parse("bytes=-0"))
    }

    @Test
    fun lastPastTheEndIsClamped() {
        assertEquals(Part(10, 999), parse("bytes=10-5000"))
        assertEquals(Part(10, 999), parse("bytes=10-99999999999999999999999999"))
    }

    @Test
    fun firstPastTheEndIsUnsatisfiable() {
        assertEquals(Unsatisfiable, parse("bytes=1000-"))
        assertEquals(Unsatisfiable, parse("bytes=1000-2000"))
        assertEquals(Unsatisfiable, parse("bytes=99999999999999999999999-"))
    }

    @Test
    fun whitespaceAndCaseAreTolerated() {
        assertEquals(Part(1, 2), parse("Bytes = 1 - 2"))
        assertEquals(Part(1, 2), parse("BYTES=1-2 "))
        assertEquals(Part(0, 0), parse("bytes=000-0000"))
    }

    @Test
    fun malformedOrUnsupportedHeadersAreIgnored() {
        val ignored =
            listOf(
                "",
                "bytes",
                "bytes=",
                "bytes=-",
                "bytes=abc",
                "bytes=1-a",
                "bytes=a-1",
                "bytes=+1-2",
                "bytes=1--2",
                "bytes=5-4",
                "items=0-1",
                "bytes=0-1,5-6",
                "bytes=0-1,",
                "bytes=0x10-20",
                "bytes=1-2-3",
                "bytes=١-٢",
            )
        for (header in ignored) assertEquals(Whole, parse(header), "header '$header'")
    }

    @Test
    fun emptyFileIsAlwaysWhole() {
        assertEquals(Whole, RangeRequest.parse("bytes=0-", 0))
        assertEquals(Whole, RangeRequest.parse("bytes=-5", 0))
        assertEquals(Whole, RangeRequest.parse("bytes=3-9", 0))
    }

    @Test
    fun randomHeadersNeverThrowAndStayInBounds() {
        val random = Random(99)
        val alphabet = "bytes=0123456789-, \t=aB+"
        repeat(20_000) {
            val header = String(CharArray(random.nextInt(0, 24)) { alphabet[random.nextInt(alphabet.length)] })
            val size = random.nextLong(0, 5000)
            when (val r = RangeRequest.parse(header, size)) {
                is Part -> assertTrue(r.first in 0..r.last && r.last < size, "$header -> $r for $size")
                else -> Unit
            }
        }
    }
}
