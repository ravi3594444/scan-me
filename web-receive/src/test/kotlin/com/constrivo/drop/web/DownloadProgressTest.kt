package com.constrivo.drop.web

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadProgressTest {
    @Test
    fun countsWrittenBytesFromTheRangeStart() {
        val progress = DownloadProgress(total = 100, offset = 40, end = 100)
        val sink = ByteArrayOutputStream()
        ProgressOutputStream(sink, progress).use { out ->
            out.write(ByteArray(50) { it.toByte() }, 0, 50)
            out.write(7)
            out.write(ByteArray(20), 5, 9)
        }
        assertEquals(51 + 9, sink.size())
        assertContentEquals(ByteArray(50) { it.toByte() }, sink.toByteArray().copyOf(50))
        assertEquals(100L, progress.position)
        assertEquals(DownloadProgress.State.RUNNING, progress.state)
        progress.finish(true)
        assertEquals(DownloadProgress.State.DONE, progress.state)
        progress.finish(false)
        assertEquals(DownloadProgress.State.DONE, progress.state, "the first outcome stands")
    }

    @Test
    fun aShortOrBrokenResponseIsFailed() {
        val short = DownloadProgress(total = 10, offset = 0, end = 10).apply { add(9) }
        short.finish(true)
        assertEquals(DownloadProgress.State.FAILED, short.state, "done means every promised byte")
        val broken = DownloadProgress(total = 10, offset = 0, end = 10).apply { add(10) }
        broken.finish(false)
        assertEquals(DownloadProgress.State.FAILED, broken.state)
    }

    @Test
    fun theTableKeepsTheNewestIdsAndRestartsAResumedOne() {
        val table = DownloadProgressTable(capacity = 3)
        val first = table.start("id-00001", 10, 0, 10)
        first.add(10)
        first.finish(true)
        val resumed = table.start("id-00001", 10, 6, 10)
        assertEquals(6L, resumed.position)
        assertEquals(DownloadProgress.State.RUNNING, assertNotNull(table["id-00001"]).state)
        table.start("id-00002", 1, 0, 1)
        table.start("id-00003", 1, 0, 1)
        table.start("id-00004", 1, 0, 1)
        assertNull(table["id-00001"], "the oldest is forgotten")
        assertNotNull(table["id-00004"])
    }

    @Test
    fun idsAreShortUrlSafeTokens() {
        assertTrue(DownloadProgressTable.isValidId("0a1b2c3d4e5f6a7b8c9d0e1f"))
        assertTrue(DownloadProgressTable.isValidId("Zip_all-1"))
        assertFalse(DownloadProgressTable.isValidId("short"))
        assertFalse(DownloadProgressTable.isValidId("x".repeat(65)))
        assertFalse(DownloadProgressTable.isValidId("has space1"))
        assertFalse(DownloadProgressTable.isValidId("ünïcode-id"))
        assertFalse(DownloadProgressTable.isValidId("../../etc"))
    }
}
