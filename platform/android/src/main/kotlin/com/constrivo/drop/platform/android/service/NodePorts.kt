package com.constrivo.drop.platform.android.service

import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.FileStore
import com.constrivo.drop.core.transfer.receive.ResumeStore
import com.constrivo.drop.core.transfer.session.Endpoint
import com.constrivo.drop.web.BrowserApprover
import com.constrivo.drop.web.ReceiveOffer
import kotlinx.coroutines.channels.ReceiveChannel
import java.nio.file.Path

/**
 * The Bluetooth handshake and head-start channel as the node uses it (architecture §6.1 note; WP7b's
 * `BluetoothChannelListener` and `BluetoothChannelConnector` on a device, in-memory pipes in the JVM tests).
 */
interface BluetoothPaths {
    /** Channels peers opened to this device, in arrival order; the node runs the responder handshake on each. */
    val incoming: ReceiveChannel<DataChannel>

    /**
     * Opens a channel to [device] (its LE addresses newest first, RFCOMM toward a desktop that publishes its Classic
     * address).
     *
     * @throws java.io.IOException when no path worked.
     */
    suspend fun connect(device: NearbyDevice): DataChannel

    /** Keeps the servers open until cancelled (the GATT server and the L2CAP listener while Bluetooth is on). */
    suspend fun serve()

    /** Retries whatever server did not open, at once (`BLUETOOTH_CONNECT` was just granted). */
    fun refresh()
}

/** Opens a TCP control connection to a LAN endpoint (a desktop from mDNS or a scanned code, F-H4). */
fun interface LanDialer {
    /** @throws java.io.IOException when the endpoint does not answer. */
    suspend fun connect(endpoint: Endpoint): DataChannel
}

/**
 * Where received files go and sent files come from (spec change N14): on a device, `MediaStoreFileStore`s over
 * MediaStore or the picked folder; in tests, `DirectoryFileStore`s.
 */
interface NodeStores {
    /** Reads the files of a send (`FileStore.openSource` of `content:` URIs, or paths in tests). */
    val sources: FileStore

    /**
     * Deletes the partials of any transfer, whatever destination it was written to (the 24 h sweep, "Clear partial
     * files", History deletes, a cancel after an app restart).
     */
    val partials: FileStore

    /**
     * A store for a new receive at [saveLocation] (`SettingKeys.SAVE_LOCATION`: null for the default).
     *
     * @throws java.io.IOException when that destination cannot be used (a picked folder whose grant is gone).
     */
    fun receiveStore(saveLocation: String?): FileStore

    /** The app-private directory of [transferId]'s partial state, where its resume plan lives (§7.6). */
    fun planDirectory(transferId: TransferId): Path

    /**
     * Records [files]' names for the stores before any byte arrives (MediaStore needs a name to create an item);
     * a no-op for stores that name files at publish.
     */
    fun recordingResumeStore(delegate: ResumeStore): ResumeStore = delegate

    /** Tells the stores who sends [transferId] (the drop subfolder's name). */
    fun setSender(
        transferId: TransferId,
        name: String?,
    ) = Unit

    /** [transferId] ended here: what the stores kept in memory about it goes (its files and records on disk stay). */
    fun forget(transferId: TransferId) = Unit
}

/**
 * The browser receive path's host (F-D6, architecture §10.3 with N15): brings up the link a computer joins by hand
 * (the phone's Wi-Fi Direct group as a legacy WPA2 network, else its local-only hotspot, through WP7c/d's
 * `WifiLinkProvider`s), serves [offer] there with web-receive's server and mDNS responder under a `MulticastLock`, and
 * tears everything down when the page goes idle or the caller is cancelled.
 */
fun interface BrowserHost {
    /**
     * Serves [offer] until the server stops (60 s after the last download) or the caller is cancelled; [onReady] gets
     * the page's addresses and the network credentials once they exist.
     *
     * @throws Exception when no link could be hosted or the server could not start.
     */
    suspend fun serve(
        offer: ReceiveOffer,
        approver: BrowserApprover,
        onReady: (BrowserShareStatus.Ready) -> Unit,
    )
}
