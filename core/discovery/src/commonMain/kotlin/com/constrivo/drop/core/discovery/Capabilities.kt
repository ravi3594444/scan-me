package com.constrivo.drop.core.discovery

import kotlin.jvm.JvmInline

/** The 16 capability bits carried in the beacon and the mDNS TXT record (architecture §5.2). */
@JvmInline
value class Capabilities(
    val bits: Int,
) {
    init {
        require(bits in 0..0xFFFF) { "capabilities are 16 bits" }
    }

    operator fun contains(flag: Flag): Boolean = bits and flag.mask != 0

    operator fun plus(flag: Flag): Capabilities = Capabilities(bits or flag.mask)

    operator fun minus(flag: Flag): Capabilities = Capabilities(bits and flag.mask.inv() and 0xFFFF)

    fun flags(): Set<Flag> = Flag.entries.filterTo(mutableSetOf()) { it in this }

    override fun toString(): String = "Capabilities(${flags().joinToString()})"

    enum class Flag(
        val bit: Int,
    ) {
        WIFI_5GHZ(0),
        WIFI_6GHZ(1),
        WIFI_6_OR_NEWER(2),
        WIFI_DIRECT(3),
        CAN_HOST_P2P_5GHZ(4),
        CAN_HOST_LOCAL_HOTSPOT(5),
        WIFI_AWARE(6),
        BLUETOOTH_RFCOMM(7),
        BLE_EXTENDED_ADVERTISING(8),
        BLE_CODED_PHY(9),
        SAVE_LOCATION_REMOVABLE(10),
        CONNECTED_TO_WIFI(11),
        DESKTOP_WITHOUT_BLUETOOTH(12),

        /**
         * The connected Wi-Fi network is on 5 GHz or above (valid with [CONNECTED_TO_WIFI]). Added by spec change N9:
         * group-owner election avoids a device whose station interface would pin the group to 2.4 GHz.
         */
        STATION_ON_5GHZ(13),
        ;

        val mask: Int get() = 1 shl bit
    }

    companion object {
        val NONE = Capabilities(0)

        fun of(vararg flags: Flag): Capabilities = Capabilities(flags.fold(0) { acc, f -> acc or f.mask })
    }
}

/** Platform glyph on the radar; 3 bits in the beacon (architecture §5.1 offset 24). */
enum class DevicePlatform(
    val code: Int,
    val wireName: String,
) {
    PHONE(0, "phone"),
    LAPTOP(1, "laptop"),
    DESKTOP(2, "desktop"),
    BROWSER_PROXY(3, "browser"),
    ;

    companion object {
        fun fromCode(code: Int): DevicePlatform? = entries.firstOrNull { it.code == code }

        fun fromWire(name: String): DevicePlatform? = entries.firstOrNull { it.wireName == name }
    }
}

/** Visibility modes (F-A5); 2 bits in the beacon. [HIDDEN] never advertises. */
enum class Visibility(
    val code: Int,
) {
    EVERYONE(0),
    EVERYONE_TEN_MINUTES(1),
    TRUSTED_ONLY(2),
    HIDDEN(3),
    ;

    companion object {
        fun fromCode(code: Int): Visibility? = entries.firstOrNull { it.code == code }
    }
}
