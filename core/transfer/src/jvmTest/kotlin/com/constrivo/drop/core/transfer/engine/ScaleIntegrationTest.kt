package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.crypto.Aead
import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.toHex
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.transfer.MemorySource
import com.constrivo.drop.core.transfer.TestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bundling at scale (F-E7, T-02), nonce uniqueness with the real cipher (S7), and a loopback throughput floor. */
class ScaleIntegrationTest {
    private val nightly = System.getProperty("drop.nightly") == "true"

    /** Records every (key, nonce) pair the AEADs seal with, on both devices. */
    private class RecordingCrypto(
        private val delegate: CryptoProvider = JcaCryptoProvider(),
    ) : CryptoProvider by delegate {
        val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val seals = AtomicLong()
        val repeats = AtomicInteger()

        override fun aead(
            algorithm: AeadAlgorithm,
            key: ByteArray,
        ): Aead {
            val inner = delegate.aead(algorithm, key)
            val keyHex = key.toHex()
            return object : Aead by inner {
                override fun seal(
                    nonce: ByteArray,
                    plaintext: ByteArray,
                    aad: ByteArray,
                ): ByteArray {
                    record(nonce)
                    return inner.seal(nonce, plaintext, aad)
                }

                override fun seal(
                    nonce: ByteArray,
                    input: ByteArray,
                    inputOffset: Int,
                    inputLength: Int,
                    aad: ByteArray,
                    output: ByteArray,
                    outputOffset: Int,
                ): Int {
                    record(nonce)
                    return inner.seal(nonce, input, inputOffset, inputLength, aad, output, outputOffset)
                }

                private fun record(nonce: ByteArray) {
                    seals.incrementAndGet()
                    if (!seen.add(keyHex + nonce.toHex())) repeats.incrementAndGet()
                }
            }
        }
    }

    @Test
    fun `F-E7 thousands of small files travel in bundles into a per-drop folder, byte-exact`() =
        runBlocking<Unit> {
            val count = if (nightly) 5_000 else 1_500
            val random = Random(41)
            val files =
                (0 until count).map { i ->
                    val size = if (nightly) random.nextInt(20_000, 400_000) else random.nextInt(0, 24_000)
                    MemorySource("IMG_%05d.jpg".format(i), random.nextBytes(size), "image/jpeg")
                }
            EnginePair(primaryKind = LinkKind.BLUETOOTH).use { pair ->
                withTimeout(if (nightly) 600_000 else 60_000) {
                    val (sending, receiving) = pair.start(files)
                    var sawBundling = false
                    val watch = pair.scope.launch { receiving.progress.first { HintCode.BUNDLING in it.hints }.also { sawBundling = true } }
                    pair.attachTcp(sending, receiving, generation = 0)
                    val sent = sending.await()
                    val received = receiving.await()
                    watch.cancel()
                    assertEquals(TransferPhase.DONE, sent.phase, "sender: $sent")
                    assertEquals(TransferPhase.DONE, received.phase, "receiver: $received")
                    assertTrue(sawBundling || HintCode.BUNDLING in sending.progress.value.hints || sent.fileCount > 0)
                    assertEquals(files.sumOf { it.size }, sending.stats.fileBytesSent, "no file byte sent twice")
                    assertTrue(sending.offer.bundleCount > 0)
                }
                // More than 20 files: one subfolder for the drop (design §9).
                val folders = Files.list(pair.received).use { s -> s.toList() }
                assertEquals(1, folders.size, "one per-drop folder")
                val folder = folders.single()
                assertTrue(Files.isDirectory(folder))
                for (file in files) {
                    assertEquals(TestSupport.sha256(file.bytes), TestSupport.sha256(folder.resolve(file.name)), file.name)
                }
            }
        }

    @Test
    fun `S7 no key and nonce pair repeats across a whole multi-stream transfer with the real cipher`() =
        runBlocking<Unit> {
            val crypto = RecordingCrypto()
            val files =
                listOf(
                    MemorySource("one.bin", TestSupport.randomBytes(24 * ProtocolConstants.MIB + 3, 51)),
                    MemorySource("two.bin", TestSupport.randomBytes(9 * ProtocolConstants.MIB, 52)),
                ) + (0 until 16).map { MemorySource("small-$it.txt", TestSupport.randomBytes(5_000 + it, 60L + it)) }
            EnginePair(primaryKind = LinkKind.BLUETOOTH, crypto = crypto).use { pair ->
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    pair.attachTcp(sending, receiving, generation = 0)
                    receiving.progress.first { it.streams >= 2 || it.isTerminal }
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                }
                assertTrue(crypto.seals.get() > 500, "sealed ${crypto.seals.get()} AEAD messages")
                assertEquals(0, crypto.repeats.get(), "a (key, nonce) pair repeated")
                for (file in files) {
                    assertEquals(
                        TestSupport.sha256(file.bytes),
                        TestSupport.sha256(pair.receivedFile(file.name)),
                        file.name,
                    )
                }
            }
        }

    @Test
    fun `F-E8 loopback throughput over TCP streams, printed, with a floor against pathologies`() =
        runBlocking<Unit> {
            val size = if (nightly) 1024 * ProtocolConstants.MIB else 128 * ProtocolConstants.MIB
            val bytes = ByteArray(size).also { Random(71).nextBytes(it) }
            val files = listOf(MemorySource("big.bin", bytes))
            EnginePair(primaryKind = LinkKind.BLUETOOTH, primaryBytesPerSecond = 50_000).use { pair ->
                var seconds = 0.0
                withTimeout(300_000) {
                    val (sending, receiving) = pair.start(files)
                    val started = System.nanoTime()
                    pair.attachTcp(sending, receiving, generation = 0)
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                    seconds = (System.nanoTime() - started) / 1e9
                }
                val mbps = size / 1e6 / seconds
                println("loopback: ${size / ProtocolConstants.MIB} MiB in %.2f s = %.1f MB/s".format(seconds, mbps))
                assertTrue(mbps >= 20.0, "loopback throughput %.1f MB/s is below the 20 MB/s floor".format(mbps))
                assertEquals(TestSupport.sha256(bytes), TestSupport.sha256(pair.receivedFile("big.bin")))
            }
        }
}
