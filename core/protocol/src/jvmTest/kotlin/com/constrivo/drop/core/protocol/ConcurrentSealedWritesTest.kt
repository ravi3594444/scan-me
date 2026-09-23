package com.constrivo.drop.core.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Real threads: coroutines on [Dispatchers.Default] share one [FrameWriter] for one stream, as control, acks,
 * heartbeats and chunks do once control has moved onto the first Wi-Fi stream (N13). Every frame must open against
 * the receiver's per-stream counter, which holds only if frames reach the wire in the order they were sealed.
 */
class ConcurrentSealedWritesTest {
    @Test
    fun framesSealedByManyThreadsOpenInWireOrder() =
        runBlocking {
            repeat(ROUNDS) { round ->
                val sender = ToyProtector(sendKey = 0xA11CE, receiveKey = 0xB0B, blockSize = 64)
                val receiver = ToyProtector(sendKey = 0xB0B, receiveKey = 0xA11CE, blockSize = 64)
                val (a, b) = InMemoryDataChannel.pair(maxSegment = 97, secondChunking = ReadChunking.random(round.toLong(), 300))
                val writer = FrameWriter(a, bufferSize = 512)
                val streamId = ProtocolConstants.STREAM_ID_FIRST_WIFI
                val received =
                    withContext(Dispatchers.Default) {
                        coroutineScope {
                            val writers =
                                List(WRITERS) { w ->
                                    async {
                                        repeat(FRAMES_PER_WRITER) { i ->
                                            when (i % 3) {
                                                0 -> {
                                                    writer.writeControl(sender, streamId, Heartbeat(w * 1_000L + i), flush = i % 2 == 0)
                                                }

                                                1 -> {
                                                    val hash = ChunkHash(ByteArray(16))
                                                    val header = ChunkHeader(TEST_ID, w, i, payloadLength = 100, hash = hash)
                                                    writer.writeChunk(sender, streamId, header, ByteArray(100) { w.toByte() })
                                                }

                                                else -> {
                                                    // A plaintext in the middle of a larger buffer, as a pooled buffer would hold it.
                                                    val buffer = ByteArray(8) + ControlCodec.encode(Heartbeat(i.toLong()))
                                                    writer.writeProtected(
                                                        sender,
                                                        FrameType.CONTROL,
                                                        streamId,
                                                        buffer,
                                                        offset = 8,
                                                        length = buffer.size - 8,
                                                        flush = false,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            launch {
                                writers.awaitAll()
                                writer.flush()
                                a.close()
                            }
                            val reader = FrameReader(b, limits = FrameLimits.SESSION)
                            var frames = 0
                            while (true) {
                                val frame = reader.readFrame() ?: break
                                // Throws ProtocolException("authentication failed") on the first frame out of order.
                                when (frame.type) {
                                    FrameType.CONTROL -> receiver.openControl(frame, streamId)
                                    else -> receiver.openChunk(frame, streamId)
                                }
                                frames++
                            }
                            frames
                        }
                    }
                assertEquals(WRITERS * FRAMES_PER_WRITER, received, "round $round")
            }
        }

    private companion object {
        const val ROUNDS = 20
        const val WRITERS = 8
        const val FRAMES_PER_WRITER = 200
    }
}
