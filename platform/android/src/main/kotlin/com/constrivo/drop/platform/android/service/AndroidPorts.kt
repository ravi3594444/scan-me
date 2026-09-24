package com.constrivo.drop.platform.android.service

import android.content.Context
import androidx.core.net.toUri
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import com.constrivo.drop.core.transfer.net.TcpSocketFactory
import com.constrivo.drop.core.transfer.receive.ResumeStore
import com.constrivo.drop.core.transfer.session.Endpoint
import com.constrivo.drop.platform.android.AndroidClocks
import com.constrivo.drop.platform.android.bluetooth.BluetoothChannelConnector
import com.constrivo.drop.platform.android.bluetooth.BluetoothChannelListener
import com.constrivo.drop.platform.android.bluetooth.BluetoothPeerTarget
import com.constrivo.drop.platform.android.storage.AndroidPendingItemResolver
import com.constrivo.drop.platform.android.storage.ContentSources
import com.constrivo.drop.platform.android.storage.MediaStoreFileStore
import com.constrivo.drop.platform.android.storage.PendingItemIndex
import com.constrivo.drop.platform.android.storage.ReceiveCatalog
import com.constrivo.drop.platform.android.storage.ReceiveDestination
import com.constrivo.drop.platform.android.storage.StorageVolumes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * [BluetoothPaths] over WP7b's channels: [listener] delivers the channels peers open (GATT stream, LE L2CAP, RFCOMM)
 * and [connector] opens one to a radar entry, its LE addresses newest first, RFCOMM toward a desktop's published
 * Classic address ([BluetoothPeerTarget.of]).
 */
class AndroidBluetoothPaths(
    private val listener: BluetoothChannelListener,
    private val connector: BluetoothChannelConnector,
) : BluetoothPaths {
    override val incoming: ReceiveChannel<DataChannel> get() = listener.incoming

    /** @throws com.constrivo.drop.platform.android.bluetooth.BluetoothChannelException when no path worked. */
    override suspend fun connect(device: NearbyDevice): DataChannel = connector.connect(BluetoothPeerTarget.of(device))

    override suspend fun serve() = listener.run()

    override fun refresh() = listener.refresh()
}

/**
 * [LanDialer] over a TCP control connection (a desktop found by mDNS or named by its scanned code, F-H4), with
 * `TCP_NODELAY` on. [factory] creates the socket: WP7d's LAN provider can pass one bound to the Wi-Fi network, so a
 * phone whose default network is mobile data still reaches the desktop.
 */
class TcpLanDialer(
    private val factory: TcpSocketFactory = TcpSocketFactory.DEFAULT,
    private val timeoutMillis: Int = TcpDataChannel.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LanDialer {
    /** @throws IOException when the endpoint does not answer within the timeout. */
    override suspend fun connect(endpoint: Endpoint): DataChannel =
        TcpDataChannel.connect(endpoint.host, endpoint.port, LinkKind.LAN, timeoutMillis, lowLatency = true, factory = factory, io = io)
}

/**
 * [NodeStores] on a device (spec change N14, architecture §10.1 "Storage"): received files go straight into MediaStore
 * (media to the gallery collections, documents to Downloads) or into the folder picked in Settings, as pending items
 * ([MediaStoreFileStore]); sends read `content:` URIs through [ContentSources]. The app-private state of partials (the
 * pending-item records, the drop folder and the resume plan) lives under `filesDir/partials/<transfer id>/`
 * ([PendingItemIndex]), which also serves the 24 h sweep and "Clear partial files" for every destination.
 */
class AndroidNodeStores(
    context: Context,
    private val wallClock: WallClock = AndroidClocks.wall,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val volumes: StorageVolumes = StorageVolumes(context),
    private val resolver: AndroidPendingItemResolver = AndroidPendingItemResolver(context, volumes),
    partialsRoot: Path = File(context.filesDir, PARTIALS_DIRECTORY).toPath(),
) : NodeStores {
    private val appContext = context.applicationContext
    private val contentSources = ContentSources(resolver, io)

    /** The app-private records of every partial, by transfer id. */
    val index: PendingItemIndex = PendingItemIndex(partialsRoot)

    /** The file lists of running receives, which name their MediaStore items. */
    val catalog: ReceiveCatalog = ReceiveCatalog()

    /** Reads sources and, for transfers no receive holds any more, deletes partials wherever they were written. */
    private val common: FileStore = storeFor(ReceiveDestination.MediaStoreVolume())

    override val sources: FileStore get() = common

    override val partials: FileStore get() = common

    /**
     * A store for a new receive at [saveLocation]: MediaStore on the primary volume by default, or the picked folder.
     *
     * @throws IOException when the picked folder can no longer be written (its grant was revoked or it was removed).
     */
    override fun receiveStore(saveLocation: String?): FileStore {
        val destination = ReceiveDestination.fromSetting(saveLocation)
        if (destination is ReceiveDestination.DocumentTree && !canWrite(destination.treeUri)) {
            throw IOException("the picked save location is no longer writable")
        }
        return storeFor(destination)
    }

    override fun planDirectory(transferId: TransferId): Path = index.directoryOf(transferId.toHex())

    override fun recordingResumeStore(delegate: ResumeStore): ResumeStore = catalog.recording(delegate)

    override fun setSender(
        transferId: TransferId,
        name: String?,
    ) = catalog.setSender(transferId, name)

    override fun forget(transferId: TransferId) = catalog.forget(transferId.toHex())

    /**
     * The MediaStore volume name received files go to for [saveLocation], or null for the primary volume, for
     * `AndroidCapabilityDetector.setSaveVolume` (capability bit 10) and the `sdcard` hint (T-23).
     */
    fun saveVolumeName(saveLocation: String?): String? = volumes.mediaStoreVolumeName(ReceiveDestination.fromSetting(saveLocation))

    private fun storeFor(destination: ReceiveDestination): MediaStoreFileStore =
        MediaStoreFileStore(
            destination = destination,
            resolver = resolver,
            index = index,
            catalog = catalog,
            mimeForExtension = AndroidPendingItemResolver::mimeForExtension,
            wallClock = wallClock,
            sources = contentSources,
            io = io,
        )

    /** Whether this app still holds a persisted write grant on [treeUri] (Settings → Save location). */
    private fun canWrite(treeUri: String): Boolean {
        val uri = runCatching { treeUri.toUri() }.getOrNull() ?: return false
        return runCatching { appContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } }
            .getOrDefault(false)
    }

    companion object {
        /** Architecture §10.1: partials' state in `getFilesDir()/partials`. */
        const val PARTIALS_DIRECTORY: String = "partials"
    }
}
