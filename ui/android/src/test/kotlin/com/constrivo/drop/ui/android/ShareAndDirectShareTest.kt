package com.constrivo.drop.ui.android

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.Ring
import com.constrivo.drop.ui.shared.model.BubbleUi
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.OemBrand
import com.constrivo.drop.ui.shared.model.RadarUiState
import com.constrivo.drop.ui.shared.model.SelfProfile
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Share-sheet parsing (design §4.3), direct-share targets and ids (F‑C3) and the OEM screens (F‑I2). */
class ShareAndDirectShareTest {
    private fun facts(
        uri: String,
        name: String? = null,
        size: Long? = null,
        mime: String? = null,
        scheme: String? = uri.substringBefore(':'),
        fallback: String? = null,
    ) = SharedFiles.Facts(uri, scheme, name, size, mime, uri.substringAfterLast('/'), fallback)

    @Test
    fun designSection43_contentUrisBecomeItemsInOrderWithoutDuplicates() {
        val items =
            SharedFiles.toItems(
                listOf(
                    facts("content://media/external/images/1", "IMG_1.jpg", 2_000_000, "image/jpeg"),
                    facts("file:///data/data/com.constrivo.drop/secret.db", "secret.db", 10, "application/octet-stream"),
                    facts("content://media/external/video/2", "clip.mp4", 40_000_000, "video/mp4"),
                    facts("content://media/external/images/1", "IMG_1.jpg", 2_000_000, "image/jpeg"),
                ),
                untitled = "Untitled",
            )
        assertEquals(listOf("content://media/external/images/1", "content://media/external/video/2"), items.map { it.id })
        assertEquals(listOf(FileKind.IMAGE, FileKind.VIDEO), items.map { it.kind })
        assertEquals(42_000_000L, items.sumOf { it.sizeBytes ?: 0 })
    }

    @Test
    fun namesAreCleanedAndFallBack() {
        val items =
            SharedFiles.toItems(
                listOf(
                    facts("content://p/doc/7", "../../evil\u202Egnp.exe", 5, "application/pdf"),
                    facts("content://p/doc/8", "   ", -1, null),
                    facts("content://p/", null, null, null),
                ),
                untitled = "Untitled",
            )
        assertEquals("evilgnp.exe", items[0].name, "path parts and bidi overrides are dropped")
        assertEquals("8", items[1].name, "a blank display name falls back to the URI's last segment")
        assertNull(items[1].sizeBytes, "a negative size is unknown")
        assertEquals("Untitled", items[2].name)
        assertNull(SharedFiles.cleanName("\u0000\n"))
    }

    @Test
    fun theKindFallsBackFromWildcardsToTheSendersTypeThenTheExtension() {
        val items =
            SharedFiles.toItems(
                listOf(
                    facts("content://p/a", "a.bin", 1, "*/*", fallback = "application/zip"),
                    facts("content://p/b", "b", 1, null, fallback = "image/*"),
                    facts("content://p/c", "c.pdf", 1, "application/pdf"),
                ),
                untitled = "Untitled",
            )
        assertEquals(listOf(FileKind.ARCHIVE, FileKind.OTHER, FileKind.DOCUMENT), items.map { it.kind })
    }

    private fun bubble(
        key: String,
        name: String?,
        trusted: Boolean,
        ring: Ring,
    ) = BubbleUi(
        key = key,
        name = name,
        initials = name?.take(1),
        avatarHash = key.hashCode(),
        platform = DevicePlatform.PHONE,
        trusted = trusted,
        ring = ring,
        lanOnly = false,
    )

    @Test
    fun fC3_trustedNamedDevicesClosestFirst() {
        val state =
            RadarUiState.initial(SelfProfile("Asha", "self")).copy(
                bubbles =
                    listOf(
                        bubble("t:far", "Dev", trusted = true, ring = Ring.OUTER),
                        bubble("u:meera", "Meera", trusted = false, ring = Ring.INNER),
                        bubble("t:rohan", "Rohan's Pixel", trusted = true, ring = Ring.INNER),
                        bubble("t:anon", null, trusted = true, ring = Ring.INNER),
                        bubble("t:mid", "Laptop", trusted = true, ring = Ring.MIDDLE),
                    ),
            )
        assertEquals(listOf("t:rohan", "t:mid", "t:far"), DirectShareTargets.of(state, max = 8).map { it.key })
        assertEquals(listOf("t:rohan", "t:mid"), DirectShareTargets.of(state, max = 2).map { it.key })
        assertTrue(DirectShareTargets.of(state, max = 0).isEmpty())
    }

    @Test
    fun fC3_shortcutIdsAreRandomStableAndResolveOnlyWhenHandedOut() {
        val store = KeyValueStore.InMemory()
        val ids = DirectShareIds(store, SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) })
        val rohan = ids.idFor("t:rohan")
        assertEquals(rohan, ids.idFor("t:rohan"), "stable for a device")
        assertNotEquals(rohan, ids.idFor("t:dev"))
        assertTrue(Regex("ds_[0-9a-f]{32}").matches(rohan))
        assertTrue("rohan" !in rohan, "the id does not reveal the device key")
        assertEquals("t:rohan", ids.keyFor(rohan))
        assertEquals("t:rohan", DirectShareIds(store).keyFor(rohan), "remembered across restarts")
        assertNull(ids.keyFor("t:rohan"), "a guessed device key is not a target")
        assertNull(ids.keyFor("ds_" + "0".repeat(32)))
        assertNull(ids.keyFor(null))
    }

    @Test
    fun fC3_rememberedIdsAreCappedKeepingTheCurrentTargets() {
        val store = KeyValueStore.InMemory()
        val ids = DirectShareIds(store)
        val all = (0 until DirectShareIds.MAX_ENTRIES + 10).map { "t:$it" }
        val handed = all.associateWith(ids::idFor)
        val current = all.takeLast(3)
        ids.trim(current)
        assertEquals(DirectShareIds.MAX_ENTRIES, store.keys("share.key.").size)
        for (key in current) assertEquals(key, ids.keyFor(handed.getValue(key)))
    }

    @Test
    fun fI2_everyBrandHasAnOemScreenToTry() {
        for (brand in OemBrand.entries) {
            val screens = OemScreens.candidates(brand)
            assertTrue(screens.isNotEmpty(), "$brand has candidates")
            for (s in screens) assertTrue(s.className.startsWith(s.packageName.substringBefore('.')), "$s is a full class name")
        }
        assertTrue(SystemMotion.isReduced(0f))
        assertTrue(!SystemMotion.isReduced(0.5f))
    }
}
