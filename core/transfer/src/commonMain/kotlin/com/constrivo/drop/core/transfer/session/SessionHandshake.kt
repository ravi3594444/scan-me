package com.constrivo.drop.core.transfer.session

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.handshake.ExpectedPeer
import com.constrivo.drop.core.crypto.handshake.HandshakeClock
import com.constrivo.drop.core.crypto.handshake.HandshakeException
import com.constrivo.drop.core.crypto.handshake.HandshakeGuard
import com.constrivo.drop.core.crypto.handshake.HandshakeInitiator
import com.constrivo.drop.core.crypto.handshake.HandshakeRandomness
import com.constrivo.drop.core.crypto.handshake.HandshakeResponder
import com.constrivo.drop.core.crypto.handshake.HandshakeResult
import com.constrivo.drop.core.crypto.handshake.LocalPeerInfo
import com.constrivo.drop.core.crypto.handshake.TrustedPeerLookup
import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.Frame
import com.constrivo.drop.core.protocol.FrameLimits
import com.constrivo.drop.core.protocol.FrameReader
import com.constrivo.drop.core.protocol.FrameType
import com.constrivo.drop.core.protocol.FrameWriter
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.protocol.StreamPurpose
import com.constrivo.drop.core.protocol.TrustShare
import com.constrivo.drop.core.protocol.writeProtected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a device needs to run the session handshake (architecture §6.2 as changed by N1–N3, S3).
 *
 * @property guard the device's one [HandshakeGuard], shared by every responder on every transport.
 * @property trustedPeers recognition secrets of trusted peers: the responder checks trusted proofs with them, and a
 *   reconnecting initiator ([SessionHandshake.initiate] with a known peer) proves the pairing with them.
 * @property requireTrustedProof Trusted-only visibility (§5.3): an untrusted `Hello` is refused.
 * @property timeoutMillis the whole handshake, Finished exchange included, must complete within this.
 * @property trustShare S3: the `TrustShare` (this device's advertising secret `k_adv`) to send once the peer's
 *   Finished verified, or null when the peer is not trusted. A first pairing is not trusted until the users confirmed
 *   the SAS; the app then calls [SecureSession.sendTrustShare].
 */
class SessionConfig(
    val crypto: CryptoProvider,
    val identity: IdentityKey,
    val local: LocalPeerInfo,
    val guard: HandshakeGuard,
    val trustedPeers: TrustedPeerLookup = TrustedPeerLookup.NONE,
    val requireTrustedProof: Boolean = false,
    val randomness: HandshakeRandomness = HandshakeRandomness.secure(crypto),
    val clock: HandshakeClock = guard.clock,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val trustShare: (HandshakeResult) -> TrustShare? = { null },
) {
    init {
        require(timeoutMillis > 0) { "timeout must be positive" }
    }

    /** The [ExpectedPeer] for reaching [identityKey] again (N3), with its recognition secret when it is trusted. */
    fun expectedPeer(identityKey: ByteArray): ExpectedPeer =
        ExpectedPeer(identityKey, trustedPeers.recognitionSecretFor(identityKey.copyOf()))

    companion object {
        /** Ten times the 400 ms budget of §15, for slow GATT links. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 10_000
    }
}

/**
 * A session could not be established or broke: the channel closed or timed out during the handshake, or the peer sent
 * frames that are not a handshake. A [HandshakeException] (refused by the crypto layer, with its reason) is thrown as
 * it is, so the caller can tell a wrong peer ([com.constrivo.drop.core.crypto.handshake.HandshakeFailure.PEER_IDENTITY_MISMATCH])
 * from a broken link.
 */
class SessionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Runs the handshake of architecture §6.2 over a [DataChannel] (spec changes N1, N2, N3, S3):
 *
 * ```
 * initiator                                  responder
 *   HELLO        ─────────────────────────▶
 *                ◀─────────────────────────  HELLO_ACK
 *   HELLO_REVEAL ─────────────────────────▶
 *   FINISHED (stream 0, counter 0) ───────▶
 *                ◀───────  FINISHED (stream 0, counter 0)
 * ```
 *
 * Each side sends its Finished as the first protected frame on stream 0 and acts on nothing from the peer before
 * [HandshakeResult.verifyPeerFinished] accepted the peer's (N2). Then, if [SessionConfig.trustShare] names one, the
 * `TrustShare` goes out as the first control message (S3). Frames are read with [FrameLimits.HANDSHAKE] until then, so an
 * unauthenticated peer can make this side allocate at most 4 KiB per frame.
 *
 * Every reconnect runs this again with the peer's identity as the expected identity (N3). On any failure the channel is
 * closed and the responder's attempt is settled ([HandshakeResponder.abort]).
 */
object SessionHandshake {
    /**
     * Runs the initiator side over [channel]. With [expectedPeer] (a resolved beacon, a scanned QR code, or a reconnect)
     * the handshake fails unless the responder has that identity.
     *
     * @throws HandshakeException if the crypto layer refused the handshake.
     * @throws SessionException if the channel failed, closed or timed out, or the peer broke the framing.
     */
    suspend fun initiate(
        channel: DataChannel,
        config: SessionConfig,
        expectedPeer: ExpectedPeer? = null,
        kind: LinkKind = channel.kind,
    ): SecureSession {
        val initiator = HandshakeInitiator(config.crypto, config.identity, config.local, expectedPeer, config.randomness, config.clock)
        return run(channel, config, kind, onFailure = {}) { reader, writer ->
            writer.writeFrame(FrameType.HELLO, initiator.start())
            val ack = expect(reader, FrameType.HELLO_ACK)
            writer.writeFrame(FrameType.HELLO_REVEAL, initiator.receiveHelloAck(ack.payload))
            initiator.result
        }
    }

    /**
     * Runs the responder side over [channel]. With [expectedPeerIdentity] (a reconnect, N3) the handshake fails unless
     * the initiator has that identity.
     *
     * @throws HandshakeException if the crypto layer refused the handshake.
     * @throws SessionException if the channel failed, closed or timed out, or the peer broke the framing.
     */
    suspend fun respond(
        channel: DataChannel,
        config: SessionConfig,
        expectedPeerIdentity: ByteArray? = null,
        kind: LinkKind = channel.kind,
    ): SecureSession {
        val responder =
            HandshakeResponder(
                config.crypto,
                config.identity,
                config.local,
                config.guard,
                config.trustedPeers,
                config.requireTrustedProof,
                expectedPeerIdentity,
                config.randomness,
            )
        return run(channel, config, kind, onFailure = { responder.abort() }) { reader, writer ->
            val hello = expect(reader, FrameType.HELLO)
            writer.writeFrame(FrameType.HELLO_ACK, responder.receiveHello(hello.payload))
            val reveal = expect(reader, FrameType.HELLO_REVEAL)
            responder.receiveHelloReveal(reveal.payload)
        }
    }

    private suspend fun run(
        channel: DataChannel,
        config: SessionConfig,
        kind: LinkKind,
        onFailure: () -> Unit,
        exchange: suspend (FrameReader, FrameWriter) -> HandshakeResult,
    ): SecureSession {
        val reader = FrameReader(channel, limits = FrameLimits.HANDSHAKE)
        val writer = FrameWriter(channel)
        var succeeded = false
        try {
            val session =
                withTimeoutOrNull(config.timeoutMillis) {
                    val result = exchange(reader, writer)
                    val ciphers = SessionCiphers(result)
                    val protector = CipherFrameProtector(ciphers, SecureSession.PRIMARY_STREAMS)
                    writer.writeProtected(protector, FrameType.FINISHED, ProtocolConstants.STREAM_ID_CONTROL, result.localFinished())
                    val finished = expect(reader, FrameType.FINISHED)
                    val mac = protector.open(FrameType.FINISHED, ProtocolConstants.STREAM_ID_CONTROL, finished.payload)
                    result.verifyPeerFinished(mac)
                    reader.limits = FrameLimits.SESSION
                    val primary =
                        SecureConnection(
                            channel,
                            kind,
                            protector,
                            ProtocolConstants.STREAM_ID_CONTROL,
                            ProtocolConstants.STREAM_ID_BLUETOOTH,
                            generation = null,
                            purpose = StreamPurpose.CONTROL,
                            open = null,
                            reader = reader,
                            writer = writer,
                        )
                    val secure = SecureSession(result, primary, ciphers)
                    config.trustShare(result)?.let { secure.sendTrustShare(it) }
                    secure
                } ?: throw SessionException("handshake did not complete within ${config.timeoutMillis} ms")
            succeeded = true
            return session
        } catch (e: HandshakeException) {
            throw e
        } catch (e: SessionException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SessionException("handshake failed: ${e.message ?: e::class.simpleName}", e)
        } finally {
            if (!succeeded) {
                onFailure()
                withContext(NonCancellable) { runCatching { channel.close() } }
            }
        }
    }

    private suspend fun expect(
        reader: FrameReader,
        type: FrameType,
    ): Frame {
        val frame = reader.readFrame() ?: throw SessionException("channel closed during the handshake, waiting for $type")
        if (frame.type != type) throw SessionException("expected $type during the handshake, got ${frame.type}")
        return frame
    }
}
