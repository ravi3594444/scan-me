package com.constrivo.drop.platform.android.bluetooth.gatt

import com.constrivo.drop.platform.android.bluetooth.gatt.GattServerRouting.Route
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The GATT server's routing of client segments ([GattServerHost], architecture §6.1 note). */
class GattServerRoutingTest {
    private val config = GattStreamConfig(initialCredits = 8, creditReturnThreshold = 2)
    private val open = GattSegments.encode(GattSegment.Open(0, 1, 8, 509))
    private val data = GattSegments.encode(GattSegment.Data(1, byteArrayOf(1)))

    private class Sink : GattSegmentTransport {
        override val maxSegmentSize: Int = 509
        val sent = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun send(segment: ByteArray) {
            sent.send(segment)
        }

        override fun disconnect() = Unit
    }

    private fun TestScope.accepted(openSegment: ByteArray = open): Pair<GattStreamChannel, Sink> {
        val sink = Sink()
        val channel = GattStreamChannel.server(sink, StandardTestDispatcher(testScheduler), config)
        channel.accept(openSegment)
        advanceUntilIdle()
        return channel to sink
    }

    @Test
    fun aFreshOpenStartsASessionAndOtherSegmentsNeedOne() =
        runTest {
            assertEquals(Route.NEW, GattServerRouting.route(open, null, sessionCount = 0, maxSessions = 4))
            assertEquals(Route.DROP, GattServerRouting.route(data, null, sessionCount = 0, maxSessions = 4))
            val (session, _) = accepted()
            assertEquals(Route.EXISTING, GattServerRouting.route(data, session, sessionCount = 1, maxSessions = 4))
        }

    @Test
    fun aByteIdenticalRepeatOfTheOpenBelongsToItsSession() =
        runTest {
            val (session, _) = accepted()
            assertTrue(session.isRepeatedOpen(open.copyOf()))
            assertEquals(Route.EXISTING, GattServerRouting.route(open.copyOf(), session, sessionCount = 1, maxSessions = 1))
        }

    @Test
    fun aDifferentOpenOrOneAfterDataIsANewStream() =
        runTest {
            val (session, _) = accepted()
            val other = GattSegments.encode(GattSegment.Open(0, 1, 16, 244))
            assertEquals(Route.NEW, GattServerRouting.route(other, session, sessionCount = 1, maxSessions = 1))
            session.onSegment(data)
            assertFalse(session.isRepeatedOpen(open))
            assertEquals(Route.NEW, GattServerRouting.route(open, session, sessionCount = 1, maxSessions = 1))
        }

    @Test
    fun aFullServerRefusesNewClientsButStillReplacesAClientsOwnSession() =
        runTest {
            assertEquals(Route.REFUSE, GattServerRouting.route(open, null, sessionCount = 4, maxSessions = 4))
            val (session, _) = accepted()
            session.onSegment(data)
            assertEquals(Route.NEW, GattServerRouting.route(open, session, sessionCount = 4, maxSessions = 4))
        }

    @Test
    fun startOffersTheChannelBeforeItAnswersTheOpen() =
        runTest {
            val sink = Sink()
            val channel = GattStreamChannel.server(sink, StandardTestDispatcher(testScheduler), config)
            var sentWhenOffered = -1
            val taken =
                GattServerRouting.start(channel, open) {
                    sentWhenOffered = if (sink.sent.tryReceive().isFailure) 0 else 1
                    true
                }
            advanceUntilIdle()
            assertTrue(taken)
            assertEquals(0, sentWhenOffered, "nothing is answered before the channel has an owner")
            assertIs<GattSegment.OpenAck>(GattSegments.decode(sink.sent.receive()))
            assertTrue(channel.isOpen)
        }

    @Test
    fun startRefusesAChannelNobodyTakes() =
        runTest {
            val sink = Sink()
            val channel = GattStreamChannel.server(sink, StandardTestDispatcher(testScheduler), config)
            assertFalse(GattServerRouting.start(channel, open) { false })
            advanceUntilIdle()
            val reset = assertIs<GattSegment.Reset>(GattSegments.decode(sink.sent.receive()))
            assertEquals(GattSegments.RESET_REFUSED, reset.reason)
            assertTrue(sink.sent.tryReceive().isFailure, "no OPEN_ACK")
            // A repeat of the refused OPEN is that session's duplicate, not a new stream.
            assertTrue(channel.isRepeatedOpen(open))
        }
}
