package com.constrivo.drop.web

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The per-session secret in the page URL, `/t/<token>/` (architecture §10.3, spec change N15).
 *
 * The token is printed in the QR code and on the phone screen, so it is short enough to type: [DEFAULT_LENGTH]
 * characters of lower-case Crockford base32 ([ALPHABET]: no `i`, `l`, `o` or `u`), 60 bits. Guessing it is not the
 * only barrier: the first browser that presents it still needs the phone's "Allow this computer?" approval
 * ([BrowserApprover]). Tokens are compared in constant time and case-insensitively, since people type them.
 *
 * [toString] never shows the value, so a token does not end up in logs by accident.
 */
class ReceiveToken private constructor(
    /** The token text, lower case. */
    val value: String,
) {
    private val bytes = value.toByteArray(Charsets.US_ASCII)

    /** Whether [candidate] is this token (case-insensitive, constant time for equal lengths). */
    fun matches(candidate: String): Boolean {
        if (candidate.length != value.length) return false
        val other = ByteArray(candidate.length)
        for (i in candidate.indices) {
            val c = candidate[i]
            // Non-ASCII characters can never match; map them to a byte outside the alphabet.
            other[i] = if (c.code < 0x80) c.lowercaseChar().code.toByte() else 0
        }
        return MessageDigest.isEqual(bytes, other)
    }

    override fun equals(other: Any?): Boolean = other is ReceiveToken && MessageDigest.isEqual(bytes, other.bytes)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ReceiveToken(${value.length} chars)"

    companion object {
        /** Lower-case Crockford base32: digits and letters without `i`, `l`, `o`, `u`. */
        const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"
        const val DEFAULT_LENGTH = 12
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 64

        /** A fresh random token of [length] characters (5 bits each). Use a cryptographic [random] in production. */
        fun generate(
            random: Random = SecureRandom().asKotlinRandom(),
            length: Int = DEFAULT_LENGTH,
        ): ReceiveToken {
            require(length in MIN_LENGTH..MAX_LENGTH) { "token length must be in $MIN_LENGTH..$MAX_LENGTH" }
            val chars = CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] }
            return ReceiveToken(String(chars))
        }

        /**
         * Parses a token received from elsewhere (for example restored after a process restart).
         *
         * @throws IllegalArgumentException when [text] is not [MIN_LENGTH]..[MAX_LENGTH] characters of [ALPHABET]
         * (upper case is accepted and lowered).
         */
        fun parse(text: String): ReceiveToken {
            val lower = text.lowercase()
            require(lower.length in MIN_LENGTH..MAX_LENGTH) { "token length must be in $MIN_LENGTH..$MAX_LENGTH" }
            require(lower.all { it in ALPHABET }) { "token must use the Crockford base32 alphabet" }
            return ReceiveToken(lower)
        }
    }
}
