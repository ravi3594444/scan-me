package com.constrivo.drop.core.transfer.receive

import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.Sha256Digest
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferLayout
import com.constrivo.drop.core.protocol.TransferUnit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ID = TransferId(ByteArray(TransferId.SIZE) { 3 })
private val MIB = ProtocolConstants.MIB.toLong()

/** F-D5: a peer-supplied name becomes one safe path component. */
class FileNameSanitizerTest {
    private fun clean(name: String) = FileNameSanitizer.sanitize(name)

    @Test
    fun `paths are cut to their last component`() {
        assertEquals("passwd", clean("../../etc/passwd"))
        assertEquals("x.dll", clean("C:\\Windows\\x.dll"))
        assertEquals("file", clean("../.."))
        assertEquals("file", clean("/"))
    }

    @Test
    fun `controls, bidi overrides and invisible characters are removed`() {
        assertEquals("photogpj.exe", clean("photo\u202Egpj.exe"), "a right-to-left override cannot hide the extension")
        assertEquals("ab.txt", clean("a\u0000\u001F\u007F\u200Bb.txt"))
        assertEquals("line.txt", clean("li\u2028ne.txt"))
        assertEquals("ok.txt", clean("o\uD800k.txt"), "an unpaired surrogate goes")
        assertEquals("\uD83D\uDE00.png", clean("\uD83D\uDE00.png"), "emoji stay")
    }

    @Test
    fun `reserved characters, hidden-file dots and device names are neutralised`() {
        assertEquals("a_b_c_.txt", clean("a<b>c?.txt"))
        assertEquals("bashrc", clean(".bashrc"))
        assertEquals("name", clean("  name. . "))
        assertEquals("_CON.TXT", clean("con.txt".uppercase()))
        assertEquals("_nul", clean("nul"))
        assertEquals("_COM1.log", clean("COM1.log"))
        assertEquals("console.txt", clean("console.txt"), "only exact device stems")
    }

    @Test
    fun `long names keep their extension within 255 bytes and the result is a fixed point`() {
        val long = "\u00E9".repeat(300) + ".jpeg"
        val cut = clean(long)
        assertTrue(cut.endsWith(".jpeg"))
        assertTrue(cut.encodeToByteArray().size <= FileNameSanitizer.MAX_NAME_BYTES)
        for (name in listOf(long, "../a<b>.txt", ".. .", "con", "photo\u202Egpj.exe")) {
            assertEquals(clean(name), clean(clean(name)), name)
        }
    }

    @Test
    fun `collision suffixes go before the extension`() {
        assertEquals("photo (1).jpg", FileNameSanitizer.withCollisionSuffix("photo.jpg", 1))
        assertEquals("archive (2)", FileNameSanitizer.withCollisionSuffix("archive", 2))
        val long = "a".repeat(250) + ".jpg"
        val suffixed = FileNameSanitizer.withCollisionSuffix(long, 12)
        assertTrue(suffixed.endsWith(" (12).jpg"))
        assertTrue(suffixed.encodeToByteArray().size <= FileNameSanitizer.MAX_NAME_BYTES)
        assertFailsWith<IllegalArgumentException> { FileNameSanitizer.withCollisionSuffix("a", 0) }
        assertEquals("jpg", FileNameSanitizer.extensionOf("A.JPG"))
        assertNull(FileNameSanitizer.extensionOf("README"))
        assertNull(FileNameSanitizer.extensionOf("trailing."))
    }

    @Test
    fun `executables and packages are flagged by extension or type`() {
        assertTrue(FileTypes.isExecutable("setup.exe", null))
        assertTrue(FileTypes.isExecutable("install.sh", null))
        assertTrue(FileTypes.isExecutable("download", "application/x-msdownload; charset=binary"))
        assertTrue(FileTypes.isAndroidPackage("game.apk", null))
        assertTrue(FileTypes.isAndroidPackage("x", "application/vnd.android.package-archive"))
        assertTrue(FileTypes.isExecutable("game.xapk", null), "packages are executables too")
        assertFalse(FileTypes.isExecutable("photo.jpg", "image/jpeg"))
        assertFalse(FileTypes.isAndroidPackage("setup.exe", null))
    }
}

/** N5 resume state: the manifest bitmap and hashes, and the in-memory store. */
class ResumeStateTest {
    private val layout = TransferLayout.of(listOf(10L, 20L, 9 * MIB), ProtocolConstants.CHUNK_SIZE, bundleSmall = true)
    private val bundles = TransferUnit(ProtocolConstants.BUNDLE_FILE_INDEX, 0)

    private fun chunk(c: Int) = TransferUnit(2, c)

    private fun hash(seed: Int) = ChunkHash(ByteArray(ChunkHash.SIZE) { (seed + it).toByte() })

    @Test
    fun `the tracker sets bits, hashes and partial prefixes and reports only dirty keys`() {
        val tracker = ManifestTracker(ID, layout)
        assertEquals(listOf(ProtocolConstants.BUNDLE_FILE_INDEX, 2), layout.trackingKeys)
        tracker.dirtyManifests(0) // the initial state
        tracker.setPartial(chunk(1), 16_384)
        assertEquals(16_384, tracker.partialPrefix(chunk(1)))
        tracker.markReceived(chunk(1), hash(1))
        assertEquals(0, tracker.partialPrefix(chunk(1)), "a received unit has no partial prefix")
        assertEquals(hash(1), tracker.hashOf(chunk(1)))
        val dirty = tracker.dirtyManifests(atMillis = 42)
        assertEquals(1, dirty.size, "only file 2 changed")
        val manifest = dirty.single()
        assertEquals(2, manifest.trackingKey)
        assertEquals(3, manifest.unitCount)
        assertTrue(manifest.isReceived(1))
        assertFalse(manifest.isReceived(0))
        assertEquals(0b010, manifest.receivedBitmap()[0].toInt(), "bit u of byte u/8")
        assertEquals(42, manifest.updatedAtMillis)
        assertTrue(tracker.dirtyManifests(43).isEmpty())

        tracker.markMissing(chunk(1))
        assertFalse(tracker.isReceived(chunk(1)))
        assertNull(tracker.hashOf(chunk(1)))
        assertEquals(0L, tracker.receivedUnits)
        tracker.markReceived(bundles, hash(9))
        assertEquals(1L, tracker.receivedUnits)
    }

    @Test
    fun `a tracker reloads stored manifests and ignores ones for other layouts`() {
        val tracker = ManifestTracker(ID, layout)
        tracker.markReceived(chunk(0), hash(0))
        tracker.setPartial(chunk(2), 1000)
        val stored = tracker.dirtyManifests(1).associateBy { it.trackingKey }
        val reloaded = ManifestTracker(ID, layout)
        reloaded.load(stored)
        assertTrue(reloaded.isReceived(chunk(0)))
        assertEquals(hash(0), reloaded.hashOf(chunk(0)))
        assertEquals(1000, reloaded.partialPrefix(chunk(2)))
        assertTrue(reloaded.dirtyManifests(2).isEmpty(), "loading is not a change")

        val other = ManifestTracker(ID, TransferLayout.of(listOf(10L, 20L, 13 * MIB), ProtocolConstants.CHUNK_SIZE, true))
        other.load(stored)
        assertFalse(other.isReceived(chunk(0)), "a manifest of 3 units does not describe 4")
    }

    @Test
    fun `a manifest refuses inconsistent input`() {
        val empty = UnitManifest.empty(ID, 2, 3, 0)
        assertEquals(0, empty.receivedCount)
        assertFailsWith<IllegalArgumentException> { UnitManifest(ID, 2, 9, ByteArray(1), ByteArray(9 * 16), updatedAtMillis = 0) }
        assertFailsWith<IllegalArgumentException> {
            UnitManifest(ID, 2, 3, ByteArray(1), ByteArray(3 * 16), partialUnit = 1, updatedAtMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            UnitManifest(ID, 2, 3, byteArrayOf(0b010), ByteArray(3 * 16), partialUnit = 1, partialBytes = 10, updatedAtMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> { empty.isReceived(3) }
    }

    @Test
    fun `the in-memory store keeps records, manifests and file states until deleted`() =
        runTest {
            val store = InMemoryResumeStore()
            val files = listOf(FileEntry(0, "a", 10), FileEntry(1, "b", 20), FileEntry(2, "c", 9 * MIB))
            val summary = ResumeSummary(3, 30 + 9 * MIB, ProtocolConstants.CHUNK_SIZE, true, 1)
            store.putManifests(listOf(UnitManifest.empty(ID, 2, 3, 0)))
            assertNull(store.load(ID), "a manifest without a record is ignored")
            val peer = ByteArray(32) { 7 }
            store.create(ID, summary, files, atMillis = 5, peerIdentityKey = peer)
            val tracker = ManifestTracker(ID, layout)
            tracker.markReceived(chunk(2), hash(2))
            store.putManifests(tracker.dirtyManifests(6))
            val sha = Sha256Digest(ByteArray(Sha256Digest.SIZE) { 1 })
            store.putFileState(ID, 2, FileResumeState(FileResumeStatus.DONE, sha, "content://x"), atMillis = 7)
            val record = store.load(ID)!!
            assertEquals(summary, record.summary)
            assertTrue(peer.contentEquals(record.peerIdentityKey), "the record names the peer it was made with")
            assertEquals(files, record.files)
            assertTrue(record.manifests.getValue(2).isReceived(2))
            assertEquals(FileResumeStatus.DONE, record.fileStates.getValue(2).status)
            assertEquals(sha, record.fileStates.getValue(2).sha256)
            assertEquals(1, store.manifestWrites)
            store.delete(ID)
            assertFalse(store.contains(ID))
            assertNull(store.load(ID))
        }
}
