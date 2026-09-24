package com.constrivo.drop.platform.android.storage

import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.platform.android.AndroidClocks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On a device (lab, WP7e; N14, F-D3, T-23, T-27): received files are pending MediaStore items written in place and
 * published by clearing `IS_PENDING`, and a cancel removes them. Needs no permission (the app owns what it creates).
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "partials-test-${System.nanoTime()}")
    private val index = PendingItemIndex(root.toPath())
    private val resolver = AndroidPendingItemResolver(context)
    private val published = ArrayList<String>()
    private val id = TransferId(Random.nextBytes(TransferId.SIZE))

    @After
    fun cleanUp() {
        for (uri in published) runCatching { context.contentResolver.delete(uri.toUri(), null, null) }
        root.deleteRecursively()
    }

    private fun store(names: List<String>): MediaStoreFileStore {
        val catalog = ReceiveCatalog().apply { record(id, names.mapIndexed { i, n -> FileEntry(i, n, 0) }) }
        return MediaStoreFileStore(
            ReceiveDestination.MediaStoreVolume(),
            resolver,
            index,
            catalog,
            AndroidPendingItemResolver::mimeForExtension,
            AndroidClocks.wall,
        )
    }

    @Test
    fun aDocumentIsWrittenInPlaceAndPublishedToDownloads() =
        runBlocking {
            val name = "drop-lab-${System.nanoTime()}.txt"
            val store = store(listOf(name))
            val bytes = Random.nextBytes(300_000)
            val partial = store.openPartial(id.toHex(), 0, bytes.size.toLong())
            // Out of order, as streams deliver units (N14: positional writes, nothing copied).
            partial.write(150_000, bytes, 150_000, 150_000)
            partial.write(0, bytes, 0, 150_000)
            partial.sync()
            assertEquals(bytes.size.toLong(), partial.length())
            assertTrue(store.freeBytes() > 0)
            println("drop-lab: primary volume removable=${store.destinationIsRemovable}, free=${store.freeBytes()}")

            val file = store.publish(partial, name, "text/plain", DropInfo(id.toHex(), 1, "Lab", AndroidClocks.wall.nowMillis()))
            published += file.uri
            assertEquals(name, file.name)
            val readBack = context.contentResolver.openInputStream(file.uri.toUri())!!.use { it.readBytes() }
            assertContentEquals(bytes, readBack)
            val pending =
                context.contentResolver.query(
                    file.uri.toUri(),
                    arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH),
                    null,
                    null,
                )!!.use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("Download/Drop/", c.getString(1))
                    c.getInt(0)
                }
            assertEquals(0, pending, "published")
        }

    @Test
    fun aCancelRemovesThePendingItem() =
        runBlocking {
            val name = "drop-lab-${System.nanoTime()}.jpg"
            val store = store(listOf(name))
            val partial = store.openPartial(id.toHex(), 0, 10)
            partial.write(0, ByteArray(10), 0, 10)
            val ref = checkNotNull(index.read(id.toHex(), 0))
            assertEquals(MediaCollection.IMAGES, ref.collection)
            assertTrue(resolver.exists(ref))
            store.deletePartials(id.toHex())
            assertFalse(resolver.exists(ref))
            assertFalse(root.resolve(id.toHex()).exists())
        }
}
