package com.constrivo.drop.core.crypto

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [CryptoProvider] on the standard Java Cryptography Architecture (JDK 15+: X25519, Ed25519,
 * AES-GCM, ChaCha20-Poly1305). Raw 32-byte keys are converted to X.509 / PKCS#8 with the fixed
 * RFC 8410 prefixes, which every JCA provider accepts.
 */
class JcaCryptoProvider(
    private val random: SecureRandom = SecureRandom(),
) : CryptoProvider {
    override val hasAesHardware: Boolean = System.getProperty("os.arch").let { it == "amd64" || it == "x86_64" || it == "aarch64" }

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

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
        val kp = KeyPairGenerator.getInstance("X25519").apply { initialize(255, random) }.generateKeyPair()
        return RawKeyPair(kp.public.encoded.takeLast32(X25519_PUBLIC_PREFIX), kp.private.encoded.takeLast32(X25519_PRIVATE_PREFIX))
    }

    override fun x25519(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): ByteArray {
        requireSize(privateKey, 32, "X25519 private key")
        requireSize(peerPublicKey, 32, "X25519 public key")
        try {
            val kf = KeyFactory.getInstance("X25519")
            val ka = KeyAgreement.getInstance("X25519")
            ka.init(kf.generatePrivate(PKCS8EncodedKeySpec(X25519_PRIVATE_PREFIX + privateKey)))
            ka.doPhase(kf.generatePublic(X509EncodedKeySpec(X25519_PUBLIC_PREFIX + peerPublicKey)), true)
            val secret = ka.generateSecret()
            if (secret.all { it == 0.toByte() }) throw CryptoException("X25519 produced an all-zero secret")
            return secret
        } catch (e: GeneralSecurityException) {
            throw CryptoException("X25519 failed", e)
        }
    }

    override fun generateEd25519(): RawKeyPair {
        val kp = KeyPairGenerator.getInstance("Ed25519").apply { initialize(255, random) }.generateKeyPair()
        return RawKeyPair(kp.public.encoded.takeLast32(ED25519_PUBLIC_PREFIX), kp.private.encoded.takeLast32(ED25519_PRIVATE_PREFIX))
    }

    override fun ed25519Sign(
        privateKey: ByteArray,
        message: ByteArray,
    ): ByteArray {
        requireSize(privateKey, 32, "Ed25519 private key")
        try {
            val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(ED25519_PRIVATE_PREFIX + privateKey))
            return Signature.getInstance("Ed25519").run {
                initSign(key)
                update(message)
                sign()
            }
        } catch (e: GeneralSecurityException) {
            throw CryptoException("Ed25519 signing failed", e)
        }
    }

    override fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_PUBLIC_PREFIX + publicKey))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    override fun aead(
        algorithm: AeadAlgorithm,
        key: ByteArray,
    ): Aead {
        requireSize(key, algorithm.keySize, "${algorithm.name} key")
        return JcaAead(algorithm, key.copyOf())
    }

    private class JcaAead(
        override val algorithm: AeadAlgorithm,
        key: ByteArray,
    ) : Aead {
        private val secretKey =
            SecretKeySpec(key, if (algorithm == AeadAlgorithm.AES_256_GCM) "AES" else "ChaCha20")

        private fun cipher(
            mode: Int,
            nonce: ByteArray,
        ): Cipher {
            requireSize(nonce, algorithm.nonceSize, "nonce")
            return when (algorithm) {
                AeadAlgorithm.AES_256_GCM -> {
                    Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, secretKey, GCMParameterSpec(128, nonce)) }
                }

                AeadAlgorithm.CHACHA20_POLY1305 -> {
                    Cipher.getInstance("ChaCha20-Poly1305").apply { init(mode, secretKey, IvParameterSpec(nonce)) }
                }
            }
        }

        override fun seal(
            nonce: ByteArray,
            plaintext: ByteArray,
            aad: ByteArray,
        ): ByteArray =
            cipher(Cipher.ENCRYPT_MODE, nonce).run {
                if (aad.isNotEmpty()) updateAAD(aad)
                doFinal(plaintext)
            }

        override fun open(
            nonce: ByteArray,
            ciphertext: ByteArray,
            aad: ByteArray,
        ): ByteArray =
            try {
                cipher(Cipher.DECRYPT_MODE, nonce).run {
                    if (aad.isNotEmpty()) updateAAD(aad)
                    doFinal(ciphertext)
                }
            } catch (e: GeneralSecurityException) {
                throw CryptoException("AEAD authentication failed", e)
            }
    }

    private companion object {
        // RFC 8410 DER prefixes for raw 32-byte keys.
        val X25519_PUBLIC_PREFIX = "302a300506032b656e032100".hexToBytes()
        val X25519_PRIVATE_PREFIX = "302e020100300506032b656e04220420".hexToBytes()
        val ED25519_PUBLIC_PREFIX = "302a300506032b6570032100".hexToBytes()
        val ED25519_PRIVATE_PREFIX = "302e020100300506032b657004220420".hexToBytes()

        fun ByteArray.takeLast32(expectedPrefix: ByteArray): ByteArray {
            check(size == expectedPrefix.size + 32 && copyOfRange(0, expectedPrefix.size).contentEquals(expectedPrefix)) {
                "unexpected key encoding"
            }
            return copyOfRange(size - 32, size)
        }

        fun requireSize(
            bytes: ByteArray,
            size: Int,
            what: String,
        ) {
            if (bytes.size != size) throw CryptoException("$what must be $size bytes, was ${bytes.size}")
        }
    }
}
