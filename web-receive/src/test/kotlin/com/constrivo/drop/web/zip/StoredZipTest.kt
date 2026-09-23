package com.constrivo.drop.web.zip

import com.constrivo.drop.web.SharedFileChangedException
import com.constrivo.drop.web.crc32
import com.constrivo.drop.web.randomBytes
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoredZipTest {
    private val temp = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() = temp.forEach { it.deleteIfExists() }

    private val dosTime = StoredZipWriter.dosDateTime(1_790_164_800_000L, ZoneOffset.UTC)

    private fun zip(
        entries: List<Pair<String, ByteArray>>,
        forceZip64: Boolean = false,
    ): Pair<StoredZipLayout, ByteArray> {
        val layout = StoredZipLayout(entries.map { ZipEntrySpec(it.first, it.second.size.toLong()) }, forceZip64)
        val out = ByteArrayOutputStream()
        StoredZipWriter(layout, dosTime, bufferSize = 4096).write(out, { i -> ByteArrayInputStream(entries[i].second) })
        return layout to out.toByteArray()
    }

    private fun toFile(bytes: ByteArray): Path =
        Files.createTempFile("drop-zip", ".zip").also {
            temp.add(it)
            Files.write(it, bytes)
        }

    /** Reads every entry back with java.util.zip and checks content, sizes, method and CRC-32. */
    private fun verifyWithZipFile(
        bytes: ByteArray,
        expected: List<Pair<String, ByteArray>>,
    ) {
        ZipFile(toFile(bytes).toFile(), Charsets.UTF_8).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(expected.map { it.first }, entries.map { it.name })
            for ((entry, pair) in entries.zip(expected)) {
                assertEquals(ZipEntry.STORED, entry.method, entry.name)
                assertEquals(pair.second.size.toLong(), entry.size, entry.name)
                assertEquals(pair.second.size.toLong(), entry.compressedSize, entry.name)
                assertEquals(crc32(pair.second), entry.crc, entry.name)
                val content = zip.getInputStream(entry).use { it.readBytes() }
                assertContentEquals(pair.second, content, entry.name)
            }
        }
    }

    private fun le(
        bytes: ByteArray,
        offset: Int,
    ) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).position(offset) as ByteBuffer

    @Test
    fun multipleFilesEmptyFileAndUnicodeNamesReadBack() {
        val entries =
            listOf(
                "hello.txt" to "Hello, drop".toByteArray(),
                "empty.bin" to ByteArray(0),
                "Fotó 日本 📷.jpg" to randomBytes(300_000, 1),
                "नमस्ते.txt" to "हिंदी".toByteArray(),
            )
        val (layout, bytes) = zip(entries)
        assertEquals(layout.totalSize, bytes.size.toLong())
        assertTrue(!layout.usesZip64End)
        verifyWithZipFile(bytes, entries)
    }

    @Test
    fun localHeadersCarrySizesAndDataDescriptorsCarryCrc() {
        val data = randomBytes(1000, 2)
        val (_, bytes) = zip(listOf("a.bin" to data))
        val b = le(bytes, 0)
        assertEquals(0x04034b50, b.getInt(0))
        assertEquals(20, b.getShort(4).toInt())
        assertEquals(0x0808, b.getShort(6).toInt(), "data descriptor and UTF-8 flags")
        assertEquals(0, b.getShort(8).toInt(), "stored")
        assertEquals(0, b.getInt(14), "CRC follows the data")
        assertEquals(1000, b.getInt(18))
        assertEquals(1000, b.getInt(22))
        val descriptor = 30 + 5 + 1000
        assertEquals(0x08074b50, b.getInt(descriptor))
        assertEquals(crc32(data).toInt(), b.getInt(descriptor + 4))
        assertEquals(1000, b.getInt(descriptor + 8))
        assertEquals(1000, b.getInt(descriptor + 12))
        val central = descriptor + 16
        assertEquals(0x02014b50, b.getInt(central))
        assertEquals((3 shl 8) or 20, b.getShort(central + 4).toInt(), "made by Unix")
        assertEquals(0x81A40000.toInt(), b.getInt(central + 38), "regular file 0644")
        assertEquals(0, b.getInt(central + 42), "local header offset")
    }

    @Test
    fun dosTimestampIsPacked() {
        // 2026-09-23 12:00:00 UTC.
        val date = ((2026 - 1980) shl 9) or (9 shl 5) or 23
        val time = 12 shl 11
        assertEquals((date shl 16) or time, dosTime)
        assertEquals((1 shl 5 or 1) shl 16, StoredZipWriter.dosDateTime(0, ZoneOffset.UTC), "clamped to 1980")
        val (_, bytes) = zip(listOf("a" to ByteArray(1)))
        assertEquals(dosTime, le(bytes, 0).getInt(10))
        ZipFile(toFile(bytes).toFile()).use { zip ->
            val entry = zip.getEntry("a")
            assertEquals(2026, entry.timeLocal.year)
            assertEquals(12, entry.timeLocal.hour)
        }
    }

    @Test
    fun forcedZip64StructuresReadBack() {
        val entries = listOf("one.txt" to "1".toByteArray(), "two/Ω.bin" to randomBytes(70_000, 3), "zero" to ByteArray(0))
        val (layout, bytes) = zip(entries, forceZip64 = true)
        assertTrue(layout.usesZip64End)
        assertEquals(layout.totalSize, bytes.size.toLong())
        val b = le(bytes, 0)
        assertEquals(45, b.getShort(4).toInt())
        assertEquals(-1, b.getInt(18), "sizes moved to the ZIP64 extra field")
        assertEquals(1, b.getShort(30 + 7).toInt(), "ZIP64 extra field id")
        // The end record points at the ZIP64 end record through the locator.
        val end = bytes.size - 22
        assertEquals(0x06054b50, b.getInt(end))
        assertEquals(0xFFFF, b.getShort(end + 10).toInt() and 0xFFFF)
        assertEquals(0x07064b50, b.getInt(end - 20))
        val zip64End = b.getLong(end - 20 + 8).toInt()
        assertEquals(0x06064b50, b.getInt(zip64End))
        assertEquals(3L, b.getLong(zip64End + 32))
        verifyWithZipFile(bytes, entries)
    }

    @Test
    fun moreThan65534EntriesUseZip64() {
        val count = 65_540
        val entries = List(count) { "f$it" to if (it % 1000 == 0) byteArrayOf(it.toByte()) else ByteArray(0) }
        val (layout, bytes) = zip(entries)
        assertTrue(layout.usesZip64End)
        assertEquals(layout.totalSize, bytes.size.toLong())
        ZipFile(toFile(bytes).toFile()).use { zip ->
            assertEquals(count, zip.size())
            assertEquals(1000.toByte(), zip.getInputStream(zip.getEntry("f1000")).use { it.readBytes() }.single())
            assertEquals(0L, zip.getEntry("f65539").size)
        }
    }

    @Test
    fun aStreamThatEndsEarlyFailsTheArchive() {
        val layout = StoredZipLayout(listOf(ZipEntrySpec("short", 100)))
        assertFailsWith<SharedFileChangedException> {
            StoredZipWriter(layout, dosTime).write(ByteArrayOutputStream(), { ByteArrayInputStream(ByteArray(50)) })
        }
    }

    @Test
    fun extraBytesPastTheSizeAreNotRead() {
        val layout = StoredZipLayout(listOf(ZipEntrySpec("a", 10)))
        val out = ByteArrayOutputStream()
        StoredZipWriter(layout, dosTime).write(out, { ByteArrayInputStream(ByteArray(1000) { 7 }) })
        assertEquals(layout.totalSize, out.size().toLong())
        verifyWithZipFile(out.toByteArray(), listOf("a" to ByteArray(10) { 7 }))
    }

    @Test
    fun emptyEntriesAreNeverOpened() {
        val layout = StoredZipLayout(listOf(ZipEntrySpec("a", 0), ZipEntrySpec("b", 0)))
        var opened = 0
        StoredZipWriter(layout, dosTime).write(ByteArrayOutputStream(), {
            opened++
            ByteArrayInputStream(ByteArray(0))
        })
        assertEquals(0, opened)
    }

    @Test
    fun onBytesSeesEveryDataByte() {
        val entries = listOf("a" to randomBytes(10_000, 4), "b" to randomBytes(5, 5))
        val layout = StoredZipLayout(entries.map { ZipEntrySpec(it.first, it.second.size.toLong()) })
        var counted = 0L
        StoredZipWriter(layout, dosTime, 1024).write(ByteArrayOutputStream(), {
            ByteArrayInputStream(entries[it].second)
        }, { counted += it })
        assertEquals(10_005L, counted)
    }

    @Test
    fun entrySpecsAreValidated() {
        assertFailsWith<IllegalArgumentException> { ZipEntrySpec("", 1) }
        assertFailsWith<IllegalArgumentException> { ZipEntrySpec("a\u0000b", 1) }
        assertFailsWith<IllegalArgumentException> { ZipEntrySpec("a", -1) }
    }

    /**
     * A real 4 GiB+ entry: the local header, data descriptor and central directory all switch to ZIP64 and the
     * central directory offset overflows 32 bits. Writes about 4.3 GB, so it only runs with `-Pdrop.nightly=true`.
     */
    @Test
    fun entryLargerThan4GiBUsesZip64() {
        assumeTrue(System.getProperty("drop.nightly") == "true", "nightly only (-Pdrop.nightly=true)")
        val size = 0x1_0000_0000L + 12_345
        val dir = Files.createTempDirectory("drop-zip64")
        assumeTrue(Files.getFileStore(dir).usableSpace > size + (1L shl 30), "not enough disk space")
        val file = dir.resolve("big.zip").also { temp.add(it) }
        val layout = StoredZipLayout(listOf(ZipEntrySpec("small.txt", 3), ZipEntrySpec("big.bin", size), ZipEntrySpec("after.txt", 2)))
        assertTrue(layout.usesZip64End)
        file.outputStream().buffered(1 shl 20).use { out ->
            StoredZipWriter(layout, dosTime, 1 shl 20).write(out, { i ->
                when (i) {
                    0 -> ByteArrayInputStream("abc".toByteArray())
                    1 -> PatternStream(size)
                    else -> ByteArrayInputStream("xy".toByteArray())
                }
            })
        }
        assertEquals(layout.totalSize, Files.size(file))
        ZipFile(file.toFile()).use { zip ->
            val big = zip.getEntry("big.bin")
            assertEquals(size, big.size)
            val crc = CRC32()
            zip.getInputStream(big).use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                }
            }
            assertEquals(PatternStream.crc(size), crc.value)
            assertEquals(big.crc, crc.value)
            assertContentEquals("xy".toByteArray(), zip.getInputStream(zip.getEntry("after.txt")).use { it.readBytes() })
        }
        Files.deleteIfExists(file)
        Files.deleteIfExists(dir)
    }

    /** A deterministic byte pattern of [size] bytes without holding it in memory. */
    private class PatternStream(
        private val size: Long,
    ) : InputStream() {
        private var position = 0L

        override fun read(): Int = if (position >= size) -1 else byteAt(position++).toInt() and 0xFF

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (position >= size) return -1
            val n = minOf(len.toLong(), size - position).toInt()
            for (i in 0 until n) b[off + i] = byteAt(position + i)
            position += n
            return n
        }

        companion object {
            fun byteAt(p: Long): Byte = ((p * 31) xor (p ushr 13)).toByte()

            fun crc(size: Long): Long {
                val crc = CRC32()
                val stream = PatternStream(size)
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = stream.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                }
                return crc.value
            }
        }
    }
}
