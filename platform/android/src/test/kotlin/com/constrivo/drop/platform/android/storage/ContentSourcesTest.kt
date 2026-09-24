package com.constrivo.drop.platform.android.storage

import com.constrivo.drop.core.transfer.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Sources read from `content:` URIs (architecture §10.1 "read via SAF/MediaStore URIs"): seekable and stream-only. */
class ContentSourcesTest {
    private val provider = FakeSources()
    private val sources = ContentSources(provider, Dispatchers.Unconfined)
    private val bytes = ByteArray(700_000) { (it % 251).toByte() }

    private fun add(
        uri: String,
        facts: SourceFacts?,
        seekable: Boolean,
    ) {
        provider.sources[uri] = FakeSources.Source(facts, bytes, seekable)
    }

    @Test
    fun `a seekable source reads any range, in slices`() =
        runTest {
            add("content://media/1", SourceFacts("IMG_1.jpg", bytes.size.toLong(), "image/jpeg", 42), seekable = true)
            val source = sources.open("content://media/1")
            assertEquals("IMG_1.jpg", source.name)
            assertEquals(bytes.size.toLong(), source.size)
            assertEquals("image/jpeg", source.mimeType)
            assertEquals(42, source.modifiedMillis)

            val buffer = ByteArray(1 shl 20)
            val n = source.read(0, buffer, 0, buffer.size)
            assertEquals(256 * 1024, n, "reads are sliced")
            val tail = ByteArray(100)
            assertEquals(100, source.read(bytes.size - 100L, tail, 0, 100))
            assertContentEquals(bytes.copyOfRange(bytes.size - 100, bytes.size), tail)
            assertEquals(-1, source.read(bytes.size.toLong(), tail, 0, 1))
            // Random access again after an earlier position: still one descriptor.
            assertEquals(10, source.read(5, tail, 0, 10))
            assertEquals(1, provider.sources.getValue("content://media/1").descriptorsOpened)
            source.close()
            assertEquals(10, source.read(5, tail, 0, 10))
            assertEquals(2, provider.sources.getValue("content://media/1").descriptorsOpened, "reopened after close")
        }

    @Test
    fun `a stream-only source skips ahead and reopens to go back`() =
        runTest {
            add("content://share/1", SourceFacts("notes.txt", bytes.size.toLong(), "text/plain"), seekable = false)
            val source = sources.open("content://share/1")
            val out = ByteArray(1000)
            assertEquals(1000, source.read(0, out, 0, 1000))
            assertContentEquals(bytes.copyOfRange(0, 1000), out)
            assertEquals(1000, source.read(300_000, out, 0, 1000))
            assertContentEquals(bytes.copyOfRange(300_000, 301_000), out)
            assertEquals(1, provider.sources.getValue("content://share/1").streamsOpened)
            assertEquals(1000, source.read(10, out, 0, 1000))
            assertContentEquals(bytes.copyOfRange(10, 1010), out)
            assertEquals(2, provider.sources.getValue("content://share/1").streamsOpened)
        }

    @Test
    fun `the picker's name wins, then the provider's, then the URI's last segment`() =
        runTest {
            add("content://a/doc%201", SourceFacts("provider.pdf", 10, null), seekable = true)
            add("content://a/doc2", SourceFacts(null, 10, null), seekable = true)
            assertEquals("picked.pdf", sources.open("content://a/doc%201", name = "picked.pdf").name)
            assertEquals("provider.pdf", sources.open("content://a/doc%201", name = " ").name)
            assertEquals("doc2", sources.open("content://a/doc2").name)
        }

    @Test
    fun `the size comes from the provider, else the descriptor, else the picker`() =
        runTest {
            add("content://a/seek", SourceFacts("a", null, null), seekable = true)
            add("content://a/stream", SourceFacts("b", null, null), seekable = false)
            assertEquals(bytes.size.toLong(), sources.open("content://a/seek").size)
            assertEquals(123, sources.open("content://a/stream", expectedSize = 123).size)
            val unknown = assertFailsWith<StorageException> { sources.open("content://a/stream") }
            assertTrue("size" in unknown.message.orEmpty())
        }

    @Test
    fun `an unreadable source is a storage error`() =
        runTest {
            assertFailsWith<StorageException> { sources.open("content://nowhere/1") }
            add("content://a/gone", null, seekable = true)
            assertFailsWith<StorageException> { sources.open("content://a/gone") }
        }
}
