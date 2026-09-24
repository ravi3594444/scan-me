package com.constrivo.drop.platform.android.crypto

import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.hexToBytes
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/** Which implementation of a primitive a provider ended up using, for the diagnostics log. */
enum class CryptoBackend {
    /** The platform's JCA (Conscrypt on Android), after it passed the RFC known-answer tests. */
    PLATFORM,

    /** BouncyCastle's lightweight RFC 7748 / RFC 8032 / RFC 8439 code, bundled with the app. */
    BOUNCY_CASTLE,
}

/** How [AndroidCryptoProvider] picks its X25519, Ed25519 and ChaCha20-Poly1305 backends. */
enum class BackendSelection {
    /** The platform when it has the algorithm and passes the RFC vectors, otherwise BouncyCastle (production). */
    PLATFORM_IF_VERIFIED,

    /** BouncyCastle only (tests of the fallback path, and devices where the platform misbehaves in the lab). */
    FALLBACK_ONLY,
}

/** X25519 on raw 32-byte keys (RFC 7748). */
internal interface X25519Engine {
    val backend: CryptoBackend

    /** `X25519(privateKey, peerPublicKey)`; throws [CryptoException] for an all-zero result. */
    fun agree(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): ByteArray

    /** `X25519(privateKey, 9)`. */
    fun publicKey(privateKey: ByteArray): ByteArray = agree(privateKey, BASE_POINT)

    companion object {
        val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }
    }
}

/** Ed25519 signing and verification on raw 32-byte seeds and public keys (RFC 8032, pure Ed25519). */
internal interface Ed25519Engine {
    val backend: CryptoBackend

    fun sign(
        seed: ByteArray,
        message: ByteArray,
    ): ByteArray

    /** Signature check only; the caller has already refused small-order keys and wrong sizes. */
    fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean
}

/** BouncyCastle's constant-time X25519 ([X25519.calculateAgreement] refuses the all-zero output). */
internal object BouncyCastleX25519 : X25519Engine {
    override val backend: CryptoBackend get() = CryptoBackend.BOUNCY_CASTLE

    override fun agree(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): ByteArray {
        val out = ByteArray(32)
        if (!X25519.calculateAgreement(privateKey, 0, peerPublicKey, 0, out, 0)) {
            throw CryptoException("X25519 produced an all-zero secret")
        }
        return out
    }

    override fun publicKey(privateKey: ByteArray): ByteArray = ByteArray(32).also { X25519.scalarMultBase(privateKey, 0, it, 0) }
}

/** BouncyCastle's RFC 8032 Ed25519 (no context, no prehash). */
internal object BouncyCastleEd25519 : Ed25519Engine {
    override val backend: CryptoBackend get() = CryptoBackend.BOUNCY_CASTLE

    override fun sign(
        seed: ByteArray,
        message: ByteArray,
    ): ByteArray = ByteArray(64).also { Ed25519.sign(seed, 0, message, 0, message.size, it, 0) }

    override fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        try {
            Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
        } catch (e: RuntimeException) {
            // A malformed point is a failed verification, never an escaping exception.
            false
        }

    /** The public key of [seed]. */
    fun publicKey(seed: ByteArray): ByteArray = ByteArray(32).also { Ed25519.generatePublicKey(seed, 0, it, 0) }
}

/**
 * X25519 through the platform JCA. Keys go through the fixed RFC 8410 DER prefixes, which the JDK and Conscrypt both
 * accept; the algorithm is looked up under [algorithm] (`X25519` on the JDK, `XDH` or its alias on Conscrypt).
 */
internal class JcaX25519(
    private val algorithm: String,
) : X25519Engine {
    override val backend: CryptoBackend get() = CryptoBackend.PLATFORM

    override fun agree(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): ByteArray {
        val secret =
            try {
                val factory = KeyFactory.getInstance(algorithm)
                val agreement = KeyAgreement.getInstance(algorithm)
                agreement.init(factory.generatePrivate(PKCS8EncodedKeySpec(X25519_PRIVATE_PREFIX + privateKey)))
                agreement.doPhase(factory.generatePublic(X509EncodedKeySpec(X25519_PUBLIC_PREFIX + peerPublicKey)), true)
                agreement.generateSecret()
            } catch (e: GeneralSecurityException) {
                // Some providers refuse low-order points themselves; that is the same failure as an all-zero secret.
                throw CryptoException("X25519 failed", e)
            } catch (e: RuntimeException) {
                throw CryptoException("X25519 failed", e)
            }
        if (secret.size != 32) throw CryptoException("X25519 returned ${secret.size} bytes")
        if (secret.all { it == 0.toByte() }) throw CryptoException("X25519 produced an all-zero secret")
        return secret
    }

    companion object {
        val X25519_PUBLIC_PREFIX = "302a300506032b656e032100".hexToBytes()
        val X25519_PRIVATE_PREFIX = "302e020100300506032b656e04220420".hexToBytes()
    }
}

/** Ed25519 through the platform JCA, with the RFC 8410 DER prefixes. */
internal class JcaEd25519(
    private val algorithm: String,
) : Ed25519Engine {
    override val backend: CryptoBackend get() = CryptoBackend.PLATFORM

    override fun sign(
        seed: ByteArray,
        message: ByteArray,
    ): ByteArray =
        try {
            val key = KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(ED25519_PRIVATE_PREFIX + seed))
            Signature.getInstance(algorithm).run {
                initSign(key)
                update(message)
                sign()
            }
        } catch (e: GeneralSecurityException) {
            throw CryptoException("Ed25519 signing failed", e)
        } catch (e: RuntimeException) {
            throw CryptoException("Ed25519 signing failed", e)
        }

    override fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean =
        try {
            val key = KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(ED25519_PUBLIC_PREFIX + publicKey))
            Signature.getInstance(algorithm).run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: RuntimeException) {
            false
        }

    companion object {
        val ED25519_PUBLIC_PREFIX = "302a300506032b6570032100".hexToBytes()
        val ED25519_PRIVATE_PREFIX = "302e020100300506032b657004220420".hexToBytes()
    }
}

/**
 * Chooses the Curve25519 engines at runtime (decision 7: "verify Ed25519/X25519 availability on API 31 first thing").
 *
 * A platform engine is used only if it exists under one of the known algorithm names and reproduces the RFC 7748 §5.2
 * and §6.1 vectors (X25519) or the RFC 8032 §7.1 test 1 and 2 vectors, including rejecting a tampered signature
 * (Ed25519). Anything else, including an exception, selects BouncyCastle. The probe costs a few milliseconds once per
 * process.
 */
internal object CurveProbe {
    private val X25519_NAMES = listOf("X25519", "XDH")
    private val ED25519_NAMES = listOf("Ed25519", "EdDSA")

    fun x25519(selection: BackendSelection): X25519Engine {
        if (selection == BackendSelection.FALLBACK_ONLY) return BouncyCastleX25519
        for (name in X25519_NAMES) {
            val engine = JcaX25519(name)
            if (passesX25519Vectors(engine)) return engine
        }
        return BouncyCastleX25519
    }

    fun ed25519(selection: BackendSelection): Ed25519Engine {
        if (selection == BackendSelection.FALLBACK_ONLY) return BouncyCastleEd25519
        for (name in ED25519_NAMES) {
            val engine = JcaEd25519(name)
            if (passesEd25519Vectors(engine)) return engine
        }
        return BouncyCastleEd25519
    }

    /** RFC 7748 §5.2 (first vector) and §6.1 (Alice's public key and the shared secret). */
    fun passesX25519Vectors(engine: X25519Engine): Boolean =
        try {
            engine.agree(Rfc7748.SCALAR_5_2.hexToBytes(), Rfc7748.U_5_2.hexToBytes()).contentEquals(Rfc7748.OUT_5_2.hexToBytes()) &&
                engine.publicKey(Rfc7748.ALICE_PRIVATE.hexToBytes()).contentEquals(Rfc7748.ALICE_PUBLIC.hexToBytes()) &&
                engine.agree(Rfc7748.ALICE_PRIVATE.hexToBytes(), Rfc7748.BOB_PUBLIC.hexToBytes()).contentEquals(Rfc7748.SHARED.hexToBytes())
        } catch (e: Exception) {
            false
        }

    /** RFC 8032 §7.1 tests 1 and 2: deterministic signatures, a valid verification and a refused tampered one. */
    fun passesEd25519Vectors(engine: Ed25519Engine): Boolean =
        try {
            Rfc8032.TESTS.all { t ->
                val signature = engine.sign(t.seed.hexToBytes(), t.message.hexToBytes())
                val tampered = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                signature.contentEquals(t.signature.hexToBytes()) &&
                    engine.verify(t.publicKey.hexToBytes(), t.message.hexToBytes(), signature) &&
                    !engine.verify(t.publicKey.hexToBytes(), t.message.hexToBytes(), tampered)
            }
        } catch (e: Exception) {
            false
        }
}

/** RFC 7748 test vectors (hex). */
internal object Rfc7748 {
    const val SCALAR_5_2 = "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"
    const val U_5_2 = "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"
    const val OUT_5_2 = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"
    const val ALICE_PRIVATE = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
    const val ALICE_PUBLIC = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
    const val BOB_PUBLIC = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    const val SHARED = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
}

/** RFC 8032 §7.1 test vectors 1 and 2 (hex). */
internal object Rfc8032 {
    class Vector(
        val seed: String,
        val publicKey: String,
        val message: String,
        val signature: String,
    )

    val TESTS: List<Vector> =
        listOf(
            Vector(
                seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
                message = "",
                signature =
                    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ),
            Vector(
                seed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                publicKey = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
                message = "72",
                signature =
                    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ),
        )
}
