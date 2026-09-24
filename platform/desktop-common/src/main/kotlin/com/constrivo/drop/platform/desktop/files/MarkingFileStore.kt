package com.constrivo.drop.platform.desktop.files

import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.PartialFile
import com.constrivo.drop.core.transfer.PublishedFile
import com.constrivo.drop.platform.desktop.DownloadMarker
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/** A file [MarkingFileStore] published: where it went ([path] for a `file:` URI), which file of which drop it is. */
data class PublishedFileEvent(
    val file: PublishedFile,
    val path: Path?,
    val fileIndex: Int,
    val mimeType: String?,
    val drop: DropInfo,
)

/**
 * A [FileStore] that marks every published file with the OS's "downloaded from elsewhere" flag (architecture §10.2:
 * Windows Mark-of-the-Web, macOS quarantine; [DownloadMarker]) and tells [onPublished] where it went, delegating
 * everything else to [delegate] (a `DirectoryFileStore` on the Received folder).
 *
 * The mark is best effort: a marker that throws is reported to [onMarkError] and the file stays published, since the
 * bytes are already verified and in place (the app's own installer warning still applies, F‑D5).
 */
class MarkingFileStore(
    private val delegate: FileStore,
    private val marker: DownloadMarker,
    private val onMarkError: (Path, Exception) -> Unit = { _, _ -> },
    private val onPublished: (PublishedFileEvent) -> Unit = {},
) : FileStore by delegate {
    override suspend fun publish(
        partial: PartialFile,
        name: String,
        mimeType: String?,
        drop: DropInfo,
    ): PublishedFile {
        val fileIndex = partial.fileIndex
        val published = delegate.publish(partial, name, mimeType, drop)
        val path = pathOf(published.uri)
        if (path != null) {
            try {
                marker.mark(path, drop.senderName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMarkError(path, e)
            }
        }
        onPublished(PublishedFileEvent(published, path, fileIndex, mimeType, drop))
        return published
    }

    companion object {
        /** The local path of a `file:` URI, or null for anything else. */
        fun pathOf(uri: String): Path? =
            try {
                val parsed = URI(uri)
                if (parsed.scheme == "file") Paths.get(parsed) else null
            } catch (_: Exception) {
                null
            }
    }
}
