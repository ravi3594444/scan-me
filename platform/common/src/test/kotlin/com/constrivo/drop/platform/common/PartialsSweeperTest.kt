package com.constrivo.drop.platform.common

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.NewTransfer
import com.constrivo.drop.core.data.ResumeDataCleaner
import com.constrivo.drop.core.data.TransferDirection
import com.constrivo.drop.core.data.TransferOutcome
import com.constrivo.drop.core.data.TransferStatus
import com.constrivo.drop.core.data.openJvm
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.DropInfo
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.PartialFile
import com.constrivo.drop.core.transfer.PublishedFile
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.StorageException
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartialsSweeperTest {
    private val crypto = JcaCryptoProvider()
    private var now = 1_000_000_000L
    private val clock = WallClock { now }
    private val data: DropData = DropData.openJvm(null, crypto = crypto, clock = clock)
    private val store = RecordingStore()
    private val sweeper = PartialsSweeper(data, store, clock)

    @AfterTest
    fun close() = data.close()

    private suspend fun receive(id: TransferId) {
        val peer = data.devices.recordPeer(crypto.generateEd25519().publicKey, "Peer", DevicePlatform.PHONE)
        data.transfers.create(NewTransfer(id, peer.id, TransferDirection.RECEIVE, 10, 1))
    }

    @Test
    fun `the sweep deletes the partials of transfers idle for 24 h and keeps the rest`() =
        runBlocking<Unit> {
            val idle = TransferId(ByteArray(16) { 1 })
            val fresh = TransferId(ByteArray(16) { 2 })
            receive(idle)
            now += ResumeDataCleaner.RETENTION_MILLIS + 1
            receive(fresh)
            val report = sweeper.cleaner.runOnce()
            assertEquals(listOf(idle), report.expired)
            assertEquals(listOf(idle.toHex()), store.deleted.toList())
            assertEquals(TransferStatus.CANCELLED, data.transfers.get(idle)?.status)
            assertEquals(TransferStatus.OFFERED, data.transfers.get(fresh)?.status)
        }

    @Test
    fun `deletePartials goes to the platform store by transfer id`() =
        runBlocking<Unit> {
            val id = TransferId(ByteArray(16) { 7 })
            sweeper.deletePartials(id)
            assertEquals(listOf(id.toHex()), store.deleted.toList())
        }

    @Test
    fun `a store that fails keeps the transfer for the next pass`() =
        runBlocking<Unit> {
            val id = TransferId(ByteArray(16) { 3 })
            receive(id)
            data.transfers.finish(id, TransferOutcome(TransferStatus.CANCELLED, 0))
            store.failing = true
            val failed = sweeper.cleaner.clearPartials()
            assertTrue(id in failed.failed.keys, failed.toString())
            store.failing = false
            val retried = sweeper.cleaner.clearPartials()
            assertTrue(id in retried.purged, retried.toString())
        }

    private class RecordingStore : FileStore {
        val deleted: MutableList<String> = Collections.synchronizedList(ArrayList())

        @Volatile
        var failing = false

        override suspend fun openSource(uri: String): SourceFile = throw StorageException("not a source store")

        override suspend fun openPartial(
            transferId: String,
            fileIndex: Int,
            expectedSize: Long,
        ): PartialFile = throw StorageException("no partials here")

        override suspend fun publish(
            partial: PartialFile,
            name: String,
            mimeType: String?,
            drop: DropInfo,
        ): PublishedFile = throw StorageException("no partials here")

        override suspend fun deletePartial(
            transferId: String,
            fileIndex: Int,
        ) = Unit

        override suspend fun deletePartials(transferId: String) {
            if (failing) throw StorageException("disk gone")
            deleted += transferId
        }

        override suspend fun freeBytes(): Long = Long.MAX_VALUE

        override val destinationIsRemovable: Boolean = false
    }
}
