package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.ProtocolConstants.FILE_LIST_PAGE_BUDGET_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** N12: Offer summary, paged FileList, and the receiver-side checks. */
class FileListPagingTest {
    private fun photos(count: Int) =
        List(count) {
            FileEntry(
                it,
                "DCIM/Camera/IMG_2026${it.toString().padStart(5, '0')}.jpg",
                3_000_000L + it,
                "image/jpeg",
            )
        }

    private fun offerFor(files: List<FileEntry>): Offer {
        val plan = BundlePlan.forFiles(files, ProtocolConstants.CHUNK_SIZE, true)
        return Offer(
            TEST_ID,
            fileCount = files.size,
            totalBytes = files.sumOf { it.size },
            mimeHistogram = MimeHistogram.of(files.map { it.mime }),
            previewNames = files.take(6).map { it.name },
            bundleCount = plan.bundleCount,
        )
    }

    @Test
    fun fiveThousandPhotosFitAQuickOfferAndAFewPages() {
        val files = photos(5000)
        val offer = offerFor(files)
        // T-02 / F-D1: the card needs only the summary, which stays small.
        assertTrue(ControlCodec.encodedSize(offer) < 512, "offer is ${ControlCodec.encodedSize(offer)} bytes")
        val pages = FileListPager.paginate(TEST_ID, files)
        assertTrue(pages.size in 10..40, "${pages.size} pages")
        for ((i, page) in pages.withIndex()) {
            assertEquals(i, page.page)
            assertEquals(i == pages.lastIndex, page.last)
            assertTrue(ControlCodec.encodedSize(page) <= FILE_LIST_PAGE_BUDGET_BYTES)
        }
        val assembler = FileListAssembler(offer)
        var result: List<FileEntry>? = null
        for (page in pages) {
            assertFalse(assembler.isComplete)
            result = assembler.add(ControlCodec.decode(ControlCodec.encode(page)) as FileList)
        }
        assertEquals(files, result)
        assertTrue(assembler.isComplete)
        assertEquals(files, assembler.received)
    }

    @Test
    fun pagesRespectTheEntryCapAndHoldAtLeastOneEntry() {
        val files = List(10) { FileEntry(it, "n".repeat(ProtocolConstants.MAX_FILE_NAME_BYTES), it.toLong()) }
        val tiny = FileListPager.paginate(TEST_ID, files, budgetBytes = 100)
        assertEquals(10, tiny.size, "an entry larger than the budget still gets its own page")
        assertTrue(tiny.all { it.files.size == 1 })
        val capped = FileListPager.paginate(TEST_ID, photos(10), maxEntries = 3)
        assertEquals(listOf(3, 3, 3, 1), capped.map { it.files.size })
        val single = FileListPager.paginate(TEST_ID, photos(1))
        assertEquals(1, single.size)
        assertTrue(single[0].last)
    }

    @Test
    fun pagerRejectsBadInput() {
        assertRejectsArgument { FileListPager.paginate(TEST_ID, emptyList()) }
        assertRejectsArgument { FileListPager.paginate(TEST_ID, listOf(FileEntry(1, "a", 1))) }
        assertRejectsArgument { FileListPager.paginate(TEST_ID, photos(2), budgetBytes = 0) }
        assertRejectsArgument { FileListPager.paginate(TEST_ID, photos(2), maxEntries = 0) }
    }

    @Test
    fun assemblerRejectsInconsistentPages() {
        val files = photos(10)
        val offer = offerFor(files)
        val pages = FileListPager.paginate(TEST_ID, files, maxEntries = 4)

        assertProtocolError("another transfer") { FileListAssembler(offer).add(pages[0].copy(transferId = OTHER_ID)) }
        assertProtocolError("out of order") { FileListAssembler(offer).add(pages[1]) }
        assertProtocolError("gap") {
            FileListAssembler(offer).apply { add(pages[0]) }.add(FileList(TEST_ID, 1, false, files.subList(5, 8)))
        }
        assertProtocolError("ends early") { FileListAssembler(offer).add(pages[0].copy(last = true)) }
        assertProtocolError("too many files") {
            FileListAssembler(offer.copy(fileCount = 3, previewNames = emptyList(), mimeHistogram = emptyMap())).add(pages[0])
        }
        assertProtocolError("sizes exceed total") { FileListAssembler(offer.copy(totalBytes = 10)).add(pages[0]) }
        assertProtocolError("sizes short of total") {
            FileListAssembler(offer.copy(totalBytes = offer.totalBytes + 1)).apply {
                add(pages[0])
                add(pages[1])
            }.add(pages[2])
        }
        val complete = FileListAssembler(offer)
        pages.forEach { complete.add(it) }
        assertProtocolError("after the last page") { complete.add(pages[2]) }
    }

    /** Sizes that wrap a 64-bit sum must not slip past `total_bytes`, the figure the user accepted and storage checked. */
    @Test
    fun assemblerRejectsSizesThatOverflowTheTotal() {
        val offer = Offer(TEST_ID, fileCount = 4, totalBytes = 100, bundleCount = 1)
        val sizes = listOf(100L, Long.MAX_VALUE, Long.MAX_VALUE, 2L)
        val page = FileList(TEST_ID, 0, true, sizes.mapIndexed { i, size -> FileEntry(i, "f$i", size) })
        assertProtocolError("wrapped sum") { FileListAssembler(offer).add(page) }
        assertProtocolError("wrapped sum, layout") { TransferLayout.of(offer, page.files) }
        // Split across pages, the running total is checked the same way.
        val pages = FileListPager.paginate(TEST_ID, page.files, maxEntries = 1)
        val assembler = FileListAssembler(offer)
        assertNull(assembler.add(pages[0]))
        assertProtocolError("wrapped sum over pages") { assembler.add(pages[1]) }
    }

    /** A file whose chunk count does not fit a u31 chunk index is the peer's fault: ProtocolException, not IllegalArgumentException. */
    @Test
    fun filesWithMoreChunksThanAChunkIndexCanNameAreRejected() {
        val chunk = ProtocolConstants.MIN_CHUNK_SIZE
        val huge = Offer(TEST_ID, fileCount = 1, totalBytes = Long.MAX_VALUE, chunkSize = chunk, bundleSmall = false, bundleCount = 0)
        val files = listOf(FileEntry(0, "a", Long.MAX_VALUE))
        assertProtocolError("assembler") { FileListAssembler(huge).add(FileList(TEST_ID, 0, true, files)) }
        assertProtocolError("layout") { TransferLayout.of(huge, files) }

        val largest = Int.MAX_VALUE.toLong() * chunk
        val fits = huge.copy(totalBytes = largest)
        val ok = listOf(FileEntry(0, "a", largest))
        assertEquals(ok, FileListAssembler(fits).add(FileList(TEST_ID, 0, true, ok)))
        assertEquals(Int.MAX_VALUE.toLong(), TransferLayout.of(fits, ok).totalUnits)
        val oneMore = listOf(FileEntry(0, "a", largest + 1))
        assertProtocolError("one byte more") {
            FileListAssembler(fits.copy(totalBytes = largest + 1)).add(FileList(TEST_ID, 0, true, oneMore))
        }
        assertProtocolError("one byte more, layout") { TransferLayout.of(fits.copy(totalBytes = largest + 1), oneMore) }
    }

    @Test
    fun assemblerChecksTheBundleCount() {
        val files = List(3) { FileEntry(it, "s$it.txt", 10) }
        val offer = Offer(TEST_ID, fileCount = 3, totalBytes = 30, bundleCount = 2)
        val page = FileListPager.paginate(TEST_ID, files).single()
        assertProtocolError { FileListAssembler(offer).add(page) }
        assertEquals(files, FileListAssembler(offer.copy(bundleCount = 1)).add(page))
        assertNull(FileListAssembler(offer.copy(fileCount = 3, bundleCount = 1)).add(page.copy(last = false, files = files.take(2))))
    }

    @Test
    fun mimeHistogramCountsAndBuckets() {
        assertEquals(mapOf("image/png" to 1, "image/jpeg" to 2), MimeHistogram.of(listOf("image/jpeg", "IMAGE/JPEG", "image/png")))
        assertEquals(mapOf(MimeHistogram.UNKNOWN_MIME to 2), MimeHistogram.of(listOf(null, " ")))
        // Too many distinct types collapse to type/* buckets.
        val many = (0 until 20).map { "image/x-$it" } + (0 until 5).map { "video/v$it" }
        assertEquals(mapOf("image/*" to 20, "video/*" to 5), MimeHistogram.of(many))
        // Still too many buckets: keep the largest, merge the rest into */*.
        val buckets = (0 until 20).flatMap { i -> List(i + 1) { "t$i/x" } }
        val histogram = MimeHistogram.of(buckets, maxEntries = 4)
        assertEquals(4, histogram.size)
        assertEquals(20, histogram["t19/*"])
        assertEquals(19, histogram["t18/*"])
        assertEquals(18, histogram["t17/*"])
        assertEquals(buckets.size, histogram.values.sum())
        // Canonical order: shorter keys first.
        assertEquals(listOf("*/*", "t17/*", "t18/*", "t19/*"), histogram.keys.toList())
        assertRejectsArgument { MimeHistogram.of(emptyList(), maxEntries = 0) }
        // Usable directly in an Offer.
        Offer(TEST_ID, fileCount = buckets.size, totalBytes = 0, mimeHistogram = histogram, bundleCount = 1)
    }

    /** Long or slash-less types from local metadata must still give histogram keys an Offer accepts. */
    @Test
    fun mimeBucketsNeverExceedTheKeyLimit() {
        val max = ProtocolConstants.MAX_MIME_BYTES
        val types =
            (0 until 10).map { "x".repeat(max - 1) + ('a' + it) } + // 255 bytes, no slash
                (0 until 5).map { "y".repeat(max - 2) + "/" + ('a' + it) } + // top-level type of 253 bytes: "…/*" is 255
                (0 until 5).map { "z".repeat(max - 1) + "/" } + // slash at byte 254: the bucket would be 256 bytes
                listOf("/leading", "image/png", "image/jpeg")
        val histogram = MimeHistogram.of(types)
        assertTrue(histogram.keys.all { it.encodeToByteArray().size <= max }, "${histogram.keys.map { it.length }}")
        assertEquals(types.size, histogram.values.sum())
        assertEquals(10 + 5 + 1, histogram[MimeHistogram.ANY], "no slash, an oversized bucket or no top-level type: */*")
        assertEquals(5, histogram["y".repeat(max - 2) + "/*"])
        assertEquals(2, histogram["image/*"])
        Offer(TEST_ID, fileCount = types.size, totalBytes = 0, mimeHistogram = histogram, bundleCount = 1)
    }
}
