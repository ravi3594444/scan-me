package com.constrivo.drop.core.crypto

/**
 * Ed25519 public keys that no key generator produces and that anyone can sign for without a private key.
 *
 * These are the eight points of small order (the torsion subgroup), in every encoding: either sign bit, and the
 * non-canonical `y ≥ p` forms of `y = 0` and `y = 1`. RFC 8032 verification as JCA implements it checks
 * `[S]B = R + [k]A` without the cofactor. For such an `A`, `[k]A` is itself a small-order point, so a signature
 * exists for any message without a private key. For the neutral element `01 00…00` the signature
 * `R = 01 00…00, S = 0` verifies for every message.
 *
 * Every [CryptoProvider.ed25519Verify] must return false for these keys. The handshake and QR decoders also refuse
 * them as malformed identity keys, whatever the provider does.
 */
object Ed25519PublicKeys {
    /** The y coordinates of the small-order points, little-endian, sign bit clear; includes `p` (0) and `p + 1` (1). */
    private val SMALL_ORDER_Y: List<ByteArray> =
        listOf(
            // y = 0: the two points of order 4.
            "0000000000000000000000000000000000000000000000000000000000000000",
            // y = 1: the neutral element (order 1).
            "0100000000000000000000000000000000000000000000000000000000000000",
            // The four points of order 8, two y values.
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
            // y = p - 1: the point of order 2.
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            // y = p and y = p + 1: non-canonical encodings of y = 0 and y = 1.
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        ).map { it.hexToBytes() }

    /** Encoded public key length. */
    const val SIZE: Int = 32

    /**
     * True if [publicKey] encodes a point of small order, with either sign bit. Such a key has no private key, and
     * signatures under it can be forged. False for keys of any other length; the caller checks sizes.
     */
    fun isSmallOrder(publicKey: ByteArray): Boolean {
        if (publicKey.size != SIZE) return false
        return SMALL_ORDER_Y.any { y ->
            var diff = 0
            for (i in 0 until SIZE - 1) diff = diff or (publicKey[i].toInt() xor y[i].toInt())
            diff = diff or ((publicKey[SIZE - 1].toInt() and 0x7F) xor y[SIZE - 1].toInt())
            diff == 0
        }
    }
}
