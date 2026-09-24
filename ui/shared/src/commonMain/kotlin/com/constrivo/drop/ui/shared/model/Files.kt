package com.constrivo.drop.ui.shared.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/** What a file is, for its glyph and for the "12 photos" summary (design §5.1). */
enum class FileKind {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,

    /** An installed app sent as an APK (decision 8: behind a feature flag). */
    APP,
    OTHER,
    ;

    companion object {
        /** The kind of a MIME type (case-insensitive, parameters ignored); null or unknown types are [OTHER]. */
        fun fromMime(mime: String?): FileKind {
            val type = mime?.substringBefore(';')?.trim()?.lowercase() ?: return OTHER
            return when {
                type.startsWith("image/") -> IMAGE
                type.startsWith("video/") -> VIDEO
                type.startsWith("audio/") -> AUDIO
                type == "application/vnd.android.package-archive" -> APP
                type in ARCHIVES -> ARCHIVE
                type.startsWith("text/") || type in DOCUMENTS || type.startsWith("application/vnd.") -> DOCUMENT
                else -> OTHER
            }
        }

        private val ARCHIVES =
            setOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/x-7z-compressed",
                "application/x-rar-compressed",
                "application/vnd.rar",
                "application/x-tar",
                "application/gzip",
                "application/x-gzip",
            )

        private val DOCUMENTS =
            setOf(
                "application/pdf",
                "application/msword",
                "application/rtf",
                "application/json",
                "application/xml",
                "application/epub+zip",
            )
    }
}

/** A file's preview: a decoded thumbnail or, for documents and while decoding, a type glyph (design §5.1). */
@Immutable
sealed interface FileThumb {
    val kind: FileKind

    /** A decoded thumbnail (the Offer's previews are at most 4 KiB each, N12). */
    class Picture(
        val bitmap: ImageBitmap,
        override val kind: FileKind = FileKind.IMAGE,
    ) : FileThumb

    /** A file-type glyph. */
    data class Glyph(
        override val kind: FileKind,
    ) : FileThumb
}

/** How a set of files is summarised: "12 photos", "3 videos", "5 photos and videos", "7 files" (design §4.3, §5.1). */
enum class SummaryKind { PHOTOS, VIDEOS, MEDIA, FILES }

/**
 * "12 photos" (design §5.1 line 2) as data: the composable turns it into a plural string.
 *
 * @throws IllegalArgumentException for a negative count.
 */
@Immutable
data class ItemSummary(
    val count: Int,
    val kind: SummaryKind,
) {
    init {
        require(count >= 0) { "count must not be negative" }
    }

    companion object {
        /** The summary of files of [kinds]: all images → photos, all videos → videos, both → media, else files. */
        fun of(kinds: Collection<FileKind>): ItemSummary = ItemSummary(kinds.size, kindOf(kinds.toSet()))

        /**
         * The summary of an Offer's MIME histogram (N12): [fileCount] files of which the histogram counts some by MIME
         * type; files it does not cover count as [FileKind.OTHER].
         */
        fun ofHistogram(
            fileCount: Int,
            mimeHistogram: Map<String, Int>,
        ): ItemSummary {
            require(fileCount >= 0) { "file count must not be negative" }
            val kinds = HashSet<FileKind>()
            var covered = 0L
            for ((mime, n) in mimeHistogram) {
                if (n <= 0) continue
                kinds += FileKind.fromMime(mime)
                covered += n
            }
            if (covered < fileCount) kinds += FileKind.OTHER
            return ItemSummary(fileCount, kindOf(kinds))
        }

        private fun kindOf(kinds: Set<FileKind>): SummaryKind =
            when {
                kinds.isEmpty() -> SummaryKind.FILES
                kinds == setOf(FileKind.IMAGE) -> SummaryKind.PHOTOS
                kinds == setOf(FileKind.VIDEO) -> SummaryKind.VIDEOS
                kinds == setOf(FileKind.IMAGE, FileKind.VIDEO) -> SummaryKind.MEDIA
                else -> SummaryKind.FILES
            }
    }
}

/**
 * A file the user picked or shared (file picker, share sheet, drag and drop).
 *
 * @property id stable within the picker (a content URI or path on the platform side).
 * @property sizeBytes null when the platform cannot tell; the running total then counts it as 0.
 */
@Immutable
data class PickedItem(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    val kind: FileKind,
    val thumb: FileThumb? = null,
) {
    init {
        require(sizeBytes == null || sizeBytes >= 0) { "size must not be negative" }
    }
}

/** Files attached from the share sheet (design §4.3) or picked for one send. */
@Immutable
data class AttachedFiles(
    val items: List<PickedItem>,
) {
    val count: Int get() = items.size
    val totalBytes: Long get() = items.sumOf { it.sizeBytes ?: 0L }
    val summary: ItemSummary get() = ItemSummary.of(items.map { it.kind })
}

/**
 * A finished received file in the tray (design §5.2).
 *
 * @property mime the type the file was saved with, if known; with [name] and [kind] it decides the installer warning
 *   ([InstallerFiles], F‑D5).
 * @property senderName who sent it, for that warning.
 */
@Immutable
data class ReceivedFile(
    val id: String,
    val transferId: String,
    val name: String,
    val kind: FileKind,
    val thumb: FileThumb = FileThumb.Glyph(kind),
    val mime: String? = null,
    val senderName: String? = null,
)

/**
 * Files that install or run code when opened (F‑D5 "APK/executable warning"): Android packages, Windows, macOS and
 * Linux installers and executables, and scripts a double-click runs. Opening one first shows a warning sheet.
 *
 * The name decides as well as the type, because the type comes from the sender: a file is an installer when its MIME
 * type or its last extension says so (after the trailing dots and spaces Windows ignores, so `photo.jpg.exe.` counts).
 */
object InstallerFiles {
    fun isInstaller(
        name: String,
        mime: String?,
        kind: FileKind,
    ): Boolean {
        if (kind == FileKind.APP) return true
        val type = mime?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type in MIME_TYPES) return true
        val base = name.trimEnd('.', ' ', '\u00A0')
        val dot = base.lastIndexOf('.')
        if (dot < 0 || dot == base.length - 1) return false
        return base.substring(dot + 1).lowercase() in EXTENSIONS
    }

    private val EXTENSIONS =
        setOf(
            // Android
            "apk",
            "apks",
            "apkm",
            "xapk",
            "aab",
            // Windows
            "exe",
            "msi",
            "msp",
            "msix",
            "msixbundle",
            "appx",
            "appxbundle",
            "bat",
            "cmd",
            "com",
            "scr",
            "pif",
            "cpl",
            "hta",
            "js",
            "jse",
            "vbs",
            "vbe",
            "wsf",
            "wsh",
            "ps1",
            "psm1",
            "reg",
            "lnk",
            // macOS and Linux
            "app",
            "dmg",
            "pkg",
            "mpkg",
            "command",
            "sh",
            "bash",
            "zsh",
            "run",
            "deb",
            "rpm",
            "appimage",
            "snap",
            "flatpakref",
            // Java
            "jar",
        )

    private val MIME_TYPES =
        setOf(
            "application/vnd.android.package-archive",
            "application/x-msdownload",
            "application/x-msdos-program",
            "application/x-ms-dos-executable",
            "application/x-dosexec",
            "application/vnd.microsoft.portable-executable",
            "application/x-ms-installer",
            "application/x-msi",
            "application/x-ms-shortcut",
            "application/x-bat",
            "application/hta",
            "application/x-executable",
            "application/x-elf",
            "application/x-mach-binary",
            "application/x-sh",
            "application/x-shellscript",
            "application/x-csh",
            "application/java-archive",
            "application/x-java-archive",
            "application/x-apple-diskimage",
            "application/vnd.debian.binary-package",
            "application/x-debian-package",
            "application/x-rpm",
            "application/x-redhat-package-manager",
            "application/vnd.appimage",
            "application/x-iso9660-appimage",
        )
}
