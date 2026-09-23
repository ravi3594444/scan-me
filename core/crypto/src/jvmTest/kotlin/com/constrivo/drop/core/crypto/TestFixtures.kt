package com.constrivo.drop.core.crypto

import com.constrivo.drop.core.crypto.handshake.HandshakeClock
import com.constrivo.drop.core.crypto.handshake.HandshakeRandomness
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Fixed keys and randomness shared by the crypto tests, so every run (and every golden vector) is reproducible.
 * Identity keys are the RFC 8032 §7.1 test keys; ephemeral keys are the RFC 7748 §6.1 Alice and Bob keys.
 */
object TestFixtures {
    /** A JCA provider whose generator is a seeded SHA1PRNG, so key generation is repeatable. */
    fun deterministicCrypto(seed: Long = 1): JcaCryptoProvider =
        JcaCryptoProvider(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) })

    val crypto: JcaCryptoProvider = deterministicCrypto()

    val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }

    /** RFC 8032 TEST 1. */
    val IDENTITY_A =
        FixedIdentityKey(
            seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes(),
            publicKeyBytes = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToBytes(),
        )

    /** RFC 8032 TEST 2. */
    val IDENTITY_B =
        FixedIdentityKey(
            seed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb".hexToBytes(),
            publicKeyBytes = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c".hexToBytes(),
        )

    /** RFC 8032 TEST 3; the man in the middle. */
    val IDENTITY_M =
        FixedIdentityKey(
            seed = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7".hexToBytes(),
            publicKeyBytes = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025".hexToBytes(),
        )

    /** RFC 8032 TEST 1024; an unrelated third device. */
    val IDENTITY_C =
        FixedIdentityKey(
            seed = "f5e5767cf153319517630f226876b86c8160cc583bc013744c6bf255f5cc0ee5".hexToBytes(),
            publicKeyBytes = "278117fc144c72340f67d0f2316e8386ceffbf2b2428c9c51fef7c597f1d426e".hexToBytes(),
        )

    /** RFC 7748 §6.1 Alice. */
    val EPHEMERAL_A =
        RawKeyPair(
            publicKey = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".hexToBytes(),
            privateKey = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToBytes(),
        )

    /** RFC 7748 §6.1 Bob. */
    val EPHEMERAL_B =
        RawKeyPair(
            publicKey = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".hexToBytes(),
            privateKey = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".hexToBytes(),
        )

    /** The wall-clock time of the golden vectors: 2026-09-21T14:13:20Z, 800 s into trusted-proof epoch 1,988,888. */
    const val FIXED_UNIX_SECONDS: Long = 1_790_000_000L

    /** Trusted-proof epoch of [FIXED_UNIX_SECONDS]. */
    const val FIXED_EPOCH: Long = 1_988_888L

    val NONCE_A: ByteArray = ByteArray(16) { it.toByte() }
    val NONCE_B: ByteArray = ByteArray(16) { (0x10 + it).toByte() }

    /** An X25519 key pair derived from [label], for tests that need more than two fixed keys. */
    fun x25519From(label: String): RawKeyPair {
        val privateKey = crypto.sha256(label.encodeToByteArray())
        return RawKeyPair(crypto.x25519(privateKey, BASE_POINT), privateKey)
    }

    /** A 16-byte nonce derived from [label]. */
    fun nonceFrom(label: String): ByteArray = crypto.sha256(label.encodeToByteArray()).copyOf(16)

    /** Randomness that always yields [pair] and [nonce] (copies, so the fixture survives wiping). */
    fun fixedRandomness(
        pair: RawKeyPair,
        nonce: ByteArray,
    ): HandshakeRandomness =
        object : HandshakeRandomness {
            override fun ephemeralKeyPair(): RawKeyPair = RawKeyPair(pair.publicKey.copyOf(), pair.privateKey.copyOf())

            override fun nonce(): ByteArray = nonce.copyOf()
        }

    fun fixedRandomness(label: String): HandshakeRandomness = fixedRandomness(x25519From("eph:$label"), nonceFrom("nonce:$label"))

    /** The Ed25519 neutral element `01 00…00`: a public key under which `NEUTRAL_POINT ‖ 0^32` signs everything. */
    val NEUTRAL_POINT: ByteArray = ByteArray(32).also { it[0] = 1 }

    /** A signature that raw JCA accepts under [NEUTRAL_POINT] for every message. */
    val FORGED_NEUTRAL_SIGNATURE: ByteArray = NEUTRAL_POINT + ByteArray(32)

    /** The small-order Ed25519 encodings: seven y values, each with the sign bit clear and set. */
    val SMALL_ORDER_KEYS: List<ByteArray> =
        listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        ).flatMap { hex ->
            val key = hex.hexToBytes()
            listOf(key, key.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() })
        }

    /** Ed25519 verification straight through JCA, without this module's checks (to show what they prevent). */
    fun rawJcaEd25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec("302a300506032b6570032100".hexToBytes() + publicKey))
        return Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(message)
            verify(signature)
        }
    }
}

/** An identity with a known seed and public key (tests only). */
class FixedIdentityKey(
    private val seed: ByteArray,
    private val publicKeyBytes: ByteArray,
    private val crypto: CryptoProvider = JcaCryptoProvider(),
) : IdentityKey {
    override val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    override fun sign(message: ByteArray): ByteArray = crypto.ed25519Sign(seed, message)
}

/**
 * A clock the test moves by hand. [advance] moves wall-clock and monotonic time together; [unixSeconds] can also be
 * set on its own to model clock skew.
 */
class FakeClock(
    @Volatile var unix: Long = TestFixtures.FIXED_UNIX_SECONDS,
    @Volatile var millis: Long = 0,
) : HandshakeClock {
    override fun unixSeconds(): Long = unix

    override fun monotonicMillis(): Long = millis

    fun advance(millis: Long) {
        this.millis += millis
        unix += millis / 1000
    }
}
