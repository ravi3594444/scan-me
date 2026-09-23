package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.crypto.CryptoProvider
import com.constrivo.drop.core.crypto.trust.TrustedProof
import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.core.protocol.WifiCredentials

/**
 * SSID and passphrase of a Wi-Fi Direct group (architecture §8 notes, spec changes S5 and N7). The group owner
 * generates them (S5).
 *
 * Form: network name `DIRECT-` + 2 symbols + `-` + the app's display name + `-` + 4 symbols, for example
 * `DIRECT-k7-Drop-m3xq` (Android's `WifiP2pConfig.Builder.setNetworkName` requires `DIRECT-` and two letters or digits,
 * at most 32 UTF-8 bytes); passphrase of [RANDOM_PASSPHRASE_LENGTH] or [PAIR_PASSPHRASE_LENGTH] symbols (8–63 printable
 * ASCII for WPA2-PSK). Symbols come from [ALPHABET]: lower-case letters and digits without `l`, `o`, `0` and `1`, so a
 * person joining from a browser's computer (N8) can read and type them; 32 symbols, 5 bits each, drawn without bias.
 *
 * - **Untrusted peer:** fresh random values per transfer ([random]): 30 bits of name, 60 bits of passphrase. The
 *   passphrase only keeps strangers off the group; the payload is protected by the session AEAD on top (§13).
 * - **Trusted pair (N7):** stable values derived with HKDF-SHA256 from the pair's recognition secret
 *   ([forTrustedPair]), the same on both devices whichever of them hosts. Android then remembers the user's approval of
 *   the `WifiNetworkSpecifier` for that SSID, so the system dialog shows once per pair instead of on every join, and
 *   the group can be persistent (F-F5). The price: the pair's SSID is the same in every session, so someone who sees
 *   it on the air twice knows the same pair is transferring again.
 */
object P2pCredentials {
    const val NETWORK_NAME_PREFIX: String = "DIRECT-"
    const val MAX_SSID_BYTES: Int = 32
    const val MIN_PASSPHRASE_LENGTH: Int = 8
    const val MAX_PASSPHRASE_LENGTH: Int = 63

    /** 60 bits; short enough to type from the screen on the browser path. */
    const val RANDOM_PASSPHRASE_LENGTH: Int = 12

    /** 80 bits for the long-lived per-pair passphrase, which nobody types. */
    const val PAIR_PASSPHRASE_LENGTH: Int = 16

    /** 32 unambiguous symbols; a random byte's low 5 bits pick one without bias. */
    const val ALPHABET: String = "abcdefghijkmnpqrstuvwxyz23456789"

    /** HKDF info labels of the per-pair derivation (salt empty, input = the 32-byte recognition secret). */
    const val PAIR_SSID_INFO: String = "drop-p2p-ssid-v1"
    const val PAIR_PASSPHRASE_INFO: String = "drop-p2p-pass-v1"

    private const val PREFIX_SYMBOLS = 2
    private const val SUFFIX_SYMBOLS = 4
    private const val MAX_LABEL_LENGTH = 16

    /** `DIRECT-` and two ASCII letters or digits (the rule `WifiP2pConfig.Builder.setNetworkName` enforces). */
    private val NETWORK_NAME = Regex("^DIRECT-[a-zA-Z0-9]{2}.*$")

    /** The app's display name reduced to ASCII letters and digits (AppIdentity renames it with the app). */
    private val label: String =
        AppIdentity.DISPLAY_NAME.filter {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9'
        }.take(MAX_LABEL_LENGTH)

    /** Fresh random credentials for one transfer with an untrusted peer, from [crypto]'s secure random source. */
    fun random(crypto: CryptoProvider): WifiCredentials =
        build(
            nameSymbols = symbols(randomBytes(crypto, PREFIX_SYMBOLS + SUFFIX_SYMBOLS)),
            passphrase = symbols(randomBytes(crypto, RANDOM_PASSPHRASE_LENGTH)),
        )

    /**
     * The stable credentials of a trusted pair (N7): HKDF-SHA256 with the recognition secret as input key material, an
     * empty salt and the info [PAIR_SSID_INFO] (6 bytes, the name symbols) or [PAIR_PASSPHRASE_INFO] (16 bytes), one
     * output byte per symbol.
     *
     * @throws IllegalArgumentException if [recognitionSecret] is not [TrustedProof.SECRET_SIZE] bytes.
     */
    fun forTrustedPair(
        crypto: CryptoProvider,
        recognitionSecret: ByteArray,
    ): WifiCredentials {
        require(recognitionSecret.size == TrustedProof.SECRET_SIZE) {
            "recognition secret must be ${TrustedProof.SECRET_SIZE} bytes, was ${recognitionSecret.size}"
        }
        val empty = ByteArray(0)
        val name = crypto.hkdfSha256(recognitionSecret, empty, PAIR_SSID_INFO.encodeToByteArray(), PREFIX_SYMBOLS + SUFFIX_SYMBOLS)
        val pass = crypto.hkdfSha256(recognitionSecret, empty, PAIR_PASSPHRASE_INFO.encodeToByteArray(), PAIR_PASSPHRASE_LENGTH)
        check(name.size == PREFIX_SYMBOLS + SUFFIX_SYMBOLS && pass.size == PAIR_PASSPHRASE_LENGTH) { "HKDF returned the wrong length" }
        return build(symbols(name), symbols(pass))
    }

    /** [forTrustedPair] when the peer is trusted ([recognitionSecret] not null), otherwise [random]. */
    fun forTransfer(
        crypto: CryptoProvider,
        recognitionSecret: ByteArray?,
    ): WifiCredentials = if (recognitionSecret != null) forTrustedPair(crypto, recognitionSecret) else random(crypto)

    /** True when [ssid] is a network name Android accepts for a Wi-Fi Direct group. */
    fun isValidNetworkName(ssid: String): Boolean = NETWORK_NAME.matches(ssid) && utf8Length(ssid) in 1..MAX_SSID_BYTES

    /** True when [passphrase] is a WPA2 passphrase: 8–63 printable ASCII characters. */
    fun isValidPassphrase(passphrase: String): Boolean =
        passphrase.length in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH && passphrase.all { it.code in 0x20..0x7E }

    /**
     * Checks credentials for a Wi-Fi Direct group, generated here or received from the peer.
     *
     * @throws LinkCredentialsException if the network name or the passphrase breaks the rules.
     */
    fun requireValidGroup(credentials: WifiCredentials): WifiCredentials {
        if (!isValidNetworkName(credentials.ssid)) {
            throw LinkCredentialsException(
                "Wi-Fi Direct network name must be DIRECT- plus two letters or digits, at most $MAX_SSID_BYTES bytes",
            )
        }
        if (!isValidPassphrase(credentials.passphrase)) {
            throw LinkCredentialsException("passphrase must be $MIN_PASSPHRASE_LENGTH-$MAX_PASSPHRASE_LENGTH printable ASCII characters")
        }
        return credentials
    }

    /**
     * Checks credentials of a local-only hotspot, which the system generates (N15): SSID of 1–32 bytes, and a WPA2
     * passphrase (8–63 printable ASCII) or a raw 64-hex-digit PSK.
     *
     * @throws LinkCredentialsException if either breaks the rules.
     */
    fun requireValidHotspot(credentials: WifiCredentials): WifiCredentials {
        if (utf8Length(credentials.ssid) !in 1..MAX_SSID_BYTES) throw LinkCredentialsException("SSID must be 1-$MAX_SSID_BYTES bytes")
        val pass = credentials.passphrase
        val rawPsk = pass.length == 64 && pass.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (!isValidPassphrase(pass) && !rawPsk) {
            throw LinkCredentialsException("passphrase must be $MIN_PASSPHRASE_LENGTH-$MAX_PASSPHRASE_LENGTH printable ASCII characters")
        }
        return credentials
    }

    private fun build(
        nameSymbols: String,
        passphrase: String,
    ): WifiCredentials {
        val prefix = nameSymbols.substring(0, PREFIX_SYMBOLS)
        val suffix = nameSymbols.substring(PREFIX_SYMBOLS)
        val ssid = if (label.isEmpty()) "$NETWORK_NAME_PREFIX$prefix-$suffix" else "$NETWORK_NAME_PREFIX$prefix-$label-$suffix"
        return requireValidGroup(WifiCredentials(ssid, passphrase))
    }

    private fun randomBytes(
        crypto: CryptoProvider,
        size: Int,
    ): ByteArray = crypto.randomBytes(size).also { check(it.size == size) { "random source returned ${it.size} bytes, not $size" } }

    private fun symbols(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        for (b in bytes) out.append(ALPHABET[b.toInt() and 0x1F])
        return out.toString()
    }

    private fun utf8Length(value: String): Int = value.encodeToByteArray().size
}
