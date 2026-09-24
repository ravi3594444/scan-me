package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.WifiCredentials
import com.constrivo.drop.core.transfer.LowLatencyChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The socket link every Android rung hands the engine (§7.4, §8): streams, dial checks, teardown (F-E11 ownership). */
class SocketActiveLinkTest {
    private val details = LinkDetails.Lan(7, "lo")

    private fun host(
        release: suspend () -> Unit = {},
        policy: LinkDialPolicy = LinkDialPolicy(listOf(IpPrefix(LOOPBACK, 8))),
    ) = SocketActiveLink(
        kind = LinkKind.HOTSPOT,
        mode = LinkMode.HOTSPOT,
        role = LinkRole.HOST,
        frequencyMhz = 2437,
        credentials = WifiCredentials("AndroidShare_1234", "passphrase1"),
        details = details,
        listenAddress = LOOPBACK,
        socketFactory = NetworkBoundSocketFactory(localAddress = LOOPBACK),
        dialPolicy = policy,
        release = release,
    )

    private fun joiner(
        release: suspend () -> Unit = {},
        policy: LinkDialPolicy = LinkDialPolicy(listOf(IpPrefix(LOOPBACK, 8))),
    ) = SocketActiveLink(
        kind = LinkKind.HOTSPOT,
        mode = LinkMode.HOTSPOT,
        role = LinkRole.JOIN,
        frequencyMhz = null,
        credentials = null,
        details = details,
        listenAddress = null,
        socketFactory = NetworkBoundSocketFactory(RecordingBinder()),
        dialPolicy = policy,
        release = release,
    )

    private suspend fun DataChannel.readExactly(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = read(buffer, read, size - read)
            if (n < 0) throw IOException("closed after $read bytes")
            read += n
        }
        return buffer
    }

    @Test
    fun aHostListensOnItsAddressAndAJoinerConnects() =
        runBlocking {
            val host = host()
            val joiner = joiner()
            assertEquals("127.0.0.1", host.localAddress)
            assertTrue(host.localPort!! > 0)
            assertNull(joiner.localAddress)
            assertNull(joiner.localPort)
            coroutineScope {
                val accepted = async { host.accept(host.localPort!!) }
                val out = joiner.connect("127.0.0.1", host.localPort!!)
                val incoming = accepted.await()
                out.write(byteArrayOf(1, 2, 3))
                assertContentEquals(byteArrayOf(1, 2, 3), incoming.readExactly(3))
                incoming.write(byteArrayOf(9))
                assertContentEquals(byteArrayOf(9), out.readExactly(1))
                assertIs<LowLatencyChannel>(out).setLowLatency(true)
                assertEquals(1, host.openStreams)
                assertEquals(1, joiner.openStreams)
                out.close()
                assertEquals(0, joiner.openStreams, "a closed stream is forgotten")
            }
            host.teardown()
            joiner.teardown()
        }

    @Test
    fun refusedAddressesAndPortsNeverConnect() =
        runBlocking {
            val joiner = joiner(policy = LinkDialPolicy(listOf(IpPrefix(ip("192.168.43.10"), 24))))
            assertFailsWith<LinkAddressRefusedException> { joiner.connect("127.0.0.1", 9) }
            assertFailsWith<LinkAddressRefusedException> { joiner.connect("drop.local", 9) }
            assertFailsWith<LinkAddressRefusedException> { joiner.connect("192.168.43.1", 0) }
            assertFailsWith<LinkAddressRefusedException> { joiner.connect("192.168.43.1", 65536) }
            joiner.teardown()
        }

    @Test
    fun acceptNeedsTheLinksOwnPortAndAListener() =
        runBlocking {
            val host = host()
            val joiner = joiner()
            assertFailsWith<IOException> { host.accept(host.localPort!! + 1) }
            assertFailsWith<IOException> { joiner.accept(1234) }
            host.teardown()
            joiner.teardown()
        }

    @Test
    fun teardownClosesTheListenerAndEveryStreamThenReleasesOnce() =
        runBlocking {
            val releases = AtomicInteger()
            val host = host(release = { releases.incrementAndGet() })
            val joiner = joiner()
            val port = host.localPort!!
            val accepted = async { host.accept(port) }
            val out = joiner.connect("127.0.0.1", port)
            val incoming = accepted.await()

            coroutineScope {
                launch { host.teardown() }
                launch { host.teardown() }
            }
            host.teardown()
            assertEquals(1, releases.get())
            assertTrue(host.isTornDown)
            // The peer sees the stream end, and the listener is gone.
            val buffer = ByteArray(1)
            assertTrue(runCatching { out.read(buffer) }.getOrDefault(-1) <= 0)
            assertFailsWith<IOException> { host.accept(port) }
            assertEquals(-1, incoming.read(buffer), "the host's own stream was closed")
            assertFailsWith<IOException> { joiner.connect("127.0.0.1", port) }
            joiner.teardown()
        }

    @Test
    fun aFailingReleaseNeverMakesTeardownThrow() =
        runBlocking {
            val link = host(release = { throw IllegalStateException("radio died") })
            link.teardown()
            val timeout = host(release = { withTimeout(1) { awaitCancellation() } })
            timeout.teardown()
            assertTrue(link.isTornDown && timeout.isTornDown)
        }

    @Test
    fun aLostLinkRefusesNewStreams() =
        runBlocking {
            val host = host()
            val joiner = joiner()
            assertTrue(joiner.markLost())
            assertEquals(false, joiner.markLost(), "reported once")
            assertFailsWith<IOException> { joiner.connect("127.0.0.1", host.localPort!!) }
            host.teardown()
            joiner.teardown()
        }

    @Test
    fun watchersEndWhenTheTeardownStarts() =
        runBlocking {
            val link = joiner()
            val started = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            link.watch {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    ended.complete(Unit)
                }
            }
            started.await()
            link.teardown()
            withTimeout(5_000) { ended.await() }
            var ranAfter = false
            link.watch { ranAfter = true }
            assertEquals(false, ranAfter)
        }

    @Test
    fun theModeMustMatchTheKind() {
        assertFailsWith<IllegalArgumentException> {
            SocketActiveLink(
                kind = LinkKind.P2P,
                mode = LinkMode.HOTSPOT,
                role = LinkRole.JOIN,
                frequencyMhz = null,
                credentials = null,
                details = details,
                listenAddress = null,
                socketFactory = NetworkBoundSocketFactory(RecordingBinder()),
                dialPolicy = LinkDialPolicy(),
                release = {},
            )
        }
    }
}
