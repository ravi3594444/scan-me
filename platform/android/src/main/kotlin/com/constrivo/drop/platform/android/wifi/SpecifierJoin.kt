package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.ladder.LinkCredentialsException
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.ladder.P2pCredentials
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.WifiCredentials
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** The security a `WifiNetworkSpecifier` asks for: `setWpa2Passphrase` or `setWpa3Passphrase`. */
enum class SpecifierSecurity {
    /** WPA2-PSK: a Wi-Fi Direct group, a WPA2 or a WPA3-SAE transition-mode hotspot. */
    WPA2_PSK,

    /** WPA3-SAE: a hotspot that runs SAE only (some 6 GHz builds). */
    WPA3_SAE,
}

/**
 * A `WifiNetworkSpecifier` request as plain values (architecture §8, hotspot join; N7, N8): the network's [ssid] and
 * [passphrase], the [security] to ask for, and optionally its [bssid]. [toString] never shows the passphrase.
 *
 * @throws LinkCredentialsException from the constructor for an SSID of 0 or more than 32 UTF-8 bytes, a passphrase
 *   that is not 8–63 printable ASCII characters (a specifier cannot carry a raw 64-digit PSK), or a malformed BSSID.
 */
class SpecifierRequestSpec(
    val ssid: String,
    val passphrase: String,
    val security: SpecifierSecurity = SpecifierSecurity.WPA2_PSK,
    val bssid: String? = null,
) {
    init {
        if (ssid.encodeToByteArray().size !in 1..MAX_SSID_BYTES) throw LinkCredentialsException("SSID must be 1-$MAX_SSID_BYTES bytes")
        if (!P2pCredentials.isValidPassphrase(passphrase)) {
            throw LinkCredentialsException("a network specifier needs a passphrase of 8-63 printable ASCII characters")
        }
        if (bssid != null && !isValidBssid(bssid)) throw LinkCredentialsException("malformed BSSID")
    }

    override fun equals(other: Any?): Boolean =
        other is SpecifierRequestSpec && other.ssid == ssid && other.passphrase == passphrase && other.security == security &&
            other.bssid == bssid

    override fun hashCode(): Int = listOf(ssid, passphrase, security, bssid).hashCode()

    override fun toString(): String = "SpecifierRequestSpec(ssid=$ssid, passphrase=<redacted>, security=$security, bssid=$bssid)"

    companion object {
        private const val MAX_SSID_BYTES = 32
        private val BSSID = Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$")

        /** A unicast MAC address in `aa:bb:cc:dd:ee:ff` form (not all zeros, not a group address). */
        fun isValidBssid(text: String): Boolean {
            if (!BSSID.matches(text)) return false
            val first = text.substring(0, 2).toInt(16)
            return (first and 1) == 0 && text.replace(":", "").any { it != '0' }
        }

        /**
         * Joining a phone's Wi-Fi Direct group as a legacy WPA2 client (N8: a phone without Wi-Fi Direct).
         *
         * @throws LinkCredentialsException for credentials that break the Wi-Fi Direct rules.
         */
        fun forGroup(credentials: WifiCredentials): SpecifierRequestSpec {
            val valid = P2pCredentials.requireValidGroup(credentials)
            return SpecifierRequestSpec(valid.ssid, valid.passphrase, SpecifierSecurity.WPA2_PSK)
        }

        /**
         * Joining a local-only hotspot with the system-generated credentials of the host's `LinkReady` (N15). WPA2 joins
         * WPA2 and WPA3-SAE transition hotspots, which is what Android starts on 2.4 and 5 GHz.
         *
         * @throws LinkCredentialsException for credentials no specifier can join.
         */
        fun forHotspot(
            credentials: WifiCredentials,
            security: SpecifierSecurity = SpecifierSecurity.WPA2_PSK,
        ): SpecifierRequestSpec {
            val valid = P2pCredentials.requireValidHotspot(credentials)
            return SpecifierRequestSpec(valid.ssid, valid.passphrase, security)
        }
    }
}

/**
 * What a network request reports (`ConnectivityManager.NetworkCallback`): Android calls `onAvailable`, then
 * `onCapabilitiesChanged` and `onLinkPropertiesChanged`, until `onLost`; `onUnavailable` when the request ends without
 * a network.
 */
sealed interface NetworkEvent {
    /** The network is up; [binder] binds sockets to it. */
    data class Available(
        val networkHandle: Long,
        val binder: SocketBinder,
    ) : NetworkEvent

    /** The network's Wi-Fi channel, from its `WifiInfo` transport info (null when unknown). */
    data class CapabilitiesChanged(
        val networkHandle: Long,
        val frequencyMhz: Int?,
    ) : NetworkEvent

    /** The network's interface and addresses (`LinkProperties`). */
    data class LinkPropertiesChanged(
        val networkHandle: Long,
        val interfaceName: String?,
        val addresses: List<InterfaceAddressInfo>,
    ) : NetworkEvent

    data class Lost(
        val networkHandle: Long,
    ) : NetworkEvent

    /** The request ended without a network: no match, or the user declined the system dialog. */
    data object Unavailable : NetworkEvent
}

/** A filed network request; [release] withdraws it (`unregisterNetworkCallback`), which disconnects the network. */
fun interface NetworkRequestHandle {
    /** Idempotent; never throws. */
    fun release()
}

/**
 * Files `WifiNetworkSpecifier` requests (`ConnectivityManager.requestNetwork`), a seam so [SpecifierJoiner] runs in JVM
 * tests. [AndroidNetworkRequester] is the platform implementation.
 */
fun interface NetworkRequester {
    /**
     * Requests the network of [spec]; [events] receives its callbacks (from any thread) until the handle is released.
     *
     * @throws SecurityException or another runtime exception when the platform refuses the request at once.
     */
    fun request(
        spec: SpecifierRequestSpec,
        events: (NetworkEvent) -> Unit,
    ): NetworkRequestHandle
}

/**
 * The SSIDs this device joined through a `WifiNetworkSpecifier` before: Android remembers the user's approval per app
 * and network, so a join of an SSID seen here needs no system dialog (N7). A heuristic (the user can revoke approvals in
 * the settings); WP7e may persist it next to the pairings.
 */
interface JoinApprovalStore {
    fun isApproved(ssid: String): Boolean

    fun markApproved(ssid: String)
}

/** A [JoinApprovalStore] in memory. Thread-safe. */
class InMemoryJoinApprovalStore : JoinApprovalStore {
    private val approved = ConcurrentHashMap.newKeySet<String>()

    override fun isApproved(ssid: String): Boolean = ssid in approved

    override fun markApproved(ssid: String) {
        approved += ssid
    }
}

/**
 * Joins networks by `WifiNetworkSpecifier` (architecture §8 hotspot join, N7, N8, T-15): the local-only hotspot of
 * another phone ([LinkMode.HOTSPOT]), or a phone's Wi-Fi Direct group as a legacy WPA2 client from a phone without Wi-Fi
 * Direct ([LinkMode.P2P_LEGACY]).
 *
 * - **Foreground.** Android rejects specifier requests from apps that are neither visible nor running a foreground
 *   service; [isForeground] is checked first and a background join fails with [WifiLinkError.BACKGROUND] without
 *   filing anything.
 * - **Approval.** The first join per SSID shows a system dialog. When [approvals] has not seen the SSID,
 *   [WifiLinkListener.onJoinApprovalNeeded] tells the UI before the request is filed; a join that ends in
 *   `onUnavailable` (no match, or the user declined) is [WifiLinkError.JOIN_UNAVAILABLE].
 * - **Up.** The link is up once the network is available and has an address; every socket is bound to that network
 *   ([NetworkBoundSocketFactory] with the network's [SocketBinder] and the address), so no byte drifts to mobile data,
 *   and dialled addresses must lie in the network's own prefixes ([LinkDialPolicy]). The measured frequency is the
 *   joined network's; later changes and the network's loss go to [listener].
 * - **Undo and teardown.** Cancelled or failed before the link is handed over, the request is released at once.
 *   Teardown releases it too (the system then reconnects the previous network) and, when the join had displaced the
 *   station, waits for it ([WifiRestore], F-E11) and reports to [WifiLinkListener.onRestore].
 * - **One at a time.** Android serves one specifier request per app: joins are serialised, and while a joined link is
 *   up another join fails with [WifiLinkError.BUSY] (the Wi-Fi Direct legacy join and the hotspot join share one
 *   joiner).
 */
class SpecifierJoiner(
    private val requester: NetworkRequester,
    private val isForeground: () -> Boolean,
    private val approvals: JoinApprovalStore = InMemoryJoinApprovalStore(),
    private val station: StationMonitor? = null,
    private val listener: WifiLinkListener = WifiLinkListener.NONE,
    private val clock: MonotonicClock,
    private val restore: RestorePolicy = RestorePolicy(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = TcpDataChannel.DEFAULT_CONNECT_TIMEOUT_MILLIS,
) {
    private val setup = Mutex()

    @Volatile private var active: SocketActiveLink? = null

    /** The joined link that is up now, if any. */
    val activeLink: SocketActiveLink? get() = active?.takeIf { !it.isTornDown }

    /**
     * Joins [spec] as a [kind] / [mode] link; suspends until the network is up with an address, then hands the link to
     * [onUp] and returns it (the [com.constrivo.drop.core.ladder.WifiLinkProvider] ownership rules).
     *
     * @throws WifiLinkException [WifiLinkError.BACKGROUND], [WifiLinkError.BUSY], [WifiLinkError.JOIN_UNAVAILABLE],
     *   [WifiLinkError.LOST], [WifiLinkError.TIMEOUT] after [timeoutMillis], or [WifiLinkError.FAILED] /
     *   [WifiLinkError.PERMISSION_MISSING] when the platform refuses the request.
     */
    suspend fun join(
        spec: SpecifierRequestSpec,
        kind: LinkKind,
        mode: LinkMode,
        timeoutMillis: Long,
        onUp: (SocketActiveLink) -> Unit,
    ): SocketActiveLink {
        require(mode.kind == kind && (mode == LinkMode.HOTSPOT || mode == LinkMode.P2P_LEGACY)) {
            "a specifier joins a hotspot or a group, not $mode"
        }
        require(timeoutMillis > 0) { "timeout must be positive" }
        return setup.withLock { joinLocked(spec, kind, mode, timeoutMillis, onUp) }
    }

    private suspend fun joinLocked(
        spec: SpecifierRequestSpec,
        kind: LinkKind,
        mode: LinkMode,
        timeoutMillis: Long,
        onUp: (SocketActiveLink) -> Unit,
    ): SocketActiveLink {
        if (activeLink != null) throw WifiLinkException(WifiLinkError.BUSY, "a network joined by specifier is already up")
        if (!isForeground()) {
            throw WifiLinkException(
                WifiLinkError.BACKGROUND,
                "Android refuses network requests from the background; start the foreground service first",
            )
        }
        val startedAt = clock.elapsedMillis()
        val stationBefore = station?.connected?.value ?: false
        if (!approvals.isApproved(spec.ssid)) listener.onJoinApprovalNeeded(spec.ssid)

        val events = Channel<NetworkEvent>(Channel.UNLIMITED)
        val handle =
            try {
                requester.request(spec) { events.trySend(it) }
            } catch (e: SecurityException) {
                throw WifiLinkException(WifiLinkError.PERMISSION_MISSING, "the network request was refused: ${e.message}", cause = e)
            } catch (e: RuntimeException) {
                throw WifiLinkException(WifiLinkError.FAILED, "the network request was refused: ${e.message}", cause = e)
            }
        var built: SocketActiveLink? = null
        var handedOver = false
        try {
            val joined = withLinkTimeout("the join of ${spec.ssid}", timeoutMillis) { awaitJoined(events) }
            val prefixes = joined.addresses.mapNotNull { it.prefix }
            lateinit var link: SocketActiveLink
            link =
                SocketActiveLink(
                    kind = kind,
                    mode = mode,
                    role = LinkRole.JOIN,
                    frequencyMhz = WifiFrequencies.valid(joined.frequencyMhz),
                    credentials = null,
                    details = LinkDetails.JoinedNetwork(spec.ssid, joined.networkHandle, joined.interfaceName, spec.security),
                    listenAddress = null,
                    socketFactory = NetworkBoundSocketFactory(joined.binder, preferredLocalAddress(joined.addresses)),
                    dialPolicy = LinkDialPolicy(prefixes),
                    release = { release(link, handle, stationBefore) },
                    io = io,
                    connectTimeoutMillis = connectTimeoutMillis,
                )
            built = link
            active = link
            approvals.markApproved(spec.ssid)
            onUp(link)
            handedOver = true
            link.watch { follow(events, link, joined.networkHandle, joined.frequencyMhz) }
            listener.onEvent(
                WifiLinkEvent.Up(kind, mode, LinkRole.JOIN, link.frequencyMhz, clock.elapsedMillis() - startedAt, link.details),
            )
            return link
        } finally {
            if (!handedOver) {
                withContext(NonCancellable) {
                    // A link that was built but never handed over releases the request (and restores) itself.
                    val link = built
                    if (link != null) link.teardown() else handle.release()
                }
                events.close()
            }
        }
    }

    /** What the join waits for: the network, its channel, interface and addresses. */
    private class Joined(
        val networkHandle: Long,
        val binder: SocketBinder,
        val frequencyMhz: Int?,
        val interfaceName: String?,
        val addresses: List<InterfaceAddressInfo>,
    )

    private suspend fun awaitJoined(events: Channel<NetworkEvent>): Joined {
        var available: NetworkEvent.Available? = null
        var frequency: Int? = null
        var interfaceName: String? = null
        var addresses: List<InterfaceAddressInfo> = emptyList()
        for (event in events) {
            when (event) {
                is NetworkEvent.Available -> {
                    available = event
                }

                is NetworkEvent.CapabilitiesChanged -> {
                    if (event.networkHandle == available?.networkHandle) frequency = event.frequencyMhz ?: frequency
                }

                is NetworkEvent.LinkPropertiesChanged -> {
                    if (event.networkHandle == available?.networkHandle) {
                        interfaceName = event.interfaceName
                        addresses = event.addresses.filter { it.prefix != null }
                    }
                }

                is NetworkEvent.Lost -> {
                    if (event.networkHandle == available?.networkHandle) {
                        throw WifiLinkException(WifiLinkError.LOST, "the joined network was lost before it had an address")
                    }
                }

                NetworkEvent.Unavailable -> {
                    throw WifiLinkException(WifiLinkError.JOIN_UNAVAILABLE, "no network matched, or the user declined it")
                }
            }
            val up = available
            if (up != null && addresses.isNotEmpty()) return Joined(up.networkHandle, up.binder, frequency, interfaceName, addresses)
        }
        throw WifiLinkException(WifiLinkError.LOST, "the network request ended")
    }

    /** After the hand-over: channel changes and the loss of the network go to [listener]. */
    private suspend fun follow(
        events: Channel<NetworkEvent>,
        link: SocketActiveLink,
        networkHandle: Long,
        initialFrequency: Int?,
    ) {
        var frequency = WifiFrequencies.valid(initialFrequency)
        for (event in events) {
            when (event) {
                is NetworkEvent.CapabilitiesChanged -> {
                    val now = WifiFrequencies.valid(event.frequencyMhz)
                    if (event.networkHandle == networkHandle && now != null && now != frequency) {
                        frequency = now
                        listener.onFrequencyChanged(link.kind, now)
                    }
                }

                is NetworkEvent.Lost -> {
                    if (event.networkHandle == networkHandle && link.markLost()) listener.onLinkLost(link.kind)
                }

                NetworkEvent.Unavailable -> {
                    if (link.markLost()) listener.onLinkLost(link.kind)
                }

                is NetworkEvent.Available, is NetworkEvent.LinkPropertiesChanged -> {
                    // A DHCP renewal keeps the address; the streams in place keep running either way.
                }
            }
        }
    }

    private suspend fun release(
        link: SocketActiveLink,
        handle: NetworkRequestHandle,
        stationBefore: Boolean,
    ) {
        val started = clock.elapsedMillis()
        try {
            handle.release()
        } finally {
            if (active === link) active = null
        }
        val report = WifiRestore.awaitRestore(link.kind, link.mode, stationBefore, station, clock, restore, started)
        listener.onRestore(report)
    }
}

/**
 * The address a joined network's sockets bind to besides the network itself: its IPv4 address. An IPv6-only network
 * binds to the network alone, since its link-local addresses carry no scope a bind could use.
 */
internal fun preferredLocalAddress(addresses: List<InterfaceAddressInfo>): InetAddress? = addresses.firstOrNull { it.isIpv4 }?.address
