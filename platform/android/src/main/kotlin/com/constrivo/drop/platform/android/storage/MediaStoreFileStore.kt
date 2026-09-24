package com.constrivo.drop.platform.android.storage

import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.PartialFile
import com.constrivo.drop.core.transfer.PublishedFile
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.StorageException
import com.constrivo.drop.core.transfer.StorageFullException
import com.constrivo.drop.core.transfer.receive.FileNameSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine's [FileStore] on Android (spec change N14; architecture §7.6, §10.1 "Storage"; F-D3, F-D4, F-D5):
 *
 * - **Partials are the final files, hidden.** The first write of a received file creates its pending item at the
 *   destination ([PendingItemResolver.create]): a MediaStore row with `IS_PENDING = 1` in the gallery collection of its
 *   type or in Downloads ([MediaRouting]), or, for a user-picked folder ([ReceiveDestination.DocumentTree]), a document
 *   under a temporary name. The engine writes it positionally through a `"rw"` descriptor, and [publish] makes it
 *   visible under its final name (clearing `IS_PENDING`, or renaming the document). Nothing is copied, so a file needs
 *   its own size in free space once, not twice (the problem N14 fixes).
 * - **The manifest stays app-private.** Which item holds which partial is recorded in [index] under the app's files
 *   directory, beside the resume plan, so a transfer resumed after an app kill (T-07) reopens the same item, and a
 *   cancel or the 24 h sweep deletes items this run did not create ([deletePartials]).
 * - **Names.** Items are created under the sanitised name of the file list ([catalog]) and published under the name the
 *   engine passes (sanitised again), never replacing a file: a taken name gets ` (1)`, ` (2)`, … A drop of more than 20
 *   files goes into one subfolder per drop (design §9), chosen once and kept across restarts.
 * - **Space and cards.** [freeBytes] is the destination volume's free space (T-27: an `Offer` that does not fit is
 *   declined before `Accept`), a full volume mid-write is [StorageFullException] (cancel with `storage`, partials
 *   cleared), and [destinationIsRemovable] drives the `sdcard` hint (T-23).
 * - **Sources.** [openSource] reads a `content:` URI the app may read ([sources]); a share's URIs stay readable while
 *   the transfer service holds their grant (architecture §10.1).
 *
 * Every pending item goes through [PendingItemLifecycle]: a step it does not allow (writing a published file) is a
 * [StorageException]. Thread-safe; blocking I/O runs on [io].
 */
class MediaStoreFileStore(
    val destination: ReceiveDestination,
    private val resolver: PendingItemResolver,
    private val index: PendingItemIndex,
    private val catalog: ReceiveCatalog,
    private val mimeForExtension: (String) -> String?,
    private val wallClock: WallClock,
    private val sources: ContentSources? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : FileStore {
    private data class Key(
        val transferId: String,
        val fileIndex: Int,
    )

    /** One file's pending item as this process knows it; all changes happen under [mutex]. */
    private class Slot {
        val mutex = Mutex()
        var state: PendingState = PendingState.ABSENT
        var ref: PendingItemRef? = null
        val handles = LinkedHashSet<MediaPartialFile>()
    }

    private val slots = ConcurrentHashMap<Key, Slot>()
    private val folderLock = Mutex()

    override val destinationIsRemovable: Boolean by lazy {
        try {
            resolver.isRemovable(destination)
        } catch (e: RuntimeException) {
            false
        }
    }

    override suspend fun openSource(uri: String): SourceFile {
        val reader = sources ?: throw StorageException("this store has no source reader")
        return reader.open(uri)
    }

    override suspend fun openPartial(
        transferId: String,
        fileIndex: Int,
        expectedSize: Long,
    ): PartialFile {
        require(fileIndex >= 0) { "file index must be non-negative" }
        require(expectedSize >= 0) { "size must be non-negative" }
        val key = Key(transferId, fileIndex)
        val slot = slots.computeIfAbsent(key) { Slot() }
        return slot.mutex.withLock {
            guardedIo("cannot open the partial of file $fileIndex") {
                if (slot.state == PendingState.PUBLISHED) throw PendingItemStateException(slot.state, PendingEvent.OPEN)
                val known = slot.ref ?: index.read(transferId, fileIndex)
                val ref = known?.takeIf { resolver.exists(it) } ?: create(transferId, fileIndex).also { if (known != null) forget(known) }
                val file = resolver.open(ref)
                slot.state =
                    PendingItemLifecycle.next(
                        if (slot.state ==
                            PendingState.DELETED
                        ) {
                            PendingState.ABSENT
                        } else {
                            slot.state
                        },
                        PendingEvent.OPEN,
                    )
                slot.ref = ref
                MediaPartialFile(transferId, fileIndex, file, slot).also { slot.handles += it }
            }
        }
    }

    override suspend fun publish(
        partial: PartialFile,
        name: String,
        mimeType: String?,
        drop: DropInfo,
    ): PublishedFile {
        partial.close()
        val key = Key(partial.transferId, partial.fileIndex)
        val slot = slots.computeIfAbsent(key) { Slot() }
        val folder = dropFolder(partial.transferId, drop)
        return slot.mutex.withLock {
            guardedIo("cannot publish ${FileNameSanitizer.sanitize(name)}") {
                val ref =
                    slot.ref ?: index.read(key.transferId, key.fileIndex) ?: throw StorageException("no partial for file ${key.fileIndex}")
                if (!resolver.exists(ref)) throw StorageException("the partial of file ${key.fileIndex} is gone")
                closeHandles(slot)
                // Checked before anything changes: a published or deleted item is not published (again). An item this
                // instance never opened (another store of the same process wrote it) is a closed one.
                val published =
                    PendingItemLifecycle.next(
                        if (slot.state ==
                            PendingState.ABSENT
                        ) {
                            PendingState.CLOSED
                        } else {
                            slot.state
                        },
                        PendingEvent.PUBLISH,
                    )
                val routed = MediaRouting.route(name, mimeType, mimeForExtension, folder.name)
                // A MediaStore row stays in the collection it was created in (the path of another one is refused).
                val target =
                    if (ref.collection == null || ref.collection == routed.collection) {
                        routed
                    } else {
                        routed.copy(
                            collection = ref.collection,
                            relativePath = routed.relativePath.replaceFirst(routed.collection.topDirectory, ref.collection.topDirectory),
                            mimeType = ref.mimeType,
                        )
                    }
                val item = resolver.publish(ref, target)
                slot.state = published
                slot.ref = ref
                runCatching { index.remove(key.transferId, key.fileIndex) }
                PublishedFile(item.uri, item.displayName)
            }
        }
    }

    override suspend fun deletePartial(
        transferId: String,
        fileIndex: Int,
    ) {
        val key = Key(transferId, fileIndex)
        val slot = slots.computeIfAbsent(key) { Slot() }
        slot.mutex.withLock {
            guardedIo("cannot delete the partial of file $fileIndex") {
                if (slot.state == PendingState.PUBLISHED) return@guardedIo
                closeHandles(slot)
                (slot.ref ?: index.read(transferId, fileIndex))?.let(resolver::delete)
                index.remove(transferId, fileIndex)
                slot.state = PendingItemLifecycle.next(slot.state, PendingEvent.DELETE)
                slot.ref = null
            }
        }
        slots.remove(key, slot)
    }

    override suspend fun deletePartials(transferId: String) {
        val mine = slots.entries.filter { it.key.transferId == transferId }
        for ((key, slot) in mine) {
            slot.mutex.withLock {
                closeHandles(slot)
                if (slot.state != PendingState.PUBLISHED) {
                    slot.ref?.let { ref -> guardedIo("cannot delete the partial of file ${key.fileIndex}") { resolver.delete(ref) } }
                    slot.state = PendingItemLifecycle.next(slot.state, PendingEvent.DELETE)
                    slot.ref = null
                }
            }
            slots.remove(key, slot)
        }
        guardedIo("cannot delete the partials of $transferId") {
            for (ref in index.all(transferId).values) resolver.delete(ref)
            index.deleteTransfer(transferId)
        }
        catalog.forget(transferId)
    }

    override suspend fun freeBytes(): Long = guardedIo("cannot read the free space") { resolver.freeBytes(destination) }

    /** The pending-item states this process knows, for tests and diagnostics. */
    fun stateOf(
        transferId: String,
        fileIndex: Int,
    ): PendingState = slots[Key(transferId, fileIndex)]?.state ?: PendingState.ABSENT

    /** Creates the pending item of [fileIndex] and records it; an item whose record cannot be written is deleted again. */
    private suspend fun create(
        transferId: String,
        fileIndex: Int,
    ): PendingItemRef {
        val entry = catalog.entry(transferId, fileIndex)
        val folder = dropFolder(transferId, null)
        val name = entry?.name?.let(FileNameSanitizer::sanitize) ?: "${FileNameSanitizer.FALLBACK_NAME}-$fileIndex"
        val target = MediaRouting.route(name, entry?.mime, mimeForExtension, folder.name)
        val ref = resolver.create(PendingItemSpec(destination, target, transferId, fileIndex, folder.name))
        try {
            index.write(transferId, fileIndex, ref)
        } catch (e: IOException) {
            runCatching { resolver.delete(ref) }
            throw e
        }
        return ref
    }

    /** An item that no longer exists: its record goes too (a new item replaces it). */
    private fun forget(ref: PendingItemRef) {
        runCatching { resolver.delete(ref) }
    }

    /**
     * The drop's subfolder (design §9: above 20 files), chosen once per transfer and kept in [index], so every file of a
     * drop lands in one folder even across an app restart. [drop] is the engine's publish information; before the first
     * publish the catalog's facts decide, at the wall-clock time of the first file.
     */
    private suspend fun dropFolder(
        transferId: String,
        drop: DropInfo?,
    ): PendingItemIndex.DropFolder =
        folderLock.withLock {
            withContext(io) {
                runCatching { index.dropFolder(transferId) }.getOrNull()?.let { return@withContext it }
                val facts = catalog.drop(transferId)
                val count = drop?.fileCount ?: facts?.fileCount ?: 0
                val sender = drop?.senderName ?: facts?.senderName
                val at = drop?.receivedAtMillis ?: wallClock.nowMillis()
                val folder = PendingItemIndex.DropFolder(MediaRouting.dropFolderName(count, sender, at, zone))
                // Chosen only once the drop's size is known; before that a file waits for its name, not its folder.
                if (drop != null || facts != null) runCatching { index.writeDropFolder(transferId, folder) }
                folder
            }
        }

    private fun closeHandles(slot: Slot) {
        for (handle in slot.handles.toList()) handle.closeNow()
        slot.handles.clear()
        if (slot.state == PendingState.OPEN) slot.state = PendingItemLifecycle.next(slot.state, PendingEvent.CLOSE)
    }

    private suspend fun <T> guardedIo(
        what: String,
        block: suspend () -> T,
    ): T =
        withContext(io) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                throw e
            } catch (e: PendingItemStateException) {
                throw StorageException("$what: ${e.message}", e)
            } catch (e: IOException) {
                throw StorageException("$what: ${e.message}", e)
            } catch (e: SecurityException) {
                // The grant on a picked folder was revoked, or the item belongs to a reinstalled copy of the app.
                throw StorageException("$what: access denied", e)
            } catch (e: IllegalArgumentException) {
                throw StorageException("$what: ${e.message}", e)
            } catch (e: IllegalStateException) {
                throw StorageException("$what: ${e.message}", e)
            }
        }

    /** One open descriptor of a pending item. Positional reads and writes; [sync] is the N5 `fsync`. */
    private inner class MediaPartialFile(
        override val transferId: String,
        override val fileIndex: Int,
        private val file: PositionalFile,
        private val slot: Slot,
    ) : PartialFile {
        @Volatile
        private var closed = false

        override suspend fun write(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            require(position >= 0 && offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
            if (closed) throw StorageException("writing file $fileIndex failed: the partial is closed")
            guardedIo("writing file $fileIndex failed") { file.write(position, buffer, offset, length) }
        }

        override suspend fun read(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "range out of bounds" }
            if (closed) throw StorageException("reading file $fileIndex failed: the partial is closed")
            return guardedIo("reading file $fileIndex failed") { file.read(position, buffer, offset, length) }
        }

        override suspend fun length(): Long {
            if (!closed) return guardedIo("reading the length of file $fileIndex failed") { file.length() }
            // A closed handle still answers from a short-lived descriptor, as a file on disk would.
            val ref = slot.ref ?: return 0L
            return guardedIo("reading the length of file $fileIndex failed") { resolver.open(ref).use { it.length() } }
        }

        override suspend fun sync() {
            // A closed handle cannot make anything durable: saying so is what keeps the manifest honest (N5).
            if (closed) throw StorageException("syncing file $fileIndex failed: the partial is closed")
            guardedIo("syncing file $fileIndex failed") { file.sync() }
        }

        override suspend fun close() {
            if (closed) return
            slot.mutex.withLock {
                if (closeNow()) slot.handles.remove(this)
                if (slot.handles.isEmpty() && slot.state == PendingState.OPEN) {
                    slot.state = PendingItemLifecycle.next(slot.state, PendingEvent.CLOSE)
                }
            }
        }

        /** Closes the descriptor; true when this call closed it. Called with the slot's lock held or from [close]. */
        fun closeNow(): Boolean {
            if (closed) return false
            closed = true
            runCatching { file.close() }
            return true
        }
    }
}
