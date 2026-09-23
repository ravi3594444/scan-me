package com.constrivo.drop.core.discovery

import kotlin.jvm.JvmInline

/**
 * A Bluetooth Classic (BR/EDR) device address, the optional last six bytes of the beacon body (spec change S10):
 * desktops that can read their own adapter address publish it so phones can open RFCOMM toward them; phones cannot
 * (Android reports `02:00:00:00:00:00`) and use GATT instead.
 *
 * Held as the 48-bit value with the first displayed octet most significant: `AA:BB:CC:DD:EE:FF` is `0xAABBCCDDEEFF`
 * and travels in the beacon as the bytes `AA BB CC DD EE FF` (display order, not the little-endian order of HCI).
 */
@JvmInline
value class BluetoothAddress(
    val value: Long,
) {
    init {
        require(value in 0..MAX_VALUE) { "a Bluetooth address is 48 bits" }
    }

    /**
     * False for the all-zero address, the broadcast address and Android's `02:00:00:00:00:00` placeholder, none of
     * which can be connected to. Only usable addresses are advertised.
     */
    val isUsable: Boolean get() = value != 0L && value != MAX_VALUE && value != ANDROID_PLACEHOLDER

    fun toByteArray(): ByteArray = ByteArray(SIZE).also { Bytes.writeBigEndian(value, it, 0, SIZE) }

    /** Upper-case colon-separated form, as platform APIs expect (`AA:BB:CC:DD:EE:FF`). */
    override fun toString(): String = Bytes.hex(value, 12).uppercase().chunked(2).joinToString(":")

    companion object {
        const val SIZE: Int = 6
        const val MAX_VALUE: Long = 0xFFFF_FFFF_FFFFL
        private const val ANDROID_PLACEHOLDER: Long = 0x0200_0000_0000L

        /** Reads six bytes at [offset] in display order. The caller guarantees `offset + 6 <= bytes.size`. */
        fun fromBytes(
            bytes: ByteArray,
            offset: Int = 0,
        ): BluetoothAddress {
            require(offset >= 0 && offset + SIZE <= bytes.size) { "need $SIZE bytes at offset $offset" }
            return BluetoothAddress(Bytes.readBigEndian(bytes, offset, SIZE))
        }

        /**
         * Parses `AA:BB:CC:DD:EE:FF` (either case, `:` or `-` separators).
         *
         * @throws DiscoveryFormatException for anything else.
         */
        fun parse(text: String): BluetoothAddress {
            val parts = text.split(':', '-')
            if (text.length != 17 || parts.size != 6 || parts.any { it.length != 2 }) {
                throw DiscoveryFormatException("not a Bluetooth address")
            }
            val separators = text.filterIndexed { i, _ -> i % 3 == 2 }.toSet()
            if (separators.size != 1) throw DiscoveryFormatException("mixed separators in a Bluetooth address")
            val v = Bytes.parseHex(parts.joinToString(""), 12) ?: throw DiscoveryFormatException("not a Bluetooth address")
            return BluetoothAddress(v)
        }
    }
}
