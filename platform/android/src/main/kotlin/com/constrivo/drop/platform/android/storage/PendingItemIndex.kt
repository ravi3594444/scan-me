package com.constrivo.drop.platform.android.storage

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** A stored index entry could not be read: not in [PendingItemIndex]'s format. The store treats it as absent. */
class PendingIndexFormatException(
    message: String,
) : IOException(message)

/**
 * The app-private record of which pending item holds which partial (spec change N14: the partial bytes live in a
 * hidden MediaStore row or document, "the manifest stays app-private"), so a transfer resumed after an app kill
 * (T-07) writes into the same item, and the 24 h sweep or a cancel can delete items it did not create in this run.
 *
 * Layout: `<root>/<transfer_id>/<file_index>.item` per file and `<root>/<transfer_id>/drop.folder` for the drop's
 * subfolder name, beside the resume plan `FileResumePlanStore` keeps in the same directory; deleting the transfer's
 * directory ([deleteTransfer]) removes all of it. Writes go to a temporary file and are renamed atomically. Values are
 * one `key=value` per line after a header line, like the resume plan.
 *
 * Blocking `java.nio.file` I/O; thread-safe for different files, and the store serialises per file.
 */
class PendingItemIndex(
    val root: Path,
) {
    /** The directory of [transferId]. @throws IllegalArgumentException for an id that is not a plain token. */
    fun directoryOf(transferId: String): Path {
        require(
            transferId.isNotEmpty() && transferId.length <= MAX_ID &&
                transferId.all {
                    it.isLetterOrDigit() || it == '-' || it == '_'
                },
        ) {
            "transfer id must be a plain token"
        }
        return root.resolve(transferId)
    }

    /** The item recorded for [fileIndex] of [transferId], or null (none, or an unreadable record). */
    fun read(
        transferId: String,
        fileIndex: Int,
    ): PendingItemRef? {
        val text = readText(itemFile(transferId, fileIndex)) ?: return null
        return try {
            parseItem(text)
        } catch (_: PendingIndexFormatException) {
            null
        }
    }

    /** Records [ref] for [fileIndex] of [transferId], replacing an earlier record. */
    fun write(
        transferId: String,
        fileIndex: Int,
        ref: PendingItemRef,
    ) = writeAtomically(itemFile(transferId, fileIndex), formatItem(ref))

    /** Forgets the record of [fileIndex] (its item was published or deleted). */
    fun remove(
        transferId: String,
        fileIndex: Int,
    ) {
        Files.deleteIfExists(itemFile(transferId, fileIndex))
    }

    /** Every readable record of [transferId], by file index. */
    fun all(transferId: String): Map<Int, PendingItemRef> {
        val dir = directoryOf(transferId)
        if (!Files.isDirectory(dir)) return emptyMap()
        val out = LinkedHashMap<Int, PendingItemRef>()
        Files.list(dir).use { entries ->
            for (path in entries) {
                val name = path.fileName.toString()
                if (!name.endsWith(ITEM_SUFFIX)) continue
                val index = name.removeSuffix(ITEM_SUFFIX).toIntOrNull()?.takeIf { it >= 0 } ?: continue
                read(transferId, index)?.let { out[index] = it }
            }
        }
        return out
    }

    /** The drop subfolder chosen for [transferId] (design §9), [DropFolder.NONE] for none, or null before it was chosen. */
    fun dropFolder(transferId: String): DropFolder? {
        val text = readText(directoryOf(transferId).resolve(FOLDER_FILE)) ?: return null
        return try {
            parseFolder(text)
        } catch (_: PendingIndexFormatException) {
            null
        }
    }

    fun writeDropFolder(
        transferId: String,
        folder: DropFolder,
    ) = writeAtomically(directoryOf(transferId).resolve(FOLDER_FILE), formatFolder(folder))

    /** Deletes the transfer's whole directory: records, the drop folder choice and the resume plan beside them. */
    fun deleteTransfer(transferId: String) {
        val dir = directoryOf(transferId)
        if (!Files.isDirectory(dir)) return
        Files.list(dir).use { entries -> entries.forEach { runCatching { Files.deleteIfExists(it) } } }
        Files.deleteIfExists(dir)
    }

    private fun itemFile(
        transferId: String,
        fileIndex: Int,
    ): Path {
        require(fileIndex >= 0) { "file index must be non-negative" }
        return directoryOf(transferId).resolve("$fileIndex$ITEM_SUFFIX")
    }

    private fun readText(path: Path): String? =
        try {
            if (Files.size(path) > MAX_TEXT) return null
            String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        } catch (_: NoSuchFileException) {
            null
        }

    private fun writeAtomically(
        target: Path,
        text: String,
    ) {
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(temp, text.toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** The per-drop subfolder of a transfer: its [name], or none ([NONE]). */
    data class DropFolder(
        val name: String?,
    ) {
        init {
            require(name == null || (name.isNotEmpty() && name.none { it == '\n' || it == '\r' || it == '/' })) { "bad folder name" }
        }

        companion object {
            val NONE: DropFolder = DropFolder(null)
        }
    }

    companion object {
        const val ITEM_SUFFIX: String = ".item"
        const val FOLDER_FILE: String = "drop.folder"
        private const val ITEM_HEADER = "drop-pending-item 1"
        private const val FOLDER_HEADER = "drop-folder 1"
        private const val MAX_TEXT = 8192L
        private const val MAX_ID = 64

        fun formatItem(ref: PendingItemRef): String =
            buildString {
                append(ITEM_HEADER).append('\n')
                append("kind=").append(ref.kind.wireName).append('\n')
                append("collection=").append(ref.collection?.name.orEmpty()).append('\n')
                append("mime=").append(ref.mimeType).append('\n')
                append("uri=").append(ref.uri).append('\n')
                append("parent=").append(ref.parentUri.orEmpty()).append('\n')
            }

        /** @throws PendingIndexFormatException unless [text] is what [formatItem] writes. */
        fun parseItem(text: String): PendingItemRef {
            val values = parse(text, ITEM_HEADER)
            val kind = values["kind"]?.let(PendingItemKind::fromWire) ?: throw PendingIndexFormatException("unknown item kind")
            val collectionName = values["collection"] ?: throw PendingIndexFormatException("no collection")
            val collection =
                if (collectionName.isEmpty()) {
                    null
                } else {
                    MediaCollection.entries.firstOrNull { it.name == collectionName }
                        ?: throw PendingIndexFormatException("unknown collection")
                }
            val mime = values["mime"] ?: throw PendingIndexFormatException("no mime")
            val uri = values["uri"] ?: throw PendingIndexFormatException("no uri")
            val parent = values["parent"] ?: throw PendingIndexFormatException("no parent")
            return try {
                PendingItemRef(uri, kind, collection, mime, parent.ifEmpty { null })
            } catch (e: IllegalArgumentException) {
                throw PendingIndexFormatException(e.message ?: "bad item")
            }
        }

        fun formatFolder(folder: DropFolder): String = "$FOLDER_HEADER\nname=${folder.name.orEmpty()}\n"

        /** @throws PendingIndexFormatException unless [text] is what [formatFolder] writes. */
        fun parseFolder(text: String): DropFolder {
            val name = parse(text, FOLDER_HEADER)["name"] ?: throw PendingIndexFormatException("no folder name")
            return try {
                DropFolder(name.ifEmpty { null })
            } catch (e: IllegalArgumentException) {
                throw PendingIndexFormatException(e.message ?: "bad folder name")
            }
        }

        private fun parse(
            text: String,
            header: String,
        ): Map<String, String> {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            if (lines.firstOrNull() != header) throw PendingIndexFormatException("missing header '$header'")
            val values = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val eq = line.indexOf('=')
                if (eq <= 0) throw PendingIndexFormatException("malformed line")
                if (values.put(line.substring(0, eq), line.substring(eq + 1)) != null) {
                    throw PendingIndexFormatException("duplicate key")
                }
            }
            return values
        }
    }
}
