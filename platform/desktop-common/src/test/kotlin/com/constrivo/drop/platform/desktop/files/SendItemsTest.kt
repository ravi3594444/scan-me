package com.constrivo.drop.platform.desktop.files

import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendItemsTest {
    private val root: Path = Files.createTempDirectory("drop-send-")

    @AfterTest
    fun cleanUp() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun file(
        relative: String,
        size: Int = 3,
    ): Path = root.resolve(relative).also { Files.createDirectories(it.parent) }.also { Files.write(it, ByteArray(size)) }

    @Test
    fun `F-C6 folders expand recursively under their own name, in path order, after loose files keep their place`() {
        val loose = file("notes.txt", 10)
        file("Trip/b.jpg")
        file("Trip/a-b.jpg")
        file("Trip/a/z.jpg")
        file("Trip/day 2/c.jpg", 7)
        Files.createDirectories(root.resolve("Trip/empty"))
        val items = SendItems.expand(listOf(loose, root.resolve("Trip")))
        assertEquals(
            listOf("notes.txt", "Trip/a/z.jpg", "Trip/a-b.jpg", "Trip/b.jpg", "Trip/day 2/c.jpg"),
            items.files.map { it.name },
        )
        assertEquals(10L, items.files.first().size)
        assertEquals(7L, items.files.last().size)
        assertTrue(items.skipped.isEmpty())
        assertEquals(false, items.truncated)
    }

    @Test
    fun `the same file dropped twice is sent once and the limit truncates`() {
        val a = file("x/a.txt")
        file("x/b.txt")
        file("x/c.txt")
        val items = SendItems.expand(listOf(a, root.resolve("x"), a))
        assertEquals(listOf("a.txt", "x/b.txt", "x/c.txt"), items.files.map { it.name })
        val limited = SendItems.expand(listOf(root.resolve("x")), limit = 2)
        assertEquals(2, limited.files.size)
        assertTrue(limited.truncated)
        assertFailsWith<IllegalArgumentException> { SendItems.expand(emptyList(), limit = 0) }
    }

    @Test
    fun `symbolic links and missing paths are skipped, never followed`() {
        val target = file("outside/secret.txt")
        file("album/one.jpg")
        val linkedFile = root.resolve("album/link.txt")
        val linkedDir = root.resolve("album/loop")
        try {
            Files.createSymbolicLink(linkedFile, target)
            Files.createSymbolicLink(linkedDir, root)
        } catch (_: Exception) {
            return // a file system without symbolic links
        }
        val items = SendItems.expand(listOf(root.resolve("album"), root.resolve("missing.txt"), linkedFile))
        assertEquals(listOf("album/one.jpg"), items.files.map { it.name })
        assertTrue(items.skipped.containsAll(listOf(linkedFile, linkedDir, root.resolve("missing.txt"))), items.skipped.toString())
    }

    @Test
    fun `a file to send needs a name and a size`() {
        assertFailsWith<IllegalArgumentException> { SendFile(root, "", 1) }
        assertFailsWith<IllegalArgumentException> { SendFile(root, "a", -1) }
    }

    @Test
    fun `published files are marked, reported, and stay published when marking fails`() =
        runBlocking<Unit> {
            val marked = ArrayList<Pair<Path, String?>>()
            val events = ArrayList<PublishedFileEvent>()
            val errors = ArrayList<Path>()
            var fail = false
            val store =
                MarkingFileStore(
                    DirectoryFileStore(root.resolve("partials"), root.resolve("Received")),
                    { path, sender ->
                        if (fail) error("no xattr")
                        marked.add(path to sender)
                    },
                    onMarkError = { path, _ -> errors.add(path) },
                    onPublished = { events += it },
                )
            val drop = DropInfo("0a0b", 2, "Alice", 0)
            for ((index, name) in listOf("a.txt", "b.txt").withIndex()) {
                fail = index == 1
                val partial = store.openPartial("0a0b", index, 3)
                partial.write(0, byteArrayOf(1, 2, 3), 0, 3)
                partial.sync()
                val published = store.publish(partial, name, "text/plain", drop)
                val path = assertNotNull(MarkingFileStore.pathOf(published.uri))
                assertTrue(Files.isRegularFile(path))
            }
            val expectedMark: Pair<Path, String?> = Pair(root.resolve("Received/a.txt"), "Alice")
            assertEquals(listOf(expectedMark), marked.toList())
            assertEquals(listOf<Path>(root.resolve("Received/b.txt")), errors.toList())
            assertEquals(listOf(0, 1), events.map { it.fileIndex })
            assertEquals("text/plain", events.first().mimeType)
            assertEquals(root.resolve("Received/b.txt"), events.last().path)
            assertNull(MarkingFileStore.pathOf("content://media/1"))
            assertNull(MarkingFileStore.pathOf("::not a uri"))
        }
}
