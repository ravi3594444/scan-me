package com.constrivo.drop.platform.android.storage

import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.core.transfer.receive.FileNameSanitizer
import com.constrivo.drop.core.transfer.receive.FileTypes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Where a received file goes (F-D4, architecture §10.1 "Storage"): the MediaStore default (media to the gallery
 * collections, documents to Downloads), or a folder the user picked with the system folder picker (Settings → Save
 * location, a SAF tree URI in `core/data`'s `SettingKeys.SAVE_LOCATION`).
 */
sealed interface ReceiveDestination {
    /** MediaStore on [volumeName] (`external_primary`, or a removable card's volume name). */
    data class MediaStoreVolume(
        val volumeName: String = PRIMARY_VOLUME,
    ) : ReceiveDestination {
        init {
            require(volumeName.isNotBlank()) { "volume name must not be blank" }
        }
    }

    /** A document tree the user picked (`ACTION_OPEN_DOCUMENT_TREE`, with a persisted read and write grant). */
    data class DocumentTree(
        val treeUri: String,
    ) : ReceiveDestination {
        init {
            require(treeUri.startsWith("content://")) { "a document tree is a content URI" }
        }
    }

    companion object {
        /** `MediaStore.VOLUME_EXTERNAL_PRIMARY`. */
        const val PRIMARY_VOLUME: String = "external_primary"

        /** The destination a stored save location names: a `content:` tree URI, else the MediaStore default. */
        fun fromSetting(saveLocation: String?): ReceiveDestination =
            if (saveLocation != null && saveLocation.startsWith("content://")) DocumentTree(saveLocation) else MediaStoreVolume()
    }
}

/** A MediaStore collection received files are written to, with its standard top-level directory. */
enum class MediaCollection(
    val topDirectory: String,
) {
    /** `MediaStore.Images`: `Pictures/`. */
    IMAGES("Pictures"),

    /** `MediaStore.Video`: `Movies/`. */
    VIDEO("Movies"),

    /** `MediaStore.Audio`: `Music/`. */
    AUDIO("Music"),

    /** `MediaStore.Downloads`: `Download/`, for everything that is not media. */
    DOWNLOADS("Download"),
}

/**
 * Where one received file is created: the [collection], the directory relative to the volume root
 * ([relativePath], always ending in `/`), the MIME type it is stored with, and its sanitised [displayName].
 */
data class MediaTarget(
    val collection: MediaCollection,
    val relativePath: String,
    val mimeType: String,
    val displayName: String,
)

/**
 * The MediaStore collection routing of received files (F-D3 "media visible in Gallery immediately after completion",
 * F-D4, F-D5), a pure function tested off-device:
 *
 * - The type comes from the file's **extension** when it names one ([mimeForExtension], Android's `MimeTypeMap`):
 *   MediaStore insists that name and type agree (it appends an extension to a name that does not match its type), and
 *   the name is what the user sees, so a sender cannot put a file in the gallery by claiming a picture type for it.
 *   Without a known extension the offered type is used only for non-media files, else `application/octet-stream`.
 * - `image/…` → [MediaCollection.IMAGES] (`Pictures/Drop/`), `video/…` → [MediaCollection.VIDEO] (`Movies/Drop/`),
 *   `audio/…` → [MediaCollection.AUDIO] (`Music/Drop/`), everything else → [MediaCollection.DOWNLOADS]
 *   (`Download/Drop/`). Installers and executables (F-D5) always go to Downloads, whatever type they claim.
 * - A drop of more than 20 files gets one subfolder per drop, `<sender> yyyy-MM-dd HH.mm.ss` (design §9, as on the
 *   desktops), inside each collection's directory ([dropFolderName]).
 */
object MediaRouting {
    /** The folder under each collection's top directory. */
    const val APP_FOLDER: String = AppIdentity.DISPLAY_NAME

    /** The type of files with no usable type (never media). */
    const val GENERIC_MIME: String = "application/octet-stream"

    /** Design §9: a drop of more than this many files goes into its own subfolder. */
    const val SUBFOLDER_THRESHOLD: Int = 20

    private val FOLDER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")

    /**
     * The target of a file named [name] (already sanitised, or sanitised here) offered with type [offeredMime], in the
     * per-drop [subfolder] (null for none).
     */
    fun route(
        name: String,
        offeredMime: String?,
        mimeForExtension: (String) -> String?,
        subfolder: String? = null,
    ): MediaTarget {
        val displayName = FileNameSanitizer.sanitize(name)
        val mime = mimeOf(displayName, offeredMime, mimeForExtension)
        val executable = FileTypes.isExecutable(displayName, mime) || FileTypes.isAndroidPackage(displayName, mime)
        val collection =
            when {
                executable -> MediaCollection.DOWNLOADS
                mime.startsWith("image/") -> MediaCollection.IMAGES
                mime.startsWith("video/") -> MediaCollection.VIDEO
                mime.startsWith("audio/") -> MediaCollection.AUDIO
                else -> MediaCollection.DOWNLOADS
            }
        val folder = subfolder?.let { FileNameSanitizer.sanitize(it) }
        val relativePath =
            buildString {
                append(collection.topDirectory).append('/').append(APP_FOLDER).append('/')
                if (folder != null) append(folder).append('/')
            }
        return MediaTarget(collection, relativePath, mime, displayName)
    }

    /** The stored type of [displayName]: its extension's, else a non-media offered type, else [GENERIC_MIME]. */
    fun mimeOf(
        displayName: String,
        offeredMime: String?,
        mimeForExtension: (String) -> String?,
    ): String {
        val extension = FileNameSanitizer.extensionOf(displayName)?.lowercase()
        val byExtension = extension?.let(mimeForExtension)?.let(::normalise)
        if (byExtension != null) return byExtension
        val offered = offeredMime?.let(::normalise) ?: return GENERIC_MIME
        val media = offered.startsWith("image/") || offered.startsWith("video/") || offered.startsWith("audio/")
        return if (media) GENERIC_MIME else offered
    }

    /** The per-drop subfolder name for a drop of [fileCount] files, or null when it needs none (design §9). */
    fun dropFolderName(
        fileCount: Int,
        senderName: String?,
        receivedAtMillis: Long,
        zone: ZoneId,
    ): String? {
        if (fileCount <= SUBFOLDER_THRESHOLD) return null
        val stamp = FOLDER_TIME.format(Instant.ofEpochMilli(receivedAtMillis).atZone(zone))
        val label = listOfNotNull(senderName?.trim()?.takeIf { it.isNotEmpty() }, stamp).joinToString(" ")
        return FileNameSanitizer.sanitize(label)
    }

    /** `type/subtype` in lower case without parameters, or null for anything that is not one specific type. */
    private fun normalise(mime: String): String? {
        val type = mime.substringBefore(';').trim().lowercase()
        val slash = type.indexOf('/')
        if (slash <= 0 || slash == type.length - 1 || '*' in type || type.count { it == '/' } != 1) return null
        if (type.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7E }) return null
        return type
    }
}
