package com.constrivo.drop.platform.android.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.constrivo.drop.core.discovery.BluetoothAddress
import com.constrivo.drop.core.discovery.NearbyDevice
import com.constrivo.drop.platform.android.bluetooth.gatt.GattClientLink
import com.constrivo.drop.platform.android.bluetooth.gatt.GattStreamChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Where to reach a peer over Bluetooth: the LE addresses its beacon was heard from (newest first) and, for a desktop,
 * the Classic address it published (S10). Build it from the radar with [of].
 */
data class BluetoothPeerTarget(
    val leAddresses: List<String>,
    val classicAddress: BluetoothAddress? = null,
) {
    companion object {
        /** The candidates of a radar entry: its Bluetooth addresses, most recently heard first, and its Classic address. */
        fun of(device: NearbyDevice): BluetoothPeerTarget =
            BluetoothPeerTarget(
                leAddresses = device.radioAddresses.sortedByDescending { it.lastSeenElapsedMillis }.map { it.address },
                classicAddress = device.classicAddress?.takeIf { it.isUsable },
            )
    }
}

/** A [BluetoothSocket] as a [StreamSocket]. The streams are taken once, when the socket is connected. */
internal class BluetoothStreamSocket(
    private val socket: BluetoothSocket,
) : StreamSocket {
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream
    override val remoteAddress: String? get() = runCatching { socket.remoteDevice?.address }.getOrNull()

    override fun close() = socket.close()
}

/**
 * Opens the handshake and head-start channel to a peer (architecture §6.1 as changed by S10 and WP7b):
 *
 * 1. **RFCOMM** first when the peer published a usable Classic address (a desktop, S10):
 *    `createInsecureRfcommSocketToServiceRecord` with [DropBluetoothProfile.RFCOMM_SERVICE_UUID].
 * 2. Otherwise, or when that fails, each LE address in turn (at most [BluetoothChannelConfig.maxLeAttempts]): connect
 *    GATT, discover the drop service, negotiate the MTU and read [ChannelInfo]. With a published PSM, an **LE L2CAP**
 *    channel (`createInsecureL2capChannel`) is the primary Android↔Android channel; the GATT client is then released.
 *    Where L2CAP fails or no PSM is published, the **GATT stream** runs over the connection already open.
 *
 * Android apps cannot learn a phone's Classic address, so phone to phone is always LE. Every path is "insecure": no
 * pairing dialog and no bonding; the session handshake (§6) authenticates the peer. The returned channel's
 * [BluetoothDataChannel.transport] says which path won, for the diagnostics log and the lab's throughput table.
 *
 * @param deviceLookup the exact `BluetoothDevice` the scan reported for an address
 *   ([com.constrivo.drop.platform.android.ble.AndroidBeaconRadio.deviceFor]); it carries the random address type an
 *   address string loses. Without it the address is looked up as a random LE address (Android 13+) or plainly.
 */
@SuppressLint("MissingPermission")
class BluetoothChannelConnector(
    context: Context,
    private val deviceLookup: (String) -> BluetoothDevice? = { null },
    private val config: BluetoothChannelConfig = BluetoothChannelConfig(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager: BluetoothManager? = appContext.getSystemService(BluetoothManager::class.java)
    private val thread = HandlerThread("drop-gatt-client").apply { start() }
    private val handler = Handler(thread.looper)

    private val adapter: BluetoothAdapter get() = manager?.adapter ?: throw IOException("this device has no Bluetooth")

    /**
     * Opens a channel to [target], trying every path above in order.
     *
     * @throws BluetoothChannelException when no path worked; its [BluetoothChannelException.attempts] say why.
     */
    suspend fun connect(target: BluetoothPeerTarget): BluetoothDataChannel {
        val attempts = ArrayList<Throwable>()
        val classic = target.classicAddress?.takeIf { it.isUsable }
        if (classic != null && config.rfcomm) {
            try {
                return connectRfcomm(classic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts += e
            }
        }
        for (address in target.leAddresses.distinct().take(config.maxLeAttempts)) {
            try {
                return connectLe(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts += e
            }
        }
        if (attempts.isEmpty()) throw BluetoothChannelException("no Bluetooth address to connect to")
        throw BluetoothChannelException("no Bluetooth channel to the peer (${attempts.size} attempts)", attempts)
    }

    /** RFCOMM toward [address] (a desktop's published Classic address). */
    suspend fun connectRfcomm(address: BluetoothAddress): BluetoothDataChannel {
        val device =
            try {
                adapter.getRemoteDevice(address.toString())
            } catch (e: IllegalArgumentException) {
                throw IOException("invalid Classic address $address", e)
            }
        val socket = createSocket { device.createInsecureRfcommSocketToServiceRecord(DropBluetoothProfile.RFCOMM_SERVICE_UUID) }
        return SocketStreamChannel.connect(
            BluetoothStreamSocket(socket),
            BluetoothTransport.RFCOMM,
            config.rfcommConnectTimeoutMillis,
            io,
        ) {
            socket.connect()
        }
    }

    /** GATT to [address], then L2CAP when the peer publishes a PSM, else the GATT stream over the same connection. */
    suspend fun connectLe(address: String): BluetoothDataChannel {
        val device = leDevice(address)
        val link = GattClientLink(appContext, device, handler, config)
        try {
            link.connect()
            link.discover()
            link.tune(le2m = runCatching { adapter.isLe2MPhySupported }.getOrDefault(false))
            val info = link.readChannelInfo()
            val psm = info.l2capPsm
            if (config.preferL2cap && psm != null) {
                try {
                    val channel = connectL2cap(device, psm)
                    // The L2CAP channel holds the LE link now; the GATT client is no longer needed.
                    link.disconnect()
                    return channel
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Fall back to the GATT stream on the connection that is already up.
                }
            }
            link.enableNotifications()
            val channel = GattStreamChannel.client(link, io, config.gatt, device.address)
            link.onSegment = channel::onSegment
            link.onLost = channel::onTransportClosed
            channel.open()
            return channel
        } catch (e: Throwable) {
            link.disconnect()
            throw e
        }
    }

    private suspend fun connectL2cap(
        device: BluetoothDevice,
        psm: Int,
    ): BluetoothDataChannel {
        val socket = createSocket { device.createInsecureL2capChannel(psm) }
        return SocketStreamChannel.connect(BluetoothStreamSocket(socket), BluetoothTransport.L2CAP, config.l2capConnectTimeoutMillis, io) {
            socket.connect()
        }
    }

    private fun leDevice(address: String): BluetoothDevice {
        deviceLookup(address)?.let { return it }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                adapter.getRemoteLeDevice(address, BluetoothDevice.ADDRESS_TYPE_RANDOM)
            } else {
                adapter.getRemoteDevice(address)
            }
        } catch (e: IllegalArgumentException) {
            throw IOException("invalid LE address $address", e)
        }
    }

    private inline fun createSocket(create: () -> BluetoothSocket): BluetoothSocket =
        try {
            create()
        } catch (e: SecurityException) {
            throw IOException("BLUETOOTH_CONNECT is not granted", e)
        }

    /** Releases the GATT callback thread. Channels already returned stay open. */
    override fun close() {
        thread.quitSafely()
    }
}
