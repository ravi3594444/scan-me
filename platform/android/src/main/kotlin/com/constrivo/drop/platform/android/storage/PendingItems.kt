package com.constrivo.drop.platform.android.storage

import java.io.IOException

/**
 * A file opened for positional reads and writes: a MediaStore or document descriptor opened `"rw"` on a device
 * ([DescriptorFile]), a `FileChannel` in tests. Blocking; the store calls it on its I/O dispatcher.
 */
interface PositionalFile : AutoCloseable {
    /** Reads up to [length] bytes at [position]; returns the count, or -1 at the end of the file. */
    fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    /**
     * Writes all [length] bytes at [position].
     *
     * @throws com.constrivo.drop.core.transfer.StorageFullException when the volume is full.
     * @throws IOException for any other failure.
     */
    fun write(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    )

    /** The current length in bytes. */
    fun length(): Long

    /** Makes every byte written so far durable (`fsync`, N5). @throws IOException when it cannot. */
    fun sync()

    /** Closes the descriptor; idempotent. */
    override fun close()
}

/** How a pending item is stored, which decides how it is published and deleted. */
enum class PendingItemKind(
    val wireName: String,
) {
    /** A MediaStore row inserted with `IS_PENDING = 1`: hidden from other apps until published. */
    MEDIA_STORE("media"),

    /** A document in a user-picked tree (SAF), created under a hidden temporary name and renamed when published. */
    DOCUMENT("document"),
    ;

    companion object {
        fun fromWire(name: String): PendingItemKind? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * A pending item of one received file: its content [uri], how it is stored, and the MediaStore [collection] and
 * [mimeType] it was created with (a MediaStore row cannot move to another collection when it is published, so the
 * publish keeps them; [collection] is null for a document). A document also names the folder it was created in
 * ([parentUri]), where its final name is chosen.
 */
data class PendingItemRef(
    val uri: String,
    val kind: PendingItemKind,
    val collection: MediaCollection?,
    val mimeType: String,
    val parentUri: String? = null,
) {
    init {
        require(uri.startsWith("content://")) { "a pending item is a content URI" }
        require(uri.none { it == '\n' || it == '\r' }) { "a URI has no line breaks" }
        require((kind == PendingItemKind.MEDIA_STORE) == (collection != null)) { "a MediaStore item names its collection" }
        require((kind == PendingItemKind.DOCUMENT) == (parentUri != null)) { "a document names its folder" }
        require(parentUri == null || (parentUri.startsWith("content://") && parentUri.none { it == '\n' || it == '\r' })) {
            "a folder is a content URI"
        }
        require(mimeType.isNotEmpty() && mimeType.none { it == '\n' || it == '\r' || it == '=' }) { "bad MIME type" }
    }
}

/**
 * What the store asks the resolver to create: the file of [fileIndex] of transfer [transferId] at [target] on
 * [destination], inside the drop's [subfolder] when it has one (MediaStore already has it in the target's relative
 * path; a picked folder gets a subfolder of that name).
 */
data class PendingItemSpec(
    val destination: ReceiveDestination,
    val target: MediaTarget,
    val transferId: String,
    val fileIndex: Int,
    val subfolder: String? = null,
)

/** A published item: where it is now ([uri], which a document rename may change) and its final [displayName]. */
data class PublishedItem(
    val uri: String,
    val displayName: String,
)

/**
 * The seam between [MediaStoreFileStore] and the platform's content resolver (MediaStore and the Storage Access
 * Framework on a device, [AndroidPendingItemResolver]; a fake in the JVM tests). Every call is blocking and may throw
 * [IOException]; a full volume is `StorageFullException`.
 */
interface PendingItemResolver {
    /** Creates the hidden item of [spec] and returns it (MediaStore: `IS_PENDING = 1`; SAF: a temporary name). */
    fun create(spec: PendingItemSpec): PendingItemRef

    /**
     * Whether [ref] is still a pending item: not removed (by the user, or MediaStore's 7-day expiry of pending rows) and
     * not published, so a record that outlived its publish never reopens the user's file for writing.
     */
    fun exists(ref: PendingItemRef): Boolean

    /** Opens [ref] for positional reads and writes (`openFileDescriptor(uri, "rw")`). */
    fun open(ref: PendingItemRef): PositionalFile

    /**
     * Makes [ref] visible as [target] (MediaStore: clears `IS_PENDING` with the final name and path; SAF: renames the
     * document), never replacing an existing file: a taken name gets ` (1)`, ` (2)`, … before its extension.
     */
    fun publish(
        ref: PendingItemRef,
        target: MediaTarget,
    ): PublishedItem

    /**
     * Deletes [ref] while it is still pending (MediaStore: `IS_PENDING` set; SAF: still under its temporary name), and
     * never a published file, whatever an out-of-date record says; a no-op when it is gone or published.
     */
    fun delete(ref: PendingItemRef)

    /** Free bytes where [destination] writes; [Long.MAX_VALUE] when the platform cannot tell. */
    fun freeBytes(destination: ReceiveDestination): Long

    /** Whether [destination] is on removable storage (the `sdcard` hint, F-D4, T-23). */
    fun isRemovable(destination: ReceiveDestination): Boolean
}

/** Where one pending item is in its life (N14): created at the first write of its file, published or deleted at the end. */
enum class PendingState {
    /** Not created yet. */
    ABSENT,

    /** Created (or found again after a restart) and open for positional writes. */
    OPEN,

    /** Closed; its bytes stay, and it can be opened again (a resume, a re-verification). */
    CLOSED,

    /** Visible to the user under its final name. Terminal. */
    PUBLISHED,

    /** Removed (verification failed for good, the transfer was cancelled, or its 24 h passed). Terminal. */
    DELETED,
}

/** A step in a pending item's life. */
enum class PendingEvent { OPEN, CLOSE, PUBLISH, DELETE }

/** A step that the pending item's current state does not allow (a write after publishing, a publish of nothing). */
class PendingItemStateException(
    val state: PendingState,
    val event: PendingEvent,
) : IllegalStateException("a pending item in state $state cannot $event")

/**
 * The life cycle of one pending item (spec change N14), as a pure reducer the store applies to every call:
 *
 * ```
 * ABSENT ──OPEN──▶ OPEN ◀──OPEN── CLOSED
 *                   │ ──CLOSE──▶     │
 *                   ├──PUBLISH──▶ PUBLISHED   (from OPEN or CLOSED; the store closes first)
 *                   └──DELETE───▶ DELETED     (from any state but PUBLISHED; ABSENT → DELETED deletes nothing)
 * ```
 *
 * CLOSE is idempotent (the engine closes a partial that is already closed), a published item cannot be written,
 * reopened or deleted through the store (it belongs to the user now), and a deleted one is opened again only as a new
 * item (the store starts over from [PendingState.ABSENT] for it).
 */
object PendingItemLifecycle {
    /** @throws PendingItemStateException when [event] is not allowed in [state]. */
    fun next(
        state: PendingState,
        event: PendingEvent,
    ): PendingState =
        when (event) {
            PendingEvent.OPEN -> {
                when (state) {
                    PendingState.ABSENT, PendingState.CLOSED, PendingState.OPEN -> PendingState.OPEN
                    else -> throw PendingItemStateException(state, event)
                }
            }

            PendingEvent.CLOSE -> {
                when (state) {
                    PendingState.OPEN, PendingState.CLOSED -> PendingState.CLOSED
                    PendingState.PUBLISHED, PendingState.DELETED, PendingState.ABSENT -> state
                }
            }

            PendingEvent.PUBLISH -> {
                when (state) {
                    PendingState.OPEN, PendingState.CLOSED -> PendingState.PUBLISHED
                    else -> throw PendingItemStateException(state, event)
                }
            }

            PendingEvent.DELETE -> {
                when (state) {
                    PendingState.PUBLISHED -> throw PendingItemStateException(state, event)
                    else -> PendingState.DELETED
                }
            }
        }
}
