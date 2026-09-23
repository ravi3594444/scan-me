package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.JcaCryptoProvider

/** The JCA provider with an HMAC call counter, to prove what runs on the hot path. */
internal class CountingCrypto(
    private val delegate: CryptoProvider = JcaCryptoProvider(),
) : CryptoProvider by delegate {
    var hmacCalls: Int = 0
        private set

    override fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        hmacCalls++
        return delegate.hmacSha256(key, data)
    }
}

/** A provider whose HMAC and SHA-256 return a fixed digest, to force collisions and zero digests. */
internal class FixedDigestCrypto(
    private val digest: ByteArray,
    private val delegate: CryptoProvider = JcaCryptoProvider(),
) : CryptoProvider by delegate {
    override fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = digest.copyOf()

    override fun sha256(data: ByteArray): ByteArray = digest.copyOf()
}

internal object Secrets {
    /** `k_adv` = bytes 0x00..0x1f, the key of the golden vectors. */
    val K0 = ByteArray(32) { it.toByte() }

    fun secret(seed: Int): ByteArray = ByteArray(32) { (seed * 31 + it * 7).toByte() }

    /** 23 Sep 2026 10:00 UTC-ish, inside epoch 1_988_888 (starts at 1_789_999_200_000 ms). */
    const val EPOCH = 1_988_888L
    const val EPOCH_START = 1_789_999_200_000L
}
