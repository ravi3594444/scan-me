package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.ladder.ActiveLink
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.JoinRequest
import com.constrivo.drop.core.ladder.LinkCredentialsException
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.ladder.WifiLinkProvider
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Timeouts of the local-only hotspot (the ladder's 6 s hotspot deadline, §7.8, usually ends a slow step first).
 *
 * @property startMillis `startLocalOnlyHotspot` until `onStarted` or `onFailed`.
 * @property addressMillis the hotspot interface's address after `onStarted`.
 * @property pollMillis how often the interfaces are read while waiting for the address.
 * @property abandonWaitMillis how long a cancelled start waits for a late `onStarted` to close its reservation (a
 *   later one is closed when it arrives).
 * @property joinMillis a hotspot join's own bound.
 */
data class HotspotTimeouts(
    val startMillis: Long = 10_000,
    val addressMillis: Long = 2_000,
    val pollMillis: Long = 100,
    val abandonWaitMillis: Long = 3_000,
    val joinMillis: Long = 15_000,
) {
    init {
        require(
            listOf(startMillis, addressMillis, pollMillis, abandonWaitMillis, joinMillis).all {
                it > 0
            },
        ) { "hotspot timeouts must be positive" }
    }
}

/**
 * The local-only hotspot rung on Android (F-E3, F-E11; architecture §4, §8; spec changes N7, N8, N15): the
 * [WifiLinkProvider] of [LinkKind.HOTSPOT], the last Wi-Fi resort because apps cannot choose its band.
 *
 * **Host.** After the permission check (`NEARBY_WIFI_DEVICES` on 13+; `ACCESS_FINE_LOCATION` and location services on
 * 12; typed [WifiLinkError.PERMISSION_MISSING] / [WifiLinkError.LOCATION_OFF] before any call),
 * `WifiManager.startLocalOnlyHotspot` runs. Its `SoftApConfiguration` gives the real, system-generated SSID,
 * passphrase and security, and on API 36+ the band ([HotspotConfigReading]); they become [ActiveLink.credentials] (the
 * ladder announces them in `LinkReady`) and [hosted] (the UI's "computer without the app" hint). The hotspot's
 * interface, which Android does not name, is the one that gained a private IPv4 address and belongs to no known
 * network ([LinkInterfaces.hotspotInterface]); the link listens there. A start failure is typed by
 * [HotspotFailureCodes] (tethering on: [WifiLinkError.INCOMPATIBLE_MODE]). On a phone on Wi-Fi without STA/AP
 * concurrency ([staApConcurrency] false) the station drops while the hotspot runs: that is allowed by default (it is the
 * last rung, and the station returns on teardown, which waits for it), or refused with
 * [WifiLinkError.INCOMPATIBLE_MODE] when [allowStationDisplacement] is false.
 *
 * **Join.** The host's credentials through [joiner]'s `WifiNetworkSpecifier` (WPA2, which also joins the WPA3
 * transition mode Android uses on 2.4 and 5 GHz); sockets bound to the joined network (T-15).
 *
 * **Cancellation and teardown.** A start cancelled before `onUp` closes the reservation, also one that arrives late.
 * Teardown closes the reservation (the hotspot stops unless another app shares it) or releases the request, and waits
 * for a displaced station ([WifiRestore], F-E11); the report goes to [WifiLinkListener.onRestore]. A hotspot the user
 * stops while it is up is reported to [WifiLinkListener.onLinkLost]. One hotspot per app at a time: a second host while
 * one is up fails with [WifiLinkError.BUSY]. [listenerFactory] binds the hosted link's listener (a seam for JVM tests).
 */
class AndroidHotspotLinkProvider(
    private val radio: HotspotRadio?,
    private val permissions: WifiPermissionContext,
    private val clock: MonotonicClock,
    private val joiner: SpecifierJoiner? = null,
    private val interfaces: InterfaceLookup = SystemInterfaceLookup,
    private val station: StationMonitor? = null,
    private val staApConcurrency: () -> Boolean = { false },
    private val allowStationDisplacement: Boolean = true,
    private val listener: WifiLinkListener = WifiLinkListener.NONE,
    private val timeouts: HotspotTimeouts = HotspotTimeouts(),
    private val restore: RestorePolicy = RestorePolicy(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = TcpDataChannel.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val listenerFactory: LinkListenerFactory = LinkListenerFactory.DEFAULT,
) : WifiLinkProvider {
    override val kind: LinkKind = LinkKind.HOTSPOT

    private val setup = Mutex()
    private val hostedState = MutableStateFlow<HotspotDetails?>(null)

    @Volatile private var active: SocketActiveLink? = null

    /** The hotspot this device hosts now, with its real SSID and passphrase (N15), or null. */
    val hosted: StateFlow<HotspotDetails?> = hostedState.asStateFlow()

    override fun supports(
        mode: LinkMode,
        role: LinkRole,
    ): Boolean = mode == LinkMode.HOTSPOT && if (role == LinkRole.HOST) radio != null else joiner != null

    /**
     * Starts the hotspot (see the class comment).
     *
     * @throws WifiLinkException typed by what failed.
     */
    override suspend fun host(
        request: HostRequest,
        onUp: (ActiveLink) -> Unit,
    ): ActiveLink {
        requireSupported(request.mode, LinkRole.HOST)
        return logged(LinkRole.HOST) { setup.withLock { hostHotspot(onUp) } }
    }

    /**
     * Joins the peer's hotspot with the credentials of its `LinkReady` (see the class comment).
     *
     * @throws WifiLinkException typed by what failed; [LinkCredentialsException] for credentials no specifier can join.
     */
    override suspend fun join(
        request: JoinRequest,
        onUp: (ActiveLink) -> Unit,
    ): ActiveLink {
        requireSupported(request.mode, LinkRole.JOIN)
        return logged(LinkRole.JOIN) {
            val joiner = joiner ?: throw WifiLinkException(WifiLinkError.UNSUPPORTED, "no network-specifier joiner")
            val credentials = request.credentials ?: throw LinkCredentialsException("no hotspot credentials to join with")
            permissions.require(WifiOperation.NETWORK_SPECIFIER)
            joiner.join(SpecifierRequestSpec.forHotspot(credentials), kind, LinkMode.HOTSPOT, timeouts.joinMillis, onUp)
        }
    }

    private suspend fun hostHotspot(onUp: (ActiveLink) -> Unit): SocketActiveLink {
        val radio = radio ?: throw WifiLinkException(WifiLinkError.UNSUPPORTED, "no Wi-Fi service to start a hotspot with")
        permissions.require(WifiOperation.LOCAL_ONLY_HOTSPOT)
        val up = active
        if (up != null && !up.isTornDown) throw WifiLinkException(WifiLinkError.BUSY, "a local-only hotspot is already up")
        val stationBefore = station?.connected?.value ?: false
        val displaces = stationBefore && !staApConcurrency()
        if (displaces && !allowStationDisplacement) {
            throw WifiLinkException(WifiLinkError.INCOMPATIBLE_MODE, "hosting would disconnect this phone's Wi-Fi (no STA/AP concurrency)")
        }
        val startedAt = clock.elapsedMillis()
        val before = withContext(io) { interfaces.snapshot() }
        val start = HotspotStart()
        try {
            radio.start(start)
        } catch (e: SecurityException) {
            throw if (e.message?.contains("location", ignoreCase = true) == true) {
                WifiLinkException(WifiLinkError.LOCATION_OFF, "startLocalOnlyHotspot needs location services: ${e.message}", cause = e)
            } else {
                WifiLinkException(WifiLinkError.PERMISSION_MISSING, "startLocalOnlyHotspot was refused: ${e.message}", cause = e)
            }
        } catch (e: IllegalStateException) {
            throw WifiLinkException(WifiLinkError.BUSY, "startLocalOnlyHotspot: ${e.message}", cause = e)
        } catch (e: RuntimeException) {
            throw WifiLinkException(WifiLinkError.FAILED, "startLocalOnlyHotspot: ${e.message}", cause = e)
        }
        var built: SocketActiveLink? = null
        var handedOver = false
        try {
            val reservation = withLinkTimeout("startLocalOnlyHotspot", timeouts.startMillis) { start.awaitStarted() }
            val details = HotspotConfigReading.details(reservation.config)
            val iface = awaitHotspotInterface(radio, before)
            val address =
                iface.addresses.firstOrNull { it.isIpv4 && IpLiteral.isPrivate(it.address) }?.address
                    ?: throw WifiLinkException(WifiLinkError.NO_ADDRESS, "the hotspot interface ${iface.name} has no private IPv4 address")
            lateinit var link: SocketActiveLink
            link =
                SocketActiveLink(
                    kind = kind,
                    mode = LinkMode.HOTSPOT,
                    role = LinkRole.HOST,
                    frequencyMhz = details.frequencyMhz,
                    credentials = details.credentials,
                    details = LinkDetails.Hotspot(details, iface.name, displaces),
                    listenAddress = address,
                    socketFactory = NetworkBoundSocketFactory(localAddress = address),
                    dialPolicy = LinkDialPolicy(LinkInterfaces.prefixes(iface)),
                    release = { releaseHotspot(link, reservation, details, stationBefore) },
                    io = io,
                    connectTimeoutMillis = connectTimeoutMillis,
                    listenerFactory = listenerFactory,
                )
            built = link
            active = link
            hostedState.value = details
            onUp(link)
            handedOver = true
            link.watch {
                start.stopped.first { it }
                if (link.markLost()) listener.onLinkLost(kind)
            }
            listener.onEvent(
                WifiLinkEvent.Up(kind, LinkMode.HOTSPOT, LinkRole.HOST, link.frequencyMhz, clock.elapsedMillis() - startedAt, link.details),
            )
            return link
        } finally {
            if (!handedOver) {
                withContext(NonCancellable) {
                    // A link that was built but never handed over closes its listener and the reservation itself.
                    val link = built
                    if (link != null) link.teardown() else start.abandon(timeouts.abandonWaitMillis)
                }
            }
        }
    }

    /** The hotspot's interface once it has its address; see [LinkInterfaces.hotspotInterface]. */
    private suspend fun awaitHotspotInterface(
        radio: HotspotRadio,
        before: List<InterfaceSnapshot>,
    ): InterfaceSnapshot =
        withTimeoutOrNull(timeouts.addressMillis) {
            var found: InterfaceSnapshot? = null
            while (found == null) {
                found = withContext(io) { LinkInterfaces.hotspotInterface(before, interfaces.snapshot(), radio.knownNetworkInterfaces()) }
                if (found == null) delay(timeouts.pollMillis)
            }
            found
        } ?: throw WifiLinkException(WifiLinkError.NO_ADDRESS, "the hotspot's interface did not show an address")

    private suspend fun releaseHotspot(
        link: SocketActiveLink,
        reservation: HotspotReservation,
        details: HotspotDetails,
        stationBefore: Boolean,
    ) {
        val started = clock.elapsedMillis()
        try {
            reservation.close()
        } catch (e: RuntimeException) {
            listener.onEvent(WifiLinkEvent.ReleaseFailed(kind, e.message))
        } finally {
            if (active === link) active = null
            hostedState.compareAndSet(details, null)
        }
        listener.onRestore(WifiRestore.awaitRestore(kind, LinkMode.HOTSPOT, stationBefore, station, clock, restore, started))
    }

    private fun requireSupported(
        mode: LinkMode,
        role: LinkRole,
    ) {
        if (!supports(mode, role)) throw WifiLinkException(WifiLinkError.UNSUPPORTED, "cannot ${role.name.lowercase()} $mode here")
    }

    private suspend fun <T> logged(
        role: LinkRole,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            listener.onEvent(WifiLinkEvent.SetupFailed(kind, LinkMode.HOTSPOT, role, null, "cancelled"))
            throw e
        } catch (e: WifiLinkException) {
            listener.onEvent(WifiLinkEvent.SetupFailed(kind, LinkMode.HOTSPOT, role, e.error, e.message))
            throw e
        } catch (e: Exception) {
            listener.onEvent(WifiLinkEvent.SetupFailed(kind, LinkMode.HOTSPOT, role, null, e.message ?: e::class.simpleName))
            throw e
        }

    /**
     * The callback of one start: the reservation or the failure, whether the hotspot stopped, and the undo of a start
     * that was given up.
     */
    private class HotspotStart : HotspotCallback {
        private val result = CompletableDeferred<HotspotReservation>()
        private val abandoned = AtomicBoolean(false)

        /** True once the system stopped the hotspot (after it was up, or before `onStarted`). */
        val stopped = MutableStateFlow(false)

        override fun onStarted(reservation: HotspotReservation) {
            val first = result.complete(reservation)
            if (!first || abandoned.get()) reservation.close()
        }

        override fun onStopped() {
            stopped.value = true
            result.completeExceptionally(WifiLinkException(WifiLinkError.LOST, "the hotspot stopped before it was up"))
        }

        override fun onFailed(reason: Int) {
            result.completeExceptionally(
                WifiLinkException(
                    HotspotFailureCodes.error(reason),
                    "startLocalOnlyHotspot failed: ${HotspotFailureCodes.describe(reason)}",
                    platformCode = reason,
                ),
            )
        }

        suspend fun awaitStarted(): HotspotReservation = result.await()

        /** Closes the reservation of a start that was given up: one that came, or comes within [waitMillis]. */
        suspend fun abandon(waitMillis: Long) {
            abandoned.set(true)
            val reservation =
                withTimeoutOrNull(waitMillis) {
                    try {
                        result.await()
                    } catch (_: WifiLinkException) {
                        null
                    }
                }
            reservation?.close()
        }
    }
}
