package com.constrivo.drop.core.discovery

import kotlin.random.Random

/** Shared test values; the golden hex strings were computed independently (Python `hmac`/`hashlib`). */
internal object Fixtures {
    val EPH = EphemeralId(0x0123456789ABL)

    /** WIFI_5GHZ, WIFI_DIRECT, CAN_HOST_P2P_5GHZ, BLUETOOTH_RFCOMM, CONNECTED_TO_WIFI, STATION_ON_5GHZ = 0x2899. */
    val CAPS =
        Capabilities.of(
            Capabilities.Flag.WIFI_5GHZ,
            Capabilities.Flag.WIFI_DIRECT,
            Capabilities.Flag.CAN_HOST_P2P_5GHZ,
            Capabilities.Flag.BLUETOOTH_RFCOMM,
            Capabilities.Flag.CONNECTED_TO_WIFI,
            Capabilities.Flag.STATION_ON_5GHZ,
        )
    val HINT = NetworkHint(0xA1B2C3D4.toInt())
    val ADDRESS = BluetoothAddress(0xAABBCCDDEEFFL)

    /** A laptop in "Everyone for 10 min". */
    val BODY = BeaconBody(EPH, CAPS, HINT, Visibility.EVERYONE_TEN_MINUTES, DevicePlatform.LAPTOP)
    const val BODY_HEX = "010123456789ab2899a1b2c3d448"

    /** A desktop in "Everyone" publishing its Classic address (S10). */
    val BODY_WITH_ADDRESS = BeaconBody(EPH, CAPS, HINT, Visibility.EVERYONE, DevicePlatform.DESKTOP, ADDRESS)
    const val BODY_WITH_ADDRESS_HEX = "010123456789ab2899a1b2c3d410aabbccddeeff"

    const val SERVICE_DATA_HEX = "020106030301df111601df$BODY_HEX"
    const val SERVICE_DATA_WITH_ADDRESS_HEX = "020106030301df171601df$BODY_WITH_ADDRESS_HEX"
    const val MANUFACTURER_DATA_HEX = "02010613ffffff6472$BODY_HEX"
    const val MANUFACTURER_DATA_WITH_ADDRESS_HEX = "02010619ffffff6472$BODY_WITH_ADDRESS_HEX"

    const val NICKNAME = "Anna's Pixel"
    const val SCAN_RESPONSE_HEX = "101601df81416e6e61277320506978656c"
    const val SCAN_RESPONSE_MANUFACTURER_HEX = "12ffffff647281416e6e61277320506978656c"

    /** 25 ASCII letters and a 3-byte euro sign: 28 bytes, cut to 25 for the 26-byte budget. */
    const val LONG_NICKNAME = "ABCDEFGHIJKLMNOPQRSTUVWXY€"
    const val SCAN_RESPONSE_SHORTENED_HEX = "1d1601df824142434445464748494a4b4c4d4e4f50515253545556575859"

    fun hex(bytes: ByteArray): String = Bytes.hex(bytes)

    fun bytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** A random valid body. */
    fun randomBody(random: Random): BeaconBody {
        val connected = random.nextBoolean()
        var caps = Capabilities(random.nextInt(0x10000)) - Capabilities.Flag.CONNECTED_TO_WIFI - Capabilities.Flag.STATION_ON_5GHZ
        var hint = NetworkHint.NONE
        if (connected) {
            caps += Capabilities.Flag.CONNECTED_TO_WIFI
            if (random.nextBoolean()) caps += Capabilities.Flag.STATION_ON_5GHZ
            hint = NetworkHint(random.nextInt().let { if (it == 0) 1 else it })
        }
        val address =
            if (random.nextBoolean()) {
                BluetoothAddress(random.nextLong(1, BluetoothAddress.MAX_VALUE)).takeIf { it.isUsable }
            } else {
                null
            }
        return BeaconBody(
            ephemeralId = EphemeralId(random.nextLong(0, EphemeralId.MAX_VALUE + 1)),
            capabilities = caps,
            networkHint = hint,
            visibility = listOf(Visibility.EVERYONE, Visibility.EVERYONE_TEN_MINUTES, Visibility.TRUSTED_ONLY).random(random),
            platform = DevicePlatform.entries.random(random),
            classicAddress = address,
        )
    }

    /** Random text mixing ASCII, accents, CJK, emoji, controls, bidi overrides and lone surrogates. */
    fun randomNickname(random: Random): String {
        val pieces =
            listOf(
                "a",
                "Z",
                " ",
                "é",
                "ß",
                "क",
                "漢",
                "😀",
                "👩\u200D💻",
                "€",
                "\u0000",
                "\n",
                "\u202E",
                "\u200F",
                "\uFEFF",
                "\uD800",
                "\uDC00",
                "'",
                "\u00A0",
            )
        return buildString { repeat(random.nextInt(0, 40)) { append(pieces.random(random)) } }
    }
}
