package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.FileEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The bounded buffer pool behind the §15 memory budget and the receiver's write queue. */
class BufferPoolTest {
    @Test
    fun `at most maxBuffers are out and acquire waits for a release`() =
        runTest {
            val pool = BufferPool(bufferSize = 1024, maxBuffers = 2)
            val a = pool.acquire()
            val b = pool.acquire()
            assertNull(pool.tryAcquire(), "the pool is exhausted")
            val waiting = async { pool.acquire() }
            runCurrent()
            assertFalse(waiting.isCompleted)
            a.release()
            val c = waiting.await()
            assertSame(a.bytes, c.bytes, "buffers are reused, not reallocated")
            assertEquals(2, pool.allocatedCount)
            b.release()
            c.release()
            assertNotNull(pool.tryAcquire())
        }

    @Test
    fun `a retained buffer returns only after every holder released it`() =
        runTest {
            val pool = BufferPool(bufferSize = 16, maxBuffers = 1)
            val buffer = pool.acquire()
            buffer.retain(2)
            buffer.release()
            buffer.release()
            assertNull(pool.tryAcquire(), "one holder is left")
            buffer.release()
            buffer.release() // ignored: already back
            val again = assertNotNull(pool.tryAcquire())
            assertNull(pool.tryAcquire(), "an extra release did not create a permit")
            again.release()
        }
}

/** The per-file rows of [TransferProgress.files]: clamped byte counts, sanitized names, cached snapshots. */
class FileProgressTableTest {
    private val files =
        listOf(
            FileEntry(0, "../../evil‮gnp.exe", 100, "application/x-msdownload"),
            FileEntry(1, "app.apk", 50, "application/vnd.android.package-archive"),
            FileEntry(2, "photo.jpg", 10, "image/jpeg"),
        )

    @Test
    fun `bytes are clamped to the file size and statuses follow`() {
        val table = FileProgressTable(files, receiver = true)
        table.addBytes(0, 60)
        assertEquals(FileStatus.IN_PROGRESS, table.statusOf(0))
        table.addBytes(0, 60)
        assertEquals(100, table.bytesOf(0))
        table.addBytes(0, -500)
        assertEquals(0, table.bytesOf(0))
        table.setStatus(2, FileStatus.DONE)
        assertEquals(10, table.bytesOf(2), "done means every byte")
        table.failUnfinished()
        assertEquals(listOf(FileStatus.FAILED, FileStatus.FAILED, FileStatus.DONE), table.snapshot().map { it.status })
    }

    @Test
    fun `the receiver shows sanitized names and flags executables and packages`() {
        val rows = FileProgressTable(files, receiver = true).snapshot()
        assertFalse(rows[0].name.contains('/') || rows[0].name.contains('‮'), rows[0].name)
        assertTrue(rows[0].isExecutable)
        assertTrue(rows[1].isAndroidPackage)
        assertFalse(rows[2].isExecutable || rows[2].isAndroidPackage)
        val sender = FileProgressTable(files, receiver = false).snapshot()
        assertEquals(files[0].name, sender[0].name, "the sender shows its own names")
        assertFalse(sender[0].isExecutable)
    }

    @Test
    fun `snapshots are cached until something changes`() {
        val table = FileProgressTable(files, receiver = true)
        val first = table.snapshot()
        assertSame(first, table.snapshot())
        table.setSaved(2, "content://drop/2", "photo.jpg")
        val second = table.snapshot()
        assertEquals("content://drop/2", second[2].savedUri)
        assertEquals(null, first[2].savedUri, "an old snapshot does not change")
    }
}
