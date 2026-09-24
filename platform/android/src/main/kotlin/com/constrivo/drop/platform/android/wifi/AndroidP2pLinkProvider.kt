package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.ladder.ActiveLink
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.JoinRequest
import com.constrivo.drop.core.ladder.LinkCredentialsException
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.ladder.P2pCredentials
import com.constrivo.drop.core.ladder.WifiLinkProvider
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.WifiCredentials
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * The Wi-Fi Direct rung on Android (F-E2, F-F5; architecture §4, §8, §9; spec changes S5, N7, N8, N9): the
 * [WifiLinkProvider] of [LinkKind.P2P], for [LinkMode.P2P] (phone to phone) and [LinkMode.P2P_LEGACY] (another device
 * joins this phone's group as a WPA2 client, or this phone, lacking Wi-Fi Direct, joins another phone's group).
 *
 * **Host** (both modes): after the permission check (`NEARBY_WIFI_DEVICES` on 13+, `ACCESS_FINE_LOCATION` on 12;
 * [WifiLinkError.PERMISSION_MISSING] before any call) and a check that Wi-Fi Direct is on, a group this app left behind
 * (a killed process) is removed, and `createGroup` runs with the ladder's credentials ([P2pConfigMapping.forHost]:
 * `DIRECT-xy-…` name, passphrase, `GROUP_OWNER_BAND_5GHZ` when both devices support it, persistent for trusted pairs).
 * Once the group is formed (the connection broadcast, confirmed by `requestConnectionInfo` and `requestGroupInfo`),
 * the link listens on the group owner's address with an ephemeral port, reports `WifiP2pGroup.getFrequency()` for the
 * 5 GHz check (§9), and a group on 5 GHz records capability bit 4 through [hostRecorder]. A re-form (attempt 1) is a
 * new formation after the ladder tore the first group down.
 *
 * **Join** ([LinkMode.P2P]): `connect(config)` with the owner's credentials ([P2pConfigMapping.forClient]), so no
 * discovery and no invitation dialog; tried again while the owner is not up yet (a busy framework, no group within
 * [P2pTimeouts.joinAttemptMillis]) until the ladder's deadline cancels it. Sockets bind to the client's DHCP address on
 * the group interface and dial only the group owner. **Legacy join** ([LinkMode.P2P_LEGACY], a phone without Wi-Fi
 * Direct): the group's SSID and WPA2 passphrase through [legacyJoiner]'s `WifiNetworkSpecifier`.
 *
 * **Cancellation and teardown.** Cancelled or failed before the link reaches `onUp`, whatever was started is undone
 * before the cancellation completes: a connect is cancelled, a group that may have formed is removed. Only groups this
 * provider asked for are removed, never another app's. After `onUp` the ladder owns the link; its teardown removes the
 * group and waits until it is gone. While a link is up, a group that disappears is reported to
 * [WifiLinkListener.onLinkLost] and a channel change to [WifiLinkListener.onFrequencyChanged].
 *
 * One Wi-Fi Direct group exists per device: while a link of this provider is up, another host or join fails with
 * [WifiLinkError.BUSY]. Setups are serialised. Thread-safe. [listenerFactory] binds hosted links' listeners (a seam for
 * JVM tests, which bind loopback).
 */
class AndroidP2pLinkProvider(
    private val radio: P2pRadio,
    private val permissions: WifiPermissionContext,
    private val clock: MonotonicClock,
    private val legacyJoiner: SpecifierJoiner? = null,
    private val interfaces: InterfaceLookup = SystemInterfaceLookup,
    private val hostRecorder: FiveGhzHostRecorder? = null,
    private val listener: WifiLinkListener = WifiLinkListener.NONE,
    private val timeouts: P2pTimeouts = P2pTimeouts(),
    private val legacyJoinTimeoutMillis: Long = DEFAULT_LEGACY_JOIN_TIMEOUT_MILLIS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = TcpDataChannel.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val listenerFactory: LinkListenerFactory = LinkListenerFactory.DEFAULT,
) : WifiLinkProvider {
    init {
        require(legacyJoinTimeoutMillis > 0) { "legacy join timeout must be positive" }
    }

    override val kind: LinkKind = LinkKind.P2P

    private val ops = P2pOperations(radio, timeouts)
    private val setup = Mutex()

    @Volatile private var active: SocketActiveLink? = null

    /** The link this provider has up now, if any. */
    val activeLink: ActiveLink? get() = active?.takeIf { !it.isTornDown }

    override fun supports(
        mode: LinkMode,
        role: LinkRole,
    ): Boolean =
        when (mode) {
            LinkMode.P2P -> radio.isAvailable
            LinkMode.P2P_LEGACY -> if (role == LinkRole.HOST) radio.isAvailable else legacyJoiner != null
            else -> false
        }

    /**
     * Hosts a group for [request] (see the class comment).
     *
     * @throws WifiLinkException typed by what failed; [LinkCredentialsException] for credentials that break the rules.
     */
    override suspend fun host(
        request: HostRequest,
        onUp: (ActiveLink) -> Unit,
    ): ActiveLink {
        requireSupported(request.mode, LinkRole.HOST)
        return logged(request.mode, LinkRole.HOST) { setup.withLock { hostGroup(request, onUp) } }
    }

    /**
     * Joins the peer's group for [request] (see the class comment).
     *
     * @throws WifiLinkException typed by what failed; [LinkCredentialsException] for credentials that break the rules.
     */
    override suspend fun join(
        request: JoinRequest,
        onUp: (ActiveLink) -> Unit,
    ): ActiveLink {
        requireSupported(request.mode, LinkRole.JOIN)
        return logged(request.mode, LinkRole.JOIN) {
            if (request.mode == LinkMode.P2P_LEGACY) joinLegacy(request, onUp) else setup.withLock { joinGroup(request, onUp) }
        }
    }

    // ---- Host ----

    private suspend fun hostGroup(
        request: HostRequest,
        onUp: (ActiveLink) -> Unit,
    ): SocketActiveLink {
        val spec = P2pConfigMapping.forHost(request)
        permissions.require(WifiOperation.WIFI_DIRECT)
        ensureIdle()
        ensureEnabled()
        val startedAt = clock.elapsedMillis()
        clearStaleGroup()
        var groupMayExist = false
        var built: SocketActiveLink? = null
        var handedOver = false
        try {
            groupMayExist = true
            val created = ops.action("createGroup") { createGroup(spec, it) }
            if (created is P2pActionResult.Failure) {
                groupMayExist = false
                throw WifiLinkException(
                    P2pFailureCodes.error(created.reason),
                    "createGroup failed: ${P2pFailureCodes.describe(created.reason)}",
                    platformCode = created.reason,
                )
            }
            val formed = ops.awaitFormed(asOwner = true, timeouts.formationMillis)
            val upSequence = radio.state.value.sequence
            val link = hostedLink(request, spec, formed).also { built = it }
            active = link
            onUp(link)
            handedOver = true
            recordHosted(link.frequencyMhz)
            watchGroup(link, asOwner = true, upSequence)
            listener.onEvent(
                WifiLinkEvent.Up(kind, request.mode, LinkRole.HOST, link.frequencyMhz, clock.elapsedMillis() - startedAt, link.details),
            )
            return link
        } finally {
            if (!handedOver) {
                withContext(NonCancellable) {
                    // A link that was built but never handed over closes its listener and removes the group itself.
                    val link = built
                    if (link != null) {
                        link.teardown()
                    } else if (groupMayExist) {
                        removeOwnGroup()
                    }
                }
            }
        }
    }

    private suspend fun hostedLink(
        request: HostRequest,
        spec: P2pGroupSpec,
        formed: FormedGroup,
    ): SocketActiveLink {
        val group = formed.group
        val snapshot = withContext(io) { interfaces.snapshot() }
        val ownerAddress =
            formed.connection.groupOwnerAddress?.takeIf { IpPrefix.isIpv4(it) }
                ?: LinkInterfaces.find(snapshot, group.interfaceName)?.ipv4?.address
                ?: throw WifiLinkException(WifiLinkError.NO_ADDRESS, "the group owner's address is unknown")
        val iface = LinkInterfaces.find(snapshot, group.interfaceName) ?: LinkInterfaces.containing(snapshot, ownerAddress)
        val frequency = WifiFrequencies.valid(group.frequencyMhz)
        lateinit var link: SocketActiveLink
        link =
            SocketActiveLink(
                kind = kind,
                mode = request.mode,
                role = LinkRole.HOST,
                frequencyMhz = frequency,
                credentials = groupCredentials(group, spec),
                details =
                    LinkDetails.WifiDirectGroup(
                        interfaceName = group.interfaceName ?: iface?.name,
                        groupOwnerAddress = ownerAddress.hostAddress,
                        band = WifiFrequencies.band(frequency),
                        persistent = request.persistent,
                        attempt = request.attempt,
                    ),
                listenAddress = ownerAddress,
                socketFactory = NetworkBoundSocketFactory(localAddress = ownerAddress),
                dialPolicy = LinkDialPolicy(LinkInterfaces.prefixes(iface)),
                release = { releaseGroup(link) },
                io = io,
                connectTimeoutMillis = connectTimeoutMillis,
                listenerFactory = listenerFactory,
            )
        return link
    }

    /**
     * The credentials joiners use: the group's own when it reports valid ones (the truth, should a build rename the
     * group), otherwise the requested ones.
     */
    private fun groupCredentials(
        group: P2pGroupSnapshot,
        spec: P2pGroupSpec,
    ): WifiCredentials {
        val name = group.networkName
        val pass = group.passphrase
        if (name == null || pass == null || !P2pCredentials.isValidNetworkName(name) || !P2pCredentials.isValidPassphrase(pass)) {
            return spec.credentials
        }
        if (name != spec.networkName) listener.onEvent(WifiLinkEvent.CredentialsDiffer(spec.networkName, name))
        return WifiCredentials(name, pass)
    }

    private fun recordHosted(frequencyMhz: Int?) {
        try {
            hostRecorder?.recordHosted(frequencyMhz)
        } catch (_: RuntimeException) {
            // The recorder reports its own failures; the link does not depend on it.
        }
    }

    // ---- Join ----

    private suspend fun joinGroup(
        request: JoinRequest,
        onUp: (ActiveLink) -> Unit,
    ): SocketActiveLink {
        val spec = P2pConfigMapping.forClient(request)
        permissions.require(WifiOperation.WIFI_DIRECT)
        ensureIdle()
        ensureEnabled()
        val startedAt = clock.elapsedMillis()
        clearStaleGroup()
        var connecting = false
        var built: SocketActiveLink? = null
        var handedOver = false
        try {
            var attempt = 0
            var formed: FormedGroup? = null
            while (formed == null) {
                if (clock.elapsedMillis() - startedAt >= timeouts.joinTotalMillis) {
                    throw WifiLinkException(WifiLinkError.TIMEOUT, "no group to join within ${timeouts.joinTotalMillis} ms")
                }
                attempt++
                connecting = true
                val result = ops.action("connect") { connect(spec, it) }
                if (result is P2pActionResult.Failure) {
                    val error = P2pFailureCodes.error(result.reason)
                    if (error == WifiLinkError.UNSUPPORTED || error == WifiLinkError.PERMISSION_MISSING) {
                        throw WifiLinkException(
                            error,
                            "connect failed: ${P2pFailureCodes.describe(result.reason)}",
                            platformCode = result.reason,
                        )
                    }
                    listener.onEvent(
                        WifiLinkEvent.Retry(kind, request.mode, attempt, "connect: ${P2pFailureCodes.describe(result.reason)}"),
                    )
                    delay(timeouts.retryMillis)
                    continue
                }
                formed =
                    try {
                        ops.awaitFormed(asOwner = false, timeouts.joinAttemptMillis)
                    } catch (e: WifiLinkException) {
                        if (e.error != WifiLinkError.TIMEOUT) throw e
                        null
                    }
                if (formed == null) {
                    listener.onEvent(WifiLinkEvent.Retry(kind, request.mode, attempt, "no group within ${timeouts.joinAttemptMillis} ms"))
                    cancelConnectQuietly()
                    delay(timeouts.retryMillis)
                }
            }
            val upSequence = radio.state.value.sequence
            val link = joinedLink(request, formed).also { built = it }
            active = link
            onUp(link)
            handedOver = true
            watchGroup(link, asOwner = false, upSequence)
            listener.onEvent(
                WifiLinkEvent.Up(kind, request.mode, LinkRole.JOIN, link.frequencyMhz, clock.elapsedMillis() - startedAt, link.details),
            )
            return link
        } finally {
            if (!handedOver && connecting) {
                withContext(NonCancellable) {
                    val link = built
                    if (link != null) {
                        link.teardown()
                    } else {
                        cancelConnectQuietly()
                        if (clientGroupFormed()) removeOwnGroup()
                    }
                }
            }
        }
    }

    /** Whether a group this device joined as a client is formed (no group existed before the join started). */
    private suspend fun clientGroupFormed(): Boolean =
        try {
            ops.probeFormed(asOwner = false) != null
        } catch (_: WifiLinkException) {
            // No answer: remove to be safe; the join started from no group at all, so it can only be the joined one.
            true
        }

    private suspend fun joinedLink(
        request: JoinRequest,
        formed: FormedGroup,
    ): SocketActiveLink {
        val group = formed.group
        val owner =
            formed.connection.groupOwnerAddress
                ?: throw WifiLinkException(WifiLinkError.NO_ADDRESS, "the group owner's address is unknown")
        val iface = awaitClientInterface(group.interfaceName, owner)
        val local = iface.ipv4?.address ?: throw WifiLinkException(WifiLinkError.NO_ADDRESS, "the group interface has no IPv4 address")
        val frequency = WifiFrequencies.valid(group.frequencyMhz)
        lateinit var link: SocketActiveLink
        link =
            SocketActiveLink(
                kind = kind,
                mode = request.mode,
                role = LinkRole.JOIN,
                frequencyMhz = frequency,
                credentials = null,
                details =
                    LinkDetails.WifiDirectGroup(
                        interfaceName = iface.name,
                        groupOwnerAddress = owner.hostAddress,
                        band = WifiFrequencies.band(frequency),
                        persistent = request.persistent,
                        attempt = request.attempt,
                    ),
                listenAddress = null,
                socketFactory = NetworkBoundSocketFactory(localAddress = local),
                dialPolicy = LinkDialPolicy(LinkInterfaces.prefixes(iface), expected = owner),
                release = { releaseGroup(link) },
                io = io,
                connectTimeoutMillis = connectTimeoutMillis,
                listenerFactory = listenerFactory,
            )
        return link
    }

    /** The client's group interface once DHCP gave it an IPv4 address (the connection broadcast can come first). */
    private suspend fun awaitClientInterface(
        name: String?,
        owner: InetAddress,
    ): InterfaceSnapshot =
        withTimeoutOrNull(timeouts.addressMillis) {
            var found: InterfaceSnapshot? = null
            while (found == null) {
                val snapshot = withContext(io) { interfaces.snapshot() }
                found = (LinkInterfaces.find(snapshot, name) ?: LinkInterfaces.containing(snapshot, owner))?.takeIf { it.ipv4 != null }
                if (found == null) delay(timeouts.pollMillis)
            }
            found
        } ?: throw WifiLinkException(WifiLinkError.NO_ADDRESS, "the group interface ${name ?: "?"} got no IPv4 address")

    private suspend fun joinLegacy(
        request: JoinRequest,
        onUp: (ActiveLink) -> Unit,
    ): ActiveLink {
        val joiner = legacyJoiner ?: throw WifiLinkException(WifiLinkError.UNSUPPORTED, "no network-specifier joiner for a legacy join")
        val credentials = request.credentials ?: throw LinkCredentialsException("no credentials to join with")
        permissions.require(WifiOperation.NETWORK_SPECIFIER)
        return joiner.join(SpecifierRequestSpec.forGroup(credentials), kind, LinkMode.P2P_LEGACY, legacyJoinTimeoutMillis, onUp)
    }

    // ---- Shared ----

    /** Loss and channel changes of an up link, from broadcasts newer than [upSequence], confirmed by a query. */
    private fun watchGroup(
        link: SocketActiveLink,
        asOwner: Boolean,
        upSequence: Long,
    ) {
        link.watch {
            var frequency = link.frequencyMhz
            radio.state.filter { it.sequence > upSequence }.collect { state ->
                if (state.connection?.groupFormed == false) {
                    val still =
                        try {
                            ops.probeFormed(asOwner) != null
                        } catch (_: WifiLinkException) {
                            true
                        }
                    if (!still && link.markLost()) listener.onLinkLost(kind)
                    return@collect
                }
                val now = WifiFrequencies.valid(state.group?.frequencyMhz)
                if (state.group?.isGroupOwner == asOwner && now != null && now != frequency) {
                    frequency = now
                    listener.onFrequencyChanged(kind, now)
                }
            }
        }
    }

    /** The teardown of an up link: remove the group and wait until it is gone (F-E11: the station was never left). */
    private suspend fun releaseGroup(link: SocketActiveLink) {
        val started = clock.elapsedMillis()
        try {
            removeOwnGroup()
        } finally {
            if (active === link) active = null
        }
        listener.onRestore(
            RestoreReport(kind, link.mode, false, true, clock.elapsedMillis() - started, ProtocolConstants.WIFI_RESTORE_BUDGET_MS),
        )
    }

    /** Removes the group this provider asked for and waits until it is gone; failures are logged, never thrown. */
    private suspend fun removeOwnGroup() {
        try {
            val result = ops.action("removeGroup") { removeGroup(it) }
            val gone = ops.awaitNoGroup(timeouts.removeMillis)
            if (!gone) {
                val reason = (result as? P2pActionResult.Failure)?.let { P2pFailureCodes.describe(it.reason) } ?: "still up"
                listener.onEvent(WifiLinkEvent.ReleaseFailed(kind, "the group did not go away after removeGroup ($reason)"))
            }
        } catch (e: WifiLinkException) {
            listener.onEvent(WifiLinkEvent.ReleaseFailed(kind, e.message))
        }
    }

    private suspend fun cancelConnectQuietly() {
        try {
            ops.action("cancelConnect") { cancelConnect(it) }
        } catch (_: WifiLinkException) {
            // Nothing was connecting, or the framework does not answer; the group check that follows decides.
        }
    }

    /**
     * Removes a group this app left behind (a killed process, a lost teardown) before a new one forms; another app's
     * group is left alone and makes this setup fail with [WifiLinkError.BUSY].
     */
    private suspend fun clearStaleGroup() {
        val group = ops.groupInfo() ?: return
        if (!P2pConfigMapping.isOwnGroupName(group.networkName)) {
            throw WifiLinkException(WifiLinkError.BUSY, "another Wi-Fi Direct group is active (${group.networkName ?: "unnamed"})")
        }
        ops.action("removeGroup") { removeGroup(it) }
        if (!ops.awaitNoGroup(timeouts.removeMillis)) {
            throw WifiLinkException(WifiLinkError.BUSY, "a group left by an earlier run did not go away")
        }
        listener.onEvent(WifiLinkEvent.StaleGroupRemoved(group.networkName))
    }

    private fun ensureIdle() {
        val up = active
        if (up != null && !up.isTornDown) throw WifiLinkException(WifiLinkError.BUSY, "a Wi-Fi Direct link is already up")
    }

    /** Wi-Fi Direct is on: from the last state broadcast, or asked once when none arrived yet. */
    private suspend fun ensureEnabled() {
        val enabled = radio.state.value.enabled ?: ops.enabled()
        if (enabled == false) throw WifiLinkException(WifiLinkError.WIFI_OFF, "Wi-Fi Direct is off")
    }

    private fun requireSupported(
        mode: LinkMode,
        role: LinkRole,
    ) {
        if (!supports(mode, role)) throw WifiLinkException(WifiLinkError.UNSUPPORTED, "cannot ${role.name.lowercase()} $mode here")
    }

    private suspend fun <T> logged(
        mode: LinkMode,
        role: LinkRole,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            listener.onEvent(WifiLinkEvent.SetupFailed(kind, mode, role, null, "cancelled"))
            throw e
        } catch (e: WifiLinkException) {
            listener.onEvent(WifiLinkEvent.SetupFailed(kind, mode, role, e.error, e.message))
            throw e
        } catch (e: Exception) {
            listener.onEvent(WifiLinkEvent.SetupFailed(kind, mode, role, null, e.message ?: e::class.simpleName))
            throw e
        }

    companion object {
        /** A legacy join's own bound; the ladder's 6 s formation deadline normally ends it first. */
        const val DEFAULT_LEGACY_JOIN_TIMEOUT_MILLIS: Long = 15_000
    }
}
