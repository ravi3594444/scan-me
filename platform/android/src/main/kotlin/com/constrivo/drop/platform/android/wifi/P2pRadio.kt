package com.constrivo.drop.platform.android.wifi

import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress

/** The answer of a `WifiP2pManager.ActionListener`: the framework accepted the request, or refused it with a reason. */
sealed interface P2pActionResult {
    data object Success : P2pActionResult

    /** [reason] is one of [P2pFailureCodes]. */
    data class Failure(
        val reason: Int,
    ) : P2pActionResult
}

/**
 * A `WifiP2pGroup` as plain values.
 *
 * @property passphrase only the group owner reads it; null on a client.
 * @property frequencyMhz `getFrequency()`, null when the platform reports 0 (unknown).
 * @property interfaceName `getInterface()`, the group's network interface (`p2p-wlan0-0`).
 */
data class P2pGroupSnapshot(
    val networkName: String?,
    val passphrase: String?,
    val frequencyMhz: Int?,
    val interfaceName: String?,
    val isGroupOwner: Boolean,
    val clientCount: Int = 0,
) {
    override fun toString(): String =
        "P2pGroupSnapshot(networkName=$networkName, passphrase=${if (passphrase == null) "null" else "<redacted>"}, " +
            "frequencyMhz=$frequencyMhz, interfaceName=$interfaceName, isGroupOwner=$isGroupOwner, clientCount=$clientCount)"
}

/** A `WifiP2pInfo` as plain values: whether a group is formed, this device's part in it, and the owner's address. */
data class P2pConnectionSnapshot(
    val groupFormed: Boolean,
    val isGroupOwner: Boolean,
    val groupOwnerAddress: InetAddress?,
)

/**
 * What the Wi-Fi Direct broadcasts last said (`WIFI_P2P_STATE_CHANGED_ACTION`, `WIFI_P2P_CONNECTION_CHANGED_ACTION`).
 * [sequence] grows with every broadcast, so waiters can tell a new one from the last.
 *
 * @property enabled whether Wi-Fi Direct is on; null before the first state broadcast.
 */
data class P2pRadioState(
    val enabled: Boolean? = null,
    val connection: P2pConnectionSnapshot? = null,
    val group: P2pGroupSnapshot? = null,
    val sequence: Long = 0,
)

/**
 * The Wi-Fi Direct calls the provider makes (architecture §8), as a seam over `WifiP2pManager` so the provider's logic
 * runs in JVM tests against a fake. [AndroidP2pRadio] is the platform implementation.
 *
 * Every call returns at once and answers through its callback, from any thread, exactly once; a call may also throw
 * `SecurityException` at once when a permission was revoked meanwhile.
 */
interface P2pRadio {
    /** False when the device has no Wi-Fi Direct service at all. */
    val isAvailable: Boolean

    /** The broadcasts' view; [P2pRadioState.sequence] moves on every broadcast. */
    val state: StateFlow<P2pRadioState>

    /** `WifiP2pManager.createGroup(channel, config, listener)`: this device becomes the group owner. */
    fun createGroup(
        spec: P2pGroupSpec,
        done: (P2pActionResult) -> Unit,
    )

    /** `WifiP2pManager.connect(channel, config, listener)` with the owner's credentials: join as a client. */
    fun connect(
        spec: P2pGroupSpec,
        done: (P2pActionResult) -> Unit,
    )

    /** `WifiP2pManager.cancelConnect`. */
    fun cancelConnect(done: (P2pActionResult) -> Unit)

    /** `WifiP2pManager.removeGroup`: leaves (client) or ends (owner) the current group. */
    fun removeGroup(done: (P2pActionResult) -> Unit)

    /** `WifiP2pManager.requestGroupInfo`; null when there is no group. */
    fun requestGroupInfo(done: (P2pGroupSnapshot?) -> Unit)

    /** `WifiP2pManager.requestConnectionInfo`. */
    fun requestConnectionInfo(done: (P2pConnectionSnapshot?) -> Unit)

    /** `WifiP2pManager.requestP2pState`: whether Wi-Fi Direct is on; null when the framework cannot say. */
    fun requestEnabled(done: (Boolean?) -> Unit)
}
