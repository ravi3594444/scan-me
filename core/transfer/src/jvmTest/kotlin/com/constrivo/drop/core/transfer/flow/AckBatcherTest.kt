package com.constrivo.drop.core.transfer.flow

import com.constrivo.drop.core.protocol.Ack
import com.constrivo.drop.core.protocol.ChunkRef
import com.constrivo.drop.core.protocol.InMemoryDataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.TransferClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §7.2 ack batching under virtual time: 8 refs or 50 ms, urgent Bluetooth blocks at once (S1). */
@OptIn(ExperimentalCoroutinesApi::class)
class AckBatcherTest {
    private val id = TransferId(ByteArray(TransferId.SIZE) { it.toByte() })

    private class Sent(
        val ack: Ack,
        val atMillis: Long,
    )

    private fun TestScope.batcher(
        sent: MutableList<Sent>,
        fail: () -> Boolean = { false },
    ): AckBatcher =
        AckBatcher(id, backgroundScope, TransferClock { testScheduler.currentTime }) { ack ->
            if (fail()) throw IOException("control route gone")
            sent += Sent(ack, testScheduler.currentTime)
        }

    private fun ref(chunk: Int) = ChunkRef(0, chunk)

    @Test
    fun `eight references go out at once`() =
        runTest {
            val sent = ArrayList<Sent>()
            val acks = batcher(sent)
            repeat(8) { acks.add(ref(it)) }
            runCurrent()
            assertEquals(1, sent.size)
            assertEquals((0 until 8).map { ref(it) }, sent[0].ack.chunks)
            assertEquals(0, sent[0].atMillis)
            assertEquals(id, sent[0].ack.transferId)
        }

    @Test
    fun `fewer references wait 50 ms after the first one`() =
        runTest {
            val sent = ArrayList<Sent>()
            val acks = batcher(sent)
            acks.add(ref(0))
            advanceTimeBy(30)
            acks.add(ref(1))
            advanceTimeBy(19)
            runCurrent()
            assertTrue(sent.isEmpty(), "still batching at 49 ms")
            advanceTimeBy(2)
            runCurrent()
            assertEquals(1, sent.size)
            assertEquals(50, sent[0].atMillis)
            assertEquals(listOf(ref(0), ref(1)), sent[0].ack.chunks)
            assertEquals(1, acks.batchesSent)
        }

    @Test
    fun `a wall clock stepped back an hour does not hold the batch`() =
        runTest {
            val sent = ArrayList<Sent>()
            var stepped = false
            val clock =
                object : TransferClock {
                    // NTP moves the wall clock back once the batch is open; the monotonic time line is not affected.
                    override fun nowMillis(): Long = testScheduler.currentTime - if (stepped) 3_600_000 else 0

                    override fun elapsedMillis(): Long = testScheduler.currentTime
                }
            val acks = AckBatcher(id, backgroundScope, clock) { sent += Sent(it, testScheduler.currentTime) }
            acks.add(ref(0))
            runCurrent()
            stepped = true
            advanceTimeBy(51)
            runCurrent()
            assertEquals(1, sent.size, "the partial batch still left after 50 ms")
            assertEquals(50, sent[0].atMillis)
        }

    @Test
    fun `an urgent Bluetooth block flushes the batch without waiting`() =
        runTest {
            val sent = ArrayList<Sent>()
            val acks = batcher(sent)
            acks.add(ref(0))
            advanceTimeBy(10)
            acks.add(ChunkRef(1, 0, blockOffset = 16_384), urgent = true)
            runCurrent()
            assertEquals(1, sent.size)
            assertEquals(10, sent[0].atMillis)
            assertEquals(listOf(ref(0), ChunkRef(1, 0, blockOffset = 16_384)), sent[0].ack.chunks)
        }

    @Test
    fun `a failed send drops that batch and batching continues`() =
        runTest {
            val sent = ArrayList<Sent>()
            var failing = true
            val acks = batcher(sent) { failing }
            acks.add(ref(0), urgent = true)
            runCurrent()
            failing = false
            acks.add(ref(1), urgent = true)
            runCurrent()
            assertEquals(listOf(listOf(ref(1))), sent.map { it.ack.chunks })
        }

    @Test
    fun `closing sends what is pending and drops what comes later`() =
        runTest {
            val sent = ArrayList<Sent>()
            val acks = batcher(sent)
            acks.add(ref(0))
            acks.add(ref(1))
            acks.closeAndJoin()
            acks.add(ref(2))
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf(listOf(ref(0), ref(1))), sent.map { it.ack.chunks })
            assertEquals(0, sent[0].atMillis, "a close does not wait for the delay")
        }
}

/** §4 and F-F1: the receiver counts Wi-Fi bytes as the socket delivers them, the sender as it writes them. */
class ArrivalMeterTest {
    @Test
    fun `bytes read through a counting channel are drained per kind`() =
        runTest {
            val meter = ArrivalMeter()
            val (a, b) = InMemoryDataChannel.pair(LinkKind.LAN)
            val counted = CountingChannel(b, onRead = meter::add)
            a.write(ByteArray(10_000))
            val buffer = ByteArray(4_000)
            var got = 0
            while (got < 10_000) got += counted.read(buffer, 0, buffer.size)
            meter.add(LinkKind.BLUETOOTH, 16_384)
            meter.add(LinkKind.BLUETOOTH, 0)
            assertEquals(mapOf(LinkKind.LAN to 10_000L, LinkKind.BLUETOOTH to 16_384L), meter.drain())
            assertTrue(meter.drain().isEmpty(), "a drain resets the counts")
            assertEquals(LinkKind.LAN, counted.kind)
            counted.close()
        }

    @Test
    fun `writes are counted in slices as they go out`() =
        runTest {
            val (a, b) = InMemoryDataChannel.pair(LinkKind.P2P, capacitySegments = 64)
            val counts = ArrayList<Long>()
            val counted = CountingChannel(a, onWrite = { _, n -> counts += n }, writeSlice = 1_000)
            counted.write(ByteArray(2_500))
            assertEquals(listOf(1_000L, 1_000L, 500L), counts)
            val buffer = ByteArray(2_500)
            var got = 0
            while (got < 2_500) got += b.read(buffer, got, buffer.size - got)
            counted.close()
        }
}
