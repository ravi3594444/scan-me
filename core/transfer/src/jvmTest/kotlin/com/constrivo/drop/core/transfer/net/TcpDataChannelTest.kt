package com.constrivo.drop.core.transfer.net

import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.TestSupport
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** §7.4 TCP connections over loopback: large writes, orderly close, a closed listener, a refused connect. */
class TcpDataChannelTest {
    @Test
    fun `bytes cross a loopback connection intact in both directions`() =
        runBlocking<Unit> {
            withTimeout(30_000) {
                TcpListener(kind = LinkKind.LAN).use { listener ->
                    val payload = TestSupport.randomBytes(8 * 1024 * 1024 + 5, 11)
                    coroutineScope {
                        val server = async { listener.accept(lowLatency = true) }
                        val client = TcpDataChannel.connect("127.0.0.1", listener.port, LinkKind.LAN)
                        val accepted = server.await()
                        assertEquals(LinkKind.LAN, client.kind)
                        assertTrue(client.remoteAddress?.port == listener.port)
                        val reading = async { readAll(accepted, payload.size) }
                        client.write(payload)
                        client.flush()
                        assertContentEquals(sha(payload), sha(reading.await()))

                        accepted.write(byteArrayOf(1, 2, 3))
                        assertContentEquals(byteArrayOf(1, 2, 3), readAll(client, 3))

                        client.setLowLatency(true)
                        client.close()
                        client.close() // idempotent
                        assertEquals(-1, accepted.read(ByteArray(16)), "the peer's orderly close reads as the end")
                        assertEquals(-1, client.read(ByteArray(16)), "a closed channel reads as the end")
                        assertFailsWith<IOException> { client.write(byteArrayOf(1)) }
                        accepted.close()
                    }
                }
            }
        }

    @Test
    fun `a closed listener refuses to accept and a closed port refuses to connect`() =
        runBlocking<Unit> {
            withTimeout(30_000) {
                val listener = TcpListener()
                listener.close()
                assertFailsWith<IOException> { listener.accept() }
                val port =
                    ServerSocket().use {
                        it.bind(InetSocketAddress("127.0.0.1", 0))
                        it.localPort
                    }
                assertFailsWith<IOException> { TcpDataChannel.connect("127.0.0.1", port, LinkKind.LAN, timeoutMillis = 2_000) }
            }
        }

    private suspend fun readAll(
        channel: TcpDataChannel,
        size: Int,
    ): ByteArray {
        val out = ByteArray(size)
        var got = 0
        while (got < size) {
            val n = channel.read(out, got, size - got)
            if (n < 0) error("stream ended at $got of $size")
            got += n
        }
        return out
    }

    private fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
