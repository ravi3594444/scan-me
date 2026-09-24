package com.constrivo.drop.platform.android.storage

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** MediaStore routing of received files (N14, F-D3, F-D4, F-D5, design §9). */
class MediaRoutingTest {
    private fun route(
        name: String,
        offered: String? = null,
        subfolder: String? = null,
    ) = MediaRouting.route(name, offered, TEST_MIME, subfolder)

    @Test
    fun `media goes to its gallery collection and everything else to Downloads`() {
        assertEquals(MediaTarget(MediaCollection.IMAGES, "Pictures/Drop/", "image/jpeg", "photo.jpg"), route("photo.jpg", "image/jpeg"))
        assertEquals(MediaTarget(MediaCollection.VIDEO, "Movies/Drop/", "video/mp4", "clip.mp4"), route("clip.mp4"))
        assertEquals(MediaTarget(MediaCollection.AUDIO, "Music/Drop/", "audio/mpeg", "song.mp3"), route("song.mp3"))
        assertEquals(MediaTarget(MediaCollection.DOWNLOADS, "Download/Drop/", "application/pdf", "report.pdf"), route("report.pdf"))
    }

    @Test
    fun `the extension decides the type, not the offer`() {
        // A sender cannot put a text file into the gallery by calling it a picture, nor hide a photo in Downloads.
        assertEquals(MediaCollection.DOWNLOADS, route("notes.txt", "image/png").collection)
        assertEquals("text/plain", route("notes.txt", "image/png").mimeType)
        assertEquals(MediaCollection.IMAGES, route("photo.png", "text/plain").collection)
    }

    @Test
    fun `without a known extension a media type is never taken from the offer`() {
        val claimed = route("holiday", "image/jpeg")
        assertEquals(MediaCollection.DOWNLOADS, claimed.collection)
        assertEquals(MediaRouting.GENERIC_MIME, claimed.mimeType)
        val document = route("readme", "text/markdown; charset=utf-8")
        assertEquals(MediaCollection.DOWNLOADS, document.collection)
        assertEquals("text/markdown", document.mimeType)
        assertEquals(MediaRouting.GENERIC_MIME, route("blob", "not a type").mimeType)
        assertEquals(MediaRouting.GENERIC_MIME, route("blob", "image/*").mimeType)
    }

    @Test
    fun `installers and executables always go to Downloads`() {
        assertEquals(MediaCollection.DOWNLOADS, route("game.apk", "image/png").collection)
        assertEquals(MediaCollection.DOWNLOADS, route("setup.exe", "video/mp4").collection)
    }

    @Test
    fun `names are sanitised and the drop subfolder sits under the app folder`() {
        val target = route("../../etc/passwd.txt", subfolder = "Dev 2026-09-24 10.00.00")
        assertEquals("passwd.txt", target.displayName)
        assertEquals("Download/Drop/Dev 2026-09-24 10.00.00/", target.relativePath)
    }

    @Test
    fun `only a drop of more than twenty files gets its own subfolder`() {
        val at = LocalDateTime.of(2026, 9, 24, 10, 5, 7).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertNull(MediaRouting.dropFolderName(MediaRouting.SUBFOLDER_THRESHOLD, "Dev", at, ZoneOffset.UTC))
        assertEquals("Dev 2026-09-24 10.05.07", MediaRouting.dropFolderName(21, "Dev", at, ZoneOffset.UTC))
        assertEquals("2026-09-24 10.05.07", MediaRouting.dropFolderName(21, "  ", at, ZoneOffset.UTC))
    }

    @Test
    fun `a stored save location names a picked folder, anything else is MediaStore`() {
        assertIs<ReceiveDestination.MediaStoreVolume>(ReceiveDestination.fromSetting(null))
        assertIs<ReceiveDestination.MediaStoreVolume>(ReceiveDestination.fromSetting("/sdcard/Download"))
        val tree = ReceiveDestination.fromSetting("content://com.android.externalstorage.documents/tree/primary%3ADrop")
        assertIs<ReceiveDestination.DocumentTree>(tree)
    }
}
