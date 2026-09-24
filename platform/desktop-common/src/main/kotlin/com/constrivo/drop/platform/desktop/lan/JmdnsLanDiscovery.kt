package com.constrivo.drop.platform.desktop.lan

import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.core.discovery.LanDiscovery
import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.discovery.LanService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import javax.jmdns.impl.tasks.state.DNSStateTask

/**
 * A [LanDiscovery] that can move to another interface address: the machine joined another network, or its address
 * changed (DHCP), and the LAN path must follow it (architecture §10.2 note, F‑H5 start at login before Wi‑Fi is up).
 */
interface RebindableLanDiscovery : LanDiscovery {
    /** Moves to [address]: the old records are withdrawn, browsing continues there and [announce] announces there. */
    suspend fun rebind(address: InetAddress)
}

/**
 * [LanDiscovery] on JmDNS (architecture §5.4, §8 "LAN discovery" on Windows and Linux; macOS may use it too until its
 * Bonjour helper exists): announces this device's DNS-SD record and browses for others of type
 * [AppIdentity.MDNS_SERVICE_TYPE], on the one interface address [address] ([LanInterfaces.select]).
 *
 * - **Announce.** [announce] registers the instance with its TXT keys ([com.constrivo.drop.core.discovery.MdnsRecord]
 *   builds them); announcing again (the next epoch's instance name, N4, or a changed nickname) first withdraws the
 *   previous registration, so exactly one record is live. [withdraw] sends the goodbye.
 * - **TTL.** Records carry RFC 6762's 120 s for host-bound records instead of JmDNS's default of an hour, so a desktop
 *   that sleeps or loses Wi‑Fi without a goodbye (or whose goodbye at an instance rotation is lost) leaves no ghost
 *   bubble on other radars for long. JmDNS reads the TTL once, from the `net.dns.ttl` system property, when its
 *   constants load: this class sets it first ([RECORD_TTL_SECONDS]; the app's `main` sets it before anything else too).
 * - **Host name.** JmDNS answers A queries for a host name of its own. The machine's host name would be a permanent,
 *   often personal identifier on the network (N4), so a random `drop-xxxxxxxx` name is used, new for every process.
 * - **Browse.** [browse] is a cold flow: each collector adds a listener; found instances are resolved and reported as
 *   [LanEvent.Found] with the first IPv4 address (else the IPv6 address), their port and TXT map; removals as
 *   [LanEvent.Lost]. A browse that fails (JmDNS cannot start on the address) is retried with back-off, reported to
 *   [onError]; the flow itself never fails. Nothing here is validated or authenticated:
 *   [com.constrivo.drop.core.discovery.NearbyDevices] parses the TXT and the handshake checks the identity.
 * - **Rebind.** [rebind] closes JmDNS on the old address and continues on the new one; every browse reports the
 *   records it had found there as lost (that network is gone) and browses again.
 * - **Shutdown.** [close] unregisters everything (goodbye packets) and stops JmDNS, bounded by [closeTimeoutMillis].
 *
 * JmDNS itself starts lazily, on [io], at the first call. All calls are thread-safe.
 *
 * @throws IOException from [announce] and [withdraw] when JmDNS cannot start on the address.
 */
class JmdnsLanDiscovery(
    address: InetAddress,
    val hostName: String = randomHostName(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val closeTimeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : RebindableLanDiscovery,
    AutoCloseable {
    private val mutex = Mutex()
    private val addressState = MutableStateFlow(address)
    private var dns: JmDNS? = null
    private var dnsAddress: InetAddress? = null
    private var registered: ServiceInfo? = null

    private var closed = false

    /** The interface address JmDNS runs on now. */
    val address: InetAddress get() = addressState.value

    init {
        require(!address.isAnyLocalAddress) { "bind JmDNS to the LAN interface address, never the wildcard" }
        require(hostName.isNotBlank() && hostName.length <= MAX_HOST_NAME && hostName.all { it.isLetterOrDigit() || it == '-' }) {
            "host name must be 1–$MAX_HOST_NAME letters, digits or '-'"
        }
        // Should JmDNS's constants have loaded before this class (the property then came too late), the announcer
        // and renewer still use the short TTL.
        DNSStateTask.setDefaultTTL(RECORD_TTL_SECONDS)
    }

    override suspend fun announce(service: LanService) {
        require(service.port in 1..MAX_PORT) { "port ${service.port} out of range" }
        require(service.instanceName.isNotBlank()) { "instance name must not be blank" }
        withContext(io) {
            mutex.withLock {
                val jmdns = instanceLocked(addressState.value)
                registered?.let { runCatching { jmdns.unregisterService(it) } }
                registered = null
                val info = ServiceInfo.create(SERVICE_TYPE, service.instanceName, service.port, 0, 0, service.txt)
                jmdns.registerService(info)
                registered = info
            }
        }
    }

    override suspend fun withdraw() {
        withContext(io) {
            mutex.withLock {
                val jmdns = dns ?: return@withLock
                registered?.let { runCatching { jmdns.unregisterService(it) } }
                registered = null
            }
        }
    }

    override suspend fun rebind(address: InetAddress) {
        require(!address.isAnyLocalAddress) { "bind JmDNS to the LAN interface address, never the wildcard" }
        withContext(io) {
            val old =
                mutex.withLock {
                    if (addressState.value == address && (dnsAddress == null || dnsAddress == address)) return@withLock null
                    val previous = synchronized(this@JmdnsLanDiscovery) { dns.also { dns = null } }
                    dnsAddress = null
                    registered = null
                    addressState.value = address
                    previous
                }
            // The goodbyes go out on the old network if it is still there; the new one hears the next announcement.
            old?.let { stop(it) }
        }
    }

    override fun browse(): Flow<LanEvent> =
        channelFlow {
            // The records found on the current address; reported lost when the address changes.
            var known = emptySet<String>()
            addressState.collectLatest { current ->
                for (name in known) send(LanEvent.Lost(name))
                known = emptySet()
                var backoff = BROWSE_RETRY_MILLIS
                while (true) {
                    try {
                        browseOn(current).collect { event ->
                            known =
                                when (event) {
                                    is LanEvent.Found -> known + event.service.instanceName
                                    is LanEvent.Lost -> known - event.instanceName
                                }
                            send(event)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError("mDNS browsing on ${current.hostAddress} failed; trying again", e)
                    }
                    delay(backoff)
                    backoff = minOf(backoff * 2, BROWSE_RETRY_MAX_MILLIS)
                }
            }
        }.buffer(Channel.UNLIMITED).flowOn(io)

    /** Browses on the JmDNS instance of [address] until cancelled (never completes normally while it runs). */
    private fun browseOn(address: InetAddress): Flow<LanEvent> =
        callbackFlow {
            val jmdns = mutex.withLock { instanceLocked(address) }
            val listener =
                object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        // Ask for the SRV, TXT and address records without blocking JmDNS's listener thread, and keep
                        // the answer up to date (persistent): serviceResolved reports each complete version.
                        runCatching { jmdns.requestServiceInfo(event.type, event.name, true, RESOLVE_WAIT_MILLIS) }
                    }

                    override fun serviceRemoved(event: ServiceEvent) {
                        trySend(LanEvent.Lost(event.name))
                    }

                    override fun serviceResolved(event: ServiceEvent) {
                        toLanService(event.info)?.let { trySend(LanEvent.Found(it)) }
                    }
                }
            jmdns.addServiceListener(SERVICE_TYPE, listener)
            awaitClose { runCatching { jmdns.removeServiceListener(SERVICE_TYPE, listener) } }
        }

    /** Withdraws the record and stops JmDNS; idempotent. Blocks up to [closeTimeoutMillis]. */
    override fun close() {
        val jmdns =
            synchronized(this) {
                if (closed) return
                closed = true
                dns.also { dns = null }
            } ?: return
        stop(jmdns)
    }

    /** [close] from a coroutine, off the caller's thread. */
    suspend fun shutdown() {
        withContext(io + NonCancellable) { withTimeoutOrNull(closeTimeoutMillis + 500) { close() } }
    }

    /** Unregisters everything on [jmdns] (goodbyes) and closes it, on a daemon thread, waiting at most [closeTimeoutMillis]. */
    private fun stop(jmdns: JmDNS) {
        val stopper =
            Thread({
                runCatching { jmdns.unregisterAllServices() }
                runCatching { jmdns.close() }
            }, "jmdns-close").apply { isDaemon = true }
        stopper.start()
        stopper.join(closeTimeoutMillis)
    }

    /**
     * The JmDNS instance on [address], started when needed. Call with [mutex] held.
     *
     * @throws IOException when JmDNS cannot start there.
     * @throws IllegalStateException after [close], or when the address changed meanwhile (the browse restarts).
     */
    private fun instanceLocked(address: InetAddress): JmDNS =
        synchronized(this) {
            check(!closed) { "this JmDNS discovery is closed" }
            if (address != addressState.value) throw IOException("the interface address changed to ${addressState.value.hostAddress}")
            dns ?: JmDNS.create(address, hostName).also {
                dns = it
                dnsAddress = address
            }
        }

    override fun toString(): String = "JmdnsLanDiscovery(${address.hostAddress}, $hostName)"

    companion object {
        /** The DNS-SD type in JmDNS's fully qualified form. */
        const val SERVICE_TYPE: String = AppIdentity.MDNS_SERVICE_TYPE + ".local."

        /** RFC 6762 §10: 120 s for records bound to a host (its address, SRV, and the TXT and PTR of its instance). */
        const val RECORD_TTL_SECONDS: Int = 120

        /** The system property JmDNS reads its record TTL from, once, when its constants load. */
        const val TTL_PROPERTY: String = "net.dns.ttl"

        const val DEFAULT_CLOSE_TIMEOUT_MILLIS: Long = 3_000
        private const val RESOLVE_WAIT_MILLIS = 1L
        private const val MAX_PORT = 65535
        private const val MAX_HOST_NAME = 63
        private const val BROWSE_RETRY_MILLIS = 1_000L
        private const val BROWSE_RETRY_MAX_MILLIS = 30_000L

        init {
            useShortTtl()
        }

        /**
         * Sets JmDNS's record TTL to [RECORD_TTL_SECONDS] unless the user chose one. Call before any JmDNS class loads
         * (the app's `main` does; this class does when it loads).
         */
        fun useShortTtl() {
            if (System.getProperty(TTL_PROPERTY) == null) System.setProperty(TTL_PROPERTY, RECORD_TTL_SECONDS.toString())
        }

        /** `drop-` and 8 random hex digits. */
        fun randomHostName(): String = "${AppIdentity.CODE_NAME}-%08x".format(SecureRandom().nextInt())

        /**
         * A browsed [info] as a [LanService]: the first IPv4 address (else IPv6, without its scope), the port and
         * every TXT key that has a value. Null when the record has no address, port or TXT keys yet (JmDNS reports a
         * service as resolved before its TXT record may have arrived; the complete version follows).
         */
        fun toLanService(info: ServiceInfo?): LanService? {
            info ?: return null
            val port = info.port
            if (port !in 1..MAX_PORT) return null
            val host =
                info.inet4Addresses.firstOrNull()?.hostAddress
                    ?: info.inet6Addresses.firstOrNull()?.hostAddress
                    ?: info.inetAddresses.firstOrNull()?.let { a ->
                        when (a) {
                            is Inet4Address, is Inet6Address -> a.hostAddress
                            else -> null
                        }
                    }
                    ?: return null
            val txt = LinkedHashMap<String, String>()
            val names = info.propertyNames
            while (names.hasMoreElements()) {
                val key = names.nextElement() ?: continue
                val value = info.getPropertyString(key) ?: continue
                txt[key] = value
            }
            if (txt.isEmpty()) return null
            return LanService(info.name, host.substringBefore('%'), port, txt)
        }
    }
}
