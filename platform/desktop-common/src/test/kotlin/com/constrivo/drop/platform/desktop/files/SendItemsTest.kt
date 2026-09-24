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
    fun `the walk stops at the limit in a deterministic order and says it truncated`() {
        for (folder in listOf("b", "a", "c")) for (i in 9 downTo 0) file("big/$folder/$i.bin")
        val limited = SendItems.expand(listOf(root.resolve("big")), limit = 12)
        assertTrue(limited.truncated)
        assertEquals((0..9).map { "big/a/$it.bin" } + listOf("big/b/0.bin", "big/b/1.bin"), limited.files.map { it.name })
        val all = SendItems.expand(listOf(root.resolve("big")), limit = 30)
        assertEquals(false, all.truncated, "exactly the limit is not truncated")
        assertEquals(30, all.files.size)
    }

    @Test
    fun `a name too long for the protocol loses leading folders, never the file name`() {
        val deep = (1..60).map { "папка-$it" } // Cyrillic: two UTF-8 bytes a character, about 1,000 bytes of folders
        val name = SendItems.sendName(deep + "IMG_0001.jpg")
        assertTrue(name.encodeToByteArray().size <= 1024, "${name.encodeToByteArray().size} bytes")
        assertTrue(name.endsWith("/IMG_0001.jpg"), name)
        assertTrue(name.startsWith("папка-"), "whole folders are dropped, not cut: $name")
        assertEquals("a/b.txt", SendItems.sendName(listOf("a", "b.txt")))
        assertEquals("only.txt", SendItems.sendName(listOf("only.txt"), maxBytes = 3), "the file name itself always stays")
        val tree = file((1..40).joinToString("/") { "Ordner-mit-langem-Namen-$it" } + "/photo.jpg")
        val sent = SendItems.expand(listOf(root.resolve("Ordner-mit-langem-Namen-1"))).files.single()
        assertEquals(tree, sent.path)
        assertTrue(sent.name.endsWith("/photo.jpg") && sent.name.encodeToByteArray().size <= 1024, sent.name)
    }

    @Test
    fun `an interrupted walk stops with InterruptedException`() {
        file("stop/a.txt")
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> { SendItems.expand(listOf(root.resolve("stop"))) }
        } finally {
            Thread.interrupted()
        }
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
