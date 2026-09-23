package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.CryptoProvider

/**
 * The DNS-SD record of the LAN path (architecture §5.4 as changed by spec change N4), service type
 * [AppIdentity.MDNS_SERVICE_TYPE].
 *
 * TXT keys, in this order:
 *
 * | Key | Value |
 * | --- | --- |
 * | `v` | `1` (decimal; later versions may only add keys) |
 * | `eph` | [ephemeralId], 12 lower-case hex digits (the same ID as the beacon, §5.3) |
 * | `cap` | [capabilities], 4 lower-case hex digits (§5.2) |
 * | `plat` | [platform] wire name: `phone`, `laptop`, `desktop` or `browser` |
 * | `nick` | [nickname], UTF-8, at most 64 bytes; omitted in Trusted-only mode (N4) |
 * | `port` | [controlPort], decimal `1`–`65535` |
 * | `vis` | [visibility] code `0`, `1` or `2`; absent means `0` (added by WP1 so LAN honours F‑A5) |
 *
 * N4: the permanent `device_id` is not published (the pack's `id` key is gone) and the [instanceName] is derived from
 * the rotating ID, so the record rotates with the beacon; announcers re-register at every epoch boundary. A
 * Trusted-only record from a stranger is dropped by [NearbyDevices]; trusted peers resolve `eph` with `k_adv`.
 *
 * Limits enforced on both sides (RFC 6763 §6): keys are printable US-ASCII without `=` (ours have at most nine
 * characters, as §6.4 recommends) and are compared case-insensitively; each `key=value` string is at most 255
 * bytes; the whole record is at most 1300 bytes. Unknown keys are ignored.
 */
data class MdnsRecord(
    val ephemeralId: EphemeralId,
    val capabilities: Capabilities,
    val platform: DevicePlatform,
    val nickname: String?,
    val controlPort: Int,
    val visibility: Visibility = Visibility.EVERYONE,
) {
    init {
        require(visibility != Visibility.HIDDEN) { "a HIDDEN device is never announced (F-A5)" }
        require(controlPort in 1..MAX_PORT) { "control port out of range: $controlPort" }
        require(visibility != Visibility.TRUSTED_ONLY || nickname == null) { "Trusted-only records carry no nickname (N4)" }
        if (nickname != null) {
            require(nickname.isNotEmpty() && Nicknames.sanitize(nickname) == nickname) { "nickname must be sanitised and non-empty" }
            require(Nicknames.utf8Length(nickname) <= Nicknames.MAX_BYTES) { "nickname longer than ${Nicknames.MAX_BYTES} UTF-8 bytes" }
        }
    }

    /** DNS-SD instance name: `drop-<eph>`. Contains neither the nickname nor a permanent ID (N4). */
    val instanceName: String get() = "${AppIdentity.CODE_NAME}-${ephemeralId.toHex()}"

    /** The TXT record as key → value, in the documented key order. */
    fun toTxt(): Map<String, String> {
        val txt = LinkedHashMap<String, String>()
        txt[KEY_VERSION] = VERSION.toString()
        txt[KEY_EPH] = ephemeralId.toHex()
        txt[KEY_CAP] = Bytes.hex(capabilities.bits.toLong(), 4)
        txt[KEY_PLATFORM] = platform.wireName
        nickname?.let { txt[KEY_NICKNAME] = it }
        txt[KEY_PORT] = controlPort.toString()
        txt[KEY_VISIBILITY] = visibility.code.toString()
        check(txtSize(txt) <= MAX_TXT_BYTES) { "TXT record too large" }
        return txt
    }

    /** A [LanService] announcing this record on [host]. */
    fun toLanService(host: String): LanService = LanService(instanceName, host, controlPort, toTxt())

    companion object {
        const val VERSION: Int = 1

        const val KEY_VERSION: String = "v"
        const val KEY_EPH: String = "eph"
        const val KEY_CAP: String = "cap"
        const val KEY_PLATFORM: String = "plat"
        const val KEY_NICKNAME: String = "nick"
        const val KEY_PORT: String = "port"
        const val KEY_VISIBILITY: String = "vis"

        /** One DNS character-string: `key=value` is at most 255 bytes (RFC 6763 §6.1). */
        const val MAX_ENTRY_BYTES: Int = 255

        /** RFC 6763 §6.2: TXT records above 1300 bytes are not recommended; larger ones are refused. */
        const val MAX_TXT_BYTES: Int = 1300

        private const val MAX_PORT = 65535

        /**
         * This device's record for the epoch containing [nowMillis], with the visibility rules of
         * [BeaconAdvertisement.create]: HIDDEN is refused (`IllegalArgumentException`), Trusted-only omits the
         * nickname, and the capability bits are normalised exactly as in the beacon so both sources agree.
         * Re-announce at the next epoch boundary ([EphemeralIds.millisUntilNextEpoch], N4).
         */
        fun create(
            crypto: CryptoProvider,
            advertisingSecret: ByteArray,
            state: LocalBeaconState,
            controlPort: Int,
            nowMillis: Long,
        ): MdnsRecord {
            val beacon = BeaconAdvertisement.create(crypto, advertisingSecret, state, BeaconCarrier.SERVICE_DATA, nowMillis)
            return MdnsRecord(
                ephemeralId = beacon.body.ephemeralId,
                capabilities = beacon.body.capabilities,
                platform = state.platform,
                nickname = if (state.visibility == Visibility.TRUSTED_ONLY) null else Nicknames.normalize(state.nickname)?.text,
                controlPort = controlPort,
                visibility = state.visibility,
            )
        }

        /**
         * Decodes and validates a TXT record. A nickname is sanitised and cut to 64 bytes; in a Trusted-only record it
         * is ignored.
         *
         * @throws DiscoveryFormatException for invalid keys, oversized entries, duplicate keys (case-insensitively),
         *   missing or malformed required keys, out-of-range values or a HIDDEN visibility.
         */
        fun fromTxt(txt: Map<String, String>): MdnsRecord {
            if (txtSize(txt) > MAX_TXT_BYTES) throw DiscoveryFormatException("TXT record larger than $MAX_TXT_BYTES bytes")
            val entries = HashMap<String, String>()
            for ((rawKey, value) in txt) {
                if (rawKey.isEmpty() || rawKey.any { it.code !in 0x20..0x7E || it == '=' }) {
                    throw DiscoveryFormatException("invalid TXT key")
                }
                if (rawKey.length + 1 + Nicknames.utf8Length(value) > MAX_ENTRY_BYTES) {
                    throw DiscoveryFormatException("TXT entry '$rawKey' longer than $MAX_ENTRY_BYTES bytes")
                }
                if (entries.put(rawKey.lowercase(), value) != null) throw DiscoveryFormatException("duplicate TXT key '$rawKey'")
            }

            fun required(key: String): String = entries[key] ?: throw DiscoveryFormatException("TXT key '$key' missing")

            val version = Bytes.parseDecimal(required(KEY_VERSION), 3)
            if (version == null || version !in 1L..255L) throw DiscoveryFormatException("bad TXT version")
            val eph = EphemeralId.parseHex(required(KEY_EPH))
            val cap = Bytes.parseHex(required(KEY_CAP), 4) ?: throw DiscoveryFormatException("cap must be 4 hex digits")
            val platform = DevicePlatform.fromWire(required(KEY_PLATFORM)) ?: throw DiscoveryFormatException("unknown plat")
            val port = Bytes.parseDecimal(required(KEY_PORT), 5)
            if (port == null || port !in 1L..MAX_PORT.toLong()) throw DiscoveryFormatException("bad port")
            val visibility =
                entries[KEY_VISIBILITY]?.let { text ->
                    Bytes.parseDecimal(text, 1)?.toInt()?.let(Visibility::fromCode)?.takeIf { it != Visibility.HIDDEN }
                        ?: throw DiscoveryFormatException("bad vis")
                } ?: Visibility.EVERYONE
            val nickname =
                if (visibility == Visibility.TRUSTED_ONLY) null else entries[KEY_NICKNAME]?.let { Nicknames.normalize(it)?.text }
            return MdnsRecord(eph, Capabilities(cap.toInt()), platform, nickname, port.toInt(), visibility)
        }

        /** Wire size of a TXT record: one length byte plus `key=value` per entry. */
        private fun txtSize(txt: Map<String, String>): Int = txt.entries.sumOf { 2 + it.key.length + Nicknames.utf8Length(it.value) }
    }
}
