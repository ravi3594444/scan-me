package com.constrivo.drop.platform.android.storage

import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.core.transfer.StorageException
import com.constrivo.drop.core.transfer.StorageFullException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MediaStoreFileStore] over [FakeResolver] (spec change N14): pending items created at the first write, written
 * positionally, published by clearing the pending flag under a free name, removed on cancel, found again after an app
 * restart through the app-private index, and the per-drop subfolder of design §9.
 */
class MediaStoreFileStoreTest {
    private val root = createTempDirectory("partials")
    private val resolver = FakeResolver()
    private val index = PendingItemIndex(root)
    private val at = LocalDateTime.of(2026, 9, 24, 10, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val clock = WallClock { at }
    private val id = TransferId(ByteArray(TransferId.SIZE) { (it + 1).toByte() })
    private val hex = id.toHex()

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store(
        destination: ReceiveDestination = ReceiveDestination.MediaStoreVolume(),
        catalog: ReceiveCatalog = ReceiveCatalog(),
    ) = MediaStoreFileStore(destination, resolver, index, catalog, TEST_MIME, clock, io = Dispatchers.Unconfined, zone = ZoneOffset.UTC)

    private fun catalogOf(vararg names: String): ReceiveCatalog =
        ReceiveCatalog().apply { record(id, names.mapIndexed { i, n -> FileEntry(i, n, 10, null) }) }

    private fun drop(count: Int) = DropInfo(hex, count, "Dev", at)

    @Test
    fun `a received photo is a pending gallery item until it is published`() =
        runTest {
            val store = store(catalog = catalogOf("photo.jpg", "report.pdf"))
            val partial = store.openPartial(hex, 0, 5)
            val spec = resolver.created.single()
            assertEquals(MediaCollection.IMAGES, spec.target.collection)
            assertEquals("Pictures/Drop/", spec.target.relativePath)
            val item = resolver.items.values.single()
            assertTrue(item.pending)
            assertEquals("photo.jpg", item.displayName)
            assertEquals(PendingState.OPEN, store.stateOf(hex, 0))

            partial.write(0, "hello".encodeToByteArray(), 0, 5)
            partial.sync()
            assertEquals(1, item.syncs)
            val back = ByteArray(5)
            assertEquals(5, partial.read(0, back, 0, 5))
            assertContentEquals("hello".encodeToByteArray(), back)

            val published = store.publish(partial, "photo.jpg", "image/jpeg", drop(2))
            assertEquals(item.uri, published.uri)
            assertEquals("photo.jpg", published.name)
            assertFalse(item.pending)
            assertEquals(0, item.openHandles)
            assertEquals(PendingState.PUBLISHED, store.stateOf(hex, 0))
            assertNull(index.read(hex, 0), "a published file keeps no record")

            store.openPartial(hex, 1, 3).let { pdf ->
                assertEquals(MediaCollection.DOWNLOADS, resolver.created.last().target.collection)
                assertEquals("Download/Drop/", resolver.created.last().target.relativePath)
                pdf.close()
            }
        }

    @Test
    fun `a taken name gets a numbered suffix instead of replacing the user's file`() =
        runTest {
            val first = store(catalog = catalogOf("photo.jpg", "photo.jpg"))
            val a = first.openPartial(hex, 0, 1).also { it.write(0, byteArrayOf(1), 0, 1) }
            val b = first.openPartial(hex, 1, 1).also { it.write(0, byteArrayOf(2), 0, 1) }
            assertEquals("photo.jpg", first.publish(a, "photo.jpg", "image/jpeg", drop(2)).name)
            assertEquals("photo (1).jpg", first.publish(b, "photo.jpg", "image/jpeg", drop(2)).name)
            assertEquals(listOf("photo.jpg", "photo (1).jpg"), resolver.published.map { it.displayName })
        }

    @Test
    fun `a transfer resumed after an app restart writes into the same item`() =
        runTest {
            val before = store(catalog = catalogOf("clip.mp4"))
            before.openPartial(hex, 0, 6).apply {
                write(0, "abc".encodeToByteArray(), 0, 3)
                sync()
                close()
            }
            assertEquals(PendingState.CLOSED, before.stateOf(hex, 0))

            // A new process: a new store and catalog over the same app-private index.
            val after = store(catalog = catalogOf("clip.mp4"))
            val partial = after.openPartial(hex, 0, 6)
            assertEquals(1, resolver.created.size, "no second item")
            assertEquals(3, partial.length())
            partial.write(3, "def".encodeToByteArray(), 0, 3)
            val published = after.publish(partial, "clip.mp4", "video/mp4", drop(1))
            assertContentEquals("abcdef".encodeToByteArray(), resolver.item(published.uri).data)
            assertEquals("Movies/Drop/", resolver.item(published.uri).relativePath)
        }

    @Test
    fun `an item that disappeared while the app was away is created again`() =
        runTest {
            store(catalog = catalogOf("song.mp3")).openPartial(hex, 0, 4).close()
            val lost = checkNotNull(index.read(hex, 0))
            resolver.items.remove(lost.uri)

            val partial = store(catalog = catalogOf("song.mp3")).openPartial(hex, 0, 4)
            assertEquals(2, resolver.created.size)
            assertEquals(0, partial.length())
            assertNotEquals(lost, index.read(hex, 0))
            partial.close()
        }

    @Test
    fun `a record that outlived its publish never reopens the published file`() =
        runTest {
            val store = store(catalog = catalogOf("photo.jpg"))
            val partial = store.openPartial(hex, 0, 1)
            val ref = checkNotNull(index.read(hex, 0))
            store.publish(partial, "photo.jpg", "image/jpeg", drop(1))
            // The record's removal was lost (a crash right after the publish).
            index.write(hex, 0, ref)

            val reopened = store(catalog = catalogOf("photo.jpg")).openPartial(hex, 0, 1)
            reopened.write(0, byteArrayOf(9), 0, 1)
            assertEquals(2, resolver.created.size, "a new pending item, not the published one")
            assertTrue(resolver.item(ref.uri).data.isEmpty())
            reopened.close()
        }

    @Test
    fun `a cancel deletes the pending items and records but never a published file`() =
        runTest {
            val store = store(catalog = catalogOf("a.jpg", "b.jpg"))
            val done = store.openPartial(hex, 0, 1).also { it.write(0, byteArrayOf(1), 0, 1) }
            val kept = store.publish(done, "a.jpg", "image/jpeg", drop(2))
            store.openPartial(hex, 1, 1).write(0, byteArrayOf(2), 0, 1)

            store.deletePartials(hex)
            assertEquals(listOf(kept.uri), resolver.items.keys.toList())
            assertFalse(Files.exists(index.directoryOf(hex)))
            assertEquals(PendingState.ABSENT, store.stateOf(hex, 1))
        }

    @Test
    fun `partials an earlier run created are deleted through the index`() =
        runTest {
            val earlier = store(catalog = catalogOf("a.pdf", "b.pdf"))
            earlier.openPartial(hex, 0, 1).close()
            earlier.openPartial(hex, 1, 1).close()
            assertEquals(2, resolver.items.size)

            store().deletePartials(hex)
            assertTrue(resolver.items.isEmpty())
            assertFalse(Files.exists(index.directoryOf(hex)))
        }

    @Test
    fun `deleting one partial removes its item and lets the file start over`() =
        runTest {
            val store = store(catalog = catalogOf("a.txt"))
            store.openPartial(hex, 0, 2).write(0, byteArrayOf(1, 2), 0, 2)
            store.deletePartial(hex, 0)
            assertTrue(resolver.items.isEmpty())
            assertNull(index.read(hex, 0))
            val again = store.openPartial(hex, 0, 2)
            assertEquals(0, again.length())
            again.close()
        }

    @Test
    fun `a published file cannot be opened or written again`() =
        runTest {
            val store = store(catalog = catalogOf("a.txt"))
            val partial = store.openPartial(hex, 0, 1)
            store.publish(partial, "a.txt", "text/plain", drop(1))
            assertFailsWith<StorageException> { partial.write(0, byteArrayOf(1), 0, 1) }
            assertFailsWith<StorageException> { partial.sync() }
            assertFailsWith<StorageException> { store.openPartial(hex, 0, 1) }
        }

    @Test
    fun `a full volume is reported as such`() =
        runTest {
            resolver.capacity = 4
            val partial = store(catalog = catalogOf("big.pdf")).openPartial(hex, 0, 8)
            partial.write(0, ByteArray(4), 0, 4)
            assertFailsWith<StorageFullException> { partial.write(4, ByteArray(4), 0, 4) }
        }

    @Test
    fun `an item whose creation fails leaves no record behind`() =
        runTest {
            resolver.failNextCreate = IOException("MediaStore refused")
            val store = store(catalog = catalogOf("a.jpg"))
            val e = assertFailsWith<StorageException> { store.openPartial(hex, 0, 1) }
            assertTrue("MediaStore refused" in e.message.orEmpty())
            assertNull(index.read(hex, 0))
            assertTrue(resolver.items.isEmpty())
        }

    @Test
    fun `a drop of more than twenty files lands in one subfolder, also after a restart`() =
        runTest {
            val names = Array(21) { "img$it.jpg" }
            val catalog = catalogOf(*names).apply { setSender(id, "Dev") }
            store(catalog = catalog).openPartial(hex, 0, 1).close()
            val folder = "Dev 2026-09-24 10.00.00"
            assertEquals(folder, resolver.created.single().subfolder)
            assertEquals("Pictures/Drop/$folder/", resolver.created.single().target.relativePath)

            // After a restart the resume record gives the file list back, but not the sender: the folder comes from the
            // index, and a publish naming another sender or time does not move the drop.
            val later = store(catalog = catalogOf(*names))
            val partial = later.openPartial(hex, 1, 1)
            assertEquals(folder, resolver.created.last().subfolder)
            val published = later.publish(partial, "img1.jpg", "image/jpeg", DropInfo(hex, 21, "Somebody else", at + 60_000))
            assertEquals("Pictures/Drop/$folder/", resolver.item(published.uri).relativePath)
        }

    @Test
    fun `a media row keeps the collection it was created in`() =
        runTest {
            val store = store(catalog = catalogOf("clip.mp4"))
            val partial = store.openPartial(hex, 0, 1)
            val published = store.publish(partial, "clip.txt", "text/plain", drop(1))
            val item = resolver.item(published.uri)
            assertEquals(MediaCollection.VIDEO, item.collection)
            assertEquals("Movies/Drop/", item.relativePath)
            assertEquals("clip.txt", item.displayName)
        }

    @Test
    fun `a picked folder gets a hidden temporary document that is renamed at publish`() =
        runTest {
            val tree = ReceiveDestination.DocumentTree("content://tree/primary%3ADrop")
            val store = store(tree, catalogOf("notes.txt"))
            val partial = store.openPartial(hex, 0, 2)
            val item = resolver.items.values.single()
            assertTrue(item.displayName.startsWith(".drop-") && item.displayName.endsWith(".part"))
            partial.write(0, "hi".encodeToByteArray(), 0, 2)
            val published = store.publish(partial, "notes.txt", "text/plain", drop(1))
            assertEquals("notes.txt", published.name)
            assertEquals("notes.txt", item.displayName)
            assertFalse(item.pending)
        }

    @Test
    fun `free space and removability come from the destination`() =
        runTest {
            resolver.free = 1234
            resolver.removable = true
            val store = store()
            assertEquals(1234, store.freeBytes())
            assertTrue(store.destinationIsRemovable)
        }

    @Test
    fun `a file whose name is not known yet still gets an item`() =
        runTest {
            val partial = store().openPartial(hex, 4, 1)
            val spec = resolver.created.single()
            assertEquals("file-4", spec.target.displayName)
            assertEquals(MediaCollection.DOWNLOADS, spec.target.collection)
            val published = store().publish(partial, "late.jpg", "image/jpeg", drop(5))
            // Created in Downloads without a type, it stays there under the name the engine gives it.
            assertEquals("late.jpg", published.name)
            assertEquals("Download/Drop/", resolver.item(published.uri).relativePath)
        }
}
