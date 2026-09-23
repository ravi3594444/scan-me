package com.constrivo.drop.core.crypto

/**
 * The device's long-lived Ed25519 identity (F-B1). The private key stays inside the store; callers only get [sign].
 * On every supported platform this is a software key wrapped at rest by a Keystore / keychain AES key
 * (spec change N11): see [SoftwareIdentityKeyStore].
 */
interface IdentityKey {
    /** 32-byte Ed25519 public key (`identity_pk`). */
    val publicKey: ByteArray

    fun sign(message: ByteArray): ByteArray
}

/** Creates the identity at first launch and keeps it across app updates (F-B1 acceptance). */
interface IdentityKeyStore {
    fun loadOrCreate(): IdentityKey

    /** "Reset identity": discards the key and creates a new one. */
    fun reset(): IdentityKey
}

/** `device_id` = SHA-256(identity_pk)[0..16] (architecture §5.3). */
fun CryptoProvider.deviceId(identityPublicKey: ByteArray): ByteArray = sha256(identityPublicKey).copyOfRange(0, 16)

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return out.concatToString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(length / 2) { i ->
        val hi = this[i * 2].digitToInt(16)
        val lo = this[i * 2 + 1].digitToInt(16)
        ((hi shl 4) or lo).toByte()
    }
}

/** Constant-time comparison for MACs, proofs and tags. */
fun constantTimeEquals(
    a: ByteArray,
    b: ByteArray,
): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
