package com.constrivo.drop.core.protocol

import com.constrivo.drop.core.protocol.ProtocolConstants.FILE_LIST_PAGE_BUDGET_BYTES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_FILE_LIST_PAGE_ENTRIES
import com.constrivo.drop.core.protocol.ProtocolConstants.MAX_MIME_HISTOGRAM_ENTRIES

/**
 * Splits a transfer's file list into [FileList] pages (spec change N12).
 *
 * Pages fill greedily in index order until the next entry would push the page's encoding past `budgetBytes`
 * (default [ProtocolConstants.FILE_LIST_PAGE_BUDGET_BYTES]) or `maxEntries`; a page always holds at least one
 * entry, and never exceeds [ProtocolConstants.MAX_CONTROL_MESSAGE_BYTES].
 */
object FileListPager {
    fun paginate(
        transferId: TransferId,
        files: List<FileEntry>,
        budgetBytes: Int = FILE_LIST_PAGE_BUDGET_BYTES,
        maxEntries: Int = MAX_FILE_LIST_PAGE_ENTRIES,
    ): List<FileList> {
        require(files.isNotEmpty()) { "a transfer has at least one file" }
        require(files.size <= ProtocolConstants.MAX_FILES_PER_TRANSFER) { "too many files" }
        require(files.withIndex().all { (i, f) -> f.index == i }) { "file indices must run 0 until ${files.size} in order" }
        require(budgetBytes in 1..MAX_CONTROL_MESSAGE_BYTES) { "budget must be 1..$MAX_CONTROL_MESSAGE_BYTES bytes" }
        require(maxEntries in 1..MAX_FILE_LIST_PAGE_ENTRIES) { "maxEntries must be 1..$MAX_FILE_LIST_PAGE_ENTRIES" }

        // Envelope, map head, transfer id, page number, last flag and array head take at most this many bytes.
        val overhead = ControlCodec.encodedSize(FileList(transferId, Int.MAX_VALUE, true, listOf(files[0]))) - entrySize(files[0]) + 4
        val pages = ArrayList<FileList>()
        var start = 0
        while (start < files.size) {
            var end = start
            var size = overhead
            while (end < files.size && end - start < maxEntries) {
                val next = entrySize(files[end])
                if (end > start && size + next > budgetBytes) break
                size += next
                end++
            }
            pages += FileList(transferId, pages.size, end == files.size, files.subList(start, end).toList())
            start = end
        }
        return pages
    }

    private fun entrySize(entry: FileEntry): Int = ProtocolCbor.encodeToByteArray(FileEntry.serializer(), entry).size
}

/**
 * Receiver-side check of the [FileList] pages for one [offer] (spec changes N12, S4).
 *
 * [add] accepts pages in order and returns the complete list when the last page arrives. It throws
 * [ProtocolException] for another transfer's page, a page out of order, non-contiguous file indices, more files than
 * `file_count`, a file with more chunks than a u31 chunk index can name, a last page that leaves files missing, sizes
 * that do not add up to `total_bytes` (checked without overflow, so the sizes the user accepted are the sizes that
 * arrive), a bundle plan whose bundle count differs from `bundle_count`, or a page after the last one.
 */
class FileListAssembler(
    private val offer: Offer,
) {
    private val files = ArrayList<FileEntry>(minOf(offer.fileCount, MAX_FILE_LIST_PAGE_ENTRIES))
    private var nextPage = 0
    private var total = 0L

    var isComplete: Boolean = false
        private set

    /** The files received so far, in index order. */
    val received: List<FileEntry> get() = files

    fun add(page: FileList): List<FileEntry>? {
        if (isComplete) throw ProtocolException("FileList page ${page.page} after the last page")
        if (page.transferId != offer.transferId) throw ProtocolException("FileList for another transfer")
        if (page.page != nextPage) throw ProtocolException("FileList page ${page.page} out of order, expected $nextPage")
        for (entry in page.files) {
            if (entry.index != files.size) throw ProtocolException("FileList skips from file ${files.size} to ${entry.index}")
            if (files.size >= offer.fileCount) throw ProtocolException("FileList lists more than ${offer.fileCount} files")
            // total <= totalBytes holds before each entry, so the subtraction cannot overflow and the sum never wraps.
            if (entry.size > offer.totalBytes - total) throw ProtocolException("FileList sizes exceed total_bytes ${offer.totalBytes}")
            checkChunkCount(entry.index, entry.size, offer.chunkSize)
            total += entry.size
            files += entry
        }
        nextPage++
        if (!page.last) return null
        if (files.size != offer.fileCount) throw ProtocolException("FileList ended after ${files.size} of ${offer.fileCount} files")
        if (total != offer.totalBytes) throw ProtocolException("FileList sizes add up to $total, offer says ${offer.totalBytes}")
        val plan = BundlePlan.of(files.map { it.size }, offer.chunkSize, offer.bundleSmall)
        if (plan.bundleCount != offer.bundleCount) {
            throw ProtocolException("bundle plan has ${plan.bundleCount} bundles, offer says ${offer.bundleCount}")
        }
        isComplete = true
        return files.toList()
    }
}

/**
 * Builds the `Offer.mime_histogram` (N12) deterministically: counts per lower-cased MIME type (a missing or blank
 * type counts as [UNKNOWN_MIME]). Above `maxEntries` distinct types, each type collapses to its top-level wildcard
 * bucket (`image/` followed by an asterisk), or to [ANY] when it has no top-level type or the bucket would exceed
 * [ProtocolConstants.MAX_MIME_BYTES]; if that is still too many, the largest buckets are kept and the rest merge into
 * [ANY]. The result is in canonical key order and sums to the number of inputs.
 */
object MimeHistogram {
    const val UNKNOWN_MIME: String = "application/octet-stream"
    const val ANY: String = "*/*"

    fun of(
        mimeTypes: Iterable<String?>,
        maxEntries: Int = MAX_MIME_HISTOGRAM_ENTRIES,
    ): Map<String, Int> {
        require(maxEntries in 1..MAX_MIME_HISTOGRAM_ENTRIES) { "maxEntries must be 1..$MAX_MIME_HISTOGRAM_ENTRIES" }
        val counts = HashMap<String, Int>()
        for (mime in mimeTypes) {
            val key =
                mime?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && utf8Length(it) in 1..ProtocolConstants.MAX_MIME_BYTES }
                    ?: UNKNOWN_MIME
            counts[key] = (counts[key] ?: 0) + 1
        }
        var result: Map<String, Int> = counts
        if (result.size > maxEntries) {
            val buckets = HashMap<String, Int>()
            for ((mime, count) in result) {
                val bucket = bucketOf(mime)
                buckets[bucket] = (buckets[bucket] ?: 0) + count
            }
            result = buckets
        }
        if (result.size > maxEntries) {
            val ranked = result.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            val kept = HashMap<String, Int>()
            var rest = 0
            for ((i, entry) in ranked.withIndex()) {
                if (i < maxEntries - 1 && entry.key != ANY) kept[entry.key] = entry.value else rest += entry.value
            }
            kept[ANY] = (kept[ANY] ?: 0) + rest
            result = kept
        }
        return canonicalOrder(result)
    }

    /** `type/` plus an asterisk for `type/subtype`; [ANY] for a value without a top-level type or one too long to bucket. */
    private fun bucketOf(mime: String): String {
        val slash = mime.indexOf('/')
        if (slash <= 0) return ANY
        val bucket = mime.substring(0, slash) + "/*"
        return if (utf8Length(bucket) in 1..ProtocolConstants.MAX_MIME_BYTES) bucket else ANY
    }
}
