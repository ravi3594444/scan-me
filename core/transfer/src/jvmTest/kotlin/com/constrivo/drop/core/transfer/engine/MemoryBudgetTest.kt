package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.transfer.TestSupport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Architecture §15: a large transfer adds at most 120 MB of memory. Runs in its own test JVM under a fixed 320 MiB
 * heap (`jvmMemoryTest` in the module's build file), with both engines in one process, over loopback TCP, from a file on
 * disk to the Received folder. CI moves 256 MiB; the nightly job (`-Pdrop.nightly=true`) moves 2 GiB.
 *
 * The measure is the heap in use right after each garbage collection (the live data, sampled every 50 ms), compared with
 * the same measure before the transfer.
 */
class MemoryBudgetTest {
    private val nightly = System.getProperty("drop.nightly") == "true"

    private fun heapAfterGc(): Long =
        ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == MemoryType.HEAP }
            .sumOf { it.collectionUsage?.used ?: 0L }

    @Test
    fun `a large loopback transfer adds at most 120 MB of heap`() {
        val size = if (nightly) 2048L * ProtocolConstants.MIB else 256L * ProtocolConstants.MIB
        EnginePair(primaryKind = LinkKind.LAN).use { pair ->
            // The source file, written in 1 MiB blocks so the test itself holds no large arrays.
            val source = pair.dir.resolve("source.bin")
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(source, StandardOpenOption.CREATE_NEW).use { out ->
                val random = Random(81)
                val block = ByteArray(ProtocolConstants.MIB)
                var written = 0L
                while (written < size) {
                    random.nextBytes(block)
                    val n = minOf(block.size.toLong(), size - written).toInt()
                    out.write(block, 0, n)
                    digest.update(block, 0, n)
                    written += n
                }
            }
            val expected = digest.digest().joinToString("") { "%02x".format(it) }

            System.gc()
            Thread.sleep(200)
            System.gc()
            val baseline = heapAfterGc()
            val peak = java.util.concurrent.atomic.AtomicLong(baseline)
            val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
            val sampler =
                thread(name = "heap-sampler", isDaemon = true) {
                    while (sampling.get()) {
                        peak.accumulateAndGet(heapAfterGc(), ::maxOf)
                        Thread.sleep(50)
                    }
                }
            val started = System.nanoTime()
            runBlocking {
                withTimeout(if (nightly) 1_200_000 else 300_000) {
                    val files = listOf(pair.senderStore.source(source, "big.bin", "application/octet-stream"))
                    val (sending, receiving) = pair.start(files)
                    pair.attachTcp(sending, receiving, generation = 0)
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                }
            }
            val seconds = (System.nanoTime() - started) / 1e9
            sampling.set(false)
            sampler.join()
            val added = (peak.get() - baseline) / 1e6
            println(
                "memory: ${size / ProtocolConstants.MIB} MiB in %.1f s (%.0f MB/s), heap after GC %.1f MB at start, peak %.1f MB, added %.1f MB"
                    .format(seconds, size / 1e6 / seconds, baseline / 1e6, peak.get() / 1e6, added),
            )
            assertTrue(added <= 120.0, "the transfer added %.1f MB of live heap (budget 120 MB)".format(added))
            assertEquals(expected, TestSupport.sha256(pair.receivedFile("big.bin")))
        }
    }
}
