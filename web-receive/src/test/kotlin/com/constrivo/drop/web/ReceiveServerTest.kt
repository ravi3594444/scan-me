package com.constrivo.drop.web

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The CIO server itself: bound address, idle shutdown on an injected clock, stop. */
class ReceiveServerTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val clock = FakeMonotonicClock()
    private val note = BytesFile("note.txt", "hello".toByteArray(), "text/plain")

    private fun session(vararg files: SharedFile = arrayOf(note)) =
        ReceiveSession(
            TEST_TOKEN,
            ReceiveOffer("Dev", files.toList()),
            { true },
            settings = ReceiveSettings(idleCheckIntervalMillis = 10, approvalWaitMillis = 5_000),
            monotonicClock = clock,
            wallClock = FIXED_WALL_CLOCK,
            random = Random(1),
        )

    private fun browser(): HttpClient =
        HttpClient
            .newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    private fun HttpClient.get(url: String): HttpResponse<ByteArray> =
        send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofByteArray())

    private fun HttpClient.approve(server: ReceiveServer) {
        assertEquals(200, get(server.ipUrl()).statusCode())
        assertEquals(200, get(server.ipUrl() + "files").statusCode())
    }

    private fun refusesConnections(address: InetSocketAddress): Boolean =
        try {
            Socket().use { it.connect(address, 1000) }
            false
        } catch (_: IOException) {
            true
        }

    @Test
    fun theWildcardAddressIsRefused() {
        assertFailsWith<IllegalArgumentException> { ReceiveServer(session(), InetAddress.getByName("0.0.0.0")) }
        assertFailsWith<IllegalArgumentException> { ReceiveServer(session(), InetAddress.getByName("::")) }
        assertFailsWith<IllegalArgumentException> { ReceiveServer(session(), loopback, port = 70_000) }
    }

    @Test
    fun servesTheSessionOnTheBoundAddressOnly() =
        runBlocking<Unit> {
            val server = ReceiveServer(session(), loopback)
            val bound = server.start()
            try {
                assertEquals(loopback, bound.address)
                assertTrue(bound.port > 0)
                assertEquals("http://drop.local:${bound.port}/t/${TEST_TOKEN.value}/", server.url())
                assertEquals("http://127.0.0.1:${bound.port}/t/${TEST_TOKEN.value}/", server.ipUrl())
                val client = browser()
                client.approve(server)
                val file = client.get(server.ipUrl() + "file/0")
                assertEquals(200, file.statusCode())
                assertContentEquals(note.bytes, file.body())
                assertEquals("5", file.headers().firstValue("content-length").orElse(null))
                assertEquals(404, client.get("http://127.0.0.1:${bound.port}/").statusCode())
                val other = otherLocalIpv4()
                if (other != null) {
                    assertTrue(refusesConnections(InetSocketAddress(other, bound.port)), "not reachable on $other")
                }
                assertFailsWith<IllegalStateException> { server.start() }
            } finally {
                server.stop()
            }
        }

    @Test
    fun stopsSixtySecondsAfterTheLastDownload() =
        runBlocking<Unit> {
            val server = ReceiveServer(session(), loopback)
            val bound = server.start()
            val client = browser()
            client.approve(server)
            clock.advance(10 * 60_000)
            delay(100)
            assertEquals(200, client.get(server.ipUrl()).statusCode(), "no download yet, so never idle")

            assertEquals(200, client.get(server.ipUrl() + "file/0").statusCode())
            clock.advance(59_999)
            delay(200)
            assertEquals(200, client.get(server.ipUrl() + "files").statusCode(), "59.999 s after the download")
            clock.advance(1)
            assertEquals(StopReason.IDLE, withTimeout(5_000) { server.awaitStopped() })
            assertTrue(server.session.isClosed)
            assertTrue(refusesConnections(bound))
        }

    @Test
    fun aDownloadInProgressKeepsTheServerUp() =
        runBlocking<Unit> {
            val slow = GatedFile(1_000_000)
            val server = ReceiveServer(session(note, slow), loopback)
            server.start()
            val client = browser()
            client.approve(server)
            val download =
                client.sendAsync(
                    HttpRequest.newBuilder(URI(server.ipUrl() + "file/1")).build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
            assertTrue(slow.reading.await(5, TimeUnit.SECONDS), "the slow download started")
            assertEquals(200, client.get(server.ipUrl() + "file/0").statusCode())
            clock.advance(5 * 60_000)
            delay(300)
            assertFalse(server.session.isClosed, "a download is still streaming")
            slow.release.countDown()
            val response = download.get(10, TimeUnit.SECONDS)
            assertEquals(200, response.statusCode())
            assertEquals(1_000_000, response.body().size)
            clock.advance(59_000)
            delay(200)
            assertFalse(server.session.isClosed)
            clock.advance(1_000)
            assertEquals(StopReason.IDLE, withTimeout(5_000) { server.awaitStopped() })
        }

    @Test
    fun stopRevokesTheTokenAndIsIdempotent() =
        runBlocking<Unit> {
            val server = ReceiveServer(session(), loopback)
            val bound = server.start()
            server.stop()
            server.stop()
            assertEquals(StopReason.STOPPED, server.awaitStopped())
            assertTrue(server.session.isClosed)
            assertTrue(refusesConnections(bound))
            assertFailsWith<IllegalStateException> { server.start() }
        }

    @Test
    fun aFileThatShrankAbortsItsResponseAndCountsAsFinished() =
        runBlocking<Unit> {
            val shrunk = BytesFile("shrunk.bin", ByteArray(50), declaredSize = 100)
            val server = ReceiveServer(session(shrunk), loopback)
            server.start()
            try {
                val client = browser()
                client.approve(server)
                assertFailsWith<IOException>("the browser sees a broken download, not a short file") {
                    client.get(server.ipUrl() + "file/0")
                }
                assertFailsWith<IOException> { client.get(server.ipUrl() + "all.zip") }
                withTimeout(5_000) { while (server.session.activity.snapshot().finished < 2) delay(10) }
                assertEquals(0, server.session.activity.snapshot().inProgress)
            } finally {
                server.stop()
            }
        }

    @Test
    fun anUploadWhoseBodyEndsEarlyIsDiscarded() =
        runBlocking<Unit> {
            val aborted = CountDownLatch(1)
            val sink =
                UploadSink { _, _, _ ->
                    object : UploadWriter {
                        override fun write(
                            buffer: ByteArray,
                            offset: Int,
                            length: Int,
                        ) = Unit

                        override fun commit(): String = error("must not commit a short upload")

                        override fun abort() = aborted.countDown()
                    }
                }
            val upload = UploadSettings(sink, 1000)
            val session =
                ReceiveSession(
                    TEST_TOKEN,
                    ReceiveOffer("Dev", listOf(note)),
                    { true },
                    upload,
                    ReceiveSettings(idleCheckIntervalMillis = 10),
                    clock,
                    FIXED_WALL_CLOCK,
                    Random(1),
                )
            val server = ReceiveServer(session, loopback)
            val bound = server.start()
            try {
                val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
                val client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).cookieHandler(cookies).build()
                client.approve(server)
                val cookie = cookies.cookieStore.cookies.single { it.name == "drop_session" }
                Socket(bound.address, bound.port).use { socket ->
                    val request =
                        "POST ${session.pathPrefix}upload?name=a.bin HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                            "Cookie: drop_session=${cookie.value}\r\nContent-Length: 100\r\n\r\n"
                    socket.getOutputStream().write(request.toByteArray() + ByteArray(40))
                    socket.shutdownOutput()
                    assertTrue(aborted.await(5, TimeUnit.SECONDS), "the partial upload was aborted")
                    val status = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    assertTrue(status.isEmpty() || status.startsWith("HTTP/1.1 400"), status)
                }
                withTimeout(5_000) { while (upload.remainingBytes != 1000L) delay(10) }
            } finally {
                server.stop()
            }
        }

    @Test
    fun urlNeedsAStartedServer() {
        val server = ReceiveServer(session(), loopback)
        assertFailsWith<IllegalStateException> { server.url() }
    }

    /** Another IPv4 address of this host, to show the server is not reachable there; null when there is none. */
    private fun otherLocalIpv4(): InetAddress? =
        NetworkInterface.networkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }

    /** A file whose stream stops half way until [release] opens. */
    private class GatedFile(
        override val size: Long,
    ) : SharedFile {
        override val name = "slow.bin"
        override val mimeType = "application/octet-stream"
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun open(offset: Long): InputStream =
            object : InputStream() {
                private var position = offset

                override fun read(): Int = throw UnsupportedOperationException()

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (position >= size) return -1
                    if (position >= size / 2 && release.count > 0) {
                        reading.countDown()
                        release.await(20, TimeUnit.SECONDS)
                    }
                    val n = minOf(len.toLong(), size - position).toInt()
                    b.fill(1, off, off + n)
                    position += n
                    return n
                }
            }
    }
}
