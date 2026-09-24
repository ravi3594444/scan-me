package com.constrivo.drop.platform.desktop

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.SoftwareIdentityKeyStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSecretStorageTest {
    private val dir: Path = Files.createTempDirectory("drop-secrets-")

    @AfterTest
    fun cleanUp() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `values round-trip, are replaced and deleted, and survive a new instance`() {
        val store = FileSecretStorage(dir.resolve("s"))
        assertNull(store.get("drop.identity.ed25519.v1"))
        store.put("drop.identity.ed25519.v1", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store.get("drop.identity.ed25519.v1"))
        store.put("drop.identity.ed25519.v1", byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), FileSecretStorage(dir.resolve("s")).get("drop.identity.ed25519.v1"))
        assertEquals(setOf("drop.identity.ed25519.v1"), store.names())
        store.delete("drop.identity.ed25519.v1")
        store.delete("drop.identity.ed25519.v1")
        assertNull(store.get("drop.identity.ed25519.v1"))
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun `arrays are copied on the way in and out`() {
        val store = FileSecretStorage(dir.resolve("s"))
        val value = byteArrayOf(5, 6)
        store.put("k", value)
        value[0] = 0
        val read = store.get("k")!!
        read[1] = 0
        assertContentEquals(byteArrayOf(5, 6), store.get("k"))
    }

    @Test
    fun `files and the directory are owner-only and no temporary file is left`() {
        val store = FileSecretStorage(dir.resolve("s"))
        store.put("drop.database-key.v1", ByteArray(33) { it.toByte() })
        assertTrue(OwnerOnlyFiles.isRestricted(store.directory))
        assertTrue(OwnerOnlyFiles.isRestricted(store.pathOf("drop.database-key.v1")))
        assertEquals(1, Files.list(store.directory).use { it.count() }.toInt())
    }

    @Test
    fun `names are encoded readably and without case collisions`() {
        assertEquals("drop.identity.ed25519.v1", FileSecretStorage.encodeName("drop.identity.ed25519.v1"))
        assertEquals("%41bc", FileSecretStorage.encodeName("Abc"))
        assertEquals("%2Ehidden", FileSecretStorage.encodeName(".hidden"))
        assertEquals("a%2Fb", FileSecretStorage.encodeName("a/b"))
        for (name in listOf("Abc", "abc", "é", "a/b", ".x", "drop.advertising-secret.v1")) {
            assertEquals(name, FileSecretStorage.decodeName(FileSecretStorage.encodeName(name)))
        }
        assertNull(FileSecretStorage.decodeName("bad%4"))
        assertNull(FileSecretStorage.decodeName("UPPER"))
        assertNull(FileSecretStorage.decodeName("%zz"))
        assertFailsWith<IllegalArgumentException> { FileSecretStorage.encodeName("") }
        assertFailsWith<IllegalArgumentException> { FileSecretStorage.encodeName("x".repeat(65)) }
    }

    @Test
    fun `malformed files raise SecretStorageException and are left untouched`() {
        val store = FileSecretStorage(dir.resolve("s"))
        val path = store.pathOf("k")
        for (bytes in listOf(ByteArray(3), "NOPE0000000000".toByteArray(), byteArrayOf(68, 83, 69, 67, 9, 0, 0, 0, 0, 1, 7))) {
            Files.write(path, bytes)
            assertFailsWith<SecretStorageException> { store.get("k") }
            assertContentEquals(bytes, Files.readAllBytes(path))
        }
        // Declared length disagrees with the payload.
        Files.write(path, byteArrayOf(68, 83, 69, 67, 1, 0, 0, 0, 0, 5, 1, 2))
        assertFailsWith<SecretStorageException> { store.get("k") }
    }

    @Test
    fun `a keychain wrap seals values, upgrades plaintext ones and refuses another wrap's`() {
        val plain = FileSecretStorage(dir.resolve("s"))
        plain.put("k", byteArrayOf(1, 2, 3))
        val wrapped = FileSecretStorage(dir.resolve("s"), XorWrap(7, 0x5A))
        assertContentEquals(byteArrayOf(1, 2, 3), wrapped.get("k"), "a plaintext value reads through a real wrap")
        // It was sealed again on that read: the plaintext store now refuses it.
        assertFailsWith<SecretStorageException> { plain.get("k") }
        assertContentEquals(byteArrayOf(1, 2, 3), wrapped.get("k"))
        assertFailsWith<SecretStorageException> { FileSecretStorage(dir.resolve("s"), XorWrap(8, 0x11)).get("k") }
        val onDisk = Files.readAllBytes(wrapped.pathOf("k"))
        assertEquals(7, onDisk[5].toInt(), "the wrap id is stored")
    }

    @Test
    fun `the identity key store keeps one identity across restarts`() {
        val crypto = JcaCryptoProvider()
        val first = SoftwareIdentityKeyStore(FileSecretStorage(dir.resolve("s")), crypto).loadOrCreate()
        val again = SoftwareIdentityKeyStore(FileSecretStorage(dir.resolve("s")), crypto).loadOrCreate()
        assertContentEquals(first.publicKey, again.publicKey)
    }

    private class XorWrap(
        override val id: Int,
        private val mask: Int,
    ) : SecretWrap {
        override fun seal(
            name: String,
            plaintext: ByteArray,
        ): ByteArray = ByteArray(plaintext.size) { (plaintext[it].toInt() xor mask).toByte() }

        override fun open(
            name: String,
            sealed: ByteArray,
        ): ByteArray = seal(name, sealed)
    }
}
