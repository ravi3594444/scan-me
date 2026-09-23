package com.constrivo.drop.core.crypto.handshake

import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.IdentityKey
import com.constrivo.drop.core.crypto.TestFixtures

/** Builds the standard two parties of the handshake tests from fixed keys, and runs exchanges between them. */
object HandshakeHarness {
    val crypto: CryptoProvider = TestFixtures.crypto

    /** A phone: caps 0x0819, "Ananya’s Pixel" (non-ASCII apostrophe), platform phone, AES preferred. */
    val INFO_A = LocalPeerInfo(caps = 0x0819, nickname = "Ananya’s Pixel", platform = 0, aeadPreference = AeadAlgorithm.AES_256_GCM)

    /** A laptop: caps 0x1089, "ThinkPad", platform laptop, AES preferred. */
    val INFO_B = LocalPeerInfo(caps = 0x1089, nickname = "ThinkPad", platform = 1, aeadPreference = AeadAlgorithm.AES_256_GCM)

    fun initiator(
        identity: IdentityKey = TestFixtures.IDENTITY_A,
        info: LocalPeerInfo = INFO_A,
        expectedPeer: ExpectedPeer? = null,
        randomness: HandshakeRandomness = TestFixtures.fixedRandomness(TestFixtures.EPHEMERAL_A, TestFixtures.NONCE_A),
    ): HandshakeInitiator = HandshakeInitiator(crypto, identity, info, expectedPeer, randomness)

    fun responder(
        identity: IdentityKey = TestFixtures.IDENTITY_B,
        info: LocalPeerInfo = INFO_B,
        trustedPeers: TrustedPeerLookup = TrustedPeerLookup.NONE,
        requireTrustedProof: Boolean = false,
        expectedPeerIdentity: ByteArray? = null,
        randomness: HandshakeRandomness = TestFixtures.fixedRandomness(TestFixtures.EPHEMERAL_B, TestFixtures.NONCE_B),
    ): HandshakeResponder = HandshakeResponder(crypto, identity, info, trustedPeers, requireTrustedProof, expectedPeerIdentity, randomness)

    /** The three messages and both results of one successful exchange. */
    class Exchange(
        val hello: ByteArray,
        val helloAck: ByteArray,
        val helloReveal: ByteArray,
        val initiator: HandshakeResult,
        val responder: HandshakeResult,
    )

    fun run(
        initiator: HandshakeInitiator = initiator(),
        responder: HandshakeResponder = responder(),
    ): Exchange {
        val hello = initiator.start()
        val ack = responder.receiveHello(hello)
        val reveal = initiator.receiveHelloAck(ack)
        val responderResult = responder.receiveHelloReveal(reveal)
        return Exchange(hello, ack, reveal, initiator.result, responderResult)
    }

    /** A lookup that knows exactly one device. */
    fun lookup(
        identityKey: ByteArray,
        secret: ByteArray,
    ): TrustedPeerLookup = TrustedPeerLookup { if (it.contentEquals(identityKey)) secret.copyOf() else null }
}
