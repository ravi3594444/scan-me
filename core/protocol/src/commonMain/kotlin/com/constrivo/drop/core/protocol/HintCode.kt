package com.constrivo.drop.core.protocol

/** Speed hint codes carried in `Hint` messages and stored in `transfer.hint_codes` (architecture §7.2, design §8.2). */
enum class HintCode(
    val wireName: String,
) {
    /** Link on 2.4 GHz although both devices support 5 GHz: "Move closer for full speed". */
    BAND24("band24"),

    /** The other device is 2.4 GHz only. */
    PEER_BAND24_ONLY("peer_band24_only"),

    /** Destination is a microSD card. */
    SDCARD("sdcard"),

    /** Thermal throttling reported; streams reduced to 2. */
    THERMAL("thermal"),

    /** Many small files are being bundled. */
    BUNDLING("bundling"),

    /** Bluetooth-only slow mode: Wi-Fi is off on one device. */
    BT_FALLBACK("bt_fallback"),

    /** LAN path slower than 10 MB/s; switching to a direct link. */
    LAN_SLOW("lan_slow"),

    /** The station interface is on a 2.4 GHz network and pins the group there (spec change N9). */
    STATION_BAND24("sta_band24"),
    ;

    companion object {
        fun fromWire(name: String): HintCode? = entries.firstOrNull { it.wireName == name }
    }
}
