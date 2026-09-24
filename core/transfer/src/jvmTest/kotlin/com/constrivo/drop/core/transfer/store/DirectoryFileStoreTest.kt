package com.constrivo.drop.core.transfer.store

import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.core.transfer.StorageException
import com.constrivo.drop.core.transfer.TestSupport
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F-D4, F-D5 and design §9: partials, publishing with collision suffixes, per-drop subfolders, confinement. */
class DirectoryFileStoreTest {
    private val dir: Path = TestSupport.tempDir("store")
    private val store = DirectoryFileStore(dir.resolve("partials"), dir.resolve("received"), zone = ZoneOffset.UTC)
    private val small = DropInfo("t1", fileCount = 2, senderName = "Asha", receivedAtMillis = 0)

    @AfterTest
    fun cleanUp() = TestSupport.deleteTree(dir)

    private suspend fun partialWith(
        transferId: String,
        index: Int,
        bytes: ByteArray,
    ) = store.openPartial(transferId, index, bytes.size.toLong()).also {
        it.write(0, bytes, 0, bytes.size)
        it.sync()
    }

    @Test
    fun `a partial is written, read back, reopened and published under its name`() =
        runTest {
            val bytes = TestSupport.randomBytes(700_000, 1)
            val partial = partialWith("t1", 0, bytes)
            assertEquals(bytes.size.toLong(), partial.length())
            val back = ByteArray(bytes.size)
            var got = 0
            while (got < back.size) got += partial.read(got.toLong(), back, got, back.size - got)
            assertContentEquals(bytes, back)
            partial.close()
            val reopened = store.openPartial("t1", 0, bytes.size.toLong())
            assertEquals(bytes.size.toLong(), reopened.length(), "a reopened partial keeps its bytes (resume)")

            val published = store.publish(reopened, "photo.jpg", "image/jpeg", small)
            assertEquals("photo.jpg", published.name)
            val target = dir.resolve("received").resolve("photo.jpg")
            assertContentEquals(bytes, Files.readAllBytes(target))
            assertEquals(target.toUri().toString(), published.uri)
            assertFalse(Files.exists(store.partialPath("t1", 0)), "the partial moved")
        }

    @Test
    fun `a taken name gets a suffix and an unsafe name stays inside the destination`() =
        runTest {
            val first = store.publish(partialWith("t1", 0, byteArrayOf(1)), "a.txt", null, small)
            val second = store.publish(partialWith("t1", 1, byteArrayOf(2)), "a.txt", null, small)
            val third = store.publish(partialWith("t1", 2, byteArrayOf(3)), "../../a.txt", null, small)
            assertEquals(listOf("a.txt", "a (1).txt", "a (2).txt"), listOf(first.name, second.name, third.name))
            assertEquals(1, Files.readAllBytes(dir.resolve("received").resolve("a.txt"))[0].toInt(), "never replaced")
            assertTrue(Files.list(dir).use { it.noneMatch { p -> p.fileName.toString() == "a.txt" } }, "nothing escaped")
        }

    @Test
    fun `a drop of more than 20 files goes into one subfolder named after the sender and time`() =
        runTest {
            val drop = DropInfo("t2", fileCount = 21, senderName = "Asha: Pixel", receivedAtMillis = 86_400_000L + 3_723_000)
            val a = store.publish(partialWith("t2", 0, byteArrayOf(1)), "a.txt", null, drop)
            val b = store.publish(partialWith("t2", 1, byteArrayOf(2)), "b.txt", null, drop)
            val folder = dir.resolve("received").resolve("Asha_ Pixel 1970-01-02 01.02.03")
            val landed = Files.list(dir.resolve("received")).use { it.toList() }
            assertTrue(Files.isRegularFile(folder.resolve("a.txt")), "files land in $landed")
            assertTrue(Files.isRegularFile(folder.resolve("b.txt")))
            assertEquals(listOf("a.txt", "b.txt"), listOf(a.name, b.name))
        }

    @Test
    fun `deleting partials removes one file or the whole transfer`() =
        runTest {
            partialWith("t3", 0, byteArrayOf(1))
            partialWith("t3", 1, byteArrayOf(2))
            store.deletePartial("t3", 0)
            assertFalse(Files.exists(store.partialPath("t3", 0)))
            assertTrue(Files.exists(store.partialPath("t3", 1)))
            store.deletePartials("t3")
            assertFalse(Files.exists(store.partialPath("t3", 1).parent))
            assertTrue(store.freeBytes() > 0)
            assertFalse(store.destinationIsRemovable)
        }

    @Test
    fun `sources read files, and odd transfer ids or missing files are refused`() =
        runTest {
            val file = dir.resolve("in.bin")
            val bytes = TestSupport.randomBytes(300_000, 2)
            Files.write(file, bytes)
            val source = store.openSource(file.toUri().toString())
            assertEquals(bytes.size.toLong(), source.size)
            assertEquals("in.bin", source.name)
            val back = ByteArray(bytes.size)
            var got = 0
            while (got < back.size) got += source.read(got.toLong(), back, got, back.size - got)
            assertContentEquals(bytes, back)
            assertEquals(-1, source.read(bytes.size.toLong(), back, 0, 10))
            source.close()
            assertFailsWith<StorageException> { store.openSource(dir.resolve("missing").toString()) }
            assertFailsWith<IllegalArgumentException> { store.openPartial("../x", 0, 1) }
            assertEquals("image/jpeg", DirectoryFileStore.probeMime(Path.of("a.JPG")))
        }
}
