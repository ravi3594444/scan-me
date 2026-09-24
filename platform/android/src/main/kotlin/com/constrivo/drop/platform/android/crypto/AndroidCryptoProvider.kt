package com.constrivo.drop.platform.android.crypto

import com.constrivo.drop.core.crypto.Aead
import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.Ed25519PublicKeys
import com.constrivo.drop.core.crypto.RawKeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [CryptoProvider] for Android (architecture §6, §7.1, §13; decision 7, spec change N11).
 *
 * - **Platform JCA** (Conscrypt on Android) for SHA-256, HMAC-SHA256, AES-256-GCM, ChaCha20-Poly1305 and
 *   [SecureRandom]; HKDF-SHA256 (RFC 5869) is written out on top of HMAC, as in `core/crypto`'s JDK provider.
 * - **X25519 and Ed25519 from the platform when it has them**, which Android 12 does not guarantee: Conscrypt is a
 *   Play-updated module whose XDH and Ed25519 support depends on its version. Each is looked up at first use under the
 *   known algorithm names and used only if it reproduces the RFC 7748 and RFC 8032 vectors ([CurveProbe]); otherwise
 *   BouncyCastle's lightweight, constant-time `rfc7748.X25519` and `rfc8032.Ed25519` take over. The choice is visible
 *   in [x25519Backend] and [ed25519Backend] for the diagnostics log, so the lab can record it per device.
 * - **Key generation** takes its randomness from [random] on both paths: an X25519 private key is 32 random bytes and
 *   its public key `X25519(k, 9)` through the chosen engine; an Ed25519 seed is 32 random bytes and its public key is
 *   derived with BouncyCastle, since JCA has no "public key from seed" operation (the math is the same).
 * - **ChaCha20-Poly1305** under `ChaCha20-Poly1305` (JDK) or `ChaCha20/Poly1305/NoPadding` (Conscrypt), else
 *   BouncyCastle, each checked against RFC 8439 §2.8.2 first. AES-256-GCM must pass the GCM specification's test
 *   case 16 or construction fails: every Android device has it.
 * - [hasAesHardware] from [AesHardwareProbe] (CPU feature list, then a benchmark).
 *
 * APK size (architecture §15, 25 MB budget): `bcprov-jdk18on` is a 7 MB jar, but the provider calls only the
 * lightweight classes directly, registers no JCA provider and needs no keep rules, so R8 keeps an estimated 150–250 KB
 * of it (the two curve classes, `math.raw`, SHA-512 and ChaCha20-Poly1305). Unshrunk the jar is 5.5 MB of dex (measured
 * with d8; about 2–3 MB compressed in the APK), so the app's release build type must enable minification. Tink's
 * subtle primitives would cost about the same after R8 but bring protobuf-lite; libsodium bindings add a native library
 * per ABI.
 *
 * Thread-safe. Construction is cheap; the probes run on first use of each primitive.
 *
 * @param backends [BackendSelection.FALLBACK_ONLY] forces the fallback (tests, or a device the lab found
 *   misbehaving).
 * @param aesHardware how [hasAesHardware] is decided; evaluated once, on first read.
 */
class AndroidCryptoProvider(
    private val random: SecureRandom = SecureRandom(),
    backends: BackendSelection = BackendSelection.PLATFORM_IF_VERIFIED,
    aesHardware: () -> Boolean = { AesHardwareProbe.detect() },
) : CryptoProvider {
    private val x25519 by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CurveProbe.x25519(backends) }
    private val ed25519 by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CurveProbe.ed25519(backends) }
    private val aesGcm by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AeadProbe.aesGcm() }
    private val chaCha by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AeadProbe.chaCha(allowPlatform = backends == BackendSelection.PLATFORM_IF_VERIFIED)
    }
    private val aesHardwareResult by lazy(LazyThreadSafetyMode.SYNCHRONIZED, aesHardware)

    override val hasAesHardware: Boolean get() = aesHardwareResult

    /** Where X25519 runs on this device (first use runs the probe). */
    val x25519Backend: CryptoBackend get() = x25519.backend

    /** Where Ed25519 signing and verification run on this device (first use runs the probe). */
    val ed25519Backend: CryptoBackend get() = ed25519.backend

    /** Where ChaCha20-Poly1305 runs on this device (first use runs the probe). */
    val chaChaBackend: CryptoBackend get() = chaCha.backend

    override fun randomBytes(size: Int): ByteArray {
        require(size >= 0) { "size must not be negative" }
        return ByteArray(size).also(random::nextBytes)
    }

    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    override fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An empty HMAC key is legal (RFC 2104) but SecretKeySpec rejects it; HMAC pads keys with zeros anyway.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..255 * 32) { "HKDF length out of range: $length" }
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, n)
            offset += n
            counter++
        }
        return out
    }

    override fun generateX25519(): RawKeyPair {
        val privateKey = randomBytes(32)
        return RawKeyPair(x25519.publicKey(privateKey), privateKey)
    }

    override fun x25519(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): ByteArray {
        requireSize(privateKey, 32, "X25519 private key")
        requireSize(peerPublicKey, 32, "X25519 public key")
        return x25519.agree(privateKey, peerPublicKey)
    }

    override fun generateEd25519(): RawKeyPair {
        val seed = randomBytes(32)
        return RawKeyPair(BouncyCastleEd25519.publicKey(seed), seed)
    }

    override fun ed25519Sign(
        privateKey: ByteArray,
        message: ByteArray,
    ): ByteArray {
        requireSize(privateKey, 32, "Ed25519 private key")
        return ed25519.sign(privateKey, message)
    }

    override fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        // RFC 8032 verification without the cofactor accepts small-order keys, under which anyone can sign.
        if (Ed25519PublicKeys.isSmallOrder(publicKey)) return false
        return ed25519.verify(publicKey, message, signature)
    }

    override fun aead(
        algorithm: AeadAlgorithm,
        key: ByteArray,
    ): Aead {
        requireSize(key, algorithm.keySize, "${algorithm.name} key")
        return when (algorithm) {
            AeadAlgorithm.AES_256_GCM -> aesGcm.create(key)
            AeadAlgorithm.CHACHA20_POLY1305 -> chaCha.create(key)
        }
    }

    override fun toString(): String = "AndroidCryptoProvider"

    private fun requireSize(
        bytes: ByteArray,
        size: Int,
        what: String,
    ) {
        if (bytes.size != size) throw CryptoException("$what must be $size bytes, was ${bytes.size}")
    }
}
