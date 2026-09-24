package com.constrivo.drop.core.transfer

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.crypto.handshake.HandshakeGuard
import com.constrivo.drop.core.crypto.handshake.LocalPeerInfo
import com.constrivo.drop.core.crypto.handshake.TrustedPeerLookup
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.InMemoryDataChannel
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.session.SecureSession
import com.constrivo.drop.core.transfer.session.SessionConfig
import com.constrivo.drop.core.transfer.session.SessionHandshake
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.random.Random

/** Shared fixtures for the transfer tests. */
internal object TestSupport {
    val crypto: CryptoProvider = JcaCryptoProvider()

    fun identity(provider: CryptoProvider = crypto): IdentityKey {
        val pair = provider.generateEd25519()
        return object : IdentityKey {
            override val publicKey: ByteArray = pair.publicKey

            override fun sign(message: ByteArray): ByteArray = provider.ed25519Sign(pair.privateKey, message)
        }
    }

    fun sessionConfig(
        name: String,
        identity: IdentityKey = identity(),
        provider: CryptoProvider = crypto,
        trusted: TrustedPeerLookup = TrustedPeerLookup.NONE,
        aead: AeadAlgorithm = AeadAlgorithm.AES_256_GCM,
    ): SessionConfig = SessionConfig(provider, identity, LocalPeerInfo(0, name, 0, aead), HandshakeGuard(), trusted)

    /** Runs the handshake over [channels]: the first end initiates, the second responds. */
    suspend fun handshake(
        initiator: SessionConfig,
        responder: SessionConfig,
        channels: Pair<DataChannel, DataChannel>,
    ): Pair<SecureSession, SecureSession> =
        coroutineScope {
            val a = async { SessionHandshake.initiate(channels.first, initiator) }
            val b = async { SessionHandshake.respond(channels.second, responder) }
            a.await() to b.await()
        }

    fun memoryPair(
        kind: LinkKind = LinkKind.LAN,
        capacitySegments: Int = 64,
    ): Pair<InMemoryDataChannel, InMemoryDataChannel> = InMemoryDataChannel.pair(kind, capacitySegments = capacitySegments)

    fun randomBytes(
        size: Int,
        seed: Long,
    ): ByteArray = Random(seed).nextBytes(size)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun tempDir(prefix: String): Path = Files.createTempDirectory("drop-$prefix-")

    fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
    }
}

/** A [SourceFile] over bytes in memory. */
internal class MemorySource(
    override val name: String,
    val bytes: ByteArray,
    override val mimeType: String? = null,
) : SourceFile {
    var reads = 0
        private set

    override val size: Long get() = bytes.size.toLong()

    override suspend fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        reads++
        if (position >= bytes.size) return -1
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
        return n
    }

    override suspend fun close() = Unit
}

/**
 * A [DataChannel] that can go silent without closing ([stall]): writes are swallowed and reads wait forever, like a
 * phone that walked out of range (the heartbeat watchdog must notice, §7.8).
 */
internal class StallableChannel(
    private val delegate: DataChannel,
) : DataChannel by delegate {
    @Volatile
    var stalled = false

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (stalled) kotlinx.coroutines.awaitCancellation()
        val n = delegate.read(buffer, offset, length)
        if (stalled) kotlinx.coroutines.awaitCancellation()
        return n
    }

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (!stalled) delegate.write(buffer, offset, length)
    }
}

/**
 * A [DataChannel] wrapper that limits the write rate to [bytesPerSecond] (a simulated Bluetooth link) and can be cut
 * mid-stream after [cutAfterBytes] written bytes (a simulated link loss).
 */
internal class FaultyChannel(
    private val delegate: DataChannel,
    private val bytesPerSecond: Long? = null,
    @Volatile var cutAfterBytes: Long? = null,
    private val onCut: suspend () -> Unit = {},
) : DataChannel by delegate {
    @Volatile
    var written: Long = 0
        private set

    @Volatile
    var cut = false
        private set

    private var credit = 0.0

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        var from = offset
        var remaining = length
        while (remaining > 0) {
            val limit = cutAfterBytes
            if (cut) throw java.io.IOException("link cut")
            val n =
                if (limit != null) {
                    val left = limit - written
                    if (left <= 0) {
                        cutNow()
                        throw java.io.IOException("link cut")
                    }
                    minOf(remaining.toLong(), left).toInt()
                } else {
                    remaining
                }
            val rate = bytesPerSecond
            if (rate != null) {
                // Pace in slices so a large write takes as long as the link would need.
                var sliceFrom = from
                var sliceLeft = n
                while (sliceLeft > 0) {
                    val slice = minOf(sliceLeft, SLICE)
                    credit += slice * 1000.0 / rate
                    val wait = credit.toLong()
                    if (wait > 0) {
                        delay(wait)
                        credit -= wait
                    }
                    delegate.write(buffer, sliceFrom, slice)
                    sliceFrom += slice
                    sliceLeft -= slice
                }
            } else {
                delegate.write(buffer, from, n)
            }
            written += n
            from += n
            remaining -= n
        }
    }

    /** Cuts the link now: both directions fail. */
    suspend fun cutNow() {
        if (cut) return
        cut = true
        onCut()
        delegate.close()
    }

    private companion object {
        const val SLICE = 1024
    }
}

/**
 * A [DataChannel] that can go silent the way a Wi-Fi Direct group does out of range ([stall]): nothing is closed, but
 * from then on writes block and reads wait until this end is closed (then they fail and end), as a TCP socket without
 * RST or FIN behaves until the kernel gives up.
 */
internal class SilentChannel(
    private val delegate: DataChannel,
) : DataChannel by delegate {
    private val closed = kotlinx.coroutines.CompletableDeferred<Unit>()

    @Volatile
    private var silent = false

    fun stall() {
        silent = true
    }

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (silent) {
            closed.await()
            return -1
        }
        val n = delegate.read(buffer, offset, length)
        if (silent) {
            closed.await()
            return -1
        }
        return n
    }

    override suspend fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (silent) {
            closed.await()
            throw java.io.IOException("closed")
        }
        delegate.write(buffer, offset, length)
    }

    override suspend fun close() {
        closed.complete(Unit)
        delegate.close()
    }
}
