package com.constrivo.drop.core.discovery

/**
 * Constants of the legacy advertising payloads (architecture §5.1 as changed by spec change S11, decision 5).
 *
 * Multi-byte values inside AD structures follow the Bluetooth Core specification: the 16-bit service UUID and the
 * company identifier are little-endian on air. Everything after them (the drop record) is our own format.
 */
object AdvertisingFormat {
    /** Legacy advertising data and scan response data are each at most 31 bytes. */
    const val LEGACY_PAYLOAD_MAX: Int = 31

    /**
     * PLACEHOLDER 16-bit service UUID, from a range the Bluetooth SIG has not allocated, for debug builds only.
     * Decision 5: the §5.1 layout needs a SIG-assigned 16-bit UUID (a 128-bit one leaves too little room); replace
     * this value, and nothing else, when it is assigned.
     */
    const val PLACEHOLDER_SERVICE_UUID_16: Int = 0xDF01

    /**
     * PLACEHOLDER company identifier for the manufacturer-data carrier: `0xFFFF` is the value the Bluetooth SIG
     * reserves for internal and interoperability tests; it must not ship. S11: Windows can only publish manufacturer
     * data, so the product needs its own SIG company identifier.
     */
    const val PLACEHOLDER_COMPANY_ID: Int = 0xFFFF

    /** The service UUID used everywhere (the placeholder until decision 5 is settled). */
    const val SERVICE_UUID_16: Int = PLACEHOLDER_SERVICE_UUID_16

    /** [SERVICE_UUID_16] expanded on the Bluetooth base UUID, for APIs that take 128-bit UUIDs (scan filters). */
    val SERVICE_UUID_128: String = "0000${Bytes.hex(SERVICE_UUID_16.toLong(), 4)}-0000-1000-8000-00805f9b34fb"

    /** The company identifier used everywhere (the placeholder until assigned). */
    const val COMPANY_ID: Int = PLACEHOLDER_COMPANY_ID

    /**
     * Two bytes (`'d' 'r'`, sent in this order) that follow the company identifier and mark manufacturer data as
     * ours: a test or shared company identifier is used by other products too.
     */
    const val MANUFACTURER_MARKER: Int = 0x6472

    /** Flags AD value: LE General Discoverable Mode (bit 1) and BR/EDR Not Supported (bit 2). */
    const val FLAGS_VALUE: Int = 0x06

    const val AD_TYPE_FLAGS: Int = 0x01
    const val AD_TYPE_INCOMPLETE_16BIT_UUIDS: Int = 0x02
    const val AD_TYPE_COMPLETE_16BIT_UUIDS: Int = 0x03
    const val AD_TYPE_SERVICE_DATA_16BIT: Int = 0x16
    const val AD_TYPE_MANUFACTURER_DATA: Int = 0xFF

    /** Drop record type of a complete nickname (scan response). */
    const val RECORD_NICKNAME_COMPLETE: Int = 0x81

    /** Drop record type of a nickname shortened to fit the scan response. */
    const val RECORD_NICKNAME_SHORTENED: Int = 0x82

    /** Room for nickname bytes in a service-data scan response: 31 − (length, type, UUID, record type). */
    const val MAX_NICKNAME_BYTES_SERVICE_DATA: Int = LEGACY_PAYLOAD_MAX - 5

    /** Room for nickname bytes in a manufacturer-data scan response: 31 − (length, type, company, marker, record type). */
    const val MAX_NICKNAME_BYTES_MANUFACTURER_DATA: Int = LEGACY_PAYLOAD_MAX - 7

    fun maxNicknameBytes(carrier: BeaconCarrier): Int =
        when (carrier) {
            BeaconCarrier.SERVICE_DATA -> MAX_NICKNAME_BYTES_SERVICE_DATA
            BeaconCarrier.MANUFACTURER_DATA -> MAX_NICKNAME_BYTES_MANUFACTURER_DATA
        }
}

/**
 * One drop record: the payload that follows the service UUID (service-data carrier) or the company identifier and
 * marker (manufacturer-data carrier). Its first byte says what it is (see [BeaconBody] for the version rule).
 */
sealed interface DropRecord {
    /** A beacon body (first byte `0x01`–`0x0F`). */
    data class Beacon(
        val body: BeaconBody,
    ) : DropRecord

    /** A scan-response nickname (record `0x81` complete or `0x82` shortened), already sanitised. */
    data class Nickname(
        val text: String,
        val truncated: Boolean,
    ) : DropRecord

    /**
     * An auxiliary record this build does not know (a type `0x80`–`0xFF` from a newer version), or a nickname record
     * with nothing printable in it. Ignored.
     */
    data class Ignored(
        val type: Int,
    ) : DropRecord

    companion object {
        /**
         * Decodes a drop record.
         *
         * @throws UnsupportedBeaconVersionException for a beacon body of an incompatible future layout.
         * @throws DiscoveryFormatException for an empty record or a malformed body.
         */
        fun decode(payload: ByteArray): DropRecord {
            if (payload.isEmpty()) throw DiscoveryFormatException("empty drop record")
            val type = payload[0].toInt() and 0xFF
            if (type < BeaconBody.AUXILIARY_RECORD_MIN) return Beacon(BeaconBody.decode(payload))
            if (type != AdvertisingFormat.RECORD_NICKNAME_COMPLETE && type != AdvertisingFormat.RECORD_NICKNAME_SHORTENED) {
                return Ignored(type)
            }
            val name =
                Nicknames.decodeReceived(payload.copyOfRange(1, payload.size))
                    ?: return Ignored(type)
            return Nickname(name.text, name.truncated || type == AdvertisingFormat.RECORD_NICKNAME_SHORTENED)
        }

        /**
         * For scanners that hand over service data per UUID (BlueZ `ServiceData`, CoreBluetooth
         * `kCBAdvDataServiceData`): [payload] is the data after the UUID. Returns null when [uuid16] is not ours.
         *
         * @throws DiscoveryFormatException as [decode].
         */
        fun fromServiceData(
            uuid16: Int,
            payload: ByteArray,
        ): DropRecord? = if (uuid16 == AdvertisingFormat.SERVICE_UUID_16) decode(payload) else null

        /**
         * For scanners that hand over manufacturer data per company (BlueZ `ManufacturerData`, WinRT
         * `BluetoothLEManufacturerData`): [data] is what follows the company identifier. Returns null when the
         * company or the marker is not ours.
         *
         * @throws DiscoveryFormatException as [decode].
         */
        fun fromManufacturerData(
            companyId: Int,
            data: ByteArray,
        ): DropRecord? {
            if (companyId != AdvertisingFormat.COMPANY_ID || data.size < 2) return null
            val marker = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            if (marker != AdvertisingFormat.MANUFACTURER_MARKER) return null
            return decode(data.copyOfRange(2, data.size))
        }
    }
}

/** One AD structure: `length ‖ type ‖ data`, with `length = 1 + data.size`. */
class AdStructure(
    val type: Int,
    val data: ByteArray,
) {
    override fun toString(): String = "AdStructure(type=0x${Bytes.hex(type.toLong(), 2)}, data=${Bytes.hex(data)})"
}

/** What [BeaconAdvertisements.parse] found in one received advertisement. */
data class ParsedAdvertisement(
    val body: BeaconBody,
    val carrier: BeaconCarrier,
    val nickname: String?,
    val nicknameTruncated: Boolean,
)

/**
 * Builds and parses the complete legacy advertising payloads of both carriers (S11):
 *
 * - [BeaconCarrier.SERVICE_DATA] (Android, Linux BlueZ): Flags `02 01 06` ‖ Complete 16-bit UUID list
 *   `03 03 uu uu` ‖ Service Data `LL 16 uu uu` + body. 25 bytes, or 31 with the Classic address.
 * - [BeaconCarrier.MANUFACTURER_DATA] (Windows): Flags `02 01 06` ‖ Manufacturer Specific `LL FF cc cc 64 72` + body.
 *   23 bytes, or 29 with the Classic address. Windows sets Flags itself; they are included here so the size
 *   accounting matches what goes on air.
 * - Scan response: Service Data `LL 16 uu uu 81|82` + nickname (UTF-8, cut at a code point boundary to at most
 *   26 bytes), or for platforms whose scanners key manufacturer data separately, Manufacturer Specific
 *   `LL FF cc cc 64 72 81|82` + nickname (at most 24 bytes). No 128-bit UUID: with the 16-bit UUID of decision 5 it
 *   is not needed and would not fit next to a useful name. Type `0x82` marks a shortened name.
 *
 * Every built payload is checked to fit [AdvertisingFormat.LEGACY_PAYLOAD_MAX].
 */
object BeaconAdvertisements {
    /**
     * The carrier payload the platform API takes: for service data the body (the platform adds the UUID), for
     * manufacturer data marker ‖ body (the platform adds the company identifier).
     */
    fun carrierPayload(
        body: BeaconBody,
        carrier: BeaconCarrier,
    ): ByteArray =
        when (carrier) {
            BeaconCarrier.SERVICE_DATA -> body.encode()
            BeaconCarrier.MANUFACTURER_DATA -> markerBytes() + body.encode()
        }

    /**
     * The scan-response record payload for [nickname] (same framing as [carrierPayload]), or null when the
     * sanitised nickname is empty.
     */
    fun nicknamePayload(
        nickname: String,
        carrier: BeaconCarrier,
    ): ByteArray? {
        val clean = Nicknames.normalize(nickname, Nicknames.MAX_BYTES) ?: return null
        val cut = Nicknames.truncateUtf8(clean.text, AdvertisingFormat.maxNicknameBytes(carrier)).trimEnd()
        if (cut.isEmpty()) return null
        val type =
            if (clean.truncated || cut.length != clean.text.length) {
                AdvertisingFormat.RECORD_NICKNAME_SHORTENED
            } else {
                AdvertisingFormat.RECORD_NICKNAME_COMPLETE
            }
        val record = byteArrayOf(type.toByte()) + cut.encodeToByteArray()
        return when (carrier) {
            BeaconCarrier.SERVICE_DATA -> record
            BeaconCarrier.MANUFACTURER_DATA -> markerBytes() + record
        }
    }

    /** The complete legacy advertising data for [body] on [carrier]. */
    fun advertisingData(
        body: BeaconBody,
        carrier: BeaconCarrier,
    ): ByteArray {
        val flags = structure(AdvertisingFormat.AD_TYPE_FLAGS, byteArrayOf(AdvertisingFormat.FLAGS_VALUE.toByte()))
        val payload =
            when (carrier) {
                BeaconCarrier.SERVICE_DATA -> {
                    flags +
                        structure(AdvertisingFormat.AD_TYPE_COMPLETE_16BIT_UUIDS, uuidBytes()) +
                        structure(AdvertisingFormat.AD_TYPE_SERVICE_DATA_16BIT, uuidBytes() + carrierPayload(body, carrier))
                }

                BeaconCarrier.MANUFACTURER_DATA -> {
                    flags + structure(AdvertisingFormat.AD_TYPE_MANUFACTURER_DATA, companyBytes() + carrierPayload(body, carrier))
                }
            }
        checkFits(payload, "advertising data")
        return payload
    }

    /** The complete scan response data for [nickname], or null when there is nothing printable to send. */
    fun scanResponseData(
        nickname: String,
        carrier: BeaconCarrier = BeaconCarrier.SERVICE_DATA,
    ): ByteArray? {
        val record = nicknamePayload(nickname, carrier) ?: return null
        val payload =
            when (carrier) {
                BeaconCarrier.SERVICE_DATA -> structure(AdvertisingFormat.AD_TYPE_SERVICE_DATA_16BIT, uuidBytes() + record)
                BeaconCarrier.MANUFACTURER_DATA -> structure(AdvertisingFormat.AD_TYPE_MANUFACTURER_DATA, companyBytes() + record)
            }
        checkFits(payload, "scan response")
        return payload
    }

    /**
     * Splits raw advertising bytes into AD structures, as Android's `ScanRecord.getBytes()` or WinRT data sections
     * deliver them (advertising data and scan response concatenated). A zero length byte is an empty structure
     * (early termination or padding) and is skipped, so zero-padded buffers parse.
     *
     * @throws DiscoveryFormatException when a length byte runs past the end of [record].
     */
    fun parseStructures(record: ByteArray): List<AdStructure> {
        val out = ArrayList<AdStructure>()
        var i = 0
        while (i < record.size) {
            val length = record[i].toInt() and 0xFF
            if (length == 0) {
                i++
                continue
            }
            if (length > record.size - i - 1) {
                throw DiscoveryFormatException("AD structure at offset $i claims $length bytes but ${record.size - i - 1} remain")
            }
            out += AdStructure(record[i + 1].toInt() and 0xFF, record.copyOfRange(i + 2, i + 1 + length))
            i += 1 + length
        }
        return out
    }

    /**
     * Finds a drop beacon in raw advertising bytes (advertising data and scan response together), from either
     * carrier. Unrelated AD structures, other UUIDs, other companies, foreign manufacturer data under our company
     * identifier and unknown drop record types are ignored.
     *
     * @return null when the advertisement carries no drop beacon body.
     * @throws UnsupportedBeaconVersionException when a body is of an incompatible future layout.
     * @throws DiscoveryFormatException for malformed AD lengths, a malformed drop record, or two different bodies.
     */
    fun parse(record: ByteArray): ParsedAdvertisement? {
        var body: BeaconBody? = null
        var carrier: BeaconCarrier? = null
        var nickname: DropRecord.Nickname? = null
        for (s in parseStructures(record)) {
            val (decoded, via) =
                when (s.type) {
                    AdvertisingFormat.AD_TYPE_SERVICE_DATA_16BIT -> {
                        if (s.data.size < 2) continue
                        (DropRecord.fromServiceData(le16(s.data), s.data.copyOfRange(2, s.data.size)) ?: continue) to
                            BeaconCarrier.SERVICE_DATA
                    }

                    AdvertisingFormat.AD_TYPE_MANUFACTURER_DATA -> {
                        if (s.data.size < 2) continue
                        (DropRecord.fromManufacturerData(le16(s.data), s.data.copyOfRange(2, s.data.size)) ?: continue) to
                            BeaconCarrier.MANUFACTURER_DATA
                    }

                    else -> {
                        continue
                    }
                }
            when (decoded) {
                is DropRecord.Beacon -> {
                    if (body != null && body != decoded.body) throw DiscoveryFormatException("two different beacon bodies")
                    if (body == null) {
                        body = decoded.body
                        carrier = via
                    }
                }

                is DropRecord.Nickname -> {
                    if (nickname == null) nickname = decoded
                }

                is DropRecord.Ignored -> {
                    // An auxiliary record of a newer version: skipped.
                }
            }
        }
        val found = body ?: return null
        return ParsedAdvertisement(found, carrier!!, nickname?.text, nickname?.truncated ?: false)
    }

    private fun structure(
        type: Int,
        data: ByteArray,
    ): ByteArray {
        check(data.size + 1 <= 0xFF) { "AD structure too long" }
        return byteArrayOf((data.size + 1).toByte(), type.toByte()) + data
    }

    private fun checkFits(
        payload: ByteArray,
        what: String,
    ) {
        check(payload.size <= AdvertisingFormat.LEGACY_PAYLOAD_MAX) {
            "$what is ${payload.size} bytes, over the ${AdvertisingFormat.LEGACY_PAYLOAD_MAX}-byte legacy limit"
        }
    }

    private fun uuidBytes(): ByteArray = le16Bytes(AdvertisingFormat.SERVICE_UUID_16)

    private fun companyBytes(): ByteArray = le16Bytes(AdvertisingFormat.COMPANY_ID)

    private fun markerBytes(): ByteArray =
        byteArrayOf((AdvertisingFormat.MANUFACTURER_MARKER ushr 8).toByte(), AdvertisingFormat.MANUFACTURER_MARKER.toByte())

    private fun le16Bytes(v: Int): ByteArray = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    private fun le16(data: ByteArray): Int = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
}
