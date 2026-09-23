package com.constrivo.drop.core.protocol

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryDataChannelTest {
    @Test
    fun bytesFlowBothWaysInOrder() =
        runTest {
            val (a, b) = InMemoryDataChannel.pair(kind = LinkKind.P2P)
            assertEquals(LinkKind.P2P, a.kind)
            a.write("0102030405".unhex())
            b.write("aa".unhex())
            val buffer = ByteArray(10)
            assertEquals(5, b.read(buffer))
            assertEquals("0102030405", buffer.copyOf(5).hex())
            assertEquals(1, a.read(buffer, 3, 7))
            assertEquals(0xAA.toByte(), buffer[3])
            assertEquals(5, a.bytesWritten)
            assertEquals(5, b.bytesRead)
        }

    @Test
    fun readChunkingCapsEachRead() =
        runTest {
            val (a, b) = InMemoryDataChannel.pair(secondChunking = ReadChunking.cycle(1, 3))
            a.write(ByteArray(10) { it.toByte() })
            val buffer = ByteArray(10)
            val sizes = ArrayList<Int>()
            var total = 0
            while (total < 10) {
                val n = b.read(buffer, total, 10 - total)
                sizes += n
                total += n
            }
            assertEquals(listOf(1, 3, 1, 3, 1, 1), sizes)
            assertContentEquals(ByteArray(10) { it.toByte() }, buffer)
            assertEquals(0, b.read(buffer, 0, 0))
        }

    @Test
    fun segmentsSplitLargeWrites() =
        runTest {
            val (a, b) = InMemoryDataChannel.pair(maxSegment = 4)
            a.write(ByteArray(10))
            val buffer = ByteArray(10)
            assertEquals(4, b.read(buffer))
            assertEquals(4, b.read(buffer))
            assertEquals(2, b.read(buffer))
        }

    @Test
    fun randomChunkingIsDeterministic() {
        val a = ReadChunking.random(seed = 9, max = 50)
        val b = ReadChunking.random(seed = 9, max = 50)
        val sizes = List(1000) { a.maxBytes(it) }
        assertEquals(sizes, List(1000) { b.maxBytes(it) })
        assertTrue(sizes.all { it in 1..50 })
        assertTrue(sizes.toSet().size > 30, "spread over the range")
        assertRejectsArgument { ReadChunking.random(1, 0) }
        assertRejectsArgument { ReadChunking.fixed(0) }
        assertRejectsArgument { ReadChunking.cycle() }
    }

    @Test
    fun writersSuspendWhenTheQueueIsFull() =
        runTest {
            val (a, b) = InMemoryDataChannel.pair(capacitySegments = 2, maxSegment = 1)
            var written = false
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    a.write(ByteArray(5))
                    written = true
                }
            yield()
            assertFalse(written, "three of five segments cannot be queued yet")
            val buffer = ByteArray(5)
            var total = 0
            while (total < 5) total += b.read(buffer, total, 5 - total)
            job.join()
            assertTrue(written)
        }

    @Test
    fun closeEndsTheStreamAfterQueuedBytes() =
        runTest {
            val (a, b) = InMemoryDataChannel.pair()
            a.write("0102".unhex())
            a.close()
            a.close() // idempotent
            assertTrue(a.isClosed)
            val buffer = ByteArray(4)
            assertEquals(2, b.read(buffer))
            assertEquals(-1, b.read(buffer))
            assertEquals(-1, a.read(buffer), "a closed end reads end of stream")
            assertFailsWith<IllegalStateException> { a.write(ByteArray(1)) }
            assertFailsWith<IllegalStateException> { b.write(ByteArray(1)) }
        }

    @Test
    fun closingUnblocksAPendingRead() =
        runTest {
            val (a, _) = InMemoryDataChannel.pair()
            var result = 0
            val job = launch(start = CoroutineStart.UNDISPATCHED) { result = a.read(ByteArray(4)) }
            a.close()
            job.join()
            assertEquals(-1, result)
        }

    @Test
    fun flushIsCounted() =
        runTest {
            val (a, _) = InMemoryDataChannel.pair()
            a.flush()
            a.flush()
            assertEquals(2, a.flushCount)
        }

    @Test
    fun rejectsBadArguments() =
        runTest {
            val (a, _) = InMemoryDataChannel.pair()
            assertFailsWith<IllegalArgumentException> { a.write(ByteArray(2), 1, 2) }
            assertFailsWith<IllegalArgumentException> { a.read(ByteArray(2), -1, 1) }
            assertFailsWith<IllegalArgumentException> { InMemoryDataChannel.pair(capacitySegments = 0) }
            assertFailsWith<IllegalArgumentException> { InMemoryDataChannel.pair(maxSegment = 0) }
        }
}
