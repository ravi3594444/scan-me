package com.constrivo.drop.platform.desktop.lan

import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.core.discovery.LanDiscovery
import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.discovery.LanService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
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

/**
 * [LanDiscovery] on JmDNS (architecture §5.4, §8 "LAN discovery" on Windows and Linux; macOS may use it too until its
 * Bonjour helper exists): announces this device's DNS-SD record and browses for others of type
 * [AppIdentity.MDNS_SERVICE_TYPE], on the one interface address [address] ([LanInterfaces.select]).
 *
 * - **Announce.** [announce] registers the instance with its TXT keys ([com.constrivo.drop.core.discovery.MdnsRecord]
 *   builds them); announcing again (the next epoch's instance name, N4, or a changed nickname) first withdraws the
 *   previous registration, so exactly one record is live. [withdraw] sends the goodbye.
 * - **Host name.** JmDNS answers A queries for a host name of its own. The machine's host name would be a permanent,
 *   often personal identifier on the network (N4), so a random `drop-xxxxxxxx` name is used, new for every process.
 * - **Browse.** [browse] is a cold flow: each collector adds a listener; found instances are resolved and reported as
 *   [LanEvent.Found] with the first IPv4 address (else the IPv6 address), their port and TXT map; removals as
 *   [LanEvent.Lost]. Nothing here is validated or authenticated: [com.constrivo.drop.core.discovery.NearbyDevices]
 *   parses the TXT and the handshake checks the identity.
 * - **Shutdown.** [close] unregisters everything (goodbye packets) and stops JmDNS, bounded by [closeTimeoutMillis].
 *
 * JmDNS itself starts lazily, on [io], at the first call. All calls are thread-safe.
 *
 * @throws IOException from [announce], [withdraw] and the [browse] flow when JmDNS cannot start on [address].
 */
class JmdnsLanDiscovery(
    val address: InetAddress,
    val hostName: String = randomHostName(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val closeTimeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS,
) : LanDiscovery,
    AutoCloseable {
    private val mutex = Mutex()
    private var dns: JmDNS? = null
    private var registered: ServiceInfo? = null

    private var closed = false

    init {
        require(!address.isAnyLocalAddress) { "bind JmDNS to the LAN interface address, never the wildcard" }
        require(hostName.isNotBlank() && hostName.length <= MAX_HOST_NAME && hostName.all { it.isLetterOrDigit() || it == '-' }) {
            "host name must be 1–$MAX_HOST_NAME letters, digits or '-'"
        }
    }

    override suspend fun announce(service: LanService) {
        require(service.port in 1..MAX_PORT) { "port ${service.port} out of range" }
        require(service.instanceName.isNotBlank()) { "instance name must not be blank" }
        withContext(io) {
            mutex.withLock {
                val jmdns = instanceLocked()
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

    override fun browse(): Flow<LanEvent> =
        callbackFlow {
            val jmdns = mutex.withLock { instanceLocked() }
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
        }.buffer(Channel.UNLIMITED).flowOn(io)

    /** Withdraws the record and stops JmDNS; idempotent. Blocks up to [closeTimeoutMillis]. */
    override fun close() {
        val jmdns =
            synchronized(this) {
                if (closed) return
                closed = true
                dns.also { dns = null }
            } ?: return
        val stopper =
            Thread({
                runCatching { jmdns.unregisterAllServices() }
                runCatching { jmdns.close() }
            }, "jmdns-close").apply { isDaemon = true }
        stopper.start()
        stopper.join(closeTimeoutMillis)
    }

    /** [close] from a coroutine, off the caller's thread. */
    suspend fun shutdown() {
        withContext(io + NonCancellable) { withTimeoutOrNull(closeTimeoutMillis + 500) { close() } }
    }

    private fun instanceLocked(): JmDNS =
        synchronized(this) {
            check(!closed) { "this JmDNS discovery is closed" }
            dns ?: JmDNS.create(address, hostName).also { dns = it }
        }

    override fun toString(): String = "JmdnsLanDiscovery(${address.hostAddress}, $hostName)"

    companion object {
        /** The DNS-SD type in JmDNS's fully qualified form. */
        const val SERVICE_TYPE: String = AppIdentity.MDNS_SERVICE_TYPE + ".local."

        const val DEFAULT_CLOSE_TIMEOUT_MILLIS: Long = 3_000
        private const val RESOLVE_WAIT_MILLIS = 1L
        private const val MAX_PORT = 65535
        private const val MAX_HOST_NAME = 63

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
