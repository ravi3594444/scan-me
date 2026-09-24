package com.constrivo.drop.platform.android.bluetooth.gatt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** What the in-memory link does with one segment a side sends. */
internal enum class Fault { NONE, DROP, DUPLICATE, FAIL }

/**
 * An in-memory GATT connection for [GattStreamChannel] tests: each direction delivers segments in order on its own
 * coroutine, like ATT on one bearer, and plays the part of [GattServerHost] on the server side (a fresh `OPEN` creates the
 * server channel). Faults can be injected per sent segment; [linkDown] cuts the connection for both sides after what is
 * already in flight.
 */
internal class GattLinkPair(
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
    maxSegment: Int = 509,
    clientConfig: GattStreamConfig = GattStreamConfig(),
    private val serverConfig: GattStreamConfig = GattStreamConfig(),
    serverMaxSegment: Int = maxSegment,
) {
    private object Lost

    inner class Link(
        override val maxSegmentSize: Int,
    ) : GattSegmentTransport {
        val outbox = Channel<Any>(Channel.UNLIMITED)
        val sent = ArrayList<ByteArray>()
        val disconnects = AtomicInteger()

        @Volatile var fault: (index: Int, segment: ByteArray) -> Fault = { _, _ -> Fault.NONE }

        override suspend fun send(segment: ByteArray) {
            if (down) throw IOException("link down")
            val index = synchronized(sent) { sent.size.also { sent += segment.copyOf() } }
            when (fault(index, segment)) {
                Fault.NONE -> {
                    outbox.send(segment.copyOf())
                }

                Fault.DROP -> {
                    // Lost on the way.
                }

                Fault.DUPLICATE -> {
                    outbox.send(segment.copyOf())
                    outbox.send(segment.copyOf())
                }

                Fault.FAIL -> {
                    throw IOException("write failed")
                }
            }
        }

        override fun disconnect() {
            disconnects.incrementAndGet()
            linkDown()
        }

        /** The types of the segments this side sent, in order. */
        fun sentTypes(): List<Int> = synchronized(sent) { sent.map { it[0].toInt() and 0xFF } }
    }

    @Volatile var down = false
        private set

    val clientLink = Link(maxSegment)
    val serverLink = Link(serverMaxSegment)
    val client: GattStreamChannel = GattStreamChannel.client(clientLink, context, clientConfig, "client")

    @Volatile var server: GattStreamChannel? = null
        private set

    /** Starts delivery in both directions. */
    fun start(): GattLinkPair {
        scope.launch {
            for (item in clientLink.outbox) {
                if (item === Lost) {
                    server?.onTransportClosed(IOException("GATT link lost"))
                    continue
                }
                val bytes = item as ByteArray
                val current = server
                val fresh = bytes.size >= 3 && bytes[0].toInt() == GattSegments.TYPE_OPEN && bytes[1].toInt() == 0 && bytes[2].toInt() == 0
                if (current == null && fresh) {
                    val created = GattStreamChannel.server(serverLink, context, serverConfig, "server")
                    server = created
                    created.accept(bytes)
                } else {
                    current?.onSegment(bytes)
                }
            }
        }
        scope.launch {
            for (item in serverLink.outbox) {
                if (item === Lost) {
                    client.onTransportClosed(IOException("GATT link lost"))
                } else {
                    client.onSegment(item as ByteArray)
                }
            }
        }
        return this
    }

    /** Cuts the connection: both sides learn it after the segments already in flight. */
    fun linkDown() {
        if (down) return
        down = true
        clientLink.outbox.trySend(Lost)
        serverLink.outbox.trySend(Lost)
    }
}
