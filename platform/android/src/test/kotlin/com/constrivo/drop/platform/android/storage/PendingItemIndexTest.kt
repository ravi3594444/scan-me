package com.constrivo.drop.platform.android.storage

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app-private records of pending items (N14: "the resume manifest stays app-private") and the lifecycle reducer. */
class PendingItemIndexTest {
    private val root = createTempDirectory("partials")
    private val index = PendingItemIndex(root)
    private val id = "0123456789abcdef0123456789abcdef"
    private val media =
        PendingItemRef("content://media/external_primary/images/media/7", PendingItemKind.MEDIA_STORE, MediaCollection.IMAGES, "image/jpeg")
    private val document =
        PendingItemRef(
            "content://tree/primary%3ADrop/document/9",
            PendingItemKind.DOCUMENT,
            null,
            "application/pdf",
            "content://tree/primary%3ADrop/document/primary%3ADrop",
        )

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `records round-trip and list by file index`() {
        index.write(id, 0, media)
        index.write(id, 3, document)
        assertEquals(media, index.read(id, 0))
        assertEquals(document, index.read(id, 3))
        assertNull(index.read(id, 1))
        assertEquals(mapOf(0 to media, 3 to document), index.all(id))
        index.remove(id, 0)
        assertEquals(mapOf(3 to document), index.all(id))
    }

    @Test
    fun `a record is replaced atomically and never left as a temporary file`() {
        index.write(id, 0, media)
        index.write(id, 0, document)
        assertEquals(document, index.read(id, 0))
        val names = Files.list(index.directoryOf(id)).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("0.item"), names)
    }

    @Test
    fun `unreadable records count as absent and are skipped by the listing`() {
        val dir = index.directoryOf(id)
        Files.createDirectories(dir)
        dir.resolve("0.item").writeText("drop-pending-item 1\nkind=spaceship\ncollection=\nmime=a/b\nuri=content://x\nparent=\n")
        dir.resolve("1.item").writeText("not a record")
        dir.resolve("resume.plan").writeText("the resume plan lives here too")
        dir.resolve("x.item").writeText(PendingItemIndex.formatItem(media))
        assertNull(index.read(id, 0))
        assertNull(index.read(id, 1))
        assertEquals(emptyMap(), index.all(id))
    }

    @Test
    fun `malformed records are refused with a format exception`() {
        val good = PendingItemIndex.formatItem(media)
        assertEquals(media, PendingItemIndex.parseItem(good))
        val broken =
            listOf(
                "",
                good.replace("drop-pending-item 1", "drop-pending-item 2"),
                good.replace("kind=media", "kind=tape"),
                good.replace("collection=IMAGES", "collection=PAINTINGS"),
                good.replace("collection=IMAGES", "collection="),
                good.replace("uri=content://", "uri=file://"),
                good.replace("mime=image/jpeg\n", ""),
                good + "kind=media\n",
                good + "no equals sign\n",
            )
        for (text in broken) assertFailsWith<PendingIndexFormatException>(text) { PendingItemIndex.parseItem(text) }
    }

    @Test
    fun `the drop folder is remembered, including the choice of none`() {
        assertNull(index.dropFolder(id))
        index.writeDropFolder(id, PendingItemIndex.DropFolder.NONE)
        assertEquals(PendingItemIndex.DropFolder.NONE, index.dropFolder(id))
        index.writeDropFolder(id, PendingItemIndex.DropFolder("Dev 2026-09-24 10.00.00"))
        assertEquals("Dev 2026-09-24 10.00.00", index.dropFolder(id)?.name)
        assertFailsWith<PendingIndexFormatException> { PendingItemIndex.parseFolder("drop-folder 1\nname=a/b\n") }
    }

    @Test
    fun `deleting a transfer removes its records, folder choice and resume plan`() {
        index.write(id, 0, media)
        index.writeDropFolder(id, PendingItemIndex.DropFolder.NONE)
        index.directoryOf(id).resolve("resume.plan").writeText("plan")
        index.deleteTransfer(id)
        assertFalse(Files.exists(index.directoryOf(id)))
        index.deleteTransfer(id)
        assertTrue(Files.isDirectory(root))
    }

    @Test
    fun `only plain transfer ids name a directory`() {
        for (bad in listOf("", "../escape", "a/b", "a.b", "x".repeat(65))) {
            assertFailsWith<IllegalArgumentException>(bad) { index.directoryOf(bad) }
        }
        assertFailsWith<IllegalArgumentException> { index.read(id, -1) }
    }

    @Test
    fun `the pending-item lifecycle allows exactly the documented steps`() {
        val next = PendingItemLifecycle::next
        assertEquals(PendingState.OPEN, next(PendingState.ABSENT, PendingEvent.OPEN))
        assertEquals(PendingState.OPEN, next(PendingState.CLOSED, PendingEvent.OPEN))
        assertEquals(PendingState.CLOSED, next(PendingState.OPEN, PendingEvent.CLOSE))
        assertEquals(PendingState.CLOSED, next(PendingState.CLOSED, PendingEvent.CLOSE))
        assertEquals(PendingState.PUBLISHED, next(PendingState.OPEN, PendingEvent.PUBLISH))
        assertEquals(PendingState.PUBLISHED, next(PendingState.CLOSED, PendingEvent.PUBLISH))
        assertEquals(PendingState.PUBLISHED, next(PendingState.PUBLISHED, PendingEvent.CLOSE))
        for (state in listOf(PendingState.ABSENT, PendingState.OPEN, PendingState.CLOSED, PendingState.DELETED)) {
            assertEquals(PendingState.DELETED, next(state, PendingEvent.DELETE))
        }
        val refused =
            listOf(
                PendingState.PUBLISHED to PendingEvent.OPEN,
                PendingState.DELETED to PendingEvent.OPEN,
                PendingState.PUBLISHED to PendingEvent.PUBLISH,
                PendingState.ABSENT to PendingEvent.PUBLISH,
                PendingState.DELETED to PendingEvent.PUBLISH,
                PendingState.PUBLISHED to PendingEvent.DELETE,
            )
        for ((state, event) in refused) {
            val e = assertFailsWith<PendingItemStateException>("$state $event") { next(state, event) }
            assertEquals(state, e.state)
            assertEquals(event, e.event)
        }
    }

    @Test
    fun `refs keep their invariants`() {
        assertFailsWith<IllegalArgumentException> {
            PendingItemRef("file:///sdcard/x", PendingItemKind.MEDIA_STORE, MediaCollection.IMAGES, "image/png")
        }
        assertFailsWith<IllegalArgumentException> { PendingItemRef("content://m/1", PendingItemKind.MEDIA_STORE, null, "image/png") }
        assertFailsWith<IllegalArgumentException> { PendingItemRef("content://d/1", PendingItemKind.DOCUMENT, null, "image/png") }
        assertFailsWith<IllegalArgumentException> {
            PendingItemRef("content://m/1\nuri=x", PendingItemKind.MEDIA_STORE, MediaCollection.IMAGES, "a/b")
        }
    }
}
