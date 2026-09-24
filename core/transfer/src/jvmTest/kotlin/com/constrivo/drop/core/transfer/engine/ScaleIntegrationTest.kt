package com.constrivo.drop.core.transfer.engine

import com.constrivo.drop.core.crypto.Aead
import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.frame.FrameLimitException
import com.constrivo.drop.core.crypto.toHex
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.transfer.MemorySource
import com.constrivo.drop.core.transfer.TestSupport
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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

        /** (key, stream id) of every seal: the stream id is the nonce's first four bytes (S7). */
        val streams: MutableSet<Pair<String, Int>> = ConcurrentHashMap.newKeySet()

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
                    val streamId =
                        ((nonce[0].toInt() and 0xFF) shl 24) or ((nonce[1].toInt() and 0xFF) shl 16) or
                            ((nonce[2].toInt() and 0xFF) shl 8) or (nonce[3].toInt() and 0xFF)
                    streams += keyHex to streamId
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
                    val receiverSaw = CompletableDeferred<Unit>()
                    val senderSaw = CompletableDeferred<Unit>()
                    val watch =
                        pair.scope.launch {
                            launch { receiving.progress.first { HintCode.BUNDLING in it.hints }.also { receiverSaw.complete(Unit) } }
                            launch { sending.progress.first { HintCode.BUNDLING in it.hints }.also { senderSaw.complete(Unit) } }
                        }
                    pair.attachTcp(sending, receiving, generation = 0)
                    val sent = sending.await()
                    val received = receiving.await()
                    watch.cancel()
                    assertEquals(TransferPhase.DONE, sent.phase, "sender: $sent")
                    assertEquals(TransferPhase.DONE, received.phase, "receiver: $received")
                    assertTrue(senderSaw.isCompleted, "the sender shows the bundling hint")
                    assertTrue(receiverSaw.isCompleted, "the receiver shows the sender's bundling hint")
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
    fun `S7 no key and nonce pair repeats across a multi-stream transfer and a reconnect with the real cipher`() =
        runBlocking<Unit> {
            val crypto = RecordingCrypto()
            val wifiSeals = AtomicInteger()
            // The eighth chunk sealed on a Wi-Fi stream finds its key at the limit: a new handshake (N3), new keys.
            val hooks =
                DebugHooks(beforeChunkSealed = { streamId ->
                    if (streamId >= 2 && wifiSeals.incrementAndGet() == 8) throw FrameLimitException("stream reached its limit (test)")
                })
            val files =
                listOf(
                    MemorySource("one.bin", TestSupport.randomBytes(48 * ProtocolConstants.MIB + 3, 51)),
                    MemorySource("two.bin", TestSupport.randomBytes(9 * ProtocolConstants.MIB, 52)),
                ) + (0 until 16).map { MemorySource("small-$it.txt", TestSupport.randomBytes(5_000 + it, 60L + it)) }
            EnginePair(primaryKind = LinkKind.BLUETOOTH, crypto = crypto, debug = hooks).use { pair ->
                withTimeout(60_000) {
                    val (sending, receiving) = pair.start(files)
                    pair.attachTcp(sending, receiving, generation = 0)
                    pair.offerReconnect()
                    // The first session dies with its link; the second one gets a link of its own.
                    while (sending.stats.sessionEpoch < 1 || receiving.stats.sessionEpoch < 1) delay(10)
                    sending.progress.first { it.phase.isConnected }
                    pair.attachTcp(sending, receiving, generation = 1)
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                    assertEquals(1, sending.stats.sessionEpoch)
                }
                assertTrue(crypto.seals.get() > 500, "sealed ${crypto.seals.get()} AEAD messages")
                assertEquals(0, crypto.repeats.get(), "a (key, nonce) pair repeated")
                val keys = crypto.streams.map { it.first }.toSet()
                assertTrue(keys.size >= 4, "two sessions, two directions each: ${keys.size} keys")
                val dataStreams = crypto.streams.filter { it.second >= 2 }
                assertTrue(dataStreams.size >= 4, "sealed on ${dataStreams.size} Wi-Fi (key, stream) pairs")
                assertTrue(dataStreams.map { it.first }.toSet().size >= 2, "Wi-Fi streams in both sessions")
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
    fun `F-D4 a removable destination shows the sdcard hint on both devices`() =
        runBlocking<Unit> {
            EnginePair(primaryKind = LinkKind.LAN, primaryBytesPerSecond = 4L * ProtocolConstants.MIB, receiverStore = { dir ->
                DirectoryFileStore(dir.resolve("partials"), dir.resolve("received"), removable = true)
            }).use { pair ->
                val files = listOf(MemorySource("clip.mp4", TestSupport.randomBytes(9 * ProtocolConstants.MIB, 53)))
                withTimeout(30_000) {
                    val (sending, receiving) = pair.start(files)
                    val receiverSaw = CompletableDeferred<Unit>()
                    val senderSaw = CompletableDeferred<Unit>()
                    val watch =
                        pair.scope.launch {
                            launch { receiving.progress.first { HintCode.SDCARD in it.hints }.also { receiverSaw.complete(Unit) } }
                            launch { sending.progress.first { HintCode.SDCARD in it.hints }.also { senderSaw.complete(Unit) } }
                        }
                    assertEquals(TransferPhase.DONE, sending.await().phase)
                    assertEquals(TransferPhase.DONE, receiving.await().phase)
                    watch.cancel()
                    assertTrue(receiverSaw.isCompleted, "the receiver shows its own sdcard hint")
                    assertTrue(senderSaw.isCompleted, "the sender shows the receiver's sdcard hint")
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
