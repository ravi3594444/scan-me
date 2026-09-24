package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.Accept
import com.constrivo.drop.core.protocol.Bundle
import com.constrivo.drop.core.protocol.BundleIndex
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.ChunkRef
import com.constrivo.drop.core.protocol.ChunkView
import com.constrivo.drop.core.protocol.FileDone
import com.constrivo.drop.core.protocol.FileEntry
import com.constrivo.drop.core.protocol.FileList
import com.constrivo.drop.core.protocol.FileListAssembler
import com.constrivo.drop.core.protocol.FrameHeader
import com.constrivo.drop.core.protocol.Hint
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.IndexRange
import com.constrivo.drop.core.protocol.LinkIntent
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.MissingChunks
import com.constrivo.drop.core.protocol.MissingUnits
import com.constrivo.drop.core.protocol.Offer
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.protocol.Resume
import com.constrivo.drop.core.protocol.Sha256Digest
import com.constrivo.drop.core.protocol.TransferEvent
import com.constrivo.drop.core.protocol.TransferLayout
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferState
import com.constrivo.drop.core.protocol.TransferUnit
import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.core.transfer.PartialFile
import com.constrivo.drop.core.transfer.StorageFullException
import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.flow.AckBatcher
import com.constrivo.drop.core.transfer.hash.sha256Digest
import com.constrivo.drop.core.transfer.receive.FileResumeState
import com.constrivo.drop.core.transfer.receive.FileResumeStatus
import com.constrivo.drop.core.transfer.receive.ManifestTracker
import com.constrivo.drop.core.transfer.receive.ResumeRecord
import com.constrivo.drop.core.transfer.receive.ResumeSummary
import com.constrivo.drop.core.transfer.session.SecureConnection
import com.constrivo.drop.core.transfer.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * The receiver's data path (architecture §7.3–7.6; spec changes S1, S2, S4, N5, N12; decision 6):
 *
 * - **Verify, then write.** Every chunk frame's payload is checked against its XXH3-128 before anything is written; a
 *   mismatch re-requests it (`Retransmit`, three strikes fail the file, §7.8). Stream readers take a buffer from the
 *   receive pool before reading a frame; the pool is the bounded 16 MiB write queue, drained by one writer per
 *   destination file, so a slow disk stops the readers and TCP pushes back on the sender (§7.4).
 * - **Durability (N5).** A unit's bit is set in the resume manifest only after its bytes were written and the `.part`
 *   was `fsync`ed, in write-behind batches at most [EngineLimits.FLUSH_MILLIS] apart, with the unit's hash, so a `.part`
 *   can be re-verified after a crash. Bluetooth blocks of a chunk are written as they arrive and their synced prefix is
 *   kept too (S1); blocks of a bundle are assembled in memory.
 * - **Files.** When a file's units are durable and its `FileDone` hash is known (S2), the `.part` is re-read and hashed
 *   with SHA-256; a match publishes it through [com.constrivo.drop.core.transfer.FileStore.publish] under its sanitised
 *   name (F-D5); a mismatch re-hashes the units against their stored hashes to name the bad ones (N5) and re-requests
 *   them. A file that fails for good releases its remaining units (they are acked) so the transfer can complete.
 * - **Resume.** `Accept.resume` and every `Resume` state the complete missing set (§7.6), with the durable prefix of a
 *   unit received as Bluetooth blocks; a file whose data is complete but whose `FileDone` never arrived gets its last
 *   unit requested again, so the sender repeats the `FileDone`.
 */
internal class ReceiveSide(
    private val run: TransferRun,
    val offer: Offer,
    private val record: ResumeRecord?,
) {
    private val config = run.config
    private val store = config.fileStore
    private val resumeStore = config.resumeStore
    private val transferId = offer.transferId
    private val partialId = transferId.toHex()
    private val lock = TransferLock()
    private val pool = BufferPool(SecureConnection.MAX_CHUNK_FRAME, EngineLimits.RECEIVE_BUFFERS)
    private val layoutReady = CompletableDeferred<TransferLayout>()
    private val verifyPermits = Semaphore(EngineLimits.VERIFY_CONCURRENCY)
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private val ackBatcher = AckBatcher(transferId, run.scope, config.clock) { run.sendControl(it) }

    @Volatile
    var layout: TransferLayout? = null
        private set

    @Volatile
    var files: FileProgressTable? = null
        private set

    @Volatile
    var bytesDone: Long = 0
        private set

    private var assembler = FileListAssembler(offer)
    private var expectedPage = 0
    private var receivedFiles: List<FileEntry>? = null

    // ---- per-unit state (global order), under [lock]; set up with the layout ----
    private lateinit var tracker: ManifestTracker
    private lateinit var unitState: ByteArray
    private val prefix = HashMap<Long, Int>()
    private val assembly = HashMap<Long, ByteArray>()
    private val writesPending = HashMap<Long, Int>()
    private val unitHash = HashMap<Long, ChunkHash>()
    private val written = LinkedHashMap<Long, Boolean>()
    private val prefixWritten = HashMap<Long, Int>()
    private var completeUnits = 0L
    private var totalUnits = 0L

    // ---- per-file state, under [lock] ----
    private lateinit var fileSha: Array<Sha256Digest?>
    private lateinit var fileState: Array<FileResumeStatus>
    private lateinit var verifying: BooleanArray
    private lateinit var unitsLeft: IntArray
    private val earlySha = HashMap<Int, Sha256Digest>()
    private val partials = HashMap<Int, PartialFile>()
    private val writers = HashMap<Int, Channel<WriteOp>>()

    // ---- lifecycle ----
    private val wifiGenerations = HashSet<Int>()
    private var firstBluetoothChunk = false
    private var accepted = false
    private var stopped = false
    private var clearPartials = false
    private var flusherJob: Job? = null
    private val writerJobs = ArrayList<Job>()

    private class WriteOp(
        val fileIndex: Int,
        val position: Long,
        val bytes: ByteArray,
        val offset: Int,
        val length: Int,
        val global: Long,
        /** Bytes of the unit's prefix on disk after this op (Bluetooth blocks of a chunk), or -1. */
        val prefixAfter: Int,
        val release: () -> Unit,
    )

    // =====================================================================================================
    // Accept
    // =====================================================================================================

    /** Whether a stored record matches this offer (a resumed transfer, T-07). */
    val isResume: Boolean get() = record != null && record.summary == ResumeSummary.of(offer)

    /**
     * Prepares the `Accept` (§7.2, §7.6): on a resume, the layout and manifests come from the [record] (re-verified
     * against their stored hashes when [EngineConfig.reverifyOnResume], N5) and `resume` is the missing set. Returns
     * null when the destination lacks the space for the bytes still missing (§7.8: decline with `storage`).
     */
    suspend fun prepareAccept(
        link: LinkIntent?,
        streamCount: Int,
    ): Accept? {
        var missing: MissingUnits? = null
        var present = 0L
        val stored = record
        if (stored != null && isResume) {
            val restored =
                try {
                    TransferLayout.of(offer, stored.files)
                } catch (e: ProtocolException) {
                    null
                }
            if (restored != null) {
                restore(stored, restored)
                missing = missingUnits()
                present = bytesDone
            } else {
                resumeStore.delete(transferId)
                store.deletePartials(partialId)
            }
        } else if (stored != null) {
            // Same id, different transfer: start over.
            resumeStore.delete(transferId)
            store.deletePartials(partialId)
        }
        val free = store.freeBytes()
        if (free < offer.totalBytes - present) return null
        if (store.destinationIsRemovable) {
            run.addLocalHint(HintCode.SDCARD)
            run.sendControl(Hint(HintCode.SDCARD, emptyMap(), transferId))
        }
        return Accept(transferId, link, missing, streamCount.coerceIn(1, ProtocolConstants.MAX_STREAMS))
    }

    /**
     * The `Accept` went out and the reducer is past `Offered`: files a resumed transfer already verified or failed are
     * reported to it, and complete files are verified.
     */
    fun accepted() {
        lock.withLock { accepted = true }
        val l = layout ?: return
        val states = lock.withLock { fileState.copyOf() }
        for (f in states.indices) {
            when (states[f]) {
                FileResumeStatus.DONE -> run.reduceLater(TransferEvent.FileVerified(f, l.fileSize(f)))
                FileResumeStatus.FAILED -> run.reduceLater(TransferEvent.FileHashMismatch(f, MissingUnits.NONE))
                FileResumeStatus.PENDING -> maybeVerify(f)
            }
        }
        checkAllReceived()
    }

    /** Declined for lack of space: nothing of this transfer stays on disk (T-27). */
    suspend fun discardStored() {
        resumeStore.delete(transferId)
        store.deletePartials(partialId)
    }

    fun startStreaming() {
        if (flusherJob != null) return
        flusherJob = run.scope.launch(config.io) { flushLoop() }
    }

    // =====================================================================================================
    // Layout
    // =====================================================================================================

    private fun setUpLayout(
        entries: List<FileEntry>,
        l: TransferLayout,
    ) {
        if (l.totalUnits > Int.MAX_VALUE) throw ProtocolException("transfer has ${l.totalUnits} units, more than this device tracks")
        tracker = ManifestTracker(transferId, l)
        totalUnits = l.totalUnits
        unitState = ByteArray(l.totalUnits.toInt())
        fileSha = arrayOfNulls(l.fileCount)
        fileState = Array(l.fileCount) { FileResumeStatus.PENDING }
        verifying = BooleanArray(l.fileCount)
        unitsLeft = IntArray(l.fileCount) { f -> if (l.bundlePlan.isBundled(f)) 1 else l.unitCount(f) }
        for ((f, digest) in earlySha) fileSha[f] = digest
        earlySha.clear()
        receivedFiles = entries
        files = FileProgressTable(entries, receiver = true)
        layout = l
    }

    /** Restores the state of a resumed transfer from [stored] before the `Accept`. */
    private suspend fun restore(
        stored: ResumeRecord,
        l: TransferLayout,
    ) {
        lock.withLock {
            setUpLayout(stored.files, l)
            tracker.load(stored.manifests)
            for ((f, state) in stored.fileStates) {
                if (f !in 0 until l.fileCount) continue
                fileState[f] = state.status
                if (state.sha256 != null) fileSha[f] = state.sha256
            }
        }
        // Units whose bytes are gone or damaged (the .part is shorter, or re-hashing disagrees) are missing again (N5).
        val damaged = ArrayList<TransferUnit>()
        for (unit in l.units()) {
            if (!lock.withLock { tracker.isReceived(unit) }) continue
            val carried = filesOf(l, unit)
            if (carried.any { lock.withLock { fileState[it] } != FileResumeStatus.PENDING }) continue
            if (!unitOnDisk(l, unit)) damaged += unit
        }
        lock.withLock {
            for (unit in damaged) tracker.markMissing(unit)
            for (unit in l.units()) {
                val g = l.globalIndex(unit)
                val carried = filesOf(l, unit)
                val resolved = carried.all { fileState[it] != FileResumeStatus.PENDING }
                when {
                    resolved -> {
                        unitState[g.toInt()] = RELEASED
                        completeUnits++
                        if (carried.any {
                                fileState[it] == FileResumeStatus.DONE
                            }
                        ) {
                            addProgress(l, unit, 0, l.unitLength(unit), live = false)
                        }
                    }

                    tracker.isReceived(unit) -> {
                        unitState[g.toInt()] = DURABLE
                        completeUnits++
                        tracker.hashOf(unit)?.let { unitHash[g] = it }
                        for (f in carried) if (fileState[f] == FileResumeStatus.PENDING) unitsLeft[f]--
                        addProgress(l, unit, 0, l.unitLength(unit), live = false)
                    }

                    else -> {
                        val bytes = tracker.partialPrefix(unit)
                        if (bytes > 0 && !unit.isBundle) {
                            unitState[g.toInt()] = RECEIVING
                            prefix[g] = bytes
                            addProgress(l, unit, 0, bytes, live = false)
                        }
                    }
                }
            }
            val table = checkNotNull(files)
            for (f in 0 until l.fileCount) {
                when (fileState[f]) {
                    FileResumeStatus.DONE -> {
                        table.setStatus(f, FileStatus.DONE)
                        stored.fileStates[f]?.savedUri?.let { table.setSaved(f, it, table.nameOf(f)) }
                    }

                    FileResumeStatus.FAILED -> {
                        table.setStatus(f, FileStatus.FAILED)
                    }

                    FileResumeStatus.PENDING -> {
                        continue
                    }
                }
            }
        }
        val dirty = lock.withLock { tracker.dirtyManifests(run.now()) }
        if (dirty.isNotEmpty()) resumeStore.putManifests(dirty)
        layoutReady.complete(l)
    }

    /** True when [unit]'s bytes are in its `.part` files and (with re-verification) hash to the stored value. */
    private suspend fun unitOnDisk(
        l: TransferLayout,
        unit: TransferUnit,
    ): Boolean {
        val expected = lock.withLock { tracker.hashOf(unit) } ?: return false
        return try {
            if (!config.reverifyOnResume) return partsPresent(l, unit)
            val bytes = readUnit(l, unit) ?: return false
            config.chunkHasher.matches(expected, bytes, 0, bytes.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** True when every `.part` [unit] writes to is long enough to hold its bytes. */
    private suspend fun partsPresent(
        l: TransferLayout,
        unit: TransferUnit,
    ): Boolean {
        if (unit.isBundle) {
            return l.bundlePlan.bundles[unit.chunkIndex].entries.all {
                partialOf(it.fileIndex, l.fileSize(it.fileIndex)).length() >=
                    it.length
            }
        }
        val plan = checkNotNull(l.chunkPlan(unit.fileIndex))
        return partialOf(unit.fileIndex, l.fileSize(unit.fileIndex)).length() >=
            plan.chunkOffset(unit.chunkIndex) + plan.chunkLength(unit.chunkIndex)
    }

    /** Reads [unit]'s plaintext back from the partials (a bundle is rebuilt from its files), or null when short. */
    private suspend fun readUnit(
        l: TransferLayout,
        unit: TransferUnit,
    ): ByteArray? {
        val length = l.unitLength(unit)
        val out = ByteArray(length)
        if (unit.isBundle) {
            val bundle = l.bundlePlan.bundles[unit.chunkIndex]
            val index = BundleIndex.encodeIndex(bundle.entries)
            index.copyInto(out)
            for (entry in bundle.entries) {
                if (!readFromPartial(
                        entry.fileIndex,
                        l.fileSize(entry.fileIndex),
                        0,
                        out,
                        index.size + entry.offset,
                        entry.length,
                    )
                ) {
                    return null
                }
            }
            return out
        }
        val plan = checkNotNull(l.chunkPlan(unit.fileIndex))
        return if (readFromPartial(
                unit.fileIndex,
                l.fileSize(unit.fileIndex),
                plan.chunkOffset(unit.chunkIndex),
                out,
                0,
                length,
            )
        ) {
            out
        } else {
            null
        }
    }

    private suspend fun readFromPartial(
        fileIndex: Int,
        size: Long,
        position: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        val partial = partialOf(fileIndex, size)
        if (partial.length() < position + length) return false
        var done = 0
        while (done < length) {
            val n = partial.read(position + done, destination, offset + done, length - done)
            if (n <= 0) return false
            done += n
        }
        return true
    }

    private suspend fun partialOf(
        fileIndex: Int,
        size: Long,
    ): PartialFile {
        lock.withLock { partials[fileIndex] }?.let { return it }
        val opened = store.openPartial(partialId, fileIndex, size)
        val kept =
            lock.withLock {
                val existing = partials[fileIndex]
                if (existing == null) partials[fileIndex] = opened
                existing
            }
        if (kept != null) {
            opened.close()
            return kept
        }
        return opened
    }

    /** One `FileList` page (N12); the complete list fixes the layout, or must equal a resumed transfer's stored one. */
    fun onFileList(page: FileList) {
        if (assembler.isComplete) return // repeated after a reconnect
        if (page.page == 0 && expectedPage != 0) {
            // The sender starts the list again (after a Resume): so do we.
            assembler = FileListAssembler(offer)
            expectedPage = 0
        }
        if (page.page != expectedPage) return // a page left over from an earlier attempt
        val complete =
            try {
                expectedPage++
                assembler.add(page) ?: return
            } catch (e: ProtocolException) {
                run.protocolViolation(e.message ?: "bad FileList")
                return
            }
        val known = receivedFiles
        if (known != null) {
            val same = known.size == complete.size && known.indices.all { sameFile(known[it], complete[it]) }
            if (!same) run.protocolViolation("the file list differs from the one this transfer was resumed with")
            return
        }
        val l =
            try {
                TransferLayout.of(offer, complete)
            } catch (e: ProtocolException) {
                run.protocolViolation(e.message ?: "bad FileList")
                return
            }
        try {
            lock.withLock { setUpLayout(complete, l) }
        } catch (e: ProtocolException) {
            run.protocolViolation(e.message ?: "transfer too large")
            return
        }
        run.scope.launch(config.io) {
            // The record exists before any chunk is placed, so no manifest write is lost to a later create.
            resumeStore.create(transferId, ResumeSummary.of(offer), complete, run.now())
            layoutReady.complete(l)
            for (f in 0 until l.fileCount) if (l.unitCount(f) == 0 && !l.bundlePlan.isBundled(f)) maybeVerify(f)
            checkAllReceived()
            run.publishProgress()
        }
    }

    private fun sameFile(
        a: FileEntry,
        b: FileEntry,
    ): Boolean = a.index == b.index && a.name == b.name && a.size == b.size && a.mime == b.mime

    /** The sender will repeat an incomplete file list from page 0 after our `Resume`. */
    private fun restartFileList() {
        if (!assembler.isComplete && receivedFiles == null) {
            assembler = FileListAssembler(offer)
            expectedPage = 0
        }
    }

    // =====================================================================================================
    // Chunks
    // =====================================================================================================

    /** Handles one chunk frame whose header [conn]'s reader just read (runs in that reader). */
    suspend fun onChunk(
        conn: Conn,
        header: FrameHeader,
    ) {
        if (lock.withLock { stopped }) {
            conn.secure.skipPayload(header)
            return
        }
        val l = layoutReady.await()
        val buffer = pool.acquire()
        val view: ChunkView
        try {
            if (lock.withLock { stopped }) {
                conn.secure.skipPayload(header)
                buffer.release()
                return
            }
            view = conn.secure.readChunk(header, buffer.bytes)
        } catch (e: Throwable) {
            buffer.release()
            throw e
        }
        val chunk = view.header
        // Wi-Fi streams count their bytes as they arrive (CountingChannel); the Bluetooth stream counts per frame.
        if (conn.isPrimary) run.arrivals.add(conn.kind, chunk.payloadLength.toLong())
        try {
            if (chunk.transferId != transferId) throw ProtocolException("chunk frame for another transfer")
            l.checkHeader(chunk)
        } catch (e: ProtocolException) {
            buffer.release()
            throw e
        }
        val unit = chunk.unit
        val g = l.globalIndex(unit)
        val blockFrame = conn.isPrimary && conn.kind == LinkKind.BLUETOOTH
        if (!config.chunkHasher.matches(chunk.hash, view.buffer, view.payloadOffset, view.payloadLength)) {
            buffer.release()
            val alreadyHave = lock.withLock { unitState[g.toInt()] >= COMPLETE }
            if (!alreadyHave) run.reduceLater(TransferEvent.ChunkHashMismatch(unit, filesOf(l, unit), chunk.blockOffset))
            return
        }
        if (unit.isBundle && !indexMatchesPlan(l, unit, view)) {
            buffer.release()
            throw ProtocolException("bundle ${unit.chunkIndex} does not match the bundle plan")
        }
        noteLink(conn)
        place(l, conn, view, buffer, g, blockFrame)
    }

    /** The part of the bundle index this frame carries equals the index the plan says (S4). */
    private fun indexMatchesPlan(
        l: TransferLayout,
        unit: TransferUnit,
        view: ChunkView,
    ): Boolean {
        val bundle = l.bundlePlan.bundles[unit.chunkIndex]
        val indexSize = Bundle.indexSize(bundle.entries.size)
        val start = view.header.blockOffset
        if (start >= indexSize) return true
        val expected = BundleIndex.encodeIndex(bundle.entries)
        val end = minOf(indexSize, start + view.payloadLength)
        for (i in start until end) if (view.buffer[view.payloadOffset + (i - start)] != expected[i]) return false
        return true
    }

    private fun noteLink(conn: Conn) {
        val link = conn.link
        val event =
            lock.withLock {
                when {
                    link == null && conn.kind == LinkKind.BLUETOOTH && !firstBluetoothChunk -> {
                        firstBluetoothChunk = true
                        TransferEvent.FirstChunkOverBluetooth
                    }

                    link != null && wifiGenerations.add(link.generation) -> {
                        TransferEvent.WifiStreamConnected(link.link.kind, link.link.freqMhz)
                    }

                    else -> {
                        null
                    }
                }
            }
        if (event != null) run.reduceLater(event)
    }

    /** Accepts the verified frame in [view] (in the pooled [buffer]) into the unit's state and queues its writes. */
    private fun place(
        l: TransferLayout,
        conn: Conn,
        view: ChunkView,
        buffer: PooledBuffer,
        g: Long,
        blockFrame: Boolean,
    ) {
        val chunk = view.header
        val unit = chunk.unit
        val unitLength = l.unitLength(unit)
        val start = chunk.blockOffset
        val end = start + chunk.payloadLength
        val ops = ArrayList<WriteOp>()
        var releaseNow = true
        var completedNow = false
        val ack: ChunkRef?
        val urgent = blockFrame
        lock.withLock {
            val state = unitState[g.toInt()]
            if (state >= COMPLETE) {
                // A duplicate (a lost ack, a resume overlap, or a unit re-requested for its FileDone): ack it again.
                ack = if (blockFrame) ChunkRef(unit.fileIndex, unit.chunkIndex, start) else ChunkRef(unit.fileIndex, unit.chunkIndex)
            } else {
                val have = prefix[g] ?: 0
                if (start > have) {
                    buffer.release()
                    throw ProtocolException("$unit frame starts at $start but only $have bytes arrived")
                }
                if (end <= have) {
                    ack = if (blockFrame) ChunkRef(unit.fileIndex, unit.chunkIndex, start) else null
                } else {
                    val complete = end == unitLength
                    if (unit.isBundle) {
                        if (start == 0 && complete) {
                            releaseNow = false
                            bundleOps(l, unit, g, view.buffer, view.payloadOffset, buffer, ops)
                            unitHash[g] = chunk.hash
                        } else {
                            val arr = assembly.getOrPut(g) { ByteArray(unitLength) }
                            view.buffer.copyInto(arr, have, view.payloadOffset + (have - start), view.payloadOffset + chunk.payloadLength)
                            if (complete) {
                                assembly.remove(g)
                                bundleOps(l, unit, g, arr, 0, null, ops)
                                unitHash[g] = config.chunkHasher.hash(arr, 0, arr.size)
                            }
                        }
                    } else {
                        val plan = checkNotNull(l.chunkPlan(unit.fileIndex))
                        releaseNow = false
                        ops +=
                            WriteOp(
                                unit.fileIndex,
                                plan.chunkOffset(unit.chunkIndex) + have,
                                view.buffer,
                                view.payloadOffset + (have - start),
                                end - have,
                                g,
                                if (complete) -1 else end,
                                buffer::release,
                            )
                        if (complete && start == 0) unitHash[g] = chunk.hash
                    }
                    addProgress(l, unit, have, end)
                    if (complete) {
                        prefix.remove(g)
                        unitState[g.toInt()] = COMPLETE
                        completeUnits++
                        completedNow = true
                    } else {
                        prefix[g] = end
                        unitState[g.toInt()] = RECEIVING
                    }
                    ack =
                        when {
                            blockFrame -> ChunkRef(unit.fileIndex, unit.chunkIndex, start)
                            complete -> ChunkRef(unit.fileIndex, unit.chunkIndex)
                            else -> null
                        }
                    writesPending[g] = (writesPending[g] ?: 0) + ops.size
                    if (ops.isEmpty() && complete) written[g] = true
                }
            }
        }
        if (releaseNow) buffer.release()
        run.progressChanged(bytesDone)
        if (ack != null) ackBatcher.add(ack, urgent)
        for (op in ops) submit(op)
        if (ops.isEmpty() && completedNow) flushSignal.trySend(Unit)
        if (completedNow) checkAllReceived()
    }

    /** Write ops for a complete bundle payload at `bytes[offset…]`: one per file that is not resolved yet. */
    private fun bundleOps(
        l: TransferLayout,
        unit: TransferUnit,
        g: Long,
        bytes: ByteArray,
        offset: Int,
        pooled: PooledBuffer?,
        out: MutableList<WriteOp>,
    ) {
        val bundle = l.bundlePlan.bundles[unit.chunkIndex]
        val dataStart = offset + Bundle.indexSize(bundle.entries.size)
        val release: () -> Unit = pooled?.let { it::release } ?: {}
        for (entry in bundle.entries) {
            if (fileState[entry.fileIndex] != FileResumeStatus.PENDING) continue
            out += WriteOp(entry.fileIndex, 0, bytes, dataStart + entry.offset, entry.length, g, -1, release)
        }
        if (pooled != null) {
            if (out.isEmpty()) pooled.release() else pooled.retain(out.size - 1)
        }
    }

    private fun submit(op: WriteOp) {
        val channel =
            lock.withLock {
                writers.getOrPut(op.fileIndex) {
                    val ch = Channel<WriteOp>(Channel.UNLIMITED)
                    writerJobs += run.scope.launch(config.io) { writeLoop(op.fileIndex, ch) }
                    ch
                }
            }
        if (channel.trySend(op).isFailure) {
            op.release()
        }
    }

    /** One writer per destination file (§7.4). */
    private suspend fun writeLoop(
        fileIndex: Int,
        channel: Channel<WriteOp>,
    ) {
        val size = checkNotNull(layout).fileSize(fileIndex)
        for (op in channel) {
            try {
                val skip = lock.withLock { stopped || fileState[fileIndex] != FileResumeStatus.PENDING }
                if (!skip) {
                    val partial = partialOf(fileIndex, size)
                    if (op.length > 0) partial.write(op.position, op.bytes, op.offset, op.length)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageFullException) {
                storageFailed()
                continue
            } catch (e: Exception) {
                storageFailed(e)
                continue
            } finally {
                op.release()
            }
            lock.withLock {
                val left = (writesPending[op.global] ?: 1) - 1
                if (left <= 0) writesPending.remove(op.global) else writesPending[op.global] = left
                if (op.prefixAfter > 0) {
                    prefixWritten[op.global] = maxOf(prefixWritten[op.global] ?: 0, op.prefixAfter)
                } else if (left <= 0 && unitState[op.global.toInt()] == COMPLETE) {
                    written[op.global] = true
                }
            }
            flushSignal.trySend(Unit)
        }
    }

    private suspend fun storageFailed(error: Exception? = null) {
        if (lock.withLock { stopped }) return
        val full = error == null || runCatching { store.freeBytes() }.getOrDefault(0L) < MIN_FREE_BYTES
        lock.withLock { stopped = true }
        if (full) {
            run.reduceLater(TransferEvent.StorageFull)
        } else {
            run.call {
                run.reduce(TransferEvent.LocalCancel(CancelReason.OTHER))
            }
        }
    }

    // =====================================================================================================
    // Durability (N5)
    // =====================================================================================================

    private suspend fun flushLoop() {
        while (true) {
            flushSignal.receiveCatching().getOrNull() ?: return
            delay(EngineLimits.FLUSH_MILLIS)
            flush()
        }
    }

    /** Syncs the partials of every unit whose writes finished, then marks those units in the manifest and stores it. */
    suspend fun flush() {
        val l = layout ?: return
        val units: List<Long>
        val prefixes: Map<Long, Int>
        val toSync: Set<Int>
        lock.withLock {
            units = written.keys.toList()
            written.clear()
            prefixes = HashMap(prefixWritten)
            prefixWritten.clear()
            val set = LinkedHashSet<Int>()
            for (g in units) filesOf(l, l.unitAt(g)).forEach { set += it }
            for (g in prefixes.keys) set += l.unitAt(g).fileIndex
            toSync = set
        }
        if (units.isEmpty() && prefixes.isEmpty()) return
        syncAll(toSync)
        // Units assembled from several frames get their hash from the synced bytes.
        val hashes = HashMap<Long, ChunkHash>()
        for (g in units) {
            val known = lock.withLock { unitHash[g] }
            if (known != null) {
                hashes[g] = known
            } else {
                val bytes = runCatching { readUnit(l, l.unitAt(g)) }.getOrNull()
                if (bytes != null) hashes[g] = config.chunkHasher.hash(bytes, 0, bytes.size)
            }
        }
        val verify = ArrayList<Int>()
        val dirty =
            lock.withLock {
                for (g in units) {
                    val unit = l.unitAt(g)
                    if (unitState[g.toInt()] != COMPLETE) continue
                    val hash = hashes[g] ?: continue
                    tracker.markReceived(unit, hash)
                    unitHash[g] = hash
                    unitState[g.toInt()] = DURABLE
                    for (f in filesOf(l, unit)) {
                        if (fileState[f] != FileResumeStatus.PENDING) continue
                        unitsLeft[f]--
                        if (unitsLeft[f] <= 0) verify += f
                    }
                }
                for ((g, bytes) in prefixes) {
                    val unit = l.unitAt(g)
                    if (unitState[g.toInt()] == RECEIVING) tracker.setPartial(unit, bytes)
                }
                tracker.dirtyManifests(run.now())
            }
        if (dirty.isNotEmpty() && !lock.withLock { stopped && clearPartials }) resumeStore.putManifests(dirty)
        for (f in verify) maybeVerify(f)
    }

    private suspend fun syncAll(fileIndices: Set<Int>) {
        val handles = lock.withLock { fileIndices.mapNotNull { partials[it] } }
        coroutineScope {
            handles.chunked(SYNC_PARALLELISM).forEach { batch ->
                batch.map { async(config.io) { runCatching { it.sync() } } }.awaitAll()
            }
        }
    }

    // =====================================================================================================
    // Files: FileDone, verification, publishing
    // =====================================================================================================

    fun onFileDone(message: FileDone) {
        val l = layout
        val f = message.fileIndex
        if (f >= offer.fileCount) {
            run.protocolViolation("FileDone for file $f of ${offer.fileCount}")
            return
        }
        if (l == null) {
            lock.withLock { earlySha[f] = message.sha256 }
            return
        }
        val store =
            lock.withLock {
                if (fileState[f] != FileResumeStatus.PENDING) return
                val changed = fileSha[f] != message.sha256
                fileSha[f] = message.sha256
                changed
            }
        if (store) {
            run.scope.launch(config.io) {
                resumeStore.putFileState(transferId, f, FileResumeState(FileResumeStatus.PENDING, message.sha256), run.now())
            }
        }
        maybeVerify(f)
    }

    private fun maybeVerify(f: Int) {
        val l = layout ?: return
        val start =
            lock.withLock {
                if (stopped || fileState[f] != FileResumeStatus.PENDING || verifying[f] || fileSha[f] == null || unitsLeft[f] > 0) return
                verifying[f] = true
                true
            }
        if (start) {
            files?.setStatus(f, FileStatus.VERIFYING)
            run.scope.launch(config.io) { verifyPermits.withPermit { verify(l, f) } }
        }
    }

    /** Whole-file SHA-256 check of the `.part` (S2), then publish or re-request (N5). */
    private suspend fun verify(
        l: TransferLayout,
        f: Int,
    ) {
        val expected = lock.withLock { fileSha[f] } ?: return
        val size = l.fileSize(f)
        val digest = sha256Digest()
        val matches =
            try {
                val partial = partialOf(f, size)
                val buffer = ByteArray(minOf(VERIFY_BUFFER.toLong(), maxOf(size, 1L)).toInt())
                var position = 0L
                var complete = partial.length() >= size
                while (complete && position < size) {
                    val n = partial.read(position, buffer, 0, minOf(buffer.size.toLong(), size - position).toInt())
                    if (n <= 0) {
                        complete = false
                        break
                    }
                    digest.update(buffer, 0, n)
                    position += n
                }
                complete && digest.digest().contentEquals(expected.toByteArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        if (lock.withLock { stopped }) return
        if (matches) publish(l, f, expected) else mismatch(l, f)
    }

    private suspend fun publish(
        l: TransferLayout,
        f: Int,
        sha: Sha256Digest,
    ) {
        val table = checkNotNull(files)
        val partial = partialOf(f, l.fileSize(f))
        val published =
            try {
                partial.close()
                store.publish(
                    partial,
                    table.nameOf(f),
                    receivedFiles?.get(f)?.mime,
                    DropInfo(partialId, l.fileCount, run.peerName, run.now()),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageFullException) {
                storageFailed()
                return
            } catch (e: Exception) {
                storageFailed(e)
                return
            }
        lock.withLock {
            partials.remove(f)
            fileState[f] = FileResumeStatus.DONE
            verifying[f] = false
            writers.remove(f)?.close()
        }
        table.setSaved(f, published.uri, published.name)
        table.setStatus(f, FileStatus.DONE)
        resumeStore.putFileState(transferId, f, FileResumeState(FileResumeStatus.DONE, sha, published.uri), run.now())
        run.reduceLater(TransferEvent.FileVerified(f, l.fileSize(f)))
    }

    /** The SHA-256 did not match: re-hash the units against their stored hashes to find the bad ones (N5). */
    private suspend fun mismatch(
        l: TransferLayout,
        f: Int,
    ) {
        val suspects = ArrayList<TransferUnit>()
        val bundle = l.bundlePlan.bundleOf(f)
        if (bundle != null) {
            suspects += bundle.unit
        } else {
            for (c in 0 until l.unitCount(f)) {
                val unit = TransferUnit(f, c)
                val expected = lock.withLock { tracker.hashOf(unit) }
                val bytes = runCatching { readUnit(l, unit) }.getOrNull()
                if (expected == null || bytes == null || !config.chunkHasher.matches(expected, bytes, 0, bytes.size)) suspects += unit
            }
            if (suspects.isEmpty()) for (c in 0 until l.unitCount(f)) suspects += TransferUnit(f, c)
        }
        val dirty =
            lock.withLock {
                for (unit in suspects) forgetUnit(l, unit)
                verifying[f] = false
                tracker.dirtyManifests(run.now())
            }
        if (dirty.isNotEmpty()) resumeStore.putManifests(dirty)
        files?.setStatus(f, FileStatus.IN_PROGRESS)
        val missing = if (bundle != null) l.unitsOf(f) else missingOf(suspects)
        run.reduceLater(TransferEvent.FileHashMismatch(f, missing))
    }

    /** Makes [unit] missing again (caller holds the lock). */
    private fun forgetUnit(
        l: TransferLayout,
        unit: TransferUnit,
    ) {
        val g = l.globalIndex(unit)
        val state = unitState[g.toInt()]
        if (state == MISSING || state == RELEASED) return
        if (state >= COMPLETE) completeUnits--
        if (state == DURABLE) {
            for (f in filesOf(l, unit)) if (fileState[f] == FileResumeStatus.PENDING) unitsLeft[f]++
        }
        tracker.markMissing(unit)
        unitState[g.toInt()] = MISSING
        prefix.remove(g)
        unitHash.remove(g)
        written.remove(g)
        addProgress(l, unit, l.unitLength(unit), 0)
    }

    private fun missingOf(units: List<TransferUnit>): MissingUnits {
        val byKey = units.groupBy { it.fileIndex }.toSortedMap(compareBy { it.toLong() and 0xFFFF_FFFFL })
        return MissingUnits(
            chunks =
                byKey.map { (key, list) ->
                    MissingChunks(key, IndexRange.coalesce(list.map { it.chunkIndex }.sorted()))
                },
        )
    }

    /** The reducer failed [f] for good (three strikes, §7.8): drop its partial and release its units. */
    fun onFileFailed(f: Int) {
        val l = layout ?: return
        val acks = ArrayList<ChunkRef>()
        lock.withLock {
            if (fileState[f] != FileResumeStatus.PENDING) return
            fileState[f] = FileResumeStatus.FAILED
            verifying[f] = false
            writers.remove(f)?.close()
            val units =
                if (l.bundlePlan.isBundled(
                        f,
                    )
                ) {
                    listOf(checkNotNull(l.bundlePlan.bundleOf(f)).unit)
                } else {
                    (0 until l.unitCount(f)).map {
                        TransferUnit(f, it)
                    }
                }
            for (unit in units) {
                val g = l.globalIndex(unit)
                val state = unitState[g.toInt()]
                if (state == RELEASED) continue
                if (filesOf(l, unit).any { fileState[it] == FileResumeStatus.PENDING }) continue
                if (state < COMPLETE) {
                    completeUnits++
                    acks += ChunkRef(unit.fileIndex, unit.chunkIndex)
                }
                unitState[g.toInt()] = RELEASED
                prefix.remove(g)
                assembly.remove(g)
            }
        }
        files?.setStatus(f, FileStatus.FAILED)
        for (ref in acks) ackBatcher.add(ref)
        run.scope.launch(config.io) {
            lock.withLock { partials.remove(f) }?.let { runCatching { it.close() } }
            runCatching { store.deletePartial(partialId, f) }
            resumeStore.putFileState(transferId, f, FileResumeState(FileResumeStatus.FAILED), run.now())
        }
        checkAllReceived()
    }

    /**
     * Raises `AllChunksAcked` when every unit is in (acks leave within 50 ms). The reducer ignores it outside the
     * streaming phases, so it is raised again after a resume until the state records it.
     */
    fun checkAllReceived() {
        if (layout == null || !layoutReady.isCompleted) return
        val all = lock.withLock { accepted && completeUnits >= totalUnits }
        if (all && !run.machineState.allUnitsAcked) run.reduceLater(TransferEvent.AllChunksAcked)
    }

    // =====================================================================================================
    // Resume
    // =====================================================================================================

    /**
     * The complete missing set for `Accept.resume` and `Resume` (§7.6): every unit not received, with the prefix
     * of a partly received one, plus the last unit of every file whose data is complete but whose `FileDone` is
     * unknown. Without a file list yet, everything.
     */
    fun missingUnits(): MissingUnits {
        val l = layout ?: return everything()
        return lock.withLock {
            val again = HashSet<Long>()
            for (f in 0 until l.fileCount) {
                if (fileState[f] != FileResumeStatus.PENDING || fileSha[f] != null) continue
                val last =
                    l.bundlePlan.bundleOf(f)?.unit
                        ?: l.unitCount(f).takeIf { it > 0 }?.let { TransferUnit(f, it - 1) }
                        ?: continue
                if (isDataComplete(l, f)) again += l.globalIndex(last)
            }
            l.missingUnits(presentBytes = { unit -> prefix[l.globalIndex(unit)] ?: 0 }) { unit ->
                val g = l.globalIndex(unit)
                unitState[g.toInt()] >= COMPLETE && g !in again
            }
        }
    }

    private fun isDataComplete(
        l: TransferLayout,
        f: Int,
    ): Boolean {
        val units =
            if (l.bundlePlan.isBundled(f)) {
                listOf(checkNotNull(l.bundlePlan.bundleOf(f)).unit)
            } else {
                (0 until l.unitCount(f)).map {
                    TransferUnit(f, it)
                }
            }
        return units.all { unitState[l.globalIndex(it).toInt()] >= COMPLETE }
    }

    private fun everything(): MissingUnits =
        MissingUnits(
            chunks =
                if (offer.bundleCount > 0) {
                    listOf(MissingChunks(ProtocolConstants.BUNDLE_FILE_INDEX, listOf(IndexRange(0, offer.bundleCount))))
                } else {
                    emptyList()
                },
            files = listOf(IndexRange(0, offer.fileCount)),
        )

    /** Sends the complete missing set on the control route (after a reconnect or a control-route change). */
    fun sendResume() {
        if (!lock.withLock { accepted }) return
        restartFileList()
        run.sendControl(Resume(transferId, missingUnits()))
        checkAllReceived()
    }

    // =====================================================================================================
    // Progress and the end
    // =====================================================================================================

    /** File bytes of `[from, to)` of [unit] to the progress (negative when `to < from`); caller holds the lock. */
    private fun addProgress(
        l: TransferLayout,
        unit: TransferUnit,
        from: Int,
        to: Int,
        live: Boolean = true,
    ) {
        if (from == to) return
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        val sign = if (to > from) 1 else -1
        val bytes =
            if (unit.isBundle) {
                val index = Bundle.indexSize(l.bundlePlan.bundles[unit.chunkIndex].entries.size)
                (minOf(hi, l.unitLength(unit)) - maxOf(lo, index)).coerceAtLeast(0).toLong()
            } else {
                (hi - lo).toLong()
            } * sign
        bytesDone += bytes
        if (sign > 0 && live) run.meter.add(bytes)
        val table = files ?: return
        if (unit.isBundle) {
            if (hi == l.unitLength(unit)) {
                for (entry in l.bundlePlan.bundles[unit.chunkIndex].entries) table.addBytes(entry.fileIndex, entry.length.toLong() * sign)
            }
        } else {
            table.addBytes(unit.fileIndex, bytes)
        }
    }

    fun markClearPartials() {
        lock.withLock { clearPartials = true }
    }

    /** The transfer ended in [state]: stop writing, store or drop the resume data, close the partials. */
    suspend fun finish(state: TransferState) {
        val done = state.phase == TransferPhase.DONE
        val clear = lock.withLock { clearPartials } || done
        val jobs =
            lock.withLock {
                val channels = writers.values.toList()
                writers.clear()
                channels.forEach { it.close() }
                writerJobs.toList()
            }
        withTimeoutOrNull(FINISH_WAIT_MILLIS) { jobs.forEach { it.join() } }
        lock.withLock { stopped = true }
        flushSignal.close()
        flusherJob?.cancel()
        if (!clear) withContext(NonCancellable) { runCatching { flush() } }
        ackBatcher.close()
        val handles =
            lock.withLock {
                val list = partials.values.toList()
                partials.clear()
                assembly.clear()
                list
            }
        for (partial in handles) runCatching { partial.close() }
        if (clear) {
            runCatching { resumeStore.delete(transferId) }
            runCatching { store.deletePartials(partialId) }
        }
        if (!done) files?.failUnfinished()
    }

    private fun filesOf(
        l: TransferLayout,
        unit: TransferUnit,
    ): List<Int> = if (unit.isBundle) l.bundlePlan.bundles[unit.chunkIndex].entries.map { it.fileIndex } else listOf(unit.fileIndex)

    private companion object {
        const val MISSING: Byte = 0
        const val RECEIVING: Byte = 1
        const val COMPLETE: Byte = 2
        const val DURABLE: Byte = 3
        const val RELEASED: Byte = 4

        const val VERIFY_BUFFER: Int = ProtocolConstants.MIB
        const val SYNC_PARALLELISM: Int = 8
        const val FINISH_WAIT_MILLIS: Long = 10_000

        /** Below this much free space a write failure counts as a full disk. */
        const val MIN_FREE_BYTES: Long = 8L * ProtocolConstants.MIB
    }
}
