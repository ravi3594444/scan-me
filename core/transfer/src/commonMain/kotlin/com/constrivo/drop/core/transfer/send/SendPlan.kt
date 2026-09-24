package com.constrivo.drop.core.transfer.send

import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.FileList
import com.constrivo.drop.core.protocol.FileListPager
import com.constrivo.drop.core.protocol.LinkOption
import com.constrivo.drop.core.protocol.MimeHistogram
import com.constrivo.drop.core.protocol.Offer
import com.constrivo.drop.core.protocol.Preview
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.protocol.TransferLayout
import com.constrivo.drop.core.transfer.SourceFile

/**
 * What the sender sends (architecture §7.2, §7.3; spec changes N12, S4): the summary [offer], the complete [files] list
 * that follows `Accept` as [fileListPages], and the [layout] both devices derive from them (bundles first, then the
 * chunks of each chunked file).
 */
class SendPlan internal constructor(
    val transferId: TransferId,
    val offer: Offer,
    val files: List<FileEntry>,
    val sources: List<SourceFile>,
    val layout: TransferLayout,
) {
    /** The `FileList` pages, in order (N12). */
    val fileListPages: List<FileList> by lazy { FileListPager.paginate(transferId, files) }

    /** Files that are bundled (S4), for the `bundling` hint. */
    val bundledFileCount: Int get() = files.indices.count { layout.bundlePlan.isBundled(it) }
}

/**
 * Builds the summary `Offer` of spec change N12 from the picked files: file count, total bytes, the MIME histogram,
 * up to six preview names (the first files' names) and the caller's previews (thumbnails of at most 4 KiB, which only
 * the platform can render), the chunk size, bundling and the `bundle_count` of the deterministic bundle plan (S4), and
 * the ladder's link options. Whole-file hashes are not in the offer; they follow as `FileDone` while streaming (S2).
 *
 * Names are made wire-safe (well-formed UTF-8 of 1..1024 bytes, cut at a code-point boundary); sanitising for the
 * receiver's file system is the receiver's job (F-D5).
 */
object OfferBuilder {
    /**
     * @throws IllegalArgumentException for an empty file list, more than [ProtocolConstants.MAX_FILES_PER_TRANSFER]
     *   files, a negative size, previews that do not fit the offer, or an invalid chunk size.
     */
    fun build(
        transferId: TransferId,
        sources: List<SourceFile>,
        previews: List<Preview> = emptyList(),
        linkOptions: List<LinkOption> = emptyList(),
        chunkSize: Int = ProtocolConstants.CHUNK_SIZE,
        bundleSmall: Boolean = true,
    ): SendPlan {
        require(sources.isNotEmpty()) { "a transfer has at least one file" }
        require(sources.size <= ProtocolConstants.MAX_FILES_PER_TRANSFER) { "at most ${ProtocolConstants.MAX_FILES_PER_TRANSFER} files" }
        val files =
            sources.mapIndexed { index, source ->
                require(source.size >= 0) { "file $index has a negative size" }
                FileEntry(index, wireName(source.name), source.size, wireMime(source.mimeType), source.modifiedMillis)
            }
        val layout = TransferLayout.of(files.map { it.size }, chunkSize, bundleSmall)
        var total = 0L
        for (file in files) {
            require(file.size <= Long.MAX_VALUE - total) { "total size overflows" }
            total += file.size
        }
        val offer =
            Offer(
                transferId = transferId,
                fileCount = files.size,
                totalBytes = total,
                mimeHistogram = MimeHistogram.of(files.map { it.mime }),
                previewNames = files.take(ProtocolConstants.MAX_PREVIEW_NAMES).map { it.name },
                previews = previews,
                chunkSize = chunkSize,
                bundleSmall = bundleSmall,
                bundleCount = layout.bundleCount,
                linkOptions = linkOptions,
            )
        return SendPlan(transferId, offer, files, sources.toList(), layout)
    }

    /** [name] as a valid `FileEntry.name`: unpaired surrogates replaced, empty names named "file", at most 1024 bytes. */
    internal fun wireName(name: String): String {
        val fixed = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val c = name[i]
            when {
                c.isHighSurrogate() && i + 1 < name.length && name[i + 1].isLowSurrogate() -> {
                    fixed.append(c).append(name[i + 1])
                    i++
                }

                c.isSurrogate() -> {
                    fixed.append('�')
                }

                else -> {
                    fixed.append(c)
                }
            }
            i++
        }
        val cut = truncate(fixed.toString(), ProtocolConstants.MAX_FILE_NAME_BYTES)
        return cut.ifEmpty { "file" }
    }

    private fun wireMime(mime: String?): String? {
        val trimmed = mime?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isSurrogate() }) return null
        return trimmed.takeIf { it.encodeToByteArray().size <= ProtocolConstants.MAX_MIME_BYTES }
    }

    private fun truncate(
        value: String,
        maxBytes: Int,
    ): String {
        var bytes = 0
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val pair = c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()
            val size =
                when {
                    pair -> 4
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
            if (bytes + size > maxBytes) break
            bytes += size
            i += if (pair) 2 else 1
        }
        return value.substring(0, i)
    }
}
