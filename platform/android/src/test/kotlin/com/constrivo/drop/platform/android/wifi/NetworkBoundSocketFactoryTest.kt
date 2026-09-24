package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.net.TcpDataChannel
import com.constrivo.drop.core.transfer.net.TcpListener
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The link socket factory of every Android link (§7.4, T-15): options, network and address binding, failures. */
class NetworkBoundSocketFactoryTest {
    @Test
    fun aLinkSocketMustBeBoundToSomething() {
        assertFailsWith<IllegalArgumentException> { NetworkBoundSocketFactory() }
        assertFailsWith<IllegalArgumentException> { NetworkBoundSocketFactory(localAddress = ip("0.0.0.0")) }
        assertFailsWith<IllegalArgumentException> { NetworkBoundSocketFactory(RecordingBinder(), bufferBytes = 0) }
    }

    @Test
    fun socketsAreBlockingUnconnectedAndBoundToTheNetworkBeforeTheAddress() {
        var observedBound: Boolean? = null
        val binder = SocketBinder { socket -> observedBound = socket.isBound }
        val channel = NetworkBoundSocketFactory(binder, LOOPBACK).create()
        channel.use {
            assertTrue(it.isBlocking)
            assertFalse(it.isConnected)
            assertEquals(false, observedBound, "the network is bound first, while the socket has no address yet")
            assertEquals(LOOPBACK, (it.localAddress as InetSocketAddress).address)
        }
    }

    @Test
    fun tcpNoDelayIsLeftToTheEngineAndBuffersAreRequested() {
        val requested = 1 shl 20
        NetworkBoundSocketFactory(RecordingBinder(), bufferBytes = requested).create().use {
            assertEquals(false, it.getOption(StandardSocketOptions.TCP_NODELAY))
            // The kernel may double or cap the size; it is never left at a small default of zero.
            assertTrue(it.getOption(StandardSocketOptions.SO_RCVBUF) > 0)
            assertTrue(it.getOption(StandardSocketOptions.SO_SNDBUF) > 0)
        }
    }

    @Test
    fun theNetworkBinderGetsTheChannelsOwnSocket() {
        val binder = RecordingBinder()
        var opened: SocketChannel? = null
        val factory = NetworkBoundSocketFactory(binder, open = { SocketChannel.open().also { opened = it } })
        factory.create().use { channel ->
            assertSame(opened, channel)
            assertEquals(1, binder.bound.size)
            assertSame(channel.socket(), binder.bound.single())
        }
    }

    @Test
    fun aFailedBindClosesTheSocket() {
        val binder = RecordingBinder().apply { failure = IOException("network gone") }
        var opened: SocketChannel? = null
        val factory = NetworkBoundSocketFactory(binder, open = { SocketChannel.open().also { opened = it } })
        assertFailsWith<IOException> { factory.create() }
        assertFalse(opened!!.isOpen)

        // TEST-NET-1: no interface of this machine has it, as a link's address after the interface went away.
        var second: SocketChannel? = null
        val gone = NetworkBoundSocketFactory(localAddress = ip("192.0.2.1"), open = { SocketChannel.open().also { second = it } })
        assertFailsWith<IOException> { gone.create() }
        assertFalse(second!!.isOpen)
    }

    @Test
    fun theEnginesConnectUsesTheFactory() =
        runBlocking {
            val binder = RecordingBinder()
            TcpListener(InetSocketAddress(LOOPBACK, 0), LinkKind.HOTSPOT).use { listener ->
                val accepted = async { listener.accept() }
                val client =
                    TcpDataChannel.connect(
                        "127.0.0.1",
                        listener.port,
                        LinkKind.HOTSPOT,
                        factory = NetworkBoundSocketFactory(binder, LOOPBACK),
                    )
                val server = accepted.await()
                client.write("hello".encodeToByteArray())
                val buffer = ByteArray(5)
                var read = 0
                while (read < 5) read += server.read(buffer, read, 5 - read)
                assertContentEquals("hello".encodeToByteArray(), buffer)
                assertEquals(1, binder.bound.size)
                client.close()
                server.close()
            }
        }
}
