package com.constrivo.drop.web

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectoryUploadSinkTest {
    private val dir: Path = Files.createTempDirectory("drop-upload")
    private val sink = DirectoryUploadSink(dir)

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun upload(
        name: String,
        bytes: ByteArray,
    ): String {
        val writer = sink.open(name, bytes.size.toLong(), "application/octet-stream")
        writer.write(bytes, 0, bytes.size)
        return writer.commit()
    }

    @Test
    fun commitsUnderTheNameAndLeavesNoPartFile() {
        assertEquals("a.txt", upload("a.txt", "hello".toByteArray()))
        assertContentEquals("hello".toByteArray(), dir.resolve("a.txt").readBytes())
        assertEquals(listOf("a.txt"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun neverOverwritesAnExistingFile() {
        dir.resolve("a.txt").writeText("mine")
        assertEquals("a (2).txt", upload("a.txt", "one".toByteArray()))
        assertEquals("a (3).txt", upload("a.txt", "two".toByteArray()))
        assertEquals("mine", dir.resolve("a.txt").toFile().readText())
        assertEquals("two", dir.resolve("a (3).txt").toFile().readText())
    }

    @Test
    fun namesStayInsideTheDirectory() {
        assertEquals("passwd", upload("../../etc/passwd", "x".toByteArray()))
        assertTrue(Files.exists(dir.resolve("passwd")))
    }

    @Test
    fun abortRemovesThePartialFile() {
        val writer = sink.open("b.bin", 10, "application/octet-stream")
        writer.write(ByteArray(5), 0, 5)
        writer.abort()
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun partialFilesAreHiddenUntilCommitted() {
        val writer = sink.open("c.bin", 3, "application/octet-stream")
        writer.write(byteArrayOf(1, 2, 3), 0, 3)
        val names = dir.listDirectoryEntries().map { it.name }
        assertTrue(names.single().startsWith(".upload-") && names.single().endsWith(".part"), names.toString())
        writer.commit()
        assertEquals(listOf("c.bin"), dir.listDirectoryEntries().map { it.name })
    }
}
