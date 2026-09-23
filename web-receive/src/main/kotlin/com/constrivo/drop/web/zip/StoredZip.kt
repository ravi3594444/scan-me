package com.constrivo.drop.web.zip

import com.constrivo.drop.web.SharedFileChangedException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.CRC32

/** One entry of a [StoredZipLayout]: a (sanitised, unique) name and the exact size of its content. */
class ZipEntrySpec(
    val name: String,
    val size: Long,
) {
    init {
        require(size >= 0) { "entry size must not be negative" }
        require(name.isNotEmpty() && '\u0000' !in name) { "entry name must be non-empty and contain no NUL" }
        require(name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) { "entry name longer than $MAX_NAME_BYTES bytes" }
    }

    private companion object {
        const val MAX_NAME_BYTES = 0xFFFF
    }
}

/**
 * The byte layout of the "Download all" archive (architecture §10.3: `stored` compression, streamed, no temp file).
 *
 * Because entries are stored and every size is known up front, the whole archive length is known before the first
 * byte is written ([totalSize]), so the response carries `Content-Length` and the page can show real progress. The
 * CRC-32 of each entry is computed while it streams and written in a data descriptor after the data (general purpose
 * bit 3), so each file is read exactly once. Layout per entry (APPNOTE 6.3.10):
 *
 * - local header: version needed 20 (45 with ZIP64), flags `0x0808` (data descriptor, UTF-8 name), method 0, the DOS
 *   time, CRC 0, and the real sizes (so streaming extractors can find the end of stored data); an entry of 4 GiB or
 *   more puts `0xFFFFFFFF` there and its sizes in a ZIP64 extra field (id 1);
 * - the data, then a data descriptor: signature, CRC-32, sizes (8-byte sizes exactly when the local header has the
 *   ZIP64 extra field, APPNOTE 4.3.9.2);
 * - central directory entries made by Unix (external attributes `0100644`), with a ZIP64 extra field holding
 *   whichever of the sizes and the local header offset do not fit in 32 bits;
 * - the ZIP64 end record and locator when the entry count, directory size or directory offset overflow the classic
 *   end record, whose fields then read `0xFFFF` / `0xFFFFFFFF`.
 *
 * [forceZip64] uses every ZIP64 structure even for small archives; tests use it to exercise the ZIP64 paths without
 * writing 4 GiB.
 */
class StoredZipLayout(
    entries: List<ZipEntrySpec>,
    internal val forceZip64: Boolean = false,
) {
    internal val names: List<ByteArray> = entries.map { it.name.toByteArray(Charsets.UTF_8) }
    internal val sizes: LongArray = LongArray(entries.size) { entries[it].size }

    /** Whether the entry's own sizes need ZIP64 (local extra field, 8-byte data descriptor). */
    internal val entryZip64: BooleanArray = BooleanArray(entries.size) { forceZip64 || sizes[it] >= MAX_32 }
    internal val localHeaderOffsets: LongArray = LongArray(entries.size)

    /** Offset of the central directory. */
    val centralDirectoryOffset: Long

    /** Size of the central directory in bytes. */
    val centralDirectorySize: Long

    /** Whether the archive ends with the ZIP64 end record and locator. */
    val usesZip64End: Boolean

    /** Exact length of the archive in bytes. */
    val totalSize: Long

    /** Number of entries. */
    val entryCount: Int get() = names.size

    init {
        var offset = 0L
        for (i in names.indices) {
            localHeaderOffsets[i] = offset
            offset = Math.addExact(offset, localHeaderLength(i) + sizes[i] + dataDescriptorLength(i))
        }
        centralDirectoryOffset = offset
        var cd = 0L
        for (i in names.indices) cd += centralHeaderLength(i)
        centralDirectorySize = cd
        usesZip64End = forceZip64 || names.size >= MAX_16 || centralDirectoryOffset >= MAX_32 || centralDirectorySize >= MAX_32
        totalSize = Math.addExact(centralDirectoryOffset + centralDirectorySize, endLength())
    }

    internal fun localHeaderLength(i: Int): Long = LOCAL_HEADER + names[i].size + (if (entryZip64[i]) LOCAL_ZIP64_EXTRA else 0)

    internal fun dataDescriptorLength(i: Int): Long = if (entryZip64[i]) DATA_DESCRIPTOR_64 else DATA_DESCRIPTOR_32

    /** Whether the central directory must move the local header offset into the ZIP64 extra field. */
    internal fun offsetZip64(i: Int): Boolean = forceZip64 || localHeaderOffsets[i] >= MAX_32

    internal fun centralExtraLength(i: Int): Int {
        val fields = (if (entryZip64[i]) 16 else 0) + (if (offsetZip64(i)) 8 else 0)
        return if (fields == 0) 0 else 4 + fields
    }

    internal fun centralHeaderLength(i: Int): Long = (CENTRAL_HEADER + names[i].size + centralExtraLength(i)).toLong()

    private fun endLength(): Long = END_RECORD + if (usesZip64End) ZIP64_END_RECORD + ZIP64_LOCATOR else 0

    internal companion object {
        const val MAX_16 = 0xFFFF
        const val MAX_32 = 0xFFFFFFFFL
        const val LOCAL_HEADER = 30L
        const val LOCAL_ZIP64_EXTRA = 20L
        const val DATA_DESCRIPTOR_32 = 16L
        const val DATA_DESCRIPTOR_64 = 24L
        const val CENTRAL_HEADER = 46
        const val END_RECORD = 22L
        const val ZIP64_END_RECORD = 56L
        const val ZIP64_LOCATOR = 20L
    }
}

/**
 * Writes a [StoredZipLayout] to a blocking [OutputStream], reading each entry once through [open]. Runs on an IO
 * thread; the caller closes [OutputStream].
 *
 * @param dosDateTime every entry's modification time, packed as MS-DOS `date << 16 | time` ([dosDateTime]).
 * @param bufferSize copy buffer size.
 */
class StoredZipWriter(
    private val layout: StoredZipLayout,
    private val dosDateTime: Int,
    private val bufferSize: Int = 64 * 1024,
) {
    /**
     * Streams the archive. [open] is called once per non-empty entry, in order, and its stream is closed after
     * [ZipEntrySpec.size] bytes; [onBytes] is told about every chunk of entry data written.
     *
     * @throws SharedFileChangedException when an entry's stream ends before its promised size; the archive is then
     *   incomplete and the response must be aborted.
     */
    fun write(
        out: OutputStream,
        open: (index: Int) -> InputStream,
        onBytes: (count: Int) -> Unit = {},
    ) {
        val crcs = LongArray(layout.entryCount)
        val buffer = ByteArray(bufferSize)
        val header = LittleEndianBuffer(256)
        var written = 0L
        for (i in 0 until layout.entryCount) {
            header.reset()
            writeLocalHeader(header, i)
            written += header.writeTo(out)
            val crc = CRC32()
            val size = layout.sizes[i]
            if (size > 0) {
                open(i).use { input ->
                    var remaining = size
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (n < 0) throw SharedFileChangedException("entry $i ended $remaining bytes early")
                        crc.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        remaining -= n
                        written += n
                        onBytes(n)
                    }
                }
            }
            crcs[i] = crc.value
            header.reset()
            header.u32(DATA_DESCRIPTOR_SIG).u32(crcs[i])
            if (layout.entryZip64[i]) header.u64(size).u64(size) else header.u32(size).u32(size)
            written += header.writeTo(out)
        }
        for (i in 0 until layout.entryCount) {
            header.reset()
            writeCentralHeader(header, i, crcs[i])
            written += header.writeTo(out)
        }
        header.reset()
        writeEnd(header)
        written += header.writeTo(out)
        check(written == layout.totalSize) { "zip layout mismatch: wrote $written of ${layout.totalSize} bytes" }
    }

    private fun writeLocalHeader(
        b: LittleEndianBuffer,
        i: Int,
    ) {
        val zip64 = layout.entryZip64[i]
        val size = layout.sizes[i]
        val name = layout.names[i]
        b
            .u32(LOCAL_HEADER_SIG)
            .u16(if (zip64) VERSION_ZIP64 else VERSION_DEFAULT)
            .u16(FLAGS)
            .u16(METHOD_STORED)
            .u32(dosDateTime.toLong() and 0xFFFFFFFFL)
            .u32(0) // CRC-32 follows in the data descriptor
            .u32(if (zip64) StoredZipLayout.MAX_32 else size)
            .u32(if (zip64) StoredZipLayout.MAX_32 else size)
            .u16(name.size)
            .u16(if (zip64) StoredZipLayout.LOCAL_ZIP64_EXTRA.toInt() else 0)
            .bytes(name)
        if (zip64) b.u16(ZIP64_EXTRA_ID).u16(16).u64(size).u64(size)
    }

    private fun writeCentralHeader(
        b: LittleEndianBuffer,
        i: Int,
        crc: Long,
    ) {
        val zip64 = layout.entryZip64[i]
        val offset64 = layout.offsetZip64(i)
        val size = layout.sizes[i]
        val name = layout.names[i]
        val version = if (zip64 || offset64) VERSION_ZIP64 else VERSION_DEFAULT
        b
            .u32(CENTRAL_HEADER_SIG)
            .u16(MADE_BY_UNIX or version)
            .u16(version)
            .u16(FLAGS)
            .u16(METHOD_STORED)
            .u32(dosDateTime.toLong() and 0xFFFFFFFFL)
            .u32(crc)
            .u32(if (zip64) StoredZipLayout.MAX_32 else size)
            .u32(if (zip64) StoredZipLayout.MAX_32 else size)
            .u16(name.size)
            .u16(layout.centralExtraLength(i))
            .u16(0) // comment length
            .u16(0) // disk number start
            .u16(0) // internal attributes
            .u32(UNIX_REGULAR_FILE_0644)
            .u32(if (offset64) StoredZipLayout.MAX_32 else layout.localHeaderOffsets[i])
            .bytes(name)
        if (zip64 || offset64) {
            b.u16(ZIP64_EXTRA_ID).u16(layout.centralExtraLength(i) - 4)
            // APPNOTE 4.5.3: only the fields whose header value is 0xFFFFFFFF, in this order.
            if (zip64) b.u64(size).u64(size)
            if (offset64) b.u64(layout.localHeaderOffsets[i])
        }
    }

    private fun writeEnd(b: LittleEndianBuffer) {
        val count = layout.entryCount.toLong()
        val cdSize = layout.centralDirectorySize
        val cdOffset = layout.centralDirectoryOffset
        if (layout.usesZip64End) {
            val zip64EndOffset = cdOffset + cdSize
            b
                .u32(ZIP64_END_SIG)
                .u64(StoredZipLayout.ZIP64_END_RECORD - 12) // size of the rest of the record
                .u16(MADE_BY_UNIX or VERSION_ZIP64)
                .u16(VERSION_ZIP64)
                .u32(0) // this disk
                .u32(0) // disk with the central directory
                .u64(count)
                .u64(count)
                .u64(cdSize)
                .u64(cdOffset)
            b
                .u32(ZIP64_LOCATOR_SIG)
                .u32(0) // disk with the ZIP64 end record
                .u64(zip64EndOffset)
                .u32(1) // total number of disks
        }
        val z = layout.usesZip64End
        val force = layout.forceZip64
        val count16 = if (force || (z && count >= StoredZipLayout.MAX_16)) StoredZipLayout.MAX_16.toLong() else count
        val size32 = if (force || (z && cdSize >= StoredZipLayout.MAX_32)) StoredZipLayout.MAX_32 else cdSize
        val offset32 = if (force || (z && cdOffset >= StoredZipLayout.MAX_32)) StoredZipLayout.MAX_32 else cdOffset
        b
            .u32(END_SIG)
            .u16(0) // this disk
            .u16(0) // disk with the central directory
            .u16(count16.toInt())
            .u16(count16.toInt())
            .u32(size32)
            .u32(offset32)
            .u16(0) // comment length
    }

    companion object {
        private const val LOCAL_HEADER_SIG = 0x04034b50L
        private const val DATA_DESCRIPTOR_SIG = 0x08074b50L
        private const val CENTRAL_HEADER_SIG = 0x02014b50L
        private const val ZIP64_END_SIG = 0x06064b50L
        private const val ZIP64_LOCATOR_SIG = 0x07064b50L
        private const val END_SIG = 0x06054b50L
        private const val VERSION_DEFAULT = 20
        private const val VERSION_ZIP64 = 45
        private const val MADE_BY_UNIX = 3 shl 8

        /** Bit 3 (sizes and CRC in a data descriptor) and bit 11 (UTF-8 names). */
        private const val FLAGS = 0x0808
        private const val METHOD_STORED = 0
        private const val ZIP64_EXTRA_ID = 0x0001

        /** `S_IFREG | 0644` in the high 16 bits (Unix "made by"), so extracted files are readable. */
        private const val UNIX_REGULAR_FILE_0644 = 0x81A40000L

        /**
         * [epochMillis] as an MS-DOS timestamp (`date << 16 | time`, two-second resolution) in [zone], clamped to
         * the representable years 1980–2107.
         */
        fun dosDateTime(
            epochMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Int {
            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
            if (t.year < 1980) return (1 shl 5 or 1) shl 16 // 1980-01-01 00:00:00
            if (t.year > 2107) return ((127 shl 9) or (12 shl 5) or 31) shl 16 or ((23 shl 11) or (59 shl 5) or 29)
            val date = ((t.year - 1980) shl 9) or (t.monthValue shl 5) or t.dayOfMonth
            val time = (t.hour shl 11) or (t.minute shl 5) or (t.second / 2)
            return (date shl 16) or time
        }
    }
}

/** A growable little-endian byte buffer for zip headers. */
internal class LittleEndianBuffer(
    initialCapacity: Int,
) {
    private var data = ByteArray(initialCapacity)
    private var size = 0

    fun reset() {
        size = 0
    }

    fun u16(value: Int): LittleEndianBuffer {
        ensure(2)
        data[size++] = value.toByte()
        data[size++] = (value ushr 8).toByte()
        return this
    }

    fun u32(value: Long): LittleEndianBuffer {
        ensure(4)
        for (shift in 0 until 32 step 8) data[size++] = (value ushr shift).toByte()
        return this
    }

    fun u64(value: Long): LittleEndianBuffer {
        ensure(8)
        for (shift in 0 until 64 step 8) data[size++] = (value ushr shift).toByte()
        return this
    }

    fun bytes(value: ByteArray): LittleEndianBuffer {
        ensure(value.size)
        value.copyInto(data, size)
        size += value.size
        return this
    }

    /** Writes the buffered bytes and returns how many. */
    fun writeTo(out: OutputStream): Int {
        out.write(data, 0, size)
        return size
    }

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }
}
