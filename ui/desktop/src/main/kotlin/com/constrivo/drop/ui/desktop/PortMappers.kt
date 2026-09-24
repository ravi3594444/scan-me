package com.constrivo.drop.ui.desktop

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.constrivo.drop.core.data.Device
import com.constrivo.drop.core.data.SettingsSnapshot
import com.constrivo.drop.core.data.TransferDirection
import com.constrivo.drop.core.data.TransferFile
import com.constrivo.drop.core.data.TransferFileStatus
import com.constrivo.drop.core.data.TransferRecord
import com.constrivo.drop.core.data.TransferStats
import com.constrivo.drop.core.data.TransferStatus
import com.constrivo.drop.core.data.VisibilityPreference
import com.constrivo.drop.core.ladder.TransportBadge
import com.constrivo.drop.core.protocol.Preview
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.transfer.receive.FileNameSanitizer
import com.constrivo.drop.platform.desktop.files.MarkingFileStore
import com.constrivo.drop.platform.desktop.files.SendFile
import com.constrivo.drop.platform.desktop.node.BrowserShareStatus
import com.constrivo.drop.platform.desktop.node.NodeDirection
import com.constrivo.drop.platform.desktop.node.NodeOffer
import com.constrivo.drop.platform.desktop.node.NodeStage
import com.constrivo.drop.platform.desktop.node.NodeTransfer
import com.constrivo.drop.platform.desktop.node.ReceivedItem
import com.constrivo.drop.ui.shared.model.AppLanguage
import com.constrivo.drop.ui.shared.model.BrowserShareHint
import com.constrivo.drop.ui.shared.model.BrowserShareState
import com.constrivo.drop.ui.shared.model.Direction
import com.constrivo.drop.ui.shared.model.FileKind
import com.constrivo.drop.ui.shared.model.FileThumb
import com.constrivo.drop.ui.shared.model.HistoryEntry
import com.constrivo.drop.ui.shared.model.HistoryFile
import com.constrivo.drop.ui.shared.model.HistoryStatus
import com.constrivo.drop.ui.shared.model.IncomingOffer
import com.constrivo.drop.ui.shared.model.ItemSummary
import com.constrivo.drop.ui.shared.model.PickedItem
import com.constrivo.drop.ui.shared.model.ReceivedFile
import com.constrivo.drop.ui.shared.model.SettingsValues
import com.constrivo.drop.ui.shared.model.StatsSnapshot
import com.constrivo.drop.ui.shared.model.TransferSnapshot
import com.constrivo.drop.ui.shared.model.TransferStage
import com.constrivo.drop.ui.shared.model.TrustedDeviceEntry
import com.constrivo.drop.ui.shared.model.VisibilityState
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import com.constrivo.drop.core.data.WifiBand as StoredBand

/**
 * The desktop node's models as the shared UI's (the ports of `ui/shared`'s `DropDependencies`): pure functions, so
 * the mapping is tested without a node, a database or a display.
 */
object PortMappers {
    /** The UI's transfer snapshot (F‑G1, design §4.2, §6 Live). Pause and "Add files" are not in the engine yet. */
    fun snapshot(t: NodeTransfer): TransferSnapshot =
        TransferSnapshot(
            id = t.id,
            peerKey = t.peerKey,
            peerName = t.peerName,
            peerPlatform = t.peerPlatform,
            direction = direction(t.direction),
            stage = stage(t.stage),
            bytesDone = t.bytesDone.coerceIn(0, maxOf(t.bytesTotal, 0)),
            bytesTotal = maxOf(t.bytesTotal, 0),
            bytesPerSecond = t.bytesPerSecond?.coerceAtLeast(0),
            etaMillis = t.etaMillis?.coerceAtLeast(0),
            badge = t.badge,
            hint = t.hint,
            summary = ItemSummary.ofHistogram(t.fileCount.coerceAtLeast(0), t.mimeHistogram),
            pairingCode = t.pairingCode,
        )

    fun stage(stage: NodeStage): TransferStage =
        when (stage) {
            NodeStage.CONNECTING -> TransferStage.CONNECTING
            NodeStage.AWAITING_ACCEPT -> TransferStage.AWAITING_ACCEPT
            NodeStage.TRANSFERRING -> TransferStage.TRANSFERRING
            NodeStage.RECONNECTING -> TransferStage.RECONNECTING
            NodeStage.WAITING_FOR_PEER -> TransferStage.WAITING_FOR_PEER
            NodeStage.VERIFYING -> TransferStage.VERIFYING
            NodeStage.DONE -> TransferStage.DONE
            NodeStage.FAILED -> TransferStage.FAILED
            NodeStage.CANCELLED -> TransferStage.CANCELLED
            NodeStage.DECLINED -> TransferStage.DECLINED
            NodeStage.NO_ANSWER -> TransferStage.NO_ANSWER
        }

    fun direction(direction: NodeDirection): Direction = if (direction == NodeDirection.SEND) Direction.SEND else Direction.RECEIVE

    /**
     * The incoming card's offer (F‑D1, design §5.1). The Offer's previews (N12, at most 4 KiB each) become pictures
     * when they decode, else type glyphs; without previews the card shows glyphs from the MIME histogram.
     */
    fun incoming(
        offer: NodeOffer,
        decode: (Preview) -> FileThumb? = ::decodePreview,
    ): IncomingOffer {
        val previews =
            offer.previews.take(MAX_PREVIEWS).map { p -> decode(p) ?: FileThumb.Glyph(FileKind.fromMime(p.mime)) }.ifEmpty {
                glyphs(offer.fileCount, offer.mimeHistogram)
            }
        return IncomingOffer(
            id = offer.id,
            senderKey = offer.senderKey,
            senderName = offer.senderName,
            senderPlatform = offer.senderPlatform,
            trusted = offer.trusted,
            sas = offer.sas,
            summary = ItemSummary.ofHistogram(offer.fileCount, offer.mimeHistogram),
            totalBytes = maxOf(offer.totalBytes, 0),
            previews = previews,
            arrivedAtMillis = offer.arrivedAtElapsedMillis,
            timeoutMillis = offer.timeoutMillis,
        )
    }

    /** Up to six type glyphs in the proportions of [histogram]; files it does not cover count as other. */
    fun glyphs(
        fileCount: Int,
        histogram: Map<String, Int>,
    ): List<FileThumb> {
        val kinds = ArrayList<FileKind>()
        for ((mime, count) in histogram.entries.sortedByDescending { it.value }) {
            repeat(minOf(count, MAX_PREVIEWS)) { kinds += FileKind.fromMime(mime) }
        }
        while (kinds.size < minOf(fileCount, MAX_PREVIEWS)) kinds += FileKind.OTHER
        return kinds.take(minOf(fileCount, MAX_PREVIEWS)).map { FileThumb.Glyph(it) }
    }

    /**
     * A preview decoded with Skia, or null when it is not a small image Skia reads. Previews come from any sender before
     * the user consented (N12 caps their bytes at 4 KiB, not their pixels): a few bytes can declare a 16384 × 16384
     * picture, so the header is read first ([Codec]) and anything larger than [MAX_PREVIEW_SIDE] on a side, or not an
     * image type, is refused before a pixel is allocated.
     */
    fun decodePreview(preview: Preview): FileThumb? {
        if (!preview.mime.startsWith("image/")) return null
        val bytes = preview.data.toByteArray()
        if (bytes.isEmpty() || bytes.size > ProtocolConstants.MAX_PREVIEW_BYTES) return null
        return try {
            Data.makeFromBytes(bytes).use { data ->
                Codec.makeFromData(data).use { codec ->
                    if (codec.width !in 1..MAX_PREVIEW_SIDE || codec.height !in 1..MAX_PREVIEW_SIDE) return null
                }
            }
            FileThumb.Picture(Image.makeFromEncoded(bytes).toComposeImageBitmap(), FileKind.fromMime(preview.mime))
        } catch (_: Exception) {
            null
        }
    }

    /** The largest preview side decoded (a thumbnail); anything larger shows the type's glyph. */
    const val MAX_PREVIEW_SIDE: Int = 512

    /** A received file for the tray (design §5.2). */
    fun received(item: ReceivedItem): ReceivedFile {
        val kind = kindOf(item.name, item.mimeType)
        return ReceivedFile(item.id, item.transferId, item.name, kind, FileThumb.Glyph(kind), item.mimeType, item.senderName)
    }

    /**
     * A History row (F‑G2). A transfer still unfinished in the database that no engine runs any more (the app was
     * closed mid-transfer) shows as interrupted until the 24 h clean-up ends it.
     */
    fun history(record: TransferRecord): HistoryEntry =
        HistoryEntry(
            id = record.id.toHex(),
            peerName = record.peerName,
            peerPlatform = record.peerPlatform,
            direction = if (record.direction == TransferDirection.SEND) Direction.SEND else Direction.RECEIVE,
            status =
                when {
                    record.status == TransferStatus.DONE && record.isPartial -> HistoryStatus.PARTIAL
                    record.status == TransferStatus.DONE -> HistoryStatus.DONE
                    record.status == TransferStatus.FAILED -> HistoryStatus.FAILED
                    record.status == TransferStatus.CANCELLED -> HistoryStatus.CANCELLED
                    else -> HistoryStatus.INTERRUPTED
                },
            startedAtMillis = record.startedAtMillis,
            durationMillis = record.durationMillis,
            bytesTotal = record.bytesTotal,
            bytesDone = record.bytesDone.coerceAtMost(record.bytesTotal),
            avgBytesPerSecond = record.avgSpeedBytesPerSecond,
            summary = ItemSummary.ofHistogram(record.fileCount, record.mimeHistogram),
            badge = record.transport?.let { TransportBadge.of(it, representativeMhz(record.band)) },
        )

    /** A centre frequency in [band], so the badge names the band History stored (the channel itself is not kept). */
    fun representativeMhz(band: StoredBand?): Int? =
        when (band) {
            StoredBand.GHZ_2_4 -> 2437
            StoredBand.GHZ_5 -> 5180
            StoredBand.GHZ_6 -> 5955
            null -> null
        }

    /**
     * A file of a History transfer (design §6 per-file open): the name shown is the last component (a sender's folder
     * path, F‑C6), and it is openable when it arrived and is still where it was saved (or, for a sent file, where it
     * was read from).
     */
    fun historyFile(
        file: TransferFile,
        exists: (Path) -> Boolean = { Files.exists(it) },
    ): HistoryFile {
        val name = FileNameSanitizer.sanitize(file.name)
        val path = pathOf(file.savedUri)
        return HistoryFile(
            id = file.index.toString(),
            name = name,
            sizeBytes = file.size,
            kind = kindOf(name, file.mimeType),
            openable = file.status == TransferFileStatus.DONE && path != null && exists(path),
            mime = file.mimeType,
        )
    }

    /** The local path of a stored `saved_uri` (a `file:` URI, or a plain path). */
    fun pathOf(savedUri: String?): Path? {
        savedUri ?: return null
        MarkingFileStore.pathOf(savedUri)?.let { return it }
        return try {
            Paths.get(savedUri).takeIf { it.isAbsolute }
        } catch (_: InvalidPathException) {
            null
        }
    }

    fun trusted(device: Device): TrustedDeviceEntry =
        TrustedDeviceEntry(device.id, device.displayName, device.platform, device.lastSeenMillis, device.autoAccept)

    /** The Stats tab (F‑G4): the last twelve weeks, oldest first, padded with empty weeks. */
    fun stats(stats: TransferStats): StatsSnapshot {
        val weeks = stats.weeks.map { it.transfers.coerceAtLeast(0) }.takeLast(StatsSnapshot.WEEKS)
        return StatsSnapshot(
            totalBytes = stats.totalBytes.coerceAtLeast(0),
            averageBytesPerSecond = stats.averageBytesPerSecond,
            transfersThisWeek = stats.transfersThisWeek.coerceAtLeast(0),
            hoursSaved = stats.hoursSaved,
            weeks = List(StatsSnapshot.WEEKS - weeks.size) { 0 } + weeks,
        )
    }

    fun settings(
        snapshot: SettingsSnapshot,
        nickname: String,
        appVersion: String,
    ): SettingsValues =
        SettingsValues(
            visibility = snapshot.visibility.mode,
            prefer5Ghz = snapshot.prefer5Ghz,
            keepScreenAwake = snapshot.keepScreenAwake,
            bundleSmallFiles = snapshot.bundleSmallFiles,
            saveLocationLabel = snapshot.saveLocation?.let { pathOf(it)?.fileName?.toString() ?: it },
            nickname = nickname,
            avatar = null,
            language = AppLanguage.ofTag(snapshot.language),
            crashReports = snapshot.crashReports,
            haptics = snapshot.haptics,
            appVersion = appVersion,
        )

    fun visibility(preference: VisibilityPreference): VisibilityState =
        VisibilityState(preference.mode, preference.expiresAtMillis, preference.revertTo)

    /**
     * The browser path's state for the "Show my code" sheet (N15). On the LAN there is no hotspot to join, so the
     * network line names the LAN in words ([lanNetwork], [noPassword]) and both addresses are the IP form: a shared
     * LAN may already have a `drop.local`.
     */
    fun browserShare(
        status: BrowserShareStatus,
        lanNetwork: String,
        noPassword: String,
    ): BrowserShareState =
        when (status) {
            BrowserShareStatus.Idle -> BrowserShareState.Idle
            BrowserShareStatus.Starting -> BrowserShareState.Starting
            is BrowserShareStatus.Ready -> BrowserShareState.Ready(BrowserShareHint(lanNetwork, noPassword, status.url, status.url))
            is BrowserShareStatus.Failed -> BrowserShareState.Failed
        }

    /** The files the user picked or dropped, as the node sends them (ids are absolute paths, names relative ones). */
    fun sendFiles(items: List<PickedItem>): List<SendFile> =
        items.mapNotNull { item ->
            val path =
                try {
                    Paths.get(item.id)
                } catch (_: InvalidPathException) {
                    return@mapNotNull null
                }
            val size = item.sizeBytes ?: runCatching { Files.size(path) }.getOrNull() ?: return@mapNotNull null
            SendFile(path, item.name.ifEmpty { path.fileName?.toString() ?: "file" }, size)
        }

    /** The picker's and banner's items for [files] (the tray glyph comes from the extension). */
    fun pickedItems(files: List<SendFile>): List<PickedItem> =
        files.map { f -> PickedItem(f.path.toString(), f.name, f.size, kindOf(f.name, null)) }

    /** A file's kind from its MIME type, else its extension (desktop files rarely carry a MIME type). */
    fun kindOf(
        name: String,
        mime: String?,
    ): FileKind {
        val byMime = FileKind.fromMime(mime)
        if (byMime != FileKind.OTHER) return byMime
        val extension = FileNameSanitizer.extensionOf(name.substringAfterLast('/')) ?: return FileKind.OTHER
        return when (extension) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "avif" -> {
                FileKind.IMAGE
            }

            "mp4", "mov", "mkv", "avi", "webm", "m4v", "3gp" -> {
                FileKind.VIDEO
            }

            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus" -> {
                FileKind.AUDIO
            }

            "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz" -> {
                FileKind.ARCHIVE
            }

            "apk", "aab", "xapk", "apks" -> {
                FileKind.APP
            }

            "pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "csv", "json", "xml", "epub" -> {
                FileKind.DOCUMENT
            }

            else -> {
                FileKind.OTHER
            }
        }
    }

    private const val MAX_PREVIEWS = 6
}
