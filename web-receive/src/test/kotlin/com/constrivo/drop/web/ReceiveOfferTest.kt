package com.constrivo.drop.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReceiveOfferTest {
    private fun file(
        name: String,
        size: Int,
        mime: String?,
    ) = BytesFile(name, ByteArray(size), mime)

    @Test
    fun summaryNamesPhotosVideosAndFiles() {
        assertEquals("12 photos · 48 MB", OfferSummary.describe(List(12) { "image/jpeg" }, 48_000_000))
        assertEquals("1 photo · 3.2 MB", OfferSummary.describe(listOf("image/heic"), 3_200_000))
        assertEquals("1 video · 1.2 GB", OfferSummary.describe(listOf("video/mp4"), 1_200_000_000))
        assertEquals("2 videos · 20 MB", OfferSummary.describe(listOf("video/mp4", "video/quicktime"), 20_000_000))
        assertEquals("3 photos and videos · 5 MB", OfferSummary.describe(listOf("image/png", "video/mp4", "image/gif"), 5_000_000))
        assertEquals("3 files · 340 KB", OfferSummary.describe(listOf("image/png", "application/pdf", "text/plain"), 340_000))
        assertEquals("1 file · 0 B", OfferSummary.describe(listOf("application/octet-stream"), 0))
    }

    @Test
    fun bytesUseDecimalUnits() {
        assertEquals("0 B", OfferSummary.formatBytes(0))
        assertEquals("999 B", OfferSummary.formatBytes(999))
        assertEquals("1 KB", OfferSummary.formatBytes(1000))
        assertEquals("1.2 KB", OfferSummary.formatBytes(1234))
        assertEquals("9.9 KB", OfferSummary.formatBytes(9_949))
        assertEquals("10 KB", OfferSummary.formatBytes(9_950))
        assertEquals("999 KB", OfferSummary.formatBytes(999_499))
        assertEquals("1 MB", OfferSummary.formatBytes(999_999))
        assertEquals("48 MB", OfferSummary.formatBytes(48_000_000))
        assertEquals("1.5 GB", OfferSummary.formatBytes(1_500_000_000))
        assertEquals("4.3 GB", OfferSummary.formatBytes(4_294_967_296))
        assertEquals("9223 PB", OfferSummary.formatBytes(Long.MAX_VALUE))
    }

    @Test
    fun namesAreSanitisedAndUnique() {
        val offer =
            ReceiveOffer(
                "Dev",
                listOf(file("../a.txt", 1, "text/plain"), file("A.txt", 2, null), file("x:y", 3, "Image/PNG; q=1")),
            )
        assertEquals(listOf("a.txt", "A (2).txt", "x_y"), offer.displayNames)
        assertEquals(listOf("text/plain", MimeTypes.OCTET_STREAM, "image/png"), offer.mimeTypes)
        assertEquals(6, offer.totalBytes)
        assertEquals("3 files · 6 B", offer.summary)
        assertEquals("Files from Dev.zip", offer.archiveName)
    }

    @Test
    fun senderNameAndSummaryAreSanitised() {
        val offer = ReceiveOffer("  Rohan‮'s Pixel\u0007 ", listOf(file("a", 1, null)), summary = " 1 thing​ ")
        assertEquals("Rohan's Pixel", offer.senderName)
        assertEquals("1 thing", offer.summary)
        assertEquals("Files from Rohan's Pixel.zip", offer.archiveName)
        val blank = ReceiveOffer("​", listOf(file("a", 1, null)), archiveName = "../x/../")
        assertEquals("Drop", blank.senderName)
        assertEquals("files.zip", blank.archiveName)
    }

    @Test
    fun anOfferNeedsFiles() {
        assertFailsWith<IllegalArgumentException> { ReceiveOffer("Dev", emptyList()) }
        assertFailsWith<IllegalArgumentException> { ReceiveOffer("Dev", listOf(BytesFile("a", ByteArray(0), declaredSize = -1))) }
    }
}
