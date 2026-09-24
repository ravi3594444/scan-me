package com.constrivo.drop.ui.desktop

import com.constrivo.drop.core.data.TransferFile
import com.constrivo.drop.core.data.TransferFileStatus
import com.constrivo.drop.core.data.TransferStats
import com.constrivo.drop.core.data.WeekCount
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.protocol.Bytes
import com.constrivo.drop.core.protocol.Preview
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.platform.desktop.files.SendFile
import com.constrivo.drop.platform.desktop.node.BrowserShareStatus
import com.constrivo.drop.platform.desktop.node.NodeDirection
import com.constrivo.drop.platform.desktop.node.NodeOffer
import com.constrivo.drop.platform.desktop.node.NodeStage
import com.constrivo.drop.platform.desktop.node.NodeTransfer
import com.constrivo.drop.ui.shared.model.BrowserShareHint
import com.constrivo.drop.ui.shared.model.BrowserShareState
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.FileThumb
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.StatsSnapshot
import com.constrivo.drop.ui.shared.model.TransferStage
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The node's models as the shared UI's (the ports' mapping, pure). */
class PortMappersTest {
    @Test
    fun everyNodeStageHasTheSameUiStage() {
        for (stage in NodeStage.entries) assertEquals(stage.name, PortMappers.stage(stage).name)
        assertEquals(Direction.SEND, PortMappers.direction(NodeDirection.SEND))
        assertEquals(Direction.RECEIVE, PortMappers.direction(NodeDirection.RECEIVE))
    }

    @Test
    fun snapshotsAreClampedAndSummarised() {
        val t =
            NodeTransfer(
                id = "t1",
                direction = NodeDirection.RECEIVE,
                peerKey = "k",
                peerDeviceId = "d",
                peerName = "Meera",
                peerPlatform = DevicePlatform.PHONE,
                stage = NodeStage.TRANSFERRING,
                fileCount = 3,
                bytesTotal = 100,
                bytesDone = 140,
                bytesPerSecond = -5,
                etaMillis = -1,
                mimeHistogram = mapOf("image/jpeg" to 3),
                pairingCode = "123456",
            )
        val s = PortMappers.snapshot(t)
        assertEquals(100, s.bytesDone, "never past the total")
        assertEquals(0, s.bytesPerSecond)
        assertEquals(0, s.etaMillis)
        assertEquals(TransferStage.TRANSFERRING, s.stage)
        assertEquals(Direction.RECEIVE, s.direction)
        assertEquals(3, s.summary.count)
        assertEquals("123456", s.pairingCode)
    }

    @Test
    fun offersShowPreviewsWhenTheyDecodeElseGlyphs() {
        val offer =
            NodeOffer(
                id = "o",
                senderDeviceId = "d",
                senderKey = "k",
                senderName = "Rohan",
                senderPlatform = DevicePlatform.PHONE,
                trusted = true,
                sas = null,
                fileCount = 5,
                totalBytes = -3,
                mimeHistogram = mapOf("image/jpeg" to 2, "video/mp4" to 1),
                previewNames = emptyList(),
                previews = emptyList(),
                arrivedAtElapsedMillis = 7,
            )
        val card = PortMappers.incoming(offer) { null }
        assertEquals(0, card.totalBytes)
        assertEquals(
            listOf(FileKind.IMAGE, FileKind.IMAGE, FileKind.VIDEO, FileKind.OTHER, FileKind.OTHER),
            card.previews.map { (it as FileThumb.Glyph).kind },
        )
        val withPreview = offer.copy(previews = listOf(Preview(0, "image/jpeg", Bytes(byteArrayOf(1, 2, 3)))))
        // Bytes that are no picture fall back to the type's glyph (Skia refuses them).
        assertEquals(FileThumb.Glyph(FileKind.IMAGE), PortMappers.incoming(withPreview).previews.single())
    }

    @Test
    fun historyFilesAreOpenableOnlyWhenDoneAndStillThere() {
        val dir = Files.createTempDirectory("drop-mappers")
        try {
            val present = Files.writeString(dir.resolve("a.pdf"), "x")
            val id = TransferId.fromHex("0123456789abcdef0123456789abcdef")
            val done = TransferFile(id, 0, "Folder/a.pdf", null, 1, null, present.toUri().toString(), TransferFileStatus.DONE)
            val file = PortMappers.historyFile(done)
            assertEquals("a.pdf", file.name, "the last component of a folder path")
            assertEquals(FileKind.DOCUMENT, file.kind)
            assertTrue(file.openable)
            assertFalse(PortMappers.historyFile(done.copy(status = TransferFileStatus.FAILED)).openable)
            assertFalse(PortMappers.historyFile(done.copy(savedUri = dir.resolve("gone.pdf").toUri().toString())).openable)
            assertFalse(PortMappers.historyFile(done.copy(savedUri = null)).openable)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun savedUrisBecomePaths() {
        assertEquals(Paths.get("/tmp/x y.txt"), PortMappers.pathOf("file:///tmp/x%20y.txt"))
        assertEquals(Paths.get("/tmp/plain"), PortMappers.pathOf("/tmp/plain"))
        assertNull(PortMappers.pathOf("relative/path"))
        assertNull(PortMappers.pathOf(null))
    }

    @Test
    fun statsArePaddedToTwelveWeeks() {
        val stats = TransferStats(4, 4_000, 2_000, 2, listOf(WeekCount(0, 0, 1, 3), WeekCount(7, 1, 2, -1)), 0)
        val snapshot = PortMappers.stats(stats)
        assertEquals(StatsSnapshot.WEEKS, snapshot.weeks.size)
        assertEquals(listOf(3, 0), snapshot.weeks.takeLast(2))
        assertEquals(2_000, snapshot.averageBytesPerSecond)
    }

    @Test
    fun theBrowserPathOnTheLanNamesTheNetworkInWords() {
        val ready =
            PortMappers.browserShare(
                BrowserShareStatus.Ready("http://192.168.1.20:8080/t/abc/", 2),
                "the same network",
                "none needed",
            )
        assertEquals(
            BrowserShareState.Ready(
                BrowserShareHint("the same network", "none needed", "http://192.168.1.20:8080/t/abc/", "http://192.168.1.20:8080/t/abc/"),
            ),
            ready,
        )
        assertEquals(BrowserShareState.Idle, PortMappers.browserShare(BrowserShareStatus.Idle, "", ""))
        assertIs<BrowserShareState.Failed>(PortMappers.browserShare(BrowserShareStatus.Failed("port in use"), "", ""))
    }

    @Test
    fun pickedItemsRoundTripToSendFilesAndKindsComeFromExtensions() {
        val dir = Files.createTempDirectory("drop-picked")
        try {
            val a = Files.writeString(dir.resolve("clip.MOV"), "12345")
            val items = PortMappers.pickedItems(listOf(SendFile(a, "Trip/clip.MOV", 5)))
            assertEquals(listOf(PickedItem(a.toString(), "Trip/clip.MOV", 5, FileKind.VIDEO)), items)
            assertEquals(listOf(SendFile(a, "Trip/clip.MOV", 5)), PortMappers.sendFiles(items))
            // An item without a size is measured; one that no longer exists is left out.
            val unsized = PickedItem(a.toString(), "clip.MOV", null, FileKind.VIDEO)
            val missing = PickedItem(dir.resolve("gone").toString(), "gone", null, FileKind.OTHER)
            assertEquals(listOf(SendFile(a, "clip.MOV", 5)), PortMappers.sendFiles(listOf(unsized, missing)))
            assertEquals(FileKind.APP, PortMappers.kindOf("game.apk", null))
            assertEquals(FileKind.AUDIO, PortMappers.kindOf("song.flac", "application/octet-stream"))
            assertEquals(FileKind.IMAGE, PortMappers.kindOf("x", "image/png"))
            assertEquals(FileKind.OTHER, PortMappers.kindOf("Makefile", null))
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
