package com.constrivo.drop.platform.android.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.constrivo.drop.core.transfer.receive.FileNameSanitizer
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * [PendingItemResolver] and [ContentSourceResolver] on the device's content resolver (spec change N14, architecture
 * §10.1 "Storage"):
 *
 * - **MediaStore** ([ReceiveDestination.MediaStoreVolume]): a row inserted into the collection of the file's type
 *   ([MediaRouting]) with `IS_PENDING = 1`, `RELATIVE_PATH` and `DISPLAY_NAME`, written through
 *   `openFileDescriptor(uri, "rw")`, and published with one update that clears `IS_PENDING` under the first free name
 *   in its folder (a taken name gets ` (n)`, and MediaStore itself never replaces a file). Pending rows are hidden from
 *   other apps and expire by themselves after seven days if something leaves them behind.
 * - **A picked folder** ([ReceiveDestination.DocumentTree], SAF with a persisted grant): a document created under a
 *   hidden temporary name (`.drop-<id>-<n>.part`, type `application/octet-stream` so no provider appends an
 *   extension), in the drop's subfolder when there is one, renamed to its free final name when published.
 * - **Sources**: `OpenableColumns` for name and size, the provider's type, and a read descriptor when the provider hands
 *   out a seekable one; paths are accepted only for app packages (`.apk`, the picker's Apps tab behind decision 8).
 *
 * Deletions only ever remove what is still pending: a MediaStore row with `IS_PENDING` set, or a document still under
 * its temporary name, so an out-of-date record can never delete a file the user already has.
 */
class AndroidPendingItemResolver(
    context: Context,
    private val volumes: StorageVolumes = StorageVolumes(context),
) : PendingItemResolver,
    ContentSourceResolver {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    // ---- PendingItemResolver ----

    override fun create(spec: PendingItemSpec): PendingItemRef =
        when (val destination = spec.destination) {
            is ReceiveDestination.MediaStoreVolume -> createMediaItem(destination, spec)
            is ReceiveDestination.DocumentTree -> createDocument(destination, spec)
        }

    private fun createMediaItem(
        destination: ReceiveDestination.MediaStoreVolume,
        spec: PendingItemSpec,
    ): PendingItemRef {
        val target = spec.target
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, target.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, target.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collectionUri(target.collection, destination.volumeName), values)
        return PendingItemRef(
            uri?.toString() ?: throw IOException("MediaStore refused a pending item for ${target.displayName}"),
            PendingItemKind.MEDIA_STORE,
            target.collection,
            target.mimeType,
        )
    }

    private fun createDocument(
        destination: ReceiveDestination.DocumentTree,
        spec: PendingItemSpec,
    ): PendingItemRef {
        val tree = destination.treeUri.toUri()
        var parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        spec.subfolder?.let { parent = folderIn(parent, FileNameSanitizer.sanitize(it)) }
        val temporary = "$TEMP_PREFIX${spec.transferId.take(TEMP_ID_CHARS)}-${spec.fileIndex}$TEMP_SUFFIX"
        val document =
            DocumentsContract.createDocument(resolver, parent, MediaRouting.GENERIC_MIME, temporary)
                ?: throw IOException("the folder refused a new file")
        return PendingItemRef(document.toString(), PendingItemKind.DOCUMENT, null, spec.target.mimeType, parent.toString())
    }

    /** The child folder [name] of [parent], created when missing. */
    private fun folderIn(
        parent: Uri,
        name: String,
    ): Uri {
        childNamed(parent, name)?.let { (uri, mime) ->
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) return uri
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("the folder refused a subfolder")
    }

    override fun exists(ref: PendingItemRef): Boolean =
        try {
            val uri = ref.uri.toUri()
            when (ref.kind) {
                PendingItemKind.MEDIA_STORE -> {
                    resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), includePending(), null)?.use { c ->
                        c.moveToFirst() && c.getInt(0) == 1
                    } ?: false
                }

                PendingItemKind.DOCUMENT -> {
                    displayName(uri)?.let { it.startsWith(TEMP_PREFIX) && it.endsWith(TEMP_SUFFIX) } ?: false
                }
            }
        } catch (e: FileNotFoundException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: SecurityException) {
            false
        }

    override fun open(ref: PendingItemRef): PositionalFile {
        val descriptor =
            resolver.openFileDescriptor(ref.uri.toUri(), "rw") ?: throw IOException("no descriptor for the partial")
        return DescriptorFile(descriptor)
    }

    override fun publish(
        ref: PendingItemRef,
        target: MediaTarget,
    ): PublishedItem =
        when (ref.kind) {
            PendingItemKind.MEDIA_STORE -> publishMediaItem(ref, target)
            PendingItemKind.DOCUMENT -> publishDocument(ref, target)
        }

    private fun publishMediaItem(
        ref: PendingItemRef,
        target: MediaTarget,
    ): PublishedItem {
        val uri = ref.uri.toUri()
        val collection = checkNotNull(ref.collection)
        val volume = MediaStore.getVolumeName(uri)
        var attempt = 0
        while (true) {
            val name = freeMediaName(collectionUri(collection, volume), target.relativePath, target.displayName, attempt, uri)
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
            val updated =
                try {
                    resolver.update(uri, values, null, null)
                } catch (e: IllegalStateException) {
                    // Another file took the name between the check and the update.
                    if (++attempt < MAX_NAME_ATTEMPTS) continue
                    throw IOException("no free name for ${target.displayName}", e)
                }
            if (updated != 1) throw IOException("MediaStore did not publish ${target.displayName}")
            // MediaStore may still have adjusted the name (its own uniqueness rule); report what it kept.
            val stored =
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                }
            return PublishedItem(uri.toString(), stored ?: name)
        }
    }

    /** The first of `name`, `name (1)`, … from [skip] on that no other row in [relativePath] of [collection] uses. */
    private fun freeMediaName(
        collection: Uri,
        relativePath: String,
        name: String,
        skip: Int,
        self: Uri,
    ): String {
        val selfId = runCatching { self.lastPathSegment?.toLong() }.getOrNull()
        for (n in skip until MAX_COLLISIONS) {
            val candidate = if (n == 0) name else FileNameSanitizer.withCollisionSuffix(name, n)
            val args =
                includePending().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    )
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relativePath, candidate))
                }
            val taken =
                resolver.query(collection, arrayOf(BaseColumns._ID), args, null)?.use { c ->
                    var other = false
                    while (c.moveToNext()) if (c.getLong(0) != selfId) other = true
                    other
                } ?: false
            if (!taken) return candidate
        }
        throw IOException("too many files named $name")
    }

    private fun publishDocument(
        ref: PendingItemRef,
        target: MediaTarget,
    ): PublishedItem {
        val uri = ref.uri.toUri()
        val parent = checkNotNull(ref.parentUri).toUri()
        val taken = childNames(parent)
        var attempt = 0
        while (true) {
            val name =
                (attempt until MAX_COLLISIONS).asSequence().map { n ->
                    if (n ==
                        0
                    ) {
                        target.displayName
                    } else {
                        FileNameSanitizer.withCollisionSuffix(target.displayName, n)
                    }
                }
                    .firstOrNull { it !in taken } ?: throw IOException("too many files named ${target.displayName}")
            val renamed =
                try {
                    DocumentsContract.renameDocument(resolver, uri, name)
                } catch (e: IllegalStateException) {
                    if (++attempt < MAX_NAME_ATTEMPTS) continue
                    throw IOException("no free name for ${target.displayName}", e)
                } ?: uri
            val stored = displayName(renamed) ?: name
            return PublishedItem(renamed.toString(), stored)
        }
    }

    override fun delete(ref: PendingItemRef) {
        val uri = ref.uri.toUri()
        try {
            when (ref.kind) {
                PendingItemKind.MEDIA_STORE -> {
                    val pending =
                        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), includePending(), null)?.use { c ->
                            c.moveToFirst() && c.getInt(0) == 1
                        } ?: false
                    if (pending) resolver.delete(uri, null, null)
                }

                PendingItemKind.DOCUMENT -> {
                    val name = displayName(uri) ?: return
                    if (name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX)) DocumentsContract.deleteDocument(resolver, uri)
                }
            }
        } catch (e: FileNotFoundException) {
            // Gone already.
        } catch (e: IllegalArgumentException) {
            // Gone already (MediaStore answers an unknown row this way).
        }
    }

    override fun freeBytes(destination: ReceiveDestination): Long = volumes.freeBytes(destination)

    override fun isRemovable(destination: ReceiveDestination): Boolean = volumes.isRemovable(destination)

    // ---- ContentSourceResolver ----

    override fun describe(uri: String): SourceFacts? {
        if (isPackagePath(uri)) {
            val file = File(uri)
            if (!file.isFile || !file.canRead()) return null
            return SourceFacts(file.name, file.length(), APK_MIME, file.lastModified())
        }
        val parsed = uri.toUri()
        if (parsed.scheme != ContentResolver.SCHEME_CONTENT) return null
        var name: String? = null
        var size: Long? = null
        var modified: Long? = null
        try {
            resolver.query(parsed, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }?.let {
                        modified = c.getLong(it)
                    }
                    if (modified == null) {
                        c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }?.let {
                            modified = c.getLong(it) * 1000
                        }
                    }
                }
            } ?: return null
        } catch (e: IllegalArgumentException) {
            // A provider without OpenableColumns: the rest can still say something.
        } catch (e: SecurityException) {
            return null
        } catch (e: RuntimeException) {
            // A hostile or broken provider (a mistyped column): this file cannot be described.
            return null
        }
        val type = runCatching { resolver.getType(parsed) }.getOrNull()
        return SourceFacts(name, size?.takeIf { it >= 0 }, type, modified?.takeIf { it > 0 })
    }

    override fun openRead(uri: String): PositionalFile? {
        val descriptor =
            if (isPackagePath(uri)) {
                ParcelFileDescriptor.open(File(uri), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                resolver.openFileDescriptor(uri.toUri(), "r") ?: return null
            }
        if (descriptor.statSize < 0) {
            // A pipe or a socket: it cannot seek, so the source streams instead.
            runCatching { descriptor.close() }
            return null
        }
        return DescriptorFile(descriptor)
    }

    override fun openStream(uri: String): InputStream =
        if (isPackagePath(uri)) {
            File(uri).inputStream()
        } else {
            resolver.openInputStream(uri.toUri()) ?: throw IOException("no stream for $uri")
        }

    // ---- helpers ----

    private fun childNames(parent: Uri): Set<String> {
        val out = HashSet<String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (!c.isNull(0)) out += c.getString(0)
        }
        return out
    }

    private fun childNamed(
        parent: Uri,
        name: String,
    ): Pair<Uri, String?>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val columns =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(1) && c.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0)) to
                        (if (c.isNull(2)) null else c.getString(2))
                }
            }
        }
        return null
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    private fun includePending(): Bundle = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }

    companion object {
        private const val TEMP_PREFIX = ".drop-"
        private const val TEMP_SUFFIX = ".part"
        private const val TEMP_ID_CHARS = 12
        private const val MAX_COLLISIONS = 10_000
        private const val MAX_NAME_ATTEMPTS = 5
        private const val APK_MIME = "application/vnd.android.package-archive"

        /** The MediaStore collection URI of [collection] on [volume]. */
        fun collectionUri(
            collection: MediaCollection,
            volume: String,
        ): Uri =
            when (collection) {
                MediaCollection.IMAGES -> MediaStore.Images.Media.getContentUri(volume)
                MediaCollection.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
                MediaCollection.AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
                MediaCollection.DOWNLOADS -> MediaStore.Downloads.getContentUri(volume)
            }

        /** An installed app's package file (the picker's Apps tab); the only paths accepted as sources. */
        fun isPackagePath(uri: String): Boolean = uri.startsWith("/") && uri.endsWith(".apk") && ".." !in uri

        /** `MimeTypeMap`'s type for an extension, the production `mimeForExtension` of [MediaRouting]. */
        fun mimeForExtension(extension: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
}
