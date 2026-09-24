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
 * coroutine, like ATT on one bearer, and the server side routes the client's segments exactly as [GattServerHost] does
 * ([GattServerRouting]): a fresh `OPEN` starts a session whose channel is offered to [onIncoming] before the `OPEN` is
 * answered, a repeated `OPEN` goes to the current session, and only the registered session owns the connection. Faults
 * can be injected per sent segment; [linkDown] cuts the connection for both sides after what is already in flight.
 */
internal class GattLinkPair(
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
    maxSegment: Int = 509,
    clientConfig: GattStreamConfig = GattStreamConfig(),
    private val serverConfig: GattStreamConfig = GattStreamConfig(),
    serverMaxSegment: Int = maxSegment,
    /** Sessions the server takes at once ([GattServerHost]'s `maxServerSessions`); 0 refuses every stream. */
    private val maxSessions: Int = 1,
    /** The server owner's answer to a new stream ([GattServerHost]'s `onIncoming`). */
    private val onIncoming: (GattStreamChannel) -> Boolean = { true },
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

    /** One server session, like [GattServerHost]'s: it notifies over the server link; only a registered one owns the link. */
    private inner class ServerSession : GattSegmentTransport {
        val channel: GattStreamChannel = GattStreamChannel.server(this, context, serverConfig, "server")

        override val maxSegmentSize: Int get() = serverLink.maxSegmentSize

        override suspend fun send(segment: ByteArray) = serverLink.send(segment)

        override fun disconnect() {
            val registered =
                synchronized(this@GattLinkPair) {
                    (current === this).also { if (it) current = null }
                }
            if (registered) serverLink.disconnect()
        }
    }

    @Volatile var down = false
        private set

    val clientLink = Link(maxSegment)
    val serverLink = Link(serverMaxSegment)
    val client: GattStreamChannel = GattStreamChannel.client(clientLink, context, clientConfig, "client")

    /** The registered session (routing). */
    @Volatile private var current: ServerSession? = null

    /** The newest session's channel, kept after it ends for assertions. */
    @Volatile var server: GattStreamChannel? = null
        private set

    /** Sessions started for fresh `OPEN`s (a repeated `OPEN` starts none). */
    val sessionsStarted = AtomicInteger()

    /** Streams refused because every session was in use. */
    val refusedForCapacity = AtomicInteger()

    /** Starts delivery in both directions. */
    fun start(): GattLinkPair {
        scope.launch {
            for (item in clientLink.outbox) {
                if (item === Lost) {
                    val gone = synchronized(this@GattLinkPair) { current.also { current = null } }
                    gone?.channel?.onTransportClosed(IOException("GATT link lost"))
                    continue
                }
                route(item as ByteArray)
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

    private fun route(bytes: ByteArray) {
        val existing = current
        when (GattServerRouting.route(bytes, existing?.channel, if (existing == null) 0 else 1, maxSessions)) {
            GattServerRouting.Route.EXISTING -> {
                existing?.channel?.onSegment(bytes)
            }

            GattServerRouting.Route.DROP -> {
                Unit
            }

            GattServerRouting.Route.REFUSE -> {
                refusedForCapacity.incrementAndGet()
                ServerSession().channel.refuse(bytes)
            }

            GattServerRouting.Route.NEW -> {
                val session = ServerSession()
                synchronized(this) { current = session }
                server = session.channel
                sessionsStarted.incrementAndGet()
                existing?.channel?.onTransportClosed(IOException("client opened a new stream"))
                GattServerRouting.start(session.channel, bytes, onIncoming)
            }
        }
    }

    /** Cuts the connection: both sides learn it after the segments already in flight. */
    fun linkDown() {
        if (down) return
        down = true
        clientLink.outbox.trySend(Lost)
        serverLink.outbox.trySend(Lost)
    }
}
