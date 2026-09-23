package com.constrivo.drop.web

import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.core.discovery.Nicknames
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * What the browser page offers: who sends, which files, and the header line (design §10: "{name} wants to send you
 * 12 photos · 48 MB").
 *
 * Names are sanitised and made unique once, here ([displayNames]), so the file list, the per-file downloads and the
 * zip entries always agree.
 *
 * @param senderName the sender's nickname; sanitised like a discovery nickname ([Nicknames.normalize]).
 * @param files at least one file, in the order the page lists them (`file/{index}` addresses this list).
 * @param summary the text after "wants to send you"; defaults to [OfferSummary.describe]. The phone app passes a
 *   localised string here.
 * @param archiveName the `all.zip` download name; defaults to "Files from {sender}.zip".
 */
class ReceiveOffer(
    senderName: String,
    val files: List<SharedFile>,
    summary: String? = null,
    archiveName: String? = null,
) {
    init {
        require(files.isNotEmpty()) { "an offer needs at least one file" }
        files.forEachIndexed { i, file -> require(file.size >= 0) { "file $i has a negative size" } }
    }

    /** The sanitised sender name shown in the page header, or the app's display name when nothing is left. */
    val senderName: String = Nicknames.normalize(senderName)?.text ?: AppIdentity.DISPLAY_NAME

    /** Sanitised, case-insensitively unique names, index for index with [files]. */
    val displayNames: List<String> = FileNames.deduplicate(files.map { FileNames.sanitize(it.name) })

    /** MIME types as served, index for index with [files] ([MimeTypes.sanitize]). */
    val mimeTypes: List<String> = files.map { MimeTypes.sanitize(it.mimeType) }

    /** Sum of the file sizes. */
    val totalBytes: Long = files.fold(0L) { sum, file -> Math.addExact(sum, file.size) }

    /** The header text after "wants to send you". */
    val summary: String = summary?.let { Nicknames.normalize(it, MAX_SUMMARY_BYTES)?.text } ?: OfferSummary.describe(mimeTypes, totalBytes)

    /** The zip download name. */
    val archiveName: String = FileNames.sanitize(archiveName ?: "Files from ${this.senderName}.zip", fallback = "files.zip")

    private companion object {
        const val MAX_SUMMARY_BYTES = 128
    }
}

/** The default header summary: "12 photos · 48 MB", "1 video · 1.2 GB", "3 files · 340 KB". */
object OfferSummary {
    /** Describes files with the given (sanitised) [mimeTypes] and [totalBytes], in English. */
    fun describe(
        mimeTypes: List<String>,
        totalBytes: Long,
    ): String {
        val count = mimeTypes.size
        val images = mimeTypes.count { it.startsWith("image/") }
        val videos = mimeTypes.count { it.startsWith("video/") }
        val noun =
            when {
                images == count -> if (count == 1) "photo" else "photos"
                videos == count -> if (count == 1) "video" else "videos"
                images + videos == count -> "photos and videos"
                else -> if (count == 1) "file" else "files"
            }
        return "$count $noun · ${formatBytes(totalBytes)}"
    }

    /**
     * Decimal units as in every speed and size readout (spec change S6): `999 B`, `1.2 KB`, `48 MB`, `1.5 GB`. One
     * decimal below 10 units (dropped when it is `.0`), none from 10 up.
     */
    fun formatBytes(bytes: Long): String {
        require(bytes >= 0) { "bytes must not be negative" }
        if (bytes < 1000) return "$bytes B"
        for (i in UNITS.indices) {
            val value = bytes / 1000.0.pow(i + 1)
            val last = i == UNITS.lastIndex
            if (value < 9.95) {
                val tenths = (value * 10).roundToLong()
                val text = if (tenths % 10 == 0L) (tenths / 10).toString() else String.format(Locale.ROOT, "%.1f", tenths / 10.0)
                return "$text ${UNITS[i]}"
            }
            if (value < 999.5 || last) return "${value.roundToLong()} ${UNITS[i]}"
        }
        error("unreachable")
    }

    private val UNITS = listOf("KB", "MB", "GB", "TB", "PB")
}
