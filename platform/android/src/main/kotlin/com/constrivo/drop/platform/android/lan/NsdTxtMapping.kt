package com.constrivo.drop.platform.android.lan

import com.constrivo.drop.core.discovery.AppIdentity
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The DNS-SD TXT record of the LAN path (architecture §5.4, core/discovery's `MdnsRecord`) as `NsdServiceInfo`
 * attributes, both ways. Pure functions.
 *
 * `NsdServiceInfo.setAttribute` throws for keys that are empty or not printable US-ASCII, contain `=`, or together with
 * their value reach 255 bytes, and for records above 1300 bytes (RFC 6763 §6); [toAttributes] checks the same rules
 * first, so announcing never throws inside the platform call.
 */
object NsdTxtMapping {
    /** `NsdServiceInfo`: key length + value length must stay below this. */
    const val ENTRY_LIMIT_BYTES: Int = 255

    /** `NsdServiceInfo`: the whole record (length byte and `=` per entry included) must not exceed this. */
    const val MAX_TXT_BYTES: Int = 1300

    /**
     * The attributes to set for [txt], in its order.
     *
     * @throws LanDiscoveryException [NsdError.BAD_PARAMETERS] naming the rule a key or the record breaks.
     */
    fun toAttributes(txt: Map<String, String>): Map<String, String> {
        var total = 0
        val seen = HashSet<String>()
        for ((key, value) in txt) {
            if (key.isEmpty() || key.any { it.code !in 0x20..0x7E || it == '=' }) {
                throw LanDiscoveryException(NsdError.BAD_PARAMETERS, "TXT keys must be printable US-ASCII without '='")
            }
            if (!seen.add(key.lowercase())) throw LanDiscoveryException(NsdError.BAD_PARAMETERS, "duplicate TXT key '$key'")
            val size = key.length + value.encodeToByteArray().size
            if (size >= ENTRY_LIMIT_BYTES) throw LanDiscoveryException(NsdError.BAD_PARAMETERS, "TXT entry '$key' is $size bytes")
            total += size + 2
        }
        if (total > MAX_TXT_BYTES) throw LanDiscoveryException(NsdError.BAD_PARAMETERS, "TXT record of $total bytes exceeds $MAX_TXT_BYTES")
        return LinkedHashMap(txt)
    }

    /**
     * A browsed service's attributes as TXT text for `MdnsRecord.fromTxt`: values are decoded as strict UTF-8; boolean
     * attributes (a key without a value) and values that are not UTF-8 are left out, so the record's own validation
     * decides what a missing key means. Keys are kept as sent (the record compares them case-insensitively).
     */
    fun fromAttributes(attributes: Map<String, ByteArray?>): Map<String, String> {
        val txt = LinkedHashMap<String, String>()
        for ((key, value) in attributes) {
            if (value == null || key.isEmpty()) continue
            txt[key] = decodeUtf8(value) ?: continue
        }
        return txt
    }

    /** True when [serviceType] as NSD reports it (`_drop._tcp.`, `_drop._tcp.local.`) is this app's type. */
    fun isOwnServiceType(serviceType: String?): Boolean {
        val normalized = serviceType?.trim()?.trimEnd('.')?.lowercase()?.removeSuffix(".local") ?: return false
        return normalized == AppIdentity.MDNS_SERVICE_TYPE
    }

    private fun decodeUtf8(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
}
