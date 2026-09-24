package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.Accept
import com.constrivo.drop.core.protocol.Ack
import com.constrivo.drop.core.protocol.Bundle
import com.constrivo.drop.core.protocol.CancelReason
import com.constrivo.drop.core.protocol.ChunkHash
import com.constrivo.drop.core.protocol.ChunkHeader
import com.constrivo.drop.core.protocol.FileDone
import com.constrivo.drop.core.protocol.Hint
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.ProtocolException
import com.constrivo.drop.core.protocol.Resume
import com.constrivo.drop.core.protocol.ResumeUnit
import com.constrivo.drop.core.protocol.Retransmit
import com.constrivo.drop.core.protocol.Sha256Digest
import com.constrivo.drop.core.protocol.TransferEvent
import com.constrivo.drop.core.protocol.TransferUnit
import com.constrivo.drop.core.transfer.TransferLock
import com.constrivo.drop.core.transfer.hash.EMPTY_SHA256
import com.constrivo.drop.core.transfer.hash.StreamingDigest
import com.constrivo.drop.core.transfer.hash.sha256Digest
import com.constrivo.drop.core.transfer.send.SendPlan
import com.constrivo.drop.core.transfer.send.SendQueue
import com.constrivo.drop.core.transfer.send.SourceReadException
import com.constrivo.drop.core.transfer.send.UnitReader
import com.constrivo.drop.core.transfer.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * The sender's data path (architecture §7.3–7.5; spec changes S1, S2, S4, N12):
 *
 * - **Queue and producer.** A [SendQueue] holds the units to send (after `Accept`: all of them, or exactly
 *   `Accept.resume`; after a `Resume`: exactly its missing set; `Retransmit` adds to the front). One producer reads them
 *   in order into pooled buffers ([EngineLimits.SEND_BUFFERS], §15), computes each frame's XXH3-128, and hashes every
 *   file with SHA-256 while it reads the file sequentially (S2); a file that cannot be read in order (a resume that
 *   starts mid-file) gets its own sequential hash pass.
 * - **Streams.** Every Wi-Fi data stream of the link in use takes prepared units from the producer as its window allows
 *   (at most [ProtocolConstants.STREAM_WINDOW] unacknowledged per stream: work stealing over one shared queue, §7.4), on
 *   at most the policy's number of streams. Without a Wi-Fi link the primary connection carries data: on Bluetooth in
 *   16 KiB blocks with one block in flight, each acknowledged (the head start, S1), on a LAN primary in whole units.
 * - **Hop.** When a Wi-Fi link is taken into use the Bluetooth stream finishes its in-flight block, waits for its ack, and
 *   puts the rest of its unit at the front of the queue from the first unacknowledged byte, so no byte is sent twice.
 * - **Acks and FileDone.** Acks retire units; `FileDone` goes out once a file's last unit is written and its SHA-256 is
 *   known; `AllChunksAcked` is raised when every unit is acknowledged and every `FileDone` is out.
 */
internal class SendSide(
    private val run: TransferRun,
    val plan: SendPlan,
) {
    private val config = run.config
    private val layout = plan.layout
    private val total = layout.totalUnits
    private val fileCount = plan.files.size
    private val lock = TransferLock()
    private val queue = SendQueue()
    private val reader = UnitReader(layout, plan.sources)
    private val pool = BufferPool(ChunkHeader.SIZE + layout.chunkSize, EngineLimits.SEND_BUFFERS)
    private val ready =
        Channel<Prepared>(Channel.UNLIMITED) { lost ->
            // A worker was cancelled while this was being handed over: put the unit back.
            lost.buffer.release()
            queue.addPriority(lost.item.unit, lost.item.global)
        }

    val files = FileProgressTable(plan.files, receiver = false)

    // ---- per-unit state, under [lock] ----
    private val acked = BitSet(total)
    private var ackedUnits = 0L
    private val sentOnce = BitSet(total)
    private val countedPrefix = HashMap<Long, Int>()
    private val inFlight = HashMap<Long, Conn>()
    private var btBlock: BtBlock? = null
    private var btUnit: Long = -1

    // ---- per-file state, under [lock] ----
    private val sha = arrayOfNulls<Sha256Digest>(fileCount)
    private val needsDone = BooleanArray(fileCount)
    private var needsDoneCount = 0
    private val pendingSend = IntArray(fileCount)
    private val hashPassRequested = BooleanArray(fileCount)
    private val hashPasses = Channel<Int>(Channel.UNLIMITED)

    // ---- scheduling, under [lock] ----
    private val workers = HashMap<Conn, Worker>()
    private var primaryWorker: Worker? = null
    private var activeGeneration: Int? = null
    private var streaming = false
    private var stopped = false
    private var awaitingResume = false
    private var fileListConfirmed = false
    private var listWritten = false
    private var allAckedPosted = false
    private var producerJob: Job? = null
    private var hashJob: Job? = null
    private var targetJob: Job? = null
    private var firstBlockReported = false

    // ---- progress and stats ----
    @Volatile
    var bytesDone: Long = 0
        private set

    @Volatile
    var activeStreams: Int = 0
        private set

    private val ackedByKind = HashMap<LinkKind, Long>()

    /** Plaintext payload bytes written in chunk frames (bundle indexes included), and file bytes among them. */
    var payloadBytesSent: Long = 0
        private set
    var fileBytesSent: Long = 0
        private set

    private class Prepared(
        val item: SendQueue.Item,
        val buffer: PooledBuffer,
        val length: Int,
        val hash: ChunkHash,
    )

    private class BtBlock(
        val global: Long,
        val offset: Int,
        val length: Int,
        val outcome: CompletableDeferred<BlockOutcome>,
    )

    private sealed interface BlockOutcome {
        data object Acked : BlockOutcome

        data class Resend(
            val from: Int,
        ) : BlockOutcome

        data object Lost : BlockOutcome
    }

    private class Worker(
        val job: Job,
    ) {
        @Volatile
        var stopRequested = false
    }

    // =====================================================================================================
    // Accept, streaming start and stop
    // =====================================================================================================

    /** The receiver accepted: the queue is everything, or exactly `Accept.resume` (§7.6). */
    fun accepted(accept: Accept) {
        val units = accept.resume?.let { layout.expand(it) } ?: layout.units().map { ResumeUnit(it, 0) }.toList()
        run.setStreamLimit(accept.streamCount)
        build(units)
    }

    fun startStreaming() {
        lock.withLock {
            if (streaming || stopped) return
            streaming = true
        }
        if (plan.bundledFileCount >= BUNDLING_HINT_MIN_FILES) {
            run.addLocalHint(HintCode.BUNDLING)
            run.sendControl(Hint(HintCode.BUNDLING, mapOf("count" to plan.bundledFileCount.toString()), plan.transferId))
        }
        producerJob = run.scope.launch(config.io) { produce() }
        hashJob = run.scope.launch(config.io) { hashFiles() }
        planHashes()
        sendReadyFileDones()
        sendFileListThenSchedule()
        targetJob = run.scope.launch { run.streamTarget.collect { scheduleWorkers() } }
    }

    /**
     * Sends the `FileList` pages (N12) and starts the stream workers only once they are written: on a Bluetooth primary
     * the pages and the head-start blocks share one connection, and the receiver cannot place a chunk before it knows
     * the file list.
     */
    private fun sendFileListThenSchedule() {
        lock.withLock { listWritten = false }
        for (page in plan.fileListPages) run.sendControl(page)
        run.scope.launch {
            run.drainOutbox()
            lock.withLock { listWritten = true }
            scheduleWorkers()
        }
    }

    suspend fun stop() {
        val jobs =
            lock.withLock {
                stopped = true
                val list = workers.values.map { it.job } + listOfNotNull(primaryWorker?.job, producerJob, hashJob, targetJob)
                workers.clear()
                primaryWorker = null
                btBlock?.outcome?.complete(BlockOutcome.Lost)
                list
            }
        queue.close()
        hashPasses.close()
        jobs.forEach { it.cancel() }
        while (true) {
            val leftover = ready.tryReceive().getOrNull() ?: break
            leftover.buffer.release()
        }
        ready.close()
    }

    // =====================================================================================================
    // Queue building, acks, retransmits, resumes
    // =====================================================================================================

    /**
     * Makes [units] the complete set still to send (`Accept.resume` or a `Resume`, §7.6): units not listed are known to
     * the receiver and count as acknowledged; listed units are queued, except those in flight on a live stream.
     */
    private fun build(units: List<ResumeUnit>) {
        val listed = BitSet(total)
        val offsets = HashMap<Long, Int>()
        val entries = ArrayList<Pair<ResumeUnit, Long>>(units.size)
        for (unit in units) {
            val g = layout.globalIndex(unit.unit)
            if (listed[g]) continue
            listed.set(g)
            if (unit.fromOffset > 0) offsets[g] = unit.fromOffset
            entries += unit to g
        }
        val toQueue = ArrayList<Pair<ResumeUnit, Long>>(entries.size)
        lock.withLock {
            var g = 0L
            while (g < total) {
                val unit = layout.unitAt(g)
                val flying = inFlight[g]?.alive == true || btUnit == g
                if (!listed[g]) {
                    inFlight.remove(g)?.window?.release()
                    if (!acked[g]) markAcked(g, unit, null, live = false)
                } else if (!flying) {
                    if (acked[g]) unack(g, unit)
                    setCountedPrefix(g, unit, offsets[g] ?: 0)
                    sentOnce.clear(g)
                }
                g++
            }
            for (f in 0 until fileCount) pendingSend[f] = 0
            for ((unit, global) in entries) {
                if (inFlight[global]?.alive == true || btUnit == global) continue
                toQueue += unit to global
                for (f in filesOf(unit.unit)) pendingSend[f]++
            }
            for (f in 0 until fileCount) {
                val want = pendingSend[f] > 0 || isEmptyChunked(f)
                if (needsDone[f] != want) {
                    needsDone[f] = want
                    needsDoneCount += if (want) 1 else -1
                }
                if (isEmptyChunked(f) && sha[f] == null) sha[f] = Sha256Digest(EMPTY_SHA256)
            }
            allAckedPosted = false
        }
        queue.replace(toQueue)
        if (streaming) planHashes()
    }

    fun onAck(ack: Ack) {
        lock.withLock {
            fileListConfirmed = true
            for (ref in ack.chunks) {
                val unit = ref.unit
                if (!layout.contains(unit)) continue
                val g = layout.globalIndex(unit)
                val blockOffset = ref.blockOffset
                if (blockOffset == null) unitAcked(g, unit) else blockAcked(g, unit, blockOffset)
            }
        }
        checkAllAcked()
        sendReadyFileDones()
        run.progressChanged(bytesDone)
    }

    /** A whole unit is acknowledged (caller holds the lock). */
    private fun unitAcked(
        g: Long,
        unit: TransferUnit,
    ) {
        val conn = inFlight.remove(g)
        conn?.window?.release()
        val wasQueued = queue.remove(g)
        if (wasQueued) unitDropped(g, unit)
        if (!acked[g]) markAcked(g, unit, conn?.kind ?: run.primary.kind, live = !wasQueued)
        if (btUnit == g) btBlock?.takeIf { it.global == g }?.outcome?.complete(BlockOutcome.Acked)
    }

    /** A Bluetooth block is acknowledged (S1; caller holds the lock). */
    private fun blockAcked(
        g: Long,
        unit: TransferUnit,
        offset: Int,
    ) {
        val block = btBlock
        if (block != null && block.global == g && block.offset == offset) {
            val newPrefix = offset + block.length
            val before = countedPrefix[g] ?: 0
            if (!acked[g] && newPrefix > before) {
                addBytes(unit, before, newPrefix, LinkKind.BLUETOOTH)
                countedPrefix[g] = newPrefix
                if (newPrefix >= layout.unitLength(unit)) markAcked(g, unit, LinkKind.BLUETOOTH, live = true)
            }
            block.outcome.complete(BlockOutcome.Acked)
        }
    }

    private fun markAcked(
        g: Long,
        unit: TransferUnit,
        kind: LinkKind?,
        live: Boolean,
    ) {
        val before = countedPrefix.remove(g) ?: 0
        addBytes(unit, before, layout.unitLength(unit), kind.takeIf { live })
        acked.set(g)
        ackedUnits++
    }

    private fun unack(
        g: Long,
        unit: TransferUnit,
    ) {
        acked.clear(g)
        ackedUnits--
        addBytes(unit, layout.unitLength(unit), 0, null)
        countedPrefix.remove(g)
    }

    /** Counts bytes `0 until from` of a unit that is not acknowledged (the receiver holds them, S1). */
    private fun setCountedPrefix(
        g: Long,
        unit: TransferUnit,
        from: Int,
    ) {
        val before = countedPrefix[g] ?: 0
        if (before == from) return
        addBytes(unit, before, from, null)
        if (from == 0) countedPrefix.remove(g) else countedPrefix[g] = from
    }

    /** Adds the file bytes of `[from, to)` of [unit] to the progress (negative when `to < from`). */
    private fun addBytes(
        unit: TransferUnit,
        from: Int,
        to: Int,
        kind: LinkKind?,
    ) {
        if (from == to) return
        val sign = if (to > from) 1 else -1
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        val bytes = dataBytesIn(unit, lo, hi) * sign
        if (bytes == 0L) return
        bytesDone += bytes
        if (sign > 0 && kind != null) {
            run.meter.add(bytes)
            ackedByKind[kind] = (ackedByKind[kind] ?: 0) + bytes
        }
        if (unit.isBundle) {
            if (hi == layout.unitLength(unit)) {
                for (entry in layout.bundlePlan.bundles[unit.chunkIndex].entries) {
                    files.addBytes(
                        entry.fileIndex,
                        entry.length.toLong() * sign,
                    )
                }
            }
        } else {
            files.addBytes(unit.fileIndex, bytes)
        }
    }

    fun onRetransmit(message: Retransmit) {
        val units = layout.expand(message.units)
        lock.withLock {
            for (resumeUnit in units) {
                val g = layout.globalIndex(resumeUnit.unit)
                val block = btBlock
                if (btUnit == g && block != null) {
                    block.outcome.complete(BlockOutcome.Resend(resumeUnit.fromOffset))
                    continue
                }
                inFlight.remove(g)?.window?.release()
                if (acked[g]) unack(g, resumeUnit.unit)
                setCountedPrefix(g, resumeUnit.unit, resumeUnit.fromOffset)
                queue.addPriority(resumeUnit, g)
            }
            allAckedPosted = false
        }
    }

    fun onResume(message: Resume) {
        val units = layout.expand(message.missing)
        build(units)
        // Without any ack yet the receiver may not hold the whole file list: it restarts its list with every Resume.
        val resend =
            lock.withLock {
                awaitingResume = false
                !fileListConfirmed
            }
        if (resend) sendFileListThenSchedule() else scheduleWorkers()
        sendReadyFileDones()
        checkAllAcked()
    }

    private fun checkAllAcked() {
        val post =
            lock.withLock {
                if (!streaming || stopped || allAckedPosted) return
                if (ackedUnits != total || !queue.isEmpty || inFlight.isNotEmpty() || btBlock != null || needsDoneCount > 0) return
                allAckedPosted = true
                true
            }
        if (post) run.reduceLater(TransferEvent.AllChunksAcked)
    }

    /** Acked bytes per link kind since the last call, for the ladder's samples (§7.4). */
    fun drainAckedByKind(): Map<LinkKind, Long> =
        lock.withLock {
            val out = HashMap(ackedByKind)
            ackedByKind.clear()
            out
        }

    // =====================================================================================================
    // FileDone and whole-file hashes (S2)
    // =====================================================================================================

    /** Decides for each file that still needs a `FileDone` whether its hash comes from the stream or a hash pass. */
    private fun planHashes() {
        val passes = ArrayList<Int>()
        lock.withLock {
            for (f in 0 until fileCount) {
                if (!needsDone[f] || sha[f] != null || hashPassRequested[f]) continue
                if (layout.bundlePlan.isBundled(f)) continue // hashed with its bundle
                val count = layout.unitCount(f)
                var inOrder = pendingSend[f] == count
                if (inOrder) {
                    for (c in 0 until count) {
                        val g = layout.globalIndex(TransferUnit(f, c))
                        if (g !in queue || countedPrefix.containsKey(g)) {
                            inOrder = false
                            break
                        }
                    }
                }
                if (!inOrder) {
                    hashPassRequested[f] = true
                    passes += f
                }
            }
        }
        for (f in passes) hashPasses.trySend(f)
    }

    private fun requestHashPass(fileIndex: Int) {
        val send =
            lock.withLock {
                if (sha[fileIndex] != null || hashPassRequested[fileIndex]) return
                hashPassRequested[fileIndex] = true
                true
            }
        if (send) hashPasses.trySend(fileIndex)
    }

    private suspend fun hashFiles() {
        val buffer = ByteArray(HASH_PASS_BUFFER)
        for (f in hashPasses) {
            if (lock.withLock { sha[f] != null }) continue
            val digest = sha256Digest()
            val size = layout.fileSize(f)
            var position = 0L
            try {
                while (position < size) {
                    val n = minOf(buffer.size.toLong(), size - position).toInt()
                    reader.readFully(f, position, buffer, 0, n)
                    digest.update(buffer, 0, n)
                    position += n
                }
            } catch (e: SourceReadException) {
                run.call { run.reduce(TransferEvent.LocalCancel(CancelReason.SOURCE)) }
                return
            }
            fileHashed(f, Sha256Digest(digest.digest()))
        }
    }

    private fun fileHashed(
        fileIndex: Int,
        digest: Sha256Digest,
    ) {
        lock.withLock { if (sha[fileIndex] == null) sha[fileIndex] = digest }
        sendReadyFileDones(fileIndex)
    }

    /** Sends `FileDone` for [only] (or every file) whose last unit is out and whose hash is known. */
    private fun sendReadyFileDones(only: Int? = null) {
        val out = ArrayList<FileDone>()
        lock.withLock {
            if (!streaming || stopped || awaitingResume) return
            val range = if (only != null) only..only else 0 until fileCount
            for (f in range) {
                val digest = sha[f] ?: continue
                if (!needsDone[f] || pendingSend[f] > 0) continue
                needsDone[f] = false
                needsDoneCount--
                out += FileDone(plan.transferId, f, digest)
            }
        }
        for (message in out) run.sendControl(message)
        if (out.isNotEmpty()) checkAllAcked()
    }

    private fun filesOf(unit: TransferUnit): List<Int> =
        if (unit.isBundle) layout.bundlePlan.bundles[unit.chunkIndex].entries.map { it.fileIndex } else listOf(unit.fileIndex)

    private fun isEmptyChunked(fileIndex: Int): Boolean = !layout.bundlePlan.isBundled(fileIndex) && layout.fileSize(fileIndex) == 0L

    /** A queued unit was acknowledged before it was sent (the receiver released it): it no longer holds up `FileDone`. */
    private fun unitDropped(
        g: Long,
        unit: TransferUnit,
    ) {
        if (sentOnce[g]) return
        sentOnce.set(g)
        for (f in filesOf(unit)) {
            pendingSend[f]--
            if (sha[f] == null && needsDone[f] && !layout.bundlePlan.isBundled(f)) requestHashPass(f)
        }
    }

    /** The last byte of a unit went out: files whose units are all out and hashed get their `FileDone`. */
    private fun unitSent(
        g: Long,
        unit: TransferUnit,
    ) {
        val affected =
            lock.withLock {
                if (sentOnce[g]) return
                sentOnce.set(g)
                filesOf(unit).onEach { pendingSend[it]-- }
            }
        for (f in affected) sendReadyFileDones(f)
    }

    // =====================================================================================================
    // Producer
    // =====================================================================================================

    private class InStream(
        val digest: StreamingDigest,
        var nextChunk: Int,
    )

    private suspend fun produce() {
        val inStream = HashMap<Int, InStream>()
        var epoch = queue.epoch
        while (true) {
            val item = queue.take() ?: return
            if (item.epoch != epoch) {
                inStream.clear()
                epoch = item.epoch
            }
            val g = item.global
            val skip = lock.withLock { stopped || acked[g] || inFlight.containsKey(g) || btUnit == g }
            if (skip) continue
            val buffer = pool.acquire()
            val length =
                try {
                    reader.read(item.unit, buffer.bytes, ChunkHeader.SIZE) { f, digest -> fileHashed(f, digest) }
                } catch (e: CancellationException) {
                    buffer.release()
                    throw e
                } catch (e: SourceReadException) {
                    buffer.release()
                    run.call { run.reduce(TransferEvent.LocalCancel(CancelReason.SOURCE)) }
                    return
                }
            if (!item.priority) hashInStream(item, buffer.bytes, length, inStream)
            val hash = config.chunkHasher.hash(buffer.bytes, ChunkHeader.SIZE, length)
            ready.send(Prepared(item, buffer, length, hash))
        }
    }

    /** Feeds a chunk read in file order to its file's SHA-256 (S2); a chunk out of order sends the file to a hash pass. */
    private fun hashInStream(
        item: SendQueue.Item,
        buffer: ByteArray,
        length: Int,
        inStream: HashMap<Int, InStream>,
    ) {
        val unit = item.unit.unit
        if (unit.isBundle) return
        val f = unit.fileIndex
        if (lock.withLock { sha[f] != null || hashPassRequested[f] || !needsDone[f] }) {
            inStream.remove(f)
            return
        }
        val state =
            if (unit.chunkIndex == 0 && item.unit.fromOffset == 0) {
                InStream(sha256Digest(), 0).also { inStream[f] = it }
            } else {
                inStream[f]
            }
        if (state == null || state.nextChunk != unit.chunkIndex || item.unit.fromOffset != 0) {
            inStream.remove(f)
            requestHashPass(f)
            return
        }
        state.digest.update(buffer, ChunkHeader.SIZE, length)
        state.nextChunk++
        if (state.nextChunk == layout.unitCount(f)) {
            inStream.remove(f)
            fileHashed(f, Sha256Digest(state.digest.digest()))
        }
    }

    // =====================================================================================================
    // Stream workers
    // =====================================================================================================

    /** The receiver moved data to link [generation] (null: back to the primary connection). */
    fun useGeneration(generation: Int?) {
        lock.withLock { activeGeneration = generation }
        scheduleWorkers()
    }

    fun onConnAdded(conn: Conn) {
        scheduleWorkers()
    }

    fun onLinkLost(generation: Int) {
        lock.withLock { if (activeGeneration == generation) activeGeneration = null }
        scheduleWorkers()
    }

    fun onConnDown(conn: Conn) {
        lock.withLock {
            workers.remove(conn)?.job?.cancel()
            requeueInFlight { it === conn }
            if (conn.isPrimary) {
                btBlock?.outcome?.complete(BlockOutcome.Lost)
                primaryWorker?.job?.cancel()
                primaryWorker = null
            }
        }
        scheduleWorkers()
    }

    fun onSessionLost() {
        lock.withLock {
            workers.values.forEach { it.job.cancel() }
            workers.clear()
            primaryWorker?.job?.cancel()
            primaryWorker = null
            btBlock?.outcome?.complete(BlockOutcome.Lost)
            requeueInFlight { true }
            activeGeneration = null
            awaitingResume = true
            activeStreams = 0
        }
    }

    fun onSessionStarted() {
        lock.withLock { awaitingResume = true }
    }

    /** Puts the unacknowledged units in flight on the matching connections back at the front of the queue. */
    private fun requeueInFlight(match: (Conn) -> Boolean) {
        val lost = inFlight.filterValues(match).keys.toList()
        for (g in lost) {
            inFlight.remove(g)
            val unit = layout.unitAt(g)
            if (!acked[g]) queue.addPriority(ResumeUnit(unit, countedPrefix[g] ?: 0), g)
        }
    }

    /** Starts and stops workers so the link in use has one per stream up to the target, or the primary carries data. */
    fun scheduleWorkers() {
        lock.withLock {
            if (!streaming || stopped || awaitingResume || !listWritten) return
            val generation = activeGeneration
            val wanted = if (generation != null) run.connectionsOf(generation).take(run.streamTarget.value) else emptyList()
            val iterator = workers.entries.iterator()
            while (iterator.hasNext()) {
                val (conn, worker) = iterator.next()
                if (conn !in wanted || !conn.alive) {
                    worker.stopRequested = true
                    worker.job.cancel()
                    iterator.remove()
                }
            }
            for (conn in wanted) {
                if (workers[conn]?.job?.isActive == true) continue
                workers[conn] = launchWorker(conn) { wifiWorker(conn, it) }
            }
            activeStreams = wanted.size
            val primary = run.primary
            if (wanted.isEmpty() && primary.alive) {
                if (primaryWorker?.job?.isActive != true) {
                    primaryWorker =
                        launchWorker(primary) {
                            if (primary.kind == LinkKind.BLUETOOTH) bluetoothWorker(primary, it) else wifiWorker(primary, it)
                        }
                }
            } else {
                primaryWorker?.let {
                    // The Bluetooth stream finishes its in-flight block (its send runs non-cancellable) and stops.
                    it.stopRequested = true
                    it.job.cancel()
                }
                primaryWorker = null
            }
        }
    }

    private fun launchWorker(
        conn: Conn,
        body: suspend (Worker) -> Unit,
    ): Worker {
        lateinit var worker: Worker
        val job =
            run.scope.launch(config.io, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    body(worker)
                } finally {
                    lock.withLock { if (workers[conn] === worker) workers.remove(conn) }
                }
            }
        worker = Worker(job)
        job.start()
        return worker
    }

    /** Whole-unit frames on a Wi-Fi stream (or a LAN primary), at most two unacknowledged (§7.4). */
    private suspend fun wifiWorker(
        conn: Conn,
        worker: Worker,
    ) {
        while (conn.alive && !worker.stopRequested) {
            conn.window.acquire()
            val prepared =
                try {
                    ready.receive()
                } catch (e: Throwable) {
                    conn.window.release()
                    throw e
                }
            val keepGoing = withContext(NonCancellable) { sendWhole(conn, prepared) }
            if (!keepGoing) return
        }
    }

    private suspend fun sendWhole(
        conn: Conn,
        prepared: Prepared,
    ): Boolean {
        val item = prepared.item
        val g = item.global
        val unit = item.unit.unit
        val stale =
            lock.withLock {
                val drop = stopped || acked[g] || item.epoch != queue.epoch || inFlight.containsKey(g)
                if (!drop) inFlight[g] = conn
                if (acked[g]) unitDropped(g, unit)
                drop
            }
        if (stale) {
            prepared.buffer.release()
            conn.window.release()
            return true
        }
        val from = item.unit.fromOffset
        val bytes = prepared.buffer.bytes
        ChunkHeader(plan.transferId, unit.fileIndex, unit.chunkIndex, from, prepared.length, prepared.hash).encodeInto(bytes, 0)
        config.debug.corruptPlaintext(unit, from, bytes, ChunkHeader.SIZE, prepared.length)
        try {
            config.debug.beforeChunkSealed(conn.streamId)
            conn.secure.sendChunk(bytes, 0, ChunkHeader.SIZE + prepared.length)
        } catch (e: Throwable) {
            prepared.buffer.release()
            lock.withLock {
                if (inFlight[g] === conn) inFlight.remove(g)
                if (!acked[g]) queue.addPriority(item.unit, g)
            }
            run.connFailed(conn, e)
            return false
        }
        prepared.buffer.release()
        lock.withLock {
            payloadBytesSent += prepared.length
            fileBytesSent += dataBytesIn(unit, from, from + prepared.length)
        }
        unitSent(g, unit)
        return true
    }

    /** The Bluetooth head start (S1): 16 KiB blocks, one in flight, each acknowledged before the next. */
    private suspend fun bluetoothWorker(
        conn: Conn,
        worker: Worker,
    ) {
        val block = ByteArray(ChunkHeader.SIZE + ProtocolConstants.BLUETOOTH_BLOCK_SIZE)
        while (conn.alive && !worker.stopRequested) {
            val prepared = ready.receive()
            val keepGoing = withContext(NonCancellable) { sendBlocks(conn, worker, prepared, block) }
            if (!keepGoing) return
        }
    }

    private suspend fun sendBlocks(
        conn: Conn,
        worker: Worker,
        prepared: Prepared,
        block: ByteArray,
    ): Boolean {
        val item = prepared.item
        val g = item.global
        val unit = item.unit.unit
        val length = layout.unitLength(unit)
        val start = item.unit.fromOffset
        val stale =
            lock.withLock {
                val drop = stopped || acked[g] || item.epoch != queue.epoch || inFlight.containsKey(g)
                if (!drop) btUnit = g
                if (acked[g]) unitDropped(g, unit)
                drop
            }
        if (stale) {
            prepared.buffer.release()
            return true
        }
        var offset = start
        var result = true
        try {
            while (offset < length) {
                val stop = worker.stopRequested || !conn.alive
                if (stop || lock.withLock { acked[g] }) {
                    if (stop) lock.withLock { if (!acked[g]) queue.addPriority(ResumeUnit(unit, offset), g) }
                    result = !stop
                    return result
                }
                val n = minOf(ProtocolConstants.BLUETOOTH_BLOCK_SIZE, length - offset)
                prepared.buffer.bytes.copyInto(
                    block,
                    ChunkHeader.SIZE,
                    ChunkHeader.SIZE + offset - start,
                    ChunkHeader.SIZE + offset - start + n,
                )
                val hash = config.chunkHasher.hash(block, ChunkHeader.SIZE, n)
                ChunkHeader(plan.transferId, unit.fileIndex, unit.chunkIndex, offset, n, hash).encodeInto(block, 0)
                config.debug.corruptPlaintext(unit, offset, block, ChunkHeader.SIZE, n)
                val outcome = CompletableDeferred<BlockOutcome>()
                lock.withLock { btBlock = BtBlock(g, offset, n, outcome) }
                try {
                    config.debug.beforeChunkSealed(conn.streamId)
                    conn.secure.sendChunk(block, 0, ChunkHeader.SIZE + n)
                } catch (e: Throwable) {
                    lock.withLock { if (!acked[g]) queue.addPriority(ResumeUnit(unit, offset), g) }
                    run.connFailed(conn, e)
                    result = false
                    return false
                }
                lock.withLock {
                    payloadBytesSent += n
                    fileBytesSent += dataBytesIn(unit, offset, offset + n)
                }
                if (!firstBlockReported) {
                    firstBlockReported = true
                    run.reduceLater(TransferEvent.FirstChunkOverBluetooth)
                }
                if (offset + n >= length) unitSent(g, unit)
                when (val o = outcome.await()) {
                    BlockOutcome.Acked -> {
                        offset += n
                    }

                    is BlockOutcome.Resend -> {
                        offset = o.from.coerceIn(start, length - 1)
                    }

                    BlockOutcome.Lost -> {
                        lock.withLock { if (!acked[g]) queue.addPriority(ResumeUnit(unit, offset), g) }
                        result = false
                        return false
                    }
                }
            }
            return true
        } finally {
            prepared.buffer.release()
            lock.withLock {
                btBlock = null
                if (btUnit == g) btUnit = -1
            }
            if (result) checkAllAcked()
        }
    }

    /** File bytes in `[from, to)` of [unit]: all of them for a chunk, those past the index for a bundle. */
    private fun dataBytesIn(
        unit: TransferUnit,
        from: Int,
        to: Int,
    ): Long {
        if (!unit.isBundle) return (to - from).toLong()
        val index = Bundle.indexSize(layout.bundlePlan.bundles[unit.chunkIndex].entries.size)
        return (minOf(to, layout.unitLength(unit)) - maxOf(from, index)).coerceAtLeast(0).toLong()
    }

    /** Test access: every unit acknowledged. */
    val allAcked: Boolean get() = lock.withLock { ackedUnits == total }

    companion object {
        /** Mirrors `HintRules.BUNDLING_HINT_MIN_FILES` in `core/ladder` (which depends on this module). */
        const val BUNDLING_HINT_MIN_FILES: Int = 10
        private const val HASH_PASS_BUFFER = ProtocolConstants.MIB
    }
}

/** A fixed-size bit set over the layout's global unit order. */
internal class BitSet(
    size: Long,
) {
    private val words = LongArray(((size + 63) / 64).toInt())

    operator fun get(index: Long): Boolean = (words[(index ushr 6).toInt()] ushr (index and 63).toInt()) and 1L != 0L

    fun set(index: Long) {
        val w = (index ushr 6).toInt()
        words[w] = words[w] or (1L shl (index and 63).toInt())
    }

    fun clear(index: Long) {
        val w = (index ushr 6).toInt()
        words[w] = words[w] and (1L shl (index and 63).toInt()).inv()
    }
}
