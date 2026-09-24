package com.constrivo.drop.platform.android.crypto

import com.constrivo.drop.core.crypto.Aead
import com.constrivo.drop.core.crypto.AeadAlgorithm
import com.constrivo.drop.core.crypto.CryptoException
import com.constrivo.drop.core.crypto.hexToBytes
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Where an AEAD comes from: a JCA transformation, or BouncyCastle's lightweight ChaCha20-Poly1305. */
internal sealed interface AeadSource {
    val backend: CryptoBackend

    fun create(key: ByteArray): Aead

    /** A JCA cipher: `AES/GCM/NoPadding`, `ChaCha20-Poly1305` (JDK) or `ChaCha20/Poly1305/NoPadding` (Conscrypt). */
    class Jca(
        val algorithm: AeadAlgorithm,
        val transformation: String,
    ) : AeadSource {
        override val backend: CryptoBackend get() = CryptoBackend.PLATFORM

        override fun create(key: ByteArray): Aead = JcaAead(algorithm, transformation, key.copyOf())
    }

    /** BouncyCastle's ChaCha20-Poly1305 (RFC 8439), for a platform without one. */
    object BouncyCastleChaCha : AeadSource {
        override val backend: CryptoBackend get() = CryptoBackend.BOUNCY_CASTLE

        override fun create(key: ByteArray): Aead = BouncyCastleChaChaAead(key.copyOf())
    }
}

/** Picks and verifies the AEAD sources once per provider (RFC 8439 §2.8.2 and the GCM spec's test case 16). */
internal object AeadProbe {
    fun aesGcm(): AeadSource {
        val source = AeadSource.Jca(AeadAlgorithm.AES_256_GCM, "AES/GCM/NoPadding")
        check(passes(source, AeadVectors.AES_GCM)) { "the platform's AES-256-GCM failed its known-answer test" }
        return source
    }

    fun chaCha(allowPlatform: Boolean = true): AeadSource {
        if (allowPlatform) {
            for (name in listOf("ChaCha20-Poly1305", "ChaCha20/Poly1305/NoPadding")) {
                val source = AeadSource.Jca(AeadAlgorithm.CHACHA20_POLY1305, name)
                if (passes(source, AeadVectors.CHACHA)) return source
            }
        }
        val fallback = AeadSource.BouncyCastleChaCha
        check(passes(fallback, AeadVectors.CHACHA)) { "ChaCha20-Poly1305 failed its known-answer test" }
        return fallback
    }

    fun passes(
        source: AeadSource,
        vector: AeadVectors.Vector,
    ): Boolean =
        try {
            val aead = source.create(vector.key.hexToBytes())
            val sealed = aead.seal(vector.nonce.hexToBytes(), vector.plaintext.hexToBytes(), vector.aad.hexToBytes())
            sealed.contentEquals(vector.sealed.hexToBytes()) &&
                aead.open(vector.nonce.hexToBytes(), sealed, vector.aad.hexToBytes()).contentEquals(vector.plaintext.hexToBytes())
        } catch (e: Exception) {
            false
        }
}

/** Known-answer vectors for the two frame AEADs (architecture §7.1). */
internal object AeadVectors {
    class Vector(
        val key: String,
        val nonce: String,
        val aad: String,
        val plaintext: String,
        /** Ciphertext ‖ 16-byte tag. */
        val sealed: String,
    )

    /** The GCM specification's test case 16 (AES-256, 96-bit IV, with AAD). */
    val AES_GCM =
        Vector(
            key = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308",
            nonce = "cafebabefacedbaddecaf888",
            aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2",
            plaintext =
                "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
            sealed =
                "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
                    "76fc6ece0f4e1768cddf8853bb2d551b",
        )

    /** RFC 8439 §2.8.2. */
    val CHACHA =
        Vector(
            key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f",
            nonce = "070000004041424344454647",
            aad = "50515253c0c1c2c3c4c5c6c7",
            plaintext =
                "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
                    .encodeToByteArray()
                    .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
            sealed =
                "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
                    "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                    "3ff4def08e4b7a9de576d26586cec64b6116" +
                    "1ae10b594f09e26a7e902ecbd0600691",
        )
}

/** True when `a[aOff, aOff + aLen)` and `b[bOff, bOff + bLen)` are the same array and intersect. */
private fun overlaps(
    a: ByteArray,
    aOff: Int,
    aLen: Int,
    b: ByteArray,
    bOff: Int,
    bLen: Int,
): Boolean = a === b && aOff < bOff + bLen && bOff < aOff + aLen

/**
 * An [Aead] on one JCA transformation, with one cached [Cipher] per direction re-initialised for every call. Calls are
 * serialised on the instance, as in the JDK provider of `core/crypto`.
 *
 * The offset forms may overlap input and output (in-place sealing by the frame cipher). `Cipher.doFinal` is specified to
 * be copy-safe, but a provider need not honour that for partially overlapping ranges, so overlapping input is staged
 * through a scratch buffer that is reused between calls.
 */
private class JcaAead(
    override val algorithm: AeadAlgorithm,
    private val transformation: String,
    key: ByteArray,
) : Aead {
    private val secretKey = SecretKeySpec(key, if (algorithm == AeadAlgorithm.AES_256_GCM) "AES" else "ChaCha20")
    private var encryptor: Cipher? = null
    private var decryptor: Cipher? = null
    private var scratch = ByteArray(0)

    private fun cipher(
        mode: Int,
        nonce: ByteArray,
        aad: ByteArray,
    ): Cipher {
        if (nonce.size != algorithm.nonceSize) throw CryptoException("nonce must be ${algorithm.nonceSize} bytes, was ${nonce.size}")
        val cipher =
            try {
                if (mode == Cipher.ENCRYPT_MODE) {
                    encryptor ?: Cipher.getInstance(transformation).also { encryptor = it }
                } else {
                    decryptor ?: Cipher.getInstance(transformation).also { decryptor = it }
                }
            } catch (e: GeneralSecurityException) {
                throw CryptoException("$transformation is not available", e)
            }
        val spec =
            when (algorithm) {
                AeadAlgorithm.AES_256_GCM -> GCMParameterSpec(algorithm.tagSize * 8, nonce)
                AeadAlgorithm.CHACHA20_POLY1305 -> IvParameterSpec(nonce)
            }
        try {
            cipher.init(mode, secretKey, spec)
        } catch (e: GeneralSecurityException) {
            throw CryptoException("AEAD initialisation failed", e)
        }
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher
    }

    override fun seal(
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray =
        synchronized(this) {
            try {
                cipher(Cipher.ENCRYPT_MODE, nonce, aad).doFinal(plaintext)
            } catch (e: GeneralSecurityException) {
                throw CryptoException("AEAD sealing failed", e)
            }
        }

    override fun open(
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray =
        synchronized(this) {
            if (ciphertext.size < algorithm.tagSize) throw CryptoException("ciphertext is shorter than an AEAD tag")
            try {
                cipher(Cipher.DECRYPT_MODE, nonce, aad).doFinal(ciphertext)
            } catch (e: GeneralSecurityException) {
                throw CryptoException("AEAD authentication failed", e)
            }
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
        checkRange(input, inputOffset, inputLength, output, outputOffset, inputLength + algorithm.tagSize)
        return synchronized(this) {
            val (src, srcOffset) = stage(input, inputOffset, inputLength, output, outputOffset, inputLength + algorithm.tagSize)
            try {
                cipher(Cipher.ENCRYPT_MODE, nonce, aad).doFinal(src, srcOffset, inputLength, output, outputOffset)
            } catch (e: GeneralSecurityException) {
                throw CryptoException("AEAD sealing failed", e)
            }
        }
    }

    override fun open(
        nonce: ByteArray,
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        aad: ByteArray,
        output: ByteArray,
        outputOffset: Int,
    ): Int {
        if (inputLength < algorithm.tagSize) throw CryptoException("ciphertext is shorter than an AEAD tag")
        checkRange(input, inputOffset, inputLength, output, outputOffset, inputLength - algorithm.tagSize)
        return synchronized(this) {
            val (src, srcOffset) = stage(input, inputOffset, inputLength, output, outputOffset, inputLength - algorithm.tagSize)
            try {
                cipher(Cipher.DECRYPT_MODE, nonce, aad).doFinal(src, srcOffset, inputLength, output, outputOffset)
            } catch (e: GeneralSecurityException) {
                throw CryptoException("AEAD authentication failed", e)
            }
        }
    }

    /** The input to hand to the cipher: [input] itself, or a copy in [scratch] when it overlaps the output range. */
    private fun stage(
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        output: ByteArray,
        outputOffset: Int,
        outputLength: Int,
    ): Pair<ByteArray, Int> {
        if (!overlaps(input, inputOffset, inputLength, output, outputOffset, outputLength)) return input to inputOffset
        if (scratch.size < inputLength) scratch = ByteArray(inputLength)
        input.copyInto(scratch, 0, inputOffset, inputOffset + inputLength)
        return scratch to 0
    }
}

/** RFC 8439 ChaCha20-Poly1305 from BouncyCastle's lightweight API. */
private class BouncyCastleChaChaAead(
    private val key: ByteArray,
) : Aead {
    override val algorithm: AeadAlgorithm get() = AeadAlgorithm.CHACHA20_POLY1305
    private val engine = ChaCha20Poly1305()
    private var scratch = ByteArray(0)

    override fun seal(
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val out = ByteArray(plaintext.size + algorithm.tagSize)
        seal(nonce, plaintext, 0, plaintext.size, aad, out, 0)
        return out
    }

    override fun open(
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        if (ciphertext.size < algorithm.tagSize) throw CryptoException("ciphertext is shorter than an AEAD tag")
        val out = ByteArray(ciphertext.size - algorithm.tagSize)
        open(nonce, ciphertext, 0, ciphertext.size, aad, out, 0)
        return out
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
        checkRange(input, inputOffset, inputLength, output, outputOffset, inputLength + algorithm.tagSize)
        return synchronized(this) { run(true, nonce, input, inputOffset, inputLength, aad, output, outputOffset) }
    }

    override fun open(
        nonce: ByteArray,
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        aad: ByteArray,
        output: ByteArray,
        outputOffset: Int,
    ): Int {
        if (inputLength < algorithm.tagSize) throw CryptoException("ciphertext is shorter than an AEAD tag")
        checkRange(input, inputOffset, inputLength, output, outputOffset, inputLength - algorithm.tagSize)
        return synchronized(this) { run(false, nonce, input, inputOffset, inputLength, aad, output, outputOffset) }
    }

    private fun run(
        encrypt: Boolean,
        nonce: ByteArray,
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        aad: ByteArray,
        output: ByteArray,
        outputOffset: Int,
    ): Int {
        if (nonce.size != algorithm.nonceSize) throw CryptoException("nonce must be ${algorithm.nonceSize} bytes, was ${nonce.size}")
        val outLength = if (encrypt) inputLength + algorithm.tagSize else inputLength - algorithm.tagSize
        var src = input
        var srcOffset = inputOffset
        if (overlaps(input, inputOffset, inputLength, output, outputOffset, outLength)) {
            if (scratch.size < inputLength) scratch = ByteArray(inputLength)
            input.copyInto(scratch, 0, inputOffset, inputOffset + inputLength)
            src = scratch
            srcOffset = 0
        }
        try {
            engine.init(encrypt, AEADParameters(KeyParameter(key), algorithm.tagSize * 8, nonce, aad))
            val n = engine.processBytes(src, srcOffset, inputLength, output, outputOffset)
            return n + engine.doFinal(output, outputOffset + n)
        } catch (e: InvalidCipherTextException) {
            throw CryptoException("AEAD authentication failed", e)
        } catch (e: IllegalArgumentException) {
            throw CryptoException("AEAD operation failed", e)
        } catch (e: IllegalStateException) {
            throw CryptoException("AEAD operation failed", e)
        }
    }
}

/** `Aead.checkRange` is internal to `core/crypto`; the same contract, reported the same way. */
private fun checkRange(
    input: ByteArray,
    inputOffset: Int,
    inputLength: Int,
    output: ByteArray,
    outputOffset: Int,
    outputLength: Int,
) {
    require(inputOffset >= 0 && inputLength >= 0 && inputLength <= input.size - inputOffset) { "input range out of bounds" }
    require(outputOffset >= 0 && outputLength >= 0 && outputLength <= output.size - outputOffset) {
        "output needs $outputLength bytes at offset $outputOffset, array has ${output.size}"
    }
}
