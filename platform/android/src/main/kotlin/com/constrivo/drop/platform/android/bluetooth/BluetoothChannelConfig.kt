package com.constrivo.drop.platform.android.bluetooth

import com.constrivo.drop.platform.android.bluetooth.gatt.GattStreamConfig
import java.io.IOException

/**
 * Timeouts and choices of the Bluetooth handshake channel (architecture §6.1 note, §7.8). Every value is a starting
 * point for the lab: the §15 budget is a 400 ms handshake, which only the first attempt of each connection path can
 * meet, so the timeouts bound how long a failing path delays the next one.
 *
 * @property preferL2cap connect an LE L2CAP channel when the peer publishes a PSM; false forces the GATT stream (for the
 *   lab, or a device pair known to fail L2CAP).
 * @property rfcomm open RFCOMM toward peers that advertise a Classic address (desktops, S10).
 * @property listenRfcomm also accept RFCOMM connections. Off by default: a desktop cannot learn a phone's Classic
 *   address (phones never advertise one), so nothing would connect; kept for paired-desktop experiments in the lab.
 * @property maxLeAttempts LE addresses of one peer tried in turn (`NearbyDevice.radioAddresses`, newest first).
 * @property maxServerSessions GATT stream sessions the server accepts at once; more are refused.
 * @property incomingQueue accepted channels waiting for the owner; when full, new ones are closed at once.
 */
data class BluetoothChannelConfig(
    val preferL2cap: Boolean = true,
    val rfcomm: Boolean = true,
    val listenRfcomm: Boolean = false,
    val gattConnectTimeoutMillis: Long = 8_000,
    val gattOperationTimeoutMillis: Long = 5_000,
    val l2capConnectTimeoutMillis: Long = 4_000,
    val rfcommConnectTimeoutMillis: Long = 8_000,
    val maxLeAttempts: Int = 3,
    val maxServerSessions: Int = 4,
    val incomingQueue: Int = 4,
    val busyRetryMillis: Long = 10,
    val busyRetries: Int = 200,
    val listenRetryMillis: Long = 2_000,
    val gatt: GattStreamConfig = GattStreamConfig(),
) {
    init {
        require(gattConnectTimeoutMillis > 0 && gattOperationTimeoutMillis > 0) { "GATT timeouts must be positive" }
        require(l2capConnectTimeoutMillis > 0 && rfcommConnectTimeoutMillis > 0) { "socket timeouts must be positive" }
        require(maxLeAttempts >= 1 && maxServerSessions >= 1 && incomingQueue >= 1) { "limits must be positive" }
        require(busyRetryMillis > 0 && busyRetries >= 1 && listenRetryMillis > 0) { "retries must be positive" }
    }
}

/** No Bluetooth channel to the peer could be opened; [attempts] holds what each path reported, in order. */
class BluetoothChannelException(
    message: String,
    val attempts: List<Throwable> = emptyList(),
) : IOException(message, attempts.lastOrNull()) {
    init {
        attempts.forEach { if (it !== cause) addSuppressed(it) }
    }
}
