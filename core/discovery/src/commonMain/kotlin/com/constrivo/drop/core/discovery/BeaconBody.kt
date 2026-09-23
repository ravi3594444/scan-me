package com.constrivo.drop.core.discovery

/**
 * The beacon body: the device's discovery facts, identical in both advertising carriers (architecture §5.1 as
 * changed by spec changes S10 and S11).
 *
 * Wire layout (14 bytes, or 20 with the Classic address); multi-byte fields are big-endian:
 *
 * | Offset | Size | Field |
 * | --- | --- | --- |
 * | 0 | 1 | Version, `0x01` |
 * | 1 | 6 | [ephemeralId]: bytes 0–5 of the HMAC output, in order (§5.3) |
 * | 7 | 2 | [capabilities]: bit *n* of §5.2 is bit *n* of the big-endian `u16` (bit 0 = LSB of byte 8) |
 * | 9 | 4 | [networkHint]: bytes 0–3 of the digest, in order (N6); zero when not connected |
 * | 13 | 1 | bits 7–6 [visibility] code, bits 5–3 [platform] code, bits 2–0 reserved (sent as 0, ignored) |
 * | 14 | 6 | optional [classicAddress], display order (`AA:BB:…` → `AA BB …`) |
 *
 * Versioning: the first byte of every drop record says what it is. `0x01`–`0x0F` are beacon bodies sharing this
 * layout: a v1 body must be exactly 14 or 20 bytes, while a later minor version (`0x02`–`0x0F`) may append fields,
 * so decoding reads bytes 0–13 and ignores the rest (including anything at offset 14). `0x10`–`0x7F` are reserved
 * for incompatible layouts ([UnsupportedBeaconVersionException]); `0x00` is invalid; `0x80`–`0xFF` are auxiliary
 * records such as the scan-response nickname, never beacon bodies.
 *
 * Invariants, checked on construction (as `IllegalArgumentException`) and on decoding (as
 * [DiscoveryFormatException]): [visibility] is never [Visibility.HIDDEN] (a hidden device does not advertise, F‑A5);
 * [Capabilities.Flag.CONNECTED_TO_WIFI] is set exactly when [networkHint] is non-zero; [Capabilities.Flag.STATION_ON_5GHZ]
 * implies [Capabilities.Flag.CONNECTED_TO_WIFI]; a [classicAddress] is [BluetoothAddress.isUsable]. A received v1
 * body with an all-zero (or otherwise unusable) address is read as having none: S10 lets phones send zeros there.
 */
data class BeaconBody(
    val ephemeralId: EphemeralId,
    val capabilities: Capabilities,
    val networkHint: NetworkHint,
    val visibility: Visibility,
    val platform: DevicePlatform,
    val classicAddress: BluetoothAddress? = null,
) {
    init {
        problem(capabilities, networkHint, visibility, platform, classicAddress)?.let {
            throw IllegalArgumentException(it)
        }
    }

    /** 14, or 20 with a [classicAddress]. */
    val encodedSize: Int get() = if (classicAddress == null) SIZE else SIZE_WITH_CLASSIC_ADDRESS

    /** The version-1 wire form. */
    fun encode(): ByteArray {
        val out = ByteArray(encodedSize)
        out[0] = VERSION.toByte()
        Bytes.writeBigEndian(ephemeralId.value, out, 1, EphemeralId.SIZE)
        Bytes.writeBigEndian(capabilities.bits.toLong(), out, 7, 2)
        Bytes.writeBigEndian(networkHint.bits.toLong(), out, 9, NetworkHint.SIZE)
        out[13] = ((visibility.code shl 6) or (platform.code shl 3)).toByte()
        classicAddress?.let { Bytes.writeBigEndian(it.value, out, SIZE, BluetoothAddress.SIZE) }
        return out
    }

    companion object {
        const val VERSION: Int = 1

        /** Body size without the Classic address. */
        const val SIZE: Int = 14

        /** Body size with the Classic address (S10). */
        const val SIZE_WITH_CLASSIC_ADDRESS: Int = 20

        /** Highest version byte that still uses this layout. */
        const val LAST_COMPATIBLE_VERSION: Int = 0x0F

        /** First byte values at or above this mark auxiliary records (for example the scan-response nickname). */
        internal const val AUXILIARY_RECORD_MIN: Int = 0x80

        private const val MAX_PLATFORM_CODE = 7

        /**
         * Decodes a body.
         *
         * @throws UnsupportedBeaconVersionException for a version reserved for an incompatible layout.
         * @throws DiscoveryFormatException for any other malformed body.
         */
        fun decode(bytes: ByteArray): BeaconBody {
            if (bytes.isEmpty()) throw DiscoveryFormatException("empty beacon body")
            val version = bytes[0].toInt() and 0xFF
            when {
                version == 0 -> throw DiscoveryFormatException("beacon version 0 is invalid")
                version >= AUXILIARY_RECORD_MIN -> throw DiscoveryFormatException("not a beacon body (record type 0x${hex2(version)})")
                version > LAST_COMPATIBLE_VERSION -> throw UnsupportedBeaconVersionException(version)
            }
            if (version == VERSION) {
                if (bytes.size != SIZE && bytes.size != SIZE_WITH_CLASSIC_ADDRESS) {
                    throw DiscoveryFormatException("a v1 beacon body is $SIZE or $SIZE_WITH_CLASSIC_ADDRESS bytes, was ${bytes.size}")
                }
            } else if (bytes.size < SIZE) {
                throw DiscoveryFormatException("beacon body v$version is shorter than $SIZE bytes (${bytes.size})")
            }
            val ephemeralId = EphemeralId.fromBytes(bytes, 1)
            val capabilities = Capabilities(Bytes.readBigEndian(bytes, 7, 2).toInt())
            val networkHint = NetworkHint.fromBytes(bytes, 9)
            val packed = bytes[13].toInt() and 0xFF
            // Two bits always map to a defined visibility; HIDDEN is rejected by the invariant check below.
            val visibility = Visibility.fromCode(packed ushr 6) ?: throw DiscoveryFormatException("bad visibility")
            val platform =
                DevicePlatform.fromCode((packed ushr 3) and 0x07)
                    ?: throw DiscoveryFormatException("unknown platform code ${(packed ushr 3) and 0x07}")
            val classicAddress =
                if (version == VERSION && bytes.size == SIZE_WITH_CLASSIC_ADDRESS) {
                    BluetoothAddress.fromBytes(bytes, SIZE).takeIf { it.isUsable }
                } else {
                    null
                }
            problem(capabilities, networkHint, visibility, platform, classicAddress)?.let {
                throw DiscoveryFormatException(it)
            }
            return BeaconBody(ephemeralId, capabilities, networkHint, visibility, platform, classicAddress)
        }

        private fun problem(
            capabilities: Capabilities,
            networkHint: NetworkHint,
            visibility: Visibility,
            platform: DevicePlatform,
            classicAddress: BluetoothAddress?,
        ): String? =
            when {
                visibility == Visibility.HIDDEN -> {
                    "a HIDDEN device never advertises (F-A5)"
                }

                (Capabilities.Flag.CONNECTED_TO_WIFI in capabilities) == networkHint.isNone -> {
                    "CONNECTED_TO_WIFI must be set exactly when the network hint is non-zero (§5.2 bit 11)"
                }

                Capabilities.Flag.STATION_ON_5GHZ in capabilities && Capabilities.Flag.CONNECTED_TO_WIFI !in capabilities -> {
                    "STATION_ON_5GHZ requires CONNECTED_TO_WIFI"
                }

                classicAddress != null && !classicAddress.isUsable -> {
                    "unusable Classic address $classicAddress"
                }

                platform.code !in 0..MAX_PLATFORM_CODE -> {
                    "platform code ${platform.code} does not fit 3 bits"
                }

                else -> {
                    null
                }
            }

        private fun hex2(v: Int): String = Bytes.hex(v.toLong(), 2)
    }
}
