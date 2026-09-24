package com.constrivo.drop.platform.android.bluetooth.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import com.constrivo.drop.platform.android.bluetooth.BluetoothChannelConfig
import com.constrivo.drop.platform.android.bluetooth.ChannelInfo
import com.constrivo.drop.platform.android.bluetooth.DropBluetoothProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The GATT client side of the handshake channel (architecture §6.1 note): connects to a peer's LE address, finds the
 * drop service, negotiates the MTU, reads [ChannelInfo], and carries the client end of a [GattStreamChannel] (writes
 * without response to the client-to-server characteristic, notifications from the server-to-client one).
 *
 * Android allows one outstanding GATT operation per connection, so every operation waits for its callback under one
 * mutex; a start the stack refuses as busy is retried after a short pause, and a write-without-response the stack
 * reports as not sent is sent again (the segment's sequence number makes a duplicate harmless). Callbacks arrive on
 * [handler]'s thread. Needs `BLUETOOTH_CONNECT`; a SecurityException becomes an [IOException]. Bonding is never
 * requested: nothing in the drop service needs encryption.
 */
@SuppressLint("MissingPermission")
internal class GattClientLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val handler: Handler,
    private val config: BluetoothChannelConfig,
) : GattSegmentTransport {
    private enum class Op { DISCOVER, MTU, READ, WRITE_DESCRIPTOR, WRITE }

    private class Pending(
        val op: Op,
        val result: CompletableDeferred<Int> = CompletableDeferred(),
    ) {
        @Volatile var value: ByteArray? = null
    }

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var pending: Pending? = null

    @Volatile private var mtu = DropBluetoothProfile.DEFAULT_ATT_MTU

    @Volatile private var clientToServer: BluetoothGattCharacteristic? = null

    @Volatile private var serverToClient: BluetoothGattCharacteristic? = null

    @Volatile private var channelInfo: BluetoothGattCharacteristic? = null

    private val opLock = Mutex()
    private val connected = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)

    /** Receives every notification of the server-to-client characteristic (the stream's segments). */
    @Volatile var onSegment: ((ByteArray) -> Unit)? = null

    /** Told once when the link goes down without [disconnect]. */
    @Volatile var onLost: ((IOException) -> Unit)? = null

    val address: String get() = device.address

    override val maxSegmentSize: Int get() = DropBluetoothProfile.segmentSize(mtu)

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected.complete(Unit)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    linkLost(IOException("GATT connection to ${device.address} ended (status $status)"))
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                complete(Op.DISCOVER, status, null)
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) this@GattClientLink.mtu = mtu
                complete(Op.MTU, status, null)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                complete(Op.READ, status, value.copyOf())
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                // Only Android 12L and older call this form.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) complete(Op.READ, status, characteristic.value?.copyOf())
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                complete(Op.WRITE_DESCRIPTOR, status, null)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                complete(Op.WRITE, status, null)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == DropBluetoothProfile.SERVER_TO_CLIENT_UUID) onSegment?.invoke(value.copyOf())
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                if (characteristic.uuid != DropBluetoothProfile.SERVER_TO_CLIENT_UUID) return
                characteristic.value?.copyOf()?.let { onSegment?.invoke(it) }
            }
        }

    private fun complete(
        op: Op,
        status: Int,
        value: ByteArray?,
    ) {
        val p = pending ?: return
        if (p.op != op) return
        p.value = value
        p.result.complete(status)
    }

    private fun linkLost(error: IOException) {
        connected.completeExceptionally(error)
        pending?.result?.completeExceptionally(error)
        if (closed.compareAndSet(false, true)) {
            try {
                gatt?.close()
            } catch (e: RuntimeException) {
                // Releasing the client interface is best effort.
            }
            onLost?.invoke(error)
        }
    }

    /**
     * Connects over LE within [BluetoothChannelConfig.gattConnectTimeoutMillis]. The `Handler` form of `connectGatt` is
     * deprecated from API 36 in favour of connection settings with an executor, which API 31–35 lack.
     */
    @Suppress("DEPRECATION")
    suspend fun connect() {
        val g =
            try {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
            } catch (e: SecurityException) {
                throw IOException("BLUETOOTH_CONNECT is not granted", e)
            } ?: throw IOException("connectGatt returned no connection")
        gatt = g
        withTimeoutOrNull(config.gattConnectTimeoutMillis) { connected.await() }
            ?: throw IOException("GATT connection to ${device.address} timed out")
    }

    /** Discovers services and finds the drop service's three characteristics. */
    suspend fun discover() {
        val status = operation(Op.DISCOVER) { if (it.discoverServices()) ISSUED else BUSY }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("service discovery failed (status $status)")
        val service =
            gatt?.getService(DropBluetoothProfile.SERVICE_UUID)
                ?: throw IOException("${device.address} has no drop GATT service")
        channelInfo =
            service.getCharacteristic(DropBluetoothProfile.CHANNEL_INFO_UUID) ?: throw IOException("no channel-info characteristic")
        clientToServer =
            service.getCharacteristic(DropBluetoothProfile.CLIENT_TO_SERVER_UUID) ?: throw IOException("no client-to-server characteristic")
        serverToClient =
            service.getCharacteristic(DropBluetoothProfile.SERVER_TO_CLIENT_UUID) ?: throw IOException("no server-to-client characteristic")
    }

    /**
     * Asks for the largest MTU and a fast connection interval (and 2M PHY when [le2m]); a refusal keeps the defaults,
     * since the stream adapts to any MTU.
     */
    suspend fun tune(le2m: Boolean) {
        runCatching { operation(Op.MTU) { if (it.requestMtu(DropBluetoothProfile.PREFERRED_ATT_MTU)) ISSUED else BUSY } }
        try {
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            if (le2m) {
                gatt?.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
            }
        } catch (e: SecurityException) {
            throw IOException("BLUETOOTH_CONNECT is not granted", e)
        }
    }

    /** Reads the peer's [ChannelInfo] (its L2CAP PSM). */
    suspend fun readChannelInfo(): ChannelInfo {
        val characteristic = channelInfo ?: throw IOException("discover() first")
        var value: ByteArray? = null
        val status =
            operation(Op.READ, onResult = { value = it }) { if (it.readCharacteristic(characteristic)) ISSUED else BUSY }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("reading channel info failed (status $status)")
        return ChannelInfo.decode(value ?: ByteArray(0))
    }

    /** Subscribes to the server-to-client characteristic (local registration plus the CCCD write). */
    suspend fun enableNotifications() {
        val characteristic = serverToClient ?: throw IOException("discover() first")
        val descriptor =
            characteristic.getDescriptor(DropBluetoothProfile.CCCD_UUID)
                ?: throw IOException("no CCCD on the server-to-client characteristic")
        val g = gatt ?: throw IOException("not connected")
        val registered =
            try {
                g.setCharacteristicNotification(characteristic, true)
            } catch (e: SecurityException) {
                throw IOException("BLUETOOTH_CONNECT is not granted", e)
            }
        if (!registered) throw IOException("could not register for notifications")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val status =
            operation(Op.WRITE_DESCRIPTOR) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    issued(it.writeDescriptor(descriptor, value))
                } else {
                    legacyWriteDescriptor(it, descriptor, value)
                }
            }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("enabling notifications failed (status $status)")
    }

    override suspend fun send(segment: ByteArray) {
        val characteristic = clientToServer ?: throw IOException("discover() first")
        var attempts = 0
        while (true) {
            val status =
                operation(Op.WRITE) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        issued(it.writeCharacteristic(characteristic, segment, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE))
                    } else {
                        legacyWriteWithoutResponse(it, characteristic, segment)
                    }
                }
            if (status == BluetoothGatt.GATT_SUCCESS) return
            // Not sent (congestion): send the same segment again; its sequence number drops a duplicate.
            if (++attempts >= config.busyRetries) throw IOException("GATT write failed (status $status)")
            delay(config.busyRetryMillis)
        }
    }

    /** Disconnects and releases the client interface. Idempotent. */
    override fun disconnect() {
        if (!closed.compareAndSet(false, true)) return
        val g = gatt
        try {
            g?.disconnect()
            g?.close()
        } catch (e: RuntimeException) {
            // SecurityException or a dead stack: the interface is gone either way.
        }
        val error = IOException("GATT link closed")
        connected.completeExceptionally(error)
        pending?.result?.completeExceptionally(error)
    }

    /**
     * Runs one GATT operation: [start] issues it ([ISSUED], [BUSY] to retry, or a refusal code), then the matching
     * callback's status is returned. Serialised; bounded by [BluetoothChannelConfig.gattOperationTimeoutMillis].
     */
    private suspend fun operation(
        op: Op,
        onResult: (ByteArray?) -> Unit = {},
        start: (BluetoothGatt) -> Int,
    ): Int =
        opLock.withLock {
            val g = gatt ?: throw IOException("GATT is not connected")
            if (closed.get()) throw IOException("GATT link closed")
            val p = Pending(op)
            pending = p
            try {
                var attempts = 0
                while (true) {
                    val issued =
                        try {
                            start(g)
                        } catch (e: SecurityException) {
                            throw IOException("BLUETOOTH_CONNECT is not granted", e)
                        }
                    if (issued == ISSUED) break
                    if (issued == BUSY && ++attempts < config.busyRetries) {
                        delay(config.busyRetryMillis)
                        continue
                    }
                    throw IOException("GATT $op was refused (code $issued)")
                }
                val status =
                    withTimeoutOrNull(config.gattOperationTimeoutMillis) { p.result.await() }
                        ?: throw IOException("GATT $op timed out")
                onResult(p.value)
                status
            } finally {
                pending = null
            }
        }

    /** Android 12 and 12L: the value travels on the shared descriptor object (safe: operations are serialised). */
    @Suppress("DEPRECATION")
    private fun legacyWriteDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Int {
        descriptor.value = value
        return if (gatt.writeDescriptor(descriptor)) ISSUED else BUSY
    }

    /** Android 12 and 12L: the value travels on the shared characteristic object (safe: operations are serialised). */
    @Suppress("DEPRECATION")
    private fun legacyWriteWithoutResponse(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Int {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = value
        return if (gatt.writeCharacteristic(characteristic)) ISSUED else BUSY
    }

    private companion object {
        const val ISSUED = 0
        const val BUSY = -1

        /** Maps an Android 13+ status code: success, busy (retry) or a refusal. */
        @SuppressLint("InlinedApi")
        fun issued(code: Int): Int =
            when (code) {
                BluetoothStatusCodes.SUCCESS -> ISSUED

                BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> BUSY

                // Any other code is a refusal; keep it distinct from the two markers.
                BUSY -> Int.MIN_VALUE

                else -> code
            }
    }
}
