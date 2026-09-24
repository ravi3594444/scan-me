package com.constrivo.drop.platform.android.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import com.constrivo.drop.platform.android.ble.BluetoothPower
import com.constrivo.drop.platform.android.ble.BluetoothPowerMonitor
import com.constrivo.drop.platform.android.bluetooth.gatt.GattServerHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the listener has open, for diagnostics and the lab. */
data class ListenerState(
    val power: BluetoothPower = BluetoothPower.OFF,
    val gattServer: Boolean = false,
    /** Failed attempts to open the GATT server since serving started; it is retried until it opens (0 once open). */
    val gattServerFailures: Int = 0,
    /** The PSM published in [ChannelInfo], or null while no L2CAP channel listens. */
    val l2capPsm: Int? = null,
    val rfcomm: Boolean = false,
    val acceptedChannels: Long = 0,
    val rejectedChannels: Long = 0,
    val lastError: String? = null,
)

/**
 * The one listener service of the handshake channel (architecture §6.1 note, WP7b): while [run] runs and Bluetooth is on
 * it keeps
 *
 * - the drop **GATT server** ([DropBluetoothProfile]) with the GATT stream for clients that cannot use L2CAP,
 * - an **LE L2CAP** server socket (`listenUsingInsecureL2capChannel`), whose PSM it publishes in the channel-info
 *   characteristic, and
 * - optionally an **RFCOMM** server socket ([BluetoothChannelConfig.listenRfcomm]).
 *
 * Every accepted connection is delivered on [incoming] as a [BluetoothDataChannel]; the transfer service (WP7e) runs the
 * responder handshake on it. When [incoming] is full a new channel is closed at once (a GATT stream is refused with a
 * `RESET`). Servers are closed when Bluetooth turns off and reopened when it comes back. Whatever does not open is
 * retried: an accept loop after [BluetoothChannelConfig.listenRetryMillis] (with a new PSM, republished), the GATT server
 * with a back-off up to [BluetoothChannelConfig.listenMaxRetryMillis], since opening it can fail right after Bluetooth
 * turns on or while `BLUETOOTH_CONNECT` is missing, and without it no peer can learn the PSM. [refresh] retries
 * everything at once. Nothing asks for pairing.
 *
 * Needs `BLUETOOTH_CONNECT`; without it the servers stay closed and [state] says why; call [refresh] once it is granted.
 */
@SuppressLint("MissingPermission")
class BluetoothChannelListener(
    context: Context,
    private val config: BluetoothChannelConfig = BluetoothChannelConfig(),
    private val power: BluetoothPowerMonitor = BluetoothPowerMonitor(context),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val manager: BluetoothManager? = appContext.getSystemService(BluetoothManager::class.java)
    private val channels = Channel<BluetoothDataChannel>(config.incomingQueue)
    private val mutableState = MutableStateFlow(ListenerState())
    private val retryNow = MutableStateFlow(0L)

    /** Accepted channels, in arrival order. */
    val incoming: ReceiveChannel<BluetoothDataChannel> get() = channels

    val state: StateFlow<ListenerState> = mutableState.asStateFlow()

    /**
     * Keeps the servers open while Bluetooth is on, until cancelled. Call from the transfer service's scope (one run at a
     * time); cancellation closes every server but leaves channels already delivered open.
     */
    suspend fun run() {
        power.start()
        try {
            power.state.collectLatest { value ->
                mutableState.update { it.copy(power = value) }
                if (value == BluetoothPower.ON) serve()
            }
        } finally {
            power.stop()
        }
    }

    /** Starts [run] in [scope]. */
    fun launchIn(scope: CoroutineScope): Job = scope.launch { run() }

    /**
     * Retries at once every server that is not open (the GATT server, an L2CAP or RFCOMM listener), without touching
     * those that are: call it when `BLUETOOTH_CONNECT` was granted, or anything else changed that may let them open.
     */
    fun refresh() {
        retryNow.update { it + 1 }
    }

    private suspend fun serve() {
        val manager = manager ?: return
        val gatt = GattServerHost(appContext, manager, config, io) { deliver(it) }
        try {
            coroutineScope {
                // The L2CAP PSM is published only through the GATT server, so a server that did not open is retried
                // (right after Bluetooth turns on, or before BLUETOOTH_CONNECT is granted, it may not).
                launch { openGattServer(gatt) }
                launch {
                    acceptLoop(
                        transport = BluetoothTransport.L2CAP,
                        open = { adapterOf(manager).listenUsingInsecureL2capChannel() },
                        onOpen = { socket ->
                            gatt.channelInfo = ChannelInfo(socket.psm.takeIf { it in 1..0xFFFF })
                            mutableState.update { it.copy(l2capPsm = gatt.channelInfo.l2capPsm) }
                        },
                        onClose = {
                            gatt.channelInfo = ChannelInfo(null)
                            mutableState.update { it.copy(l2capPsm = null) }
                        },
                    )
                }
                if (config.listenRfcomm) {
                    launch {
                        acceptLoop(
                            transport = BluetoothTransport.RFCOMM,
                            open = {
                                adapterOf(manager).listenUsingInsecureRfcommWithServiceRecord(
                                    DropBluetoothProfile.RFCOMM_SERVICE_NAME,
                                    DropBluetoothProfile.RFCOMM_SERVICE_UUID,
                                )
                            },
                            onOpen = { mutableState.update { it.copy(rfcomm = true) } },
                            onClose = { mutableState.update { it.copy(rfcomm = false) } },
                        )
                    }
                }
                // The GATT server stays up until serving is cancelled (Bluetooth off, or the owner stops).
                awaitCancellation()
            }
        } finally {
            gatt.close()
            mutableState.update { it.copy(gattServer = false, gattServerFailures = 0) }
        }
    }

    /** Opens [gatt], retrying with back-off until it opens or serving ends. */
    private suspend fun openGattServer(gatt: GattServerHost) {
        retryWithBackoff(config.listenRetryMillis, config.listenMaxRetryMillis, retryNow) { attempt ->
            val opened =
                try {
                    gatt.open()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    // A stack that throws while Bluetooth settles: retried like a refusal.
                    gatt.close()
                    false
                }
            mutableState.update {
                if (opened) {
                    it.copy(gattServer = true, gattServerFailures = 0)
                } else {
                    it.copy(gattServer = false, gattServerFailures = attempt, lastError = "GATT server did not open (attempt $attempt)")
                }
            }
            opened
        }
    }

    private fun adapterOf(manager: BluetoothManager) = manager.adapter ?: throw IOException("no Bluetooth adapter")

    /** Opens a server socket with [open] and accepts connections until cancelled, reopening it after failures. */
    private suspend fun acceptLoop(
        transport: BluetoothTransport,
        open: () -> BluetoothServerSocket,
        onOpen: (BluetoothServerSocket) -> Unit,
        onClose: () -> Unit,
    ) {
        while (currentCoroutineContext().isActive) {
            var seen = retryNow.value
            val server =
                try {
                    open()
                } catch (e: IOException) {
                    fail("$transport listen failed: ${e.message}")
                    null
                } catch (e: SecurityException) {
                    fail("$transport listen needs BLUETOOTH_CONNECT")
                    null
                }
            if (server != null) {
                onOpen(server)
                val blocking = CancellableBlocking(io.asExecutor()) { runCatching { server.close() } }
                try {
                    while (currentCoroutineContext().isActive) {
                        // A connection accepted just as the loop is cancelled is closed, not left holding the link.
                        val socket = blocking.call(discard = { runCatching { it.close() } }) { server.accept() }
                        val channel = SocketStreamChannel(BluetoothStreamSocket(socket), transport, io)
                        deliver(channel)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    fail("$transport accept failed: ${e.message}")
                } finally {
                    onClose()
                    withContext(NonCancellable) { runCatching { server.close() } }
                }
                seen = retryNow.value
            }
            awaitRetry(config.listenRetryMillis, retryNow, seen)
        }
    }

    /** Hands [channel] to the owner; false (and the channel closed) when [incoming] is full. */
    private fun deliver(channel: BluetoothDataChannel): Boolean {
        val accepted = channels.trySend(channel).isSuccess
        if (accepted) {
            mutableState.update { it.copy(acceptedChannels = it.acceptedChannels + 1) }
        } else {
            mutableState.update { it.copy(rejectedChannels = it.rejectedChannels + 1) }
            // The GATT host ends a refused stream itself.
            if (channel is SocketStreamChannel) channel.closeNow()
        }
        return accepted
    }

    private fun fail(message: String) {
        mutableState.update { it.copy(lastError = message) }
    }
}
