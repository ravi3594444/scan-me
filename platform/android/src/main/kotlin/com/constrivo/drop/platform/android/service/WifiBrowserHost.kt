package com.constrivo.drop.platform.android.service

import android.content.Context
import android.net.wifi.WifiManager
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.discovery.MonotonicClock
import com.constrivo.drop.core.ladder.ActiveLink
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.ladder.P2pCredentials
import com.constrivo.drop.core.ladder.WifiLinkProvider
import com.constrivo.drop.platform.android.AndroidClocks
import com.constrivo.drop.web.BrowserApprover
import com.constrivo.drop.web.ReceiveOffer
import com.constrivo.drop.web.ReceiveServer
import com.constrivo.drop.web.ReceiveSession
import com.constrivo.drop.web.ReceiveToken
import com.constrivo.drop.web.mdns.MdnsResponder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * The browser receive page's [BrowserHost] on a phone (F-D6, architecture §10.3 with N15):
 *
 * 1. hosts the link a computer joins by hand through WP7c/d's providers: the Wi-Fi Direct group as a legacy WPA2
 *    network ([LinkMode.P2P_LEGACY], with fresh random credentials, [P2pCredentials.random]), else the local-only
 *    hotspot ([LinkMode.HOTSPOT], whose credentials the system picks). 2.4 GHz is asked for, since any computer can
 *    join it;
 * 2. serves the offer with web-receive's [ReceiveServer] bound to that link's IPv4 address only, and answers
 *    `drop.local` with an [MdnsResponder] on the same interface while a `WifiManager.MulticastLock` is held (without it
 *    some devices filter the multicast queries out before they reach the socket);
 * 3. reports the page's addresses and the network's real credentials through `onReady`, then waits until the page
 *    goes idle (60 s after the last download) or the caller is cancelled, and tears everything down, the link last
 *    (F-E11: the phone's own Wi-Fi comes back).
 *
 * Each new browser needs the phone's "Allow this computer?" answer ([BrowserApprover], asked through the UI).
 *
 * @param providers the phone's Wi-Fi rungs; with none that can host, [serve] fails at once.
 */
class WifiBrowserHost(
    context: Context,
    private val providers: List<WifiLinkProvider>,
    private val crypto: CryptoProvider,
    private val monotonicClock: MonotonicClock = AndroidClocks.elapsedRealtime,
    private val log: (String) -> Unit = {},
) : BrowserHost {
    private val wifi: WifiManager? = context.applicationContext.getSystemService(WifiManager::class.java)

    /** Whether any provider can host a link a computer joins. */
    val available: Boolean get() = choose() != null

    /**
     * @throws IOException when no link can be hosted, the link has no IPv4 address, or the server cannot bind.
     */
    override suspend fun serve(
        offer: ReceiveOffer,
        approver: BrowserApprover,
        onReady: (BrowserShareStatus.Ready) -> Unit,
    ) {
        val (provider, mode) = choose() ?: throw IOException("no Wi-Fi link can be hosted on this phone")
        val link = AtomicReference<ActiveLink?>()
        var server: ReceiveServer? = null
        var mdns: MdnsResponder? = null
        val lock = wifi?.createMulticastLock(LOCK_TAG)?.apply { setReferenceCounted(false) }
        try {
            val credentials = if (mode == LinkMode.P2P_LEGACY) P2pCredentials.random(crypto) else null
            val up = provider.host(HostRequest(mode, credentials, requestFiveGhz = false)) { link.set(it) }
            link.set(up)
            val address =
                up.localAddress?.let { runCatching { InetAddress.getByName(it) }.getOrNull() } as? Inet4Address
                    ?: throw IOException("the ${mode.name.lowercase()} link has no IPv4 address")
            val networkInterface = NetworkInterface.getByInetAddress(address) ?: throw IOException("no interface carries $address")
            val session = ReceiveSession(ReceiveToken.generate(), offer, approver, monotonicClock = monotonicClock)
            val started = ReceiveServer(session, address).also { server = it }
            started.start()
            lock?.acquire()
            mdns =
                try {
                    MdnsResponder(address, networkInterface, clock = monotonicClock).also { it.start() }
                } catch (e: IOException) {
                    // drop.local is a convenience; the page's IP address still works.
                    log("mDNS responder did not start: ${e.message}")
                    null
                }
            val network = up.credentials ?: credentials ?: throw IOException("the link has no credentials a computer could join with")
            log("browser page up on ${mode.name.lowercase()} for ${offer.files.size} files")
            onReady(BrowserShareStatus.Ready(started.url(), started.ipUrl(), network.ssid, network.passphrase, offer.files.size))
            val reason = started.awaitStopped()
            log("browser page stopped: ${reason.name.lowercase()}")
        } finally {
            withContext(NonCancellable) {
                server?.let { runCatching { it.stop() } }
                mdns?.let { runCatching { it.close() } }
                lock?.let { if (it.isHeld) runCatching { it.release() } }
                link.getAndSet(null)?.let { active -> withTimeoutOrNull(TEARDOWN_MILLIS) { runCatching { active.teardown() } } }
            }
        }
    }

    private fun choose(): Pair<WifiLinkProvider, LinkMode>? {
        for (mode in HOST_MODES) {
            val provider = providers.firstOrNull { it.kind == mode.kind && it.supports(mode, LinkRole.HOST) }
            if (provider != null) return provider to mode
        }
        return null
    }

    private companion object {
        const val LOCK_TAG = "drop:browser-mdns"
        const val TEARDOWN_MILLIS = 10_000L

        /** The group first (its band is ours to choose), then the hotspot (N8, N15). */
        val HOST_MODES = listOf(LinkMode.P2P_LEGACY, LinkMode.HOTSPOT)
    }
}
