package com.constrivo.drop.platform.desktop.node

import com.constrivo.drop.core.ladder.engine.LadderTransferBridge
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.PartialFile
import com.constrivo.drop.core.transfer.PublishedFile
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.StorageException
import com.constrivo.drop.core.transfer.engine.Transfer
import com.constrivo.drop.core.transfer.engine.TransferProgress
import com.constrivo.drop.core.transfer.session.SecureSession
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import com.constrivo.drop.platform.desktop.files.MarkingFileStore
import com.constrivo.drop.platform.desktop.files.SendFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a send offers, kept so the same transfer can be offered again after an interruption or an app restart (T‑07,
 * S8): the same files in the same order with the same chunk size and bundling give the same `Offer` summary, which the
 * receiver's resume record must match (`ResumeSummary`).
 */
internal class SendSpec(
    val files: List<SendFile>,
    val bundleSmall: Boolean,
    val chunkSize: Int = ProtocolConstants.CHUNK_SIZE,
)

/**
 * One engine run of a transfer over one session: its own scope (cancelled when the attempt is retired, which the engine
 * treats like an app kill: partial files and the resume record stay), the session, the engine's [transfer], the ladder
 * bridge, and the sender's sources. A transfer whose link drops gets a new attempt: the sender offers it again with the
 * same id and the receiver resumes from its record (architecture §10.2 note).
 */
internal class Attempt(
    val scope: CoroutineScope,
    val session: SecureSession,
    val transfer: Transfer,
    val bridge: LadderTransferBridge?,
    val sources: List<SourceFile>,
    /** The peer's address on the primary connection: the only host the LAN rung may join. */
    val peerAddress: InetAddress?,
) {
    /** Completed when the attempt is retired (a newer attempt took over, or the node stops). */
    val retired = CompletableDeferred<Unit>()

    /** Completed once the retirement finished (the scope ended and the handles are closed). */
    val retirementDone = CompletableDeferred<Unit>()

    /** Set by the first caller of the retirement, which performs it; later callers wait for [retirementDone]. */
    val retiring = AtomicBoolean(false)
}

/** How an [Attempt] ended from the node's point of view. */
internal sealed interface AttemptEnd {
    /** The transfer ended ([progress] is its last word). */
    class Final(
        val progress: TransferProgress,
    ) : AttemptEnd

    /** The sender's link dropped after the accept: the transfer is offered again on a new session (S8). */
    data object Interrupted : AttemptEnd

    /** The attempt was retired: a newer attempt of the same transfer took over, or the node stops. */
    data object Retired : AttemptEnd
}

/** The receiver's file store of one transfer, shared by all its attempts so its files land in one per-drop folder. */
internal class ReceiveStore(
    val directory: DirectoryFileStore,
    val marking: MarkingFileStore,
)

/**
 * A [FileStore] whose store is chosen once the `Offer` names its transfer: the store of the running transfer it resumes,
 * or a new one on the current Received folder. The engine needs a store before it reads the `Offer`; it uses it only
 * after. Until then, and when no store can be used, every operation fails like [UnavailableFileStore].
 */
internal class DeferredFileStore : FileStore {
    @Volatile
    var delegate: FileStore = UnavailableFileStore

    override suspend fun openSource(uri: String): SourceFile = delegate.openSource(uri)

    override suspend fun openPartial(
        transferId: String,
        fileIndex: Int,
        expectedSize: Long,
    ): PartialFile = delegate.openPartial(transferId, fileIndex, expectedSize)

    override suspend fun publish(
        partial: PartialFile,
        name: String,
        mimeType: String?,
        drop: DropInfo,
    ): PublishedFile = delegate.publish(partial, name, mimeType, drop)

    override suspend fun deletePartial(
        transferId: String,
        fileIndex: Int,
    ) = delegate.deletePartial(transferId, fileIndex)

    override suspend fun deletePartials(transferId: String) = delegate.deletePartials(transferId)

    override suspend fun freeBytes(): Long = delegate.freeBytes()

    override val destinationIsRemovable: Boolean get() = delegate.destinationIsRemovable
}

/** A store that cannot store anything: an offer that reaches it is declined with `storage` (§7.8). */
internal object UnavailableFileStore : FileStore {
    private fun unavailable(): Nothing = throw StorageException("the Received folder cannot be used")

    override suspend fun openSource(uri: String): SourceFile = unavailable()

    override suspend fun openPartial(
        transferId: String,
        fileIndex: Int,
        expectedSize: Long,
    ): PartialFile = unavailable()

    override suspend fun publish(
        partial: PartialFile,
        name: String,
        mimeType: String?,
        drop: DropInfo,
    ): PublishedFile = unavailable()

    override suspend fun deletePartial(
        transferId: String,
        fileIndex: Int,
    ) = Unit

    override suspend fun deletePartials(transferId: String) = Unit

    override suspend fun freeBytes(): Long = 0

    override val destinationIsRemovable: Boolean = false
}
