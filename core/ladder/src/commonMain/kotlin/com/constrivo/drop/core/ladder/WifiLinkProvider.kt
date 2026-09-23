package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.WifiCredentials

/** This device's part in a link: it hosts (group owner, hotspot host, LAN listener) or joins. */
enum class LinkRole {
    HOST,
    JOIN,
}

/**
 * What [WifiLinkProvider.host] brings up (architecture §8).
 *
 * @property credentials for a Wi-Fi Direct group, the SSID and passphrase this device generated as group owner (S5,
 *   [P2pCredentials]); null for the hotspot, whose credentials the system picks (N15), and for the LAN.
 * @property requestFiveGhz ask for a 5 GHz group (`setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ)`); ignored by the
 *   hotspot, whose band apps cannot choose (N8).
 * @property persistent a trusted pair: `enablePersistentMode(true)` so the group is remembered (F-F5, N7).
 * @property attempt 0 for the first formation, 1 for the one re-form after a 2.4 GHz result (§9).
 */
data class HostRequest(
    val mode: LinkMode,
    val credentials: WifiCredentials? = null,
    val requestFiveGhz: Boolean = false,
    val persistent: Boolean = false,
    val attempt: Int = 0,
) {
    init {
        require(mode.isWifi) { "Bluetooth is not a Wi-Fi link" }
        require(attempt >= 0) { "attempt must be non-negative" }
        require(mode.kind != LinkKind.P2P || credentials != null) { "the group owner brings the credentials (S5)" }
    }
}

/**
 * What [WifiLinkProvider.join] joins.
 *
 * @property mode [LinkMode.P2P] joins with Wi-Fi Direct (`WifiP2pManager.connect(config)` with the network name,
 *   passphrase and band); [LinkMode.P2P_LEGACY] and [LinkMode.HOTSPOT] join as a WPA2 client (`WifiNetworkSpecifier`,
 *   `WiFiAdapter.ConnectAsync`, CoreWLAN `associate`, NetworkManager `AddAndActivateConnection`); [LinkMode.LAN] binds
 *   to the current network.
 * @property credentials the host's SSID and passphrase; null for the LAN.
 * @property hostAddress and [hostPort] where the host accepts streams, from its `LinkReady`, when known.
 */
data class JoinRequest(
    val mode: LinkMode,
    val credentials: WifiCredentials? = null,
    val requestFiveGhz: Boolean = false,
    val persistent: Boolean = false,
    val hostAddress: String? = null,
    val hostPort: Int? = null,
    val attempt: Int = 0,
) {
    init {
        require(mode.isWifi) { "Bluetooth is not a Wi-Fi link" }
        require(attempt >= 0) { "attempt must be non-negative" }
        require(mode == LinkMode.LAN || credentials != null) { "joining a group or hotspot needs its credentials" }
        require(hostPort == null || hostPort in 1..65535) { "port $hostPort out of range" }
    }
}

/**
 * A Wi-Fi link that is up on this device (architecture §8). The engine opens data streams over it; sockets are bound
 * to the link's network, never mobile data (§7.4).
 */
interface ActiveLink {
    val kind: LinkKind
    val mode: LinkMode
    val role: LinkRole

    /** The measured channel in MHz (`WifiP2pGroup.getFrequency()`, the joined network's frequency), or null if unknown. */
    val frequencyMhz: Int?

    /** Host only: the credentials joiners use (the system-generated ones for a hotspot, N15). Null when joining. */
    val credentials: WifiCredentials?

    /** Where this device accepts streams on the link (the host's listen address and port), announced in `LinkReady`. */
    val localAddress: String?
    val localPort: Int?

    /** Opens a stream to the peer over this link (sockets bound to the link's network, never mobile data). */
    suspend fun connect(
        address: String,
        port: Int,
    ): DataChannel

    /** Accepts the next inbound stream on [port]. */
    suspend fun accept(port: Int): DataChannel

    /**
     * Leaves the group, stops the hotspot, or releases the network request, and gives the previous Wi-Fi back (F-E11:
     * Android releases the `NetworkRequest` and removes the group; Windows reconnects the saved profile; macOS
     * associates back to the previous SSID; Linux activates the previous connection). Returns once that is done or the
     * platform has handed it to the system. Idempotent; must not throw for a link that is already gone.
     */
    suspend fun teardown()
}

/**
 * One rung of the ladder on this platform: Wi-Fi Direct, local-only hotspot or LAN (architecture §4, §8). A provider
 * for [LinkKind.P2P] serves both [LinkMode.P2P] and [LinkMode.P2P_LEGACY]; [supports] says which roles work here
 * (Android: host and join for Wi-Fi Direct and hotspot; Windows, macOS and Linux: join only, N8 and N10).
 *
 * Both calls suspend until the link is up on this device or fails. The ladder cancels them when a candidate times out
 * or loses a race (N9); a provider must then undo whatever it started (remove the half-formed group, release the
 * network request) before the cancellation completes. [join] keeps retrying while the host is not up yet.
 */
interface WifiLinkProvider {
    val kind: LinkKind

    fun supports(
        mode: LinkMode,
        role: LinkRole,
    ): Boolean

    /** Hosts the link (group owner, hotspot host, LAN listener). The result reports the real band. */
    suspend fun host(request: HostRequest): ActiveLink

    /** Joins a link the peer hosts. */
    suspend fun join(request: JoinRequest): ActiveLink
}
