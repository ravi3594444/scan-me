package com.constrivo.drop.platform.desktop.data

import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.receive.ResumeSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A stored resume plan could not be read: not in [FileResumePlanStore]'s format. The adapter treats it as no record
 * (the transfer starts over, which is always safe).
 */
class ResumePlanFormatException(
    message: String,
) : Exception(message)

/**
 * The part of a receiver's resume record (architecture §7.6) that `core/data`'s schema has no column for: the
 * `Offer`'s unit plan ([ResumeSummary]: file count, total bytes, chunk size, bundling and bundle count). A resumed
 * `Offer` must repeat it exactly, or the transfer starts over. [DataResumeStore] keeps everything else in the database.
 */
interface ResumePlanStore {
    /** The stored plan, or null when there is none. @throws ResumePlanFormatException for a malformed one. */
    suspend fun read(transferId: TransferId): ResumeSummary?

    suspend fun write(
        transferId: TransferId,
        summary: ResumeSummary,
    )

    suspend fun delete(transferId: TransferId)
}

/**
 * [ResumePlanStore] as a small text file `resume.plan` in the transfer's partial-file directory ([directoryOf],
 * `<partials>/<transfer_id>/` of `DirectoryFileStore`), so it goes wherever the partial files go: deleting the
 * partials (completion, cancel, the 24 h sweep, "Clear partial files") deletes the plan with them, and a plan never
 * outlives the bytes it describes. Writes are atomic (temporary file, then a rename). Uses only `java.nio.file`, so the
 * Android app can use it for its app-private partials too.
 *
 * Format, one `key=value` per line after the header line `drop-resume-plan 1`: `files`, `bytes`, `chunk`, `bundle`
 * (`true`/`false`) and `bundles`, all decimal.
 */
class FileResumePlanStore(
    private val directoryOf: (TransferId) -> Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ResumePlanStore {
    override suspend fun read(transferId: TransferId): ResumeSummary? =
        withContext(io) {
            val text =
                try {
                    String(Files.readAllBytes(fileOf(transferId)), StandardCharsets.UTF_8)
                } catch (_: NoSuchFileException) {
                    return@withContext null
                }
            parse(text)
        }

    override suspend fun write(
        transferId: TransferId,
        summary: ResumeSummary,
    ) {
        withContext(io) {
            val target = fileOf(transferId)
            Files.createDirectories(target.parent)
            val temp = target.resolveSibling("$FILE_NAME.tmp")
            Files.write(temp, format(summary).toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    override suspend fun delete(transferId: TransferId) {
        withContext(io) {
            try {
                Files.deleteIfExists(fileOf(transferId))
            } catch (e: IOException) {
                throw IOException("cannot delete the resume plan of ${transferId.toHex()}", e)
            }
        }
    }

    private fun fileOf(transferId: TransferId): Path = directoryOf(transferId).resolve(FILE_NAME)

    companion object {
        const val FILE_NAME: String = "resume.plan"
        private const val HEADER = "drop-resume-plan 1"
        private const val MAX_TEXT = 512

        fun format(summary: ResumeSummary): String =
            buildString {
                append(HEADER).append('\n')
                append("files=").append(summary.fileCount).append('\n')
                append("bytes=").append(summary.totalBytes).append('\n')
                append("chunk=").append(summary.chunkSize).append('\n')
                append("bundle=").append(summary.bundleSmall).append('\n')
                append("bundles=").append(summary.bundleCount).append('\n')
            }

        /** @throws ResumePlanFormatException when [text] is not what [format] writes, or a value is out of range. */
        fun parse(text: String): ResumeSummary {
            if (text.length > MAX_TEXT) throw ResumePlanFormatException("resume plan longer than $MAX_TEXT characters")
            val lines = text.split('\n').filter { it.isNotEmpty() }
            if (lines.firstOrNull() != HEADER) throw ResumePlanFormatException("not a resume plan")
            val values = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val eq = line.indexOf('=')
                if (eq <= 0) throw ResumePlanFormatException("malformed line in resume plan")
                if (values.put(line.substring(0, eq), line.substring(eq + 1)) != null) {
                    throw ResumePlanFormatException("duplicate key in resume plan")
                }
            }

            fun long(key: String): Long =
                values[key]?.takeIf { v -> v.isNotEmpty() && v.all { it in '0'..'9' } && v.length <= 19 }?.toLongOrNull()
                    ?: throw ResumePlanFormatException("resume plan key '$key' missing or not a number")

            val files = long("files")
            val bytes = long("bytes")
            val chunk = long("chunk")
            val bundles = long("bundles")
            val bundle =
                when (values["bundle"]) {
                    "true" -> true
                    "false" -> false
                    else -> throw ResumePlanFormatException("resume plan key 'bundle' missing or not a boolean")
                }
            if (files !in 1..ProtocolConstants.MAX_FILES_PER_TRANSFER.toLong()) throw ResumePlanFormatException("file count out of range")
            if (chunk !in
                ProtocolConstants.MIN_CHUNK_SIZE.toLong()..ProtocolConstants.CHUNK_SIZE.toLong()
            ) {
                throw ResumePlanFormatException("chunk size out of range")
            }
            if (bundles > files || (!bundle && bundles != 0L)) throw ResumePlanFormatException("bundle count out of range")
            return ResumeSummary(files.toInt(), bytes, chunk.toInt(), bundle, bundles.toInt())
        }
    }
}
