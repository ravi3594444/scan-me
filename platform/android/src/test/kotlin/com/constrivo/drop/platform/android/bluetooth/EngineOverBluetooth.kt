package com.constrivo.drop.platform.android.bluetooth

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.handshake.HandshakeGuard
import com.constrivo.drop.core.crypto.handshake.LocalPeerInfo
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.transfer.SourceFile
import com.constrivo.drop.core.transfer.engine.EngineConfig
import com.constrivo.drop.core.transfer.engine.TransferEngine
import com.constrivo.drop.core.transfer.session.SecureSession
import com.constrivo.drop.core.transfer.session.SessionConfig
import com.constrivo.drop.core.transfer.session.SessionHandshake
import com.constrivo.drop.core.transfer.store.DirectoryFileStore
import com.constrivo.drop.platform.android.crypto.AndroidCryptoProvider
import com.constrivo.drop.platform.android.crypto.BackendSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Runs the real session handshake and transfer engine of `core/transfer` over a pair of Bluetooth channels, as the
 * transfer service will (WP7e): the channel is the primary connection of kind `BLUETOOTH`, and with no Wi-Fi link every
 * byte of the transfer goes over it. The phone side uses [AndroidCryptoProvider] on the BouncyCastle path.
 */
internal object EngineOverBluetooth {
    private val crypto: CryptoProvider = AndroidCryptoProvider(backends = BackendSelection.FALLBACK_ONLY, aesHardware = { false })

    private fun identity(): IdentityKey {
        val pair = crypto.generateEd25519()
        return object : IdentityKey {
            override val publicKey: ByteArray = pair.publicKey

            override fun sign(message: ByteArray): ByteArray = crypto.ed25519Sign(pair.privateKey, message)
        }
    }

    fun config(name: String): SessionConfig =
        SessionConfig(crypto, identity(), LocalPeerInfo(0, name, 0, AeadAlgorithm.CHACHA20_POLY1305), HandshakeGuard())

    /** The handshake over [channels]: the first end initiates (the device that connected), the second responds. */
    suspend fun handshake(
        channels: Pair<DataChannel, DataChannel>,
        initiator: SessionConfig = config("Sender"),
        responder: SessionConfig = config("Receiver"),
    ): Pair<SecureSession, SecureSession> =
        coroutineScope {
            val a = async { SessionHandshake.initiate(channels.first, initiator) }
            val b = async { SessionHandshake.respond(channels.second, responder) }
            a.await() to b.await()
        }

    /** Sends [files] over [channels] end to end and checks every received byte. */
    suspend fun transfer(
        channels: Pair<DataChannel, DataChannel>,
        files: List<Pair<String, ByteArray>>,
        timeoutMillis: Long = 60_000,
    ) {
        val dir = Files.createTempDirectory("drop-bt-engine")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val senderConfig = config("Sender")
            val receiverConfig = config("Receiver")
            withTimeout(timeoutMillis) {
                val (a, b) = handshake(channels, senderConfig, receiverConfig)
                assertContentEquals(receiverConfig.identity.publicKey, a.peerIdentityKey)
                assertContentEquals(senderConfig.identity.publicKey, b.peerIdentityKey)
                val store = DirectoryFileStore(dir.resolve("partials"), dir.resolve("received"))
                val sendEngine = TransferEngine(EngineConfig(senderConfig, store, lingerMillis = 200), scope)
                val receiveEngine = TransferEngine(EngineConfig(receiverConfig, store, lingerMillis = 200), scope)
                val sources = files.map { (name, bytes) -> MemorySource(name, bytes) }
                val sending = sendEngine.send(a, sources)
                val incoming = async { receiveEngine.receive(b) }.await()
                val receiving = incoming.accept()
                val sent = sending.await()
                val received = receiving.await()
                assertEquals(TransferPhase.DONE, sent.phase, "sender: $sent")
                assertEquals(TransferPhase.DONE, received.phase, "receiver: $received")
            }
            for ((name, bytes) in files) {
                val out = dir.resolve("received").resolve(name)
                assertEquals(sha256(bytes), sha256(Files.readAllBytes(out)), name)
            }
        } finally {
            scope.cancel()
            deleteTree(dir)
        }
    }

    fun randomBytes(
        size: Int,
        seed: Long,
    ): ByteArray = Random(seed).nextBytes(size)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private class MemorySource(
        override val name: String,
        val bytes: ByteArray,
    ) : SourceFile {
        override val size: Long get() = bytes.size.toLong()
        override val mimeType: String? get() = null

        override suspend fun read(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            return n
        }

        override suspend fun close() = Unit
    }
}
