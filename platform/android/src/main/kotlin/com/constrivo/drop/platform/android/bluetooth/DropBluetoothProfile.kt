package com.constrivo.drop.platform.android.bluetooth

import com.constrivo.drop.core.discovery.AdvertisingFormat
import java.io.IOException
import java.util.UUID

/**
 * The Bluetooth profile of the handshake and head-start channel (architecture §6.1 as changed by S10 and WP7b). Every
 * platform that talks to an Android phone over Bluetooth (desktops in WP10, iPhone later) must use exactly these
 * values; they live in this one file so the rename of decision 1 and the SIG assignment of decision 5 stay one commit.
 *
 * - **GATT service** [SERVICE_UUID]: the advertised 16-bit service UUID on the Bluetooth base UUID, so the UUID a
 *   scanner filters on is the service it connects to. Characteristics:
 *   - [CHANNEL_INFO_UUID] (read): [ChannelInfo], where the LE L2CAP PSM is published.
 *   - [CLIENT_TO_SERVER_UUID] (write without response, and write): segments of the GATT stream, client to server.
 *   - [SERVER_TO_CLIENT_UUID] (notify, with the [CCCD_UUID] descriptor): segments, server to client.
 *   No characteristic needs encryption or authentication, so no pairing is ever requested (our own handshake secures
 *   the channel, §6).
 * - **RFCOMM** service record [RFCOMM_SERVICE_UUID] named [RFCOMM_SERVICE_NAME], toward desktops that publish their
 *   Classic address in the beacon (S10).
 */
object DropBluetoothProfile {
    val SERVICE_UUID: UUID = UUID.fromString(AdvertisingFormat.SERVICE_UUID_128)

    /** Read: [ChannelInfo]. */
    val CHANNEL_INFO_UUID: UUID = UUID.fromString("b7c90001-7a3e-4f8e-9d41-6c2f0e5a8d13")

    /** Write without response: GATT stream segments from the client (the device that connected). */
    val CLIENT_TO_SERVER_UUID: UUID = UUID.fromString("b7c90002-7a3e-4f8e-9d41-6c2f0e5a8d13")

    /** Notify: GATT stream segments from the server. */
    val SERVER_TO_CLIENT_UUID: UUID = UUID.fromString("b7c90003-7a3e-4f8e-9d41-6c2f0e5a8d13")

    /** The standard Client Characteristic Configuration descriptor. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** SDP service UUID of the RFCOMM channel; the same UUID as the GATT service. */
    val RFCOMM_SERVICE_UUID: UUID = SERVICE_UUID

    const val RFCOMM_SERVICE_NAME: String = "Drop"

    /** The ATT MTU a client asks for: 517 gives the 512-byte attribute maximum per write. */
    const val PREFERRED_ATT_MTU: Int = 517

    /** The ATT MTU every LE link starts with. */
    const val DEFAULT_ATT_MTU: Int = 23

    /** ATT header bytes of a write or notification. */
    const val ATT_HEADER: Int = 3

    /** The largest attribute value (Core spec Vol 3 Part F §3.2.9). */
    const val MAX_ATTRIBUTE_VALUE: Int = 512

    /** The value bytes one write or notification carries at [attMtu]. */
    fun segmentSize(attMtu: Int): Int = (attMtu - ATT_HEADER).coerceIn(DEFAULT_ATT_MTU - ATT_HEADER, MAX_ATTRIBUTE_VALUE)
}

/** A peer's channel-info value did not parse. */
class BluetoothProfileException(
    message: String,
) : IOException(message)

/**
 * The value of [DropBluetoothProfile.CHANNEL_INFO_UUID]: `u8 version (1) ‖ u8 flags ‖ u16 PSM (big-endian)`, 4 bytes.
 * Flag bit 0 says an LE L2CAP channel is listening on the PSM; other bits are reserved (sent 0, ignored). A later version
 * may append fields; readers take the first four bytes of any version ≥ 1.
 *
 * @property l2capPsm the LE L2CAP PSM to connect to, or null when this device does not listen (the GATT stream is the
 *   only way in).
 */
data class ChannelInfo(
    val l2capPsm: Int?,
) {
    init {
        require(l2capPsm == null || l2capPsm in 1..0xFFFF) { "PSM $l2capPsm is not a 16-bit value" }
    }

    fun encode(): ByteArray {
        val psm = l2capPsm ?: 0
        val flags = if (l2capPsm != null) FLAG_L2CAP else 0
        return byteArrayOf(VERSION.toByte(), flags.toByte(), (psm ushr 8).toByte(), psm.toByte())
    }

    companion object {
        const val VERSION: Int = 1
        const val SIZE: Int = 4
        private const val FLAG_L2CAP = 0x01

        /** @throws BluetoothProfileException for a value shorter than four bytes, version 0, or a listening PSM of 0. */
        fun decode(value: ByteArray): ChannelInfo {
            if (value.size < SIZE) throw BluetoothProfileException("channel info is ${value.size} bytes, need $SIZE")
            val version = value[0].toInt() and 0xFF
            if (version < VERSION) throw BluetoothProfileException("channel info version $version")
            val flags = value[1].toInt() and 0xFF
            val psm = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
            if (flags and FLAG_L2CAP == 0) return ChannelInfo(null)
            if (psm == 0) throw BluetoothProfileException("L2CAP flag set with PSM 0")
            return ChannelInfo(psm)
        }
    }
}

/** Which Bluetooth transport a [BluetoothDataChannel] runs on (architecture §6.1 note, WP7b). */
enum class BluetoothTransport {
    /** LE L2CAP connection-oriented channel: the primary Android↔Android channel. */
    L2CAP,

    /** The GATT stream: the fallback where L2CAP fails, and the way in for stacks without LE L2CAP. */
    GATT,

    /** RFCOMM toward a desktop that published its Classic address (S10). */
    RFCOMM,
}

/**
 * A Bluetooth link to a peer as the transfer engine uses it: a [com.constrivo.drop.core.protocol.DataChannel] of kind
 * `BLUETOOTH` (the handshake, the head start and the Bluetooth control stream, §7.5), plus which [transport] carries it
 * and the peer's address for logs.
 */
interface BluetoothDataChannel : com.constrivo.drop.core.protocol.DataChannel {
    val transport: BluetoothTransport

    /** The peer's Bluetooth address as the platform reports it, or null when unknown. */
    val remoteAddress: String?
}
