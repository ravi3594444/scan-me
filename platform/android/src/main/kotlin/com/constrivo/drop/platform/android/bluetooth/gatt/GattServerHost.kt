package com.constrivo.drop.platform.android.bluetooth.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.constrivo.drop.platform.android.bluetooth.BluetoothChannelConfig
import com.constrivo.drop.platform.android.bluetooth.ChannelInfo
import com.constrivo.drop.platform.android.bluetooth.DropBluetoothProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * The drop GATT server (architecture §6.1 note): publishes [channelInfo] (this device's L2CAP PSM) for reading and
 * carries the server end of one [GattStreamChannel] per connected client. A client's first segment must be an `OPEN`
 * with sequence number 0; it creates the session, which is handed to [onIncoming] once accepted (a `false` answer closes
 * it again). A new `OPEN` from the same device replaces its session; disconnection ends it.
 *
 * Notifications are serialised across all clients and each waits for `onNotificationSent`, so the stack never holds more
 * than one; a busy stack is retried. Characteristics need no encryption, so no client is ever asked to pair. Callbacks
 * run on binder threads and only hand segments over. Needs `BLUETOOTH_CONNECT`.
 */
@SuppressLint("MissingPermission")
internal class GattServerHost(
    private val context: Context,
    private val manager: BluetoothManager,
    private val config: BluetoothChannelConfig,
    private val streamContext: CoroutineContext,
    private val onIncoming: (GattStreamChannel) -> Boolean,
) {
    /** What reads of the channel-info characteristic return; updated when the L2CAP listener (re)opens. */
    @Volatile var channelInfo: ChannelInfo = ChannelInfo(null)

    @Volatile private var server: BluetoothGattServer? = null

    @Volatile private var serverToClient: BluetoothGattCharacteristic? = null

    @Volatile private var serviceAdded: CompletableDeferred<Int>? = null

    private val sessions = ConcurrentHashMap<String, Session>()
    private val mtus = ConcurrentHashMap<String, Int>()
    private val notifyLock = Mutex()

    @Volatile private var pendingNotify: Pair<String, CompletableDeferred<Int>>? = null

    /** Clients with an open stream, for diagnostics. */
    val sessionCount: Int get() = sessions.size

    private inner class Session(
        val device: BluetoothDevice,
    ) : GattSegmentTransport {
        val channel: GattStreamChannel = GattStreamChannel.server(this, streamContext, config.gatt, device.address)

        override val maxSegmentSize: Int
            get() = DropBluetoothProfile.segmentSize(mtus[device.address] ?: DropBluetoothProfile.DEFAULT_ATT_MTU)

        override suspend fun send(segment: ByteArray) = notify(device, segment)

        override fun disconnect() {
            // Only a session still registered owns the connection; a replaced one leaves the link to its successor.
            if (sessions.remove(device.address, this)) {
                try {
                    server?.cancelConnection(device)
                } catch (e: RuntimeException) {
                    // Already disconnected, or BLUETOOTH_CONNECT revoked.
                }
            }
        }
    }

    private val callback =
        object : BluetoothGattServerCallback() {
            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService,
            ) {
                serviceAdded?.complete(status)
            }

            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                if (newState != BluetoothProfile.STATE_DISCONNECTED) return
                val address = device.address
                mtus.remove(address)
                pendingNotify?.let { (target, done) ->
                    if (target == address) done.completeExceptionally(IOException("client $address disconnected"))
                }
                sessions.remove(address)?.channel?.onTransportClosed(IOException("GATT client disconnected (status $status)"))
            }

            override fun onMtuChanged(
                device: BluetoothDevice,
                mtu: Int,
            ) {
                mtus[device.address] = mtu
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid != DropBluetoothProfile.CHANNEL_INFO_UUID) {
                    respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                    return
                }
                val value = channelInfo.encode()
                if (offset < 0 || offset > value.size) {
                    respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                } else {
                    respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val accepted =
                    characteristic.uuid == DropBluetoothProfile.CLIENT_TO_SERVER_UUID && !preparedWrite && offset == 0 && value != null
                if (responseNeeded) {
                    respond(
                        device,
                        requestId,
                        if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        0,
                        null,
                    )
                }
                if (accepted) onClientSegment(device, value.copyOf())
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor,
            ) {
                if (descriptor.uuid == DropBluetoothProfile.CCCD_UUID) {
                    val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value.copyOfRange(offset.coerceIn(0, value.size), value.size))
                } else {
                    respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                // The CCCD subscription itself is tracked by the stack; notifications go only to streams opened with OPEN.
                val ok = descriptor.uuid == DropBluetoothProfile.CCCD_UUID && !preparedWrite && offset == 0
                if (responseNeeded) {
                    respond(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
            }

            override fun onExecuteWrite(
                device: BluetoothDevice,
                requestId: Int,
                execute: Boolean,
            ) {
                // No prepared writes are accepted, so there is nothing to execute.
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            override fun onNotificationSent(
                device: BluetoothDevice,
                status: Int,
            ) {
                pendingNotify?.let { (target, done) -> if (target == device.address) done.complete(status) }
            }
        }

    /**
     * Opens the server and adds the drop service; false when the platform refuses (Bluetooth off, permission missing).
     */
    suspend fun open(): Boolean {
        if (server != null) return true
        val service = BluetoothGattService(DropBluetoothProfile.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                DropBluetoothProfile.CHANNEL_INFO_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                DropBluetoothProfile.CLIENT_TO_SERVER_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        val notify =
            BluetoothGattCharacteristic(
                DropBluetoothProfile.SERVER_TO_CLIENT_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        notify.addDescriptor(
            BluetoothGattDescriptor(
                DropBluetoothProfile.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(notify)
        val opened =
            try {
                manager.openGattServer(context, callback)
            } catch (e: SecurityException) {
                null
            } ?: return false
        server = opened
        serverToClient = notify
        val added = CompletableDeferred<Int>()
        serviceAdded = added
        val issued =
            try {
                opened.addService(service)
            } catch (e: SecurityException) {
                false
            }
        val status = if (issued) withTimeoutOrNull(config.gattOperationTimeoutMillis) { added.await() } else null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            close()
            return false
        }
        return true
    }

    /** Ends every session and closes the server. Idempotent. */
    fun close() {
        val s = server
        server = null
        serverToClient = null
        val closing = sessions.values.toList()
        sessions.clear()
        closing.forEach { it.channel.onTransportClosed(IOException("GATT server closed")) }
        pendingNotify?.second?.completeExceptionally(IOException("GATT server closed"))
        try {
            s?.close()
        } catch (e: RuntimeException) {
            // Closing a server of a dead stack.
        }
    }

    private fun onClientSegment(
        device: BluetoothDevice,
        value: ByteArray,
    ) {
        val address = device.address
        val fresh = isFreshOpen(value)
        val existing = sessions[address]
        if (existing != null && !fresh) {
            existing.channel.onSegment(value)
            return
        }
        // A segment without a session belongs to a stream this server no longer has: the client times out.
        if (!fresh) return
        if (existing == null && sessions.size >= config.maxServerSessions) return
        val session = Session(device)
        sessions[address] = session
        existing?.channel?.onTransportClosed(IOException("client opened a new stream"))
        session.channel.accept(value)
        if (!onIncoming(session.channel)) {
            sessions.remove(address, session)
            session.channel.onTransportClosed(IOException("no room for another incoming channel"))
        }
    }

    private fun isFreshOpen(value: ByteArray): Boolean =
        value.size >= GattSegments.HEADER_SIZE &&
            (value[0].toInt() and 0xFF) == GattSegments.TYPE_OPEN &&
            value[1].toInt() == 0 &&
            value[2].toInt() == 0

    private suspend fun notify(
        device: BluetoothDevice,
        value: ByteArray,
    ) {
        notifyLock.withLock {
            var attempts = 0
            while (true) {
                val s = server ?: throw IOException("GATT server closed")
                val characteristic = serverToClient ?: throw IOException("GATT server closed")
                val done = CompletableDeferred<Int>()
                pendingNotify = device.address to done
                try {
                    val issued =
                        try {
                            startNotify(s, device, characteristic, value)
                        } catch (e: SecurityException) {
                            throw IOException("BLUETOOTH_CONNECT is not granted", e)
                        }
                    val status =
                        when (issued) {
                            ISSUED -> {
                                withTimeoutOrNull(config.gattOperationTimeoutMillis) { done.await() }
                                    ?: throw IOException("notification to ${device.address} timed out")
                            }

                            BUSY -> {
                                null
                            }

                            else -> {
                                throw IOException("notification to ${device.address} refused (code $issued)")
                            }
                        }
                    if (status == BluetoothGatt.GATT_SUCCESS) return
                } finally {
                    pendingNotify = null
                }
                if (++attempts >= config.busyRetries) throw IOException("notification to ${device.address} kept failing")
                delay(config.busyRetryMillis)
            }
        }
    }

    private fun startNotify(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (val code = server.notifyCharacteristicChanged(device, characteristic, false, value)) {
                BluetoothStatusCodes.SUCCESS -> ISSUED
                ERROR_BUSY -> BUSY
                else -> if (code == BUSY) Int.MIN_VALUE else code
            }
        } else {
            legacyNotify(server, device, characteristic, value)
        }

    /** Android 12 and 12L: the value travels on the shared characteristic (safe: notifications are serialised). */
    @Suppress("DEPRECATION")
    private fun legacyNotify(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Int {
        characteristic.value = value
        return if (server.notifyCharacteristicChanged(device, characteristic, false)) ISSUED else BUSY
    }

    private fun respond(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        try {
            server?.sendResponse(device, requestId, status, offset, value)
        } catch (e: RuntimeException) {
            // The client left, or BLUETOOTH_CONNECT was revoked.
        }
    }

    private companion object {
        const val ISSUED = 0
        const val BUSY = -1

        /** `BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY` (Android 13+). */
        const val ERROR_BUSY = 201
    }
}
