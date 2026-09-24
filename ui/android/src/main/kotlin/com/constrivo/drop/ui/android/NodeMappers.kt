package com.constrivo.drop.ui.android

import androidx.compose.ui.graphics.ImageBitmap
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
import com.constrivo.drop.core.transfer.receive.FileNameSanitizer
import com.constrivo.drop.platform.android.service.BrowserShareStatus
import com.constrivo.drop.platform.android.service.NodeDirection
import com.constrivo.drop.platform.android.service.NodeOffer
import com.constrivo.drop.platform.android.service.NodeStage
import com.constrivo.drop.platform.android.service.NodeTransfer
import com.constrivo.drop.platform.android.service.ReceivedItem
import com.constrivo.drop.platform.android.service.SendItem
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
import java.net.URLDecoder
import com.constrivo.drop.core.data.WifiBand as StoredBand

/**
 * The phone node's models as the shared UI's (the ports of `ui/shared`'s `DropDependencies`, architecture §10.1 as
 * changed by WP7ef): pure functions, so the mapping is tested without a service, a database or a device. The desktop
 * shell has the same mapping over its own node (`ui/desktop`'s `PortMappers`).
 */
internal object NodeMappers {
    /** The UI's transfer snapshot (F-G1, design §4.2, §6 Live). */
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
     * The incoming card's offer (F-D1, design §5.1). The Offer's previews (N12, at most 4 KiB each, from a device the
     * user has not accepted yet) become pictures only through [decode], which must refuse anything that is not a small
     * image; otherwise, and without previews, the card shows type glyphs from the MIME histogram.
     */
    fun incoming(
        offer: NodeOffer,
        decode: (Preview) -> FileThumb?,
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

    /** A received file for the tray (design §5.2); its id finds its `content:` URI ([ReceivedFiles]). */
    fun received(item: ReceivedItem): ReceivedFile {
        val kind = kindOf(item.name, item.mimeType)
        return ReceivedFile(item.id, item.transferId, item.name, kind, FileThumb.Glyph(kind), item.mimeType, item.senderName)
    }

    /**
     * A History row (F-G2). A transfer still unfinished in the database that no node runs any more (the app was closed
     * mid-transfer) shows as interrupted until the 24 h clean-up ends it.
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
     * A file of a History transfer (design §6 per-file open): openable when it arrived (or, for a sent file, was sent)
     * and History knows where it is, a `content:` URI (MediaStore, the picked folder, the picker's document) or an app's
     * package path. Whether it still exists is the viewer's to find out.
     */
    fun historyFile(file: TransferFile): HistoryFile {
        val name = FileNameSanitizer.sanitize(file.name)
        return HistoryFile(
            id = file.index.toString(),
            name = name,
            sizeBytes = file.size,
            kind = kindOf(name, file.mimeType),
            openable = file.status == TransferFileStatus.DONE && isOpenable(file.savedUri),
            mime = file.mimeType,
        )
    }

    /** Whether [savedUri] is something the system can open for the user. */
    fun isOpenable(savedUri: String?): Boolean = savedUri != null && savedUri.startsWith("content://")

    fun trusted(device: Device): TrustedDeviceEntry =
        TrustedDeviceEntry(device.id, device.displayName, device.platform, device.lastSeenMillis, device.autoAccept)

    /** The Stats tab (F-G4): the last twelve weeks, oldest first, padded with empty weeks. */
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

    fun visibility(preference: VisibilityPreference): VisibilityState =
        VisibilityState(preference.mode, preference.expiresAtMillis, preference.revertTo)

    /** The language Settings shows: the system's per-app choice on Android 13+ ([systemChoice]), else the stored one. */
    fun language(
        snapshot: SettingsSnapshot?,
        systemChoice: AppLanguage?,
    ): AppLanguage = systemChoice ?: AppLanguage.ofTag(snapshot?.language)

    /** Settings (F-G5, design §6) from the database's [snapshot], with what the phone keeps outside it. */
    fun settings(
        snapshot: SettingsSnapshot,
        nickname: String,
        avatar: ImageBitmap?,
        language: AppLanguage,
        appVersion: String,
    ): SettingsValues =
        SettingsValues(
            visibility = snapshot.visibility.mode,
            prefer5Ghz = snapshot.prefer5Ghz,
            keepScreenAwake = snapshot.keepScreenAwake,
            bundleSmallFiles = snapshot.bundleSmallFiles,
            saveLocationLabel = saveLocationLabel(snapshot.saveLocation),
            nickname = nickname,
            avatar = avatar,
            language = language,
            crashReports = snapshot.crashReports,
            haptics = snapshot.haptics,
            appVersion = appVersion,
        )

    /**
     * How Settings names a picked save location (a SAF tree URI, `…/tree/primary%3ADownload%2FDrop`): its path on the
     * volume (`Download/Drop`), or the volume for its root; null for the default (MediaStore: the gallery and
     * Downloads).
     */
    fun saveLocationLabel(saveLocation: String?): String? {
        if (saveLocation.isNullOrBlank()) return null
        val encoded = saveLocation.substringAfter("/tree/", "").substringBefore('/')
        if (encoded.isEmpty()) return saveLocation.substringAfterLast('/').ifEmpty { saveLocation }
        val documentId = runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrDefault(encoded)
        val path = documentId.substringAfter(':', "").trim('/')
        return path.ifEmpty { documentId.substringBefore(':') }
    }

    /** The browser page for the "Show my code" sheet (N15): the phone's network and the page's two addresses. */
    fun browserShare(status: BrowserShareStatus): BrowserShareState =
        when (status) {
            BrowserShareStatus.Idle -> BrowserShareState.Idle
            BrowserShareStatus.Starting -> BrowserShareState.Starting
            is BrowserShareStatus.Ready -> BrowserShareState.Ready(BrowserShareHint(status.ssid, status.password, status.url, status.ipUrl))
            is BrowserShareStatus.Failed -> BrowserShareState.Failed
        }

    /** The files the user picked or shared, as the node sends them (ids are `content:` URIs, or an app's APK path). */
    fun sendItems(items: List<PickedItem>): List<SendItem> =
        items.mapNotNull { item ->
            if (item.id.isBlank()) return@mapNotNull null
            SendItem(item.id, item.name, item.sizeBytes?.takeIf { it >= 0 })
        }

    /** A file's kind from its MIME type, else its extension. */
    fun kindOf(
        name: String,
        mime: String?,
    ): FileKind {
        val byMime = FileKind.fromMime(mime)
        if (byMime != FileKind.OTHER) return byMime
        val extension = FileNameSanitizer.extensionOf(name.substringAfterLast('/')) ?: return FileKind.OTHER
        return when (extension) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif" -> FileKind.IMAGE
            "mp4", "mov", "mkv", "webm", "m4v", "3gp" -> FileKind.VIDEO
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus" -> FileKind.AUDIO
            "zip", "7z", "rar", "tar", "gz", "tgz" -> FileKind.ARCHIVE
            "apk", "aab", "xapk", "apks" -> FileKind.APP
            "pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "rtf", "csv", "epub" -> FileKind.DOCUMENT
            else -> FileKind.OTHER
        }
    }

    /** The largest preview side decoded (a thumbnail); anything larger shows the type's glyph. */
    const val MAX_PREVIEW_SIDE: Int = 512

    private const val MAX_PREVIEWS = 6
}
