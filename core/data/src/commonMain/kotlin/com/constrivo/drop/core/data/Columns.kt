package com.constrivo.drop.core.data

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferPhase
import com.constrivo.drop.core.protocol.TransferRole

/**
 * Maps a domain value to the text stored in a database column and back (architecture §12).
 *
 * [encode] never fails for a valid value. [decode] accepts exactly the texts [encode] produces; anything else raises
 * [DataCorruptionException], because the schema's CHECK constraints keep other values out of the columns.
 */
interface ColumnCodec<T> {
    fun encode(value: T): String

    /** @throws DataCorruptionException if [text] is not a value this codec writes. */
    fun decode(text: String): T
}

/**
 * `transfer.status` (architecture §12). The eight values of the spec; [TransferPhase] maps onto them with [of]
 * (both streaming phases are [STREAMING], and `Reconnecting` and `Parked` of spec change S8 are both [INTERRUPTED]).
 */
enum class TransferStatus(
    val dbValue: String,
    /** Done, failed or cancelled: the transfer has a finish time and no longer changes. */
    val isTerminal: Boolean = false,
) {
    OFFERED("offered"),
    ACCEPTED("accepted"),
    STREAMING("streaming"),
    INTERRUPTED("interrupted"),
    VERIFYING("verifying"),
    DONE("done", isTerminal = true),
    FAILED("failed", isTerminal = true),
    CANCELLED("cancelled", isTerminal = true),
    ;

    companion object : ColumnCodec<TransferStatus> {
        /** The stored status of a state-machine phase; equals `TransferStatus.decode(phase.storedStatus)`. */
        fun of(phase: TransferPhase): TransferStatus =
            when (phase) {
                TransferPhase.OFFERED -> OFFERED
                TransferPhase.ACCEPTED -> ACCEPTED
                TransferPhase.STREAMING_BLUETOOTH, TransferPhase.STREAMING_WIFI -> STREAMING
                TransferPhase.VERIFYING -> VERIFYING
                TransferPhase.RECONNECTING, TransferPhase.PARKED -> INTERRUPTED
                TransferPhase.DONE -> DONE
                TransferPhase.CANCELLED -> CANCELLED
                TransferPhase.FAILED -> FAILED
            }

        override fun encode(value: TransferStatus): String = value.dbValue

        override fun decode(text: String): TransferStatus =
            entries.firstOrNull { it.dbValue == text } ?: throw DataCorruptionException("unknown transfer status '$text'")
    }
}

/** `transfer.direction`: whether this device sent or received the transfer. */
enum class TransferDirection(
    val dbValue: String,
) {
    SEND("send"),
    RECEIVE("receive"),
    ;

    companion object : ColumnCodec<TransferDirection> {
        /** The direction of the side that runs [role]. */
        fun of(role: TransferRole): TransferDirection =
            when (role) {
                TransferRole.SENDER -> SEND
                TransferRole.RECEIVER -> RECEIVE
            }

        override fun encode(value: TransferDirection): String = value.dbValue

        override fun decode(text: String): TransferDirection =
            entries.firstOrNull { it.dbValue == text } ?: throw DataCorruptionException("unknown transfer direction '$text'")
    }
}

/** `transfer_file.status`. [isFinal] files no longer change status. */
enum class TransferFileStatus(
    val dbValue: String,
    val isFinal: Boolean = false,
) {
    /** Listed but no byte moved yet. */
    PENDING("pending"),

    /** Bytes are moving. */
    IN_PROGRESS("in_progress"),

    /** Receiver: verified and published. Sender: the receiver confirmed it (its `FileDone` / `Complete`). */
    DONE("done", isFinal = true),

    /** Failed after three mismatches (§7.8), or the source became unreadable. */
    FAILED("failed", isFinal = true),

    /** The transfer ended before this file finished. */
    CANCELLED("cancelled", isFinal = true),
    ;

    companion object : ColumnCodec<TransferFileStatus> {
        override fun encode(value: TransferFileStatus): String = value.dbValue

        override fun decode(text: String): TransferFileStatus =
            entries.firstOrNull { it.dbValue == text } ?: throw DataCorruptionException("unknown file status '$text'")
    }
}

/** `transfer.band`: the Wi-Fi band of the measured link frequency (`LinkReady.freq_mhz`), for the badge (F-F2). */
enum class WifiBand(
    val dbValue: String,
) {
    GHZ_2_4("2.4"),
    GHZ_5("5"),
    GHZ_6("6"),
    ;

    companion object : ColumnCodec<WifiBand> {
        /**
         * The band of a channel centre frequency: 2400–2500 MHz is 2.4 GHz, 4900–5924 MHz is 5 GHz (including the
         * 4.9 GHz public-safety and 5.9 GHz channels) and 5925–7125 MHz is 6 GHz. Null for anything else, including
         * the `0` sent when no Wi-Fi link exists.
         */
        fun fromFrequencyMhz(frequencyMhz: Int): WifiBand? =
            when (frequencyMhz) {
                in 2400..2500 -> GHZ_2_4
                in 4900..5924 -> GHZ_5
                in 5925..7125 -> GHZ_6
                else -> null
            }

        override fun encode(value: WifiBand): String = value.dbValue

        override fun decode(text: String): WifiBand =
            entries.firstOrNull { it.dbValue == text } ?: throw DataCorruptionException("unknown Wi-Fi band '$text'")
    }
}

/** `transfer.transport`: [LinkKind.wireName] (`lan`, `p2p`, `hotspot`, `bluetooth`), the same text as in CBOR. */
object LinkKindColumn : ColumnCodec<LinkKind> {
    override fun encode(value: LinkKind): String = value.wireName

    override fun decode(text: String): LinkKind = LinkKind.fromWire(text) ?: throw DataCorruptionException("unknown link kind '$text'")
}

/**
 * `device.platform`: [DevicePlatform.wireName] (`phone`, `laptop`, `desktop`, `browser`), the `plat` values of the
 * mDNS record. [DevicePlatform.UNKNOWN], a platform added by a later app version, is stored as `unknown`.
 */
object DevicePlatformColumn : ColumnCodec<DevicePlatform> {
    const val UNKNOWN_VALUE: String = "unknown"

    override fun encode(value: DevicePlatform): String = if (value.isKnown) value.wireName else UNKNOWN_VALUE

    override fun decode(text: String): DevicePlatform =
        when (text) {
            UNKNOWN_VALUE -> DevicePlatform.UNKNOWN
            else -> DevicePlatform.fromWire(text).takeIf { it.isKnown } ?: throw DataCorruptionException("unknown platform '$text'")
        }
}

/**
 * `transfer.hint_codes`: [HintCode.wireName]s joined by commas, in the order the hints first fired, each at most
 * once. The empty list is the empty string (the repository stores it as NULL).
 */
object HintCodesColumn : ColumnCodec<List<HintCode>> {
    override fun encode(value: List<HintCode>): String = value.distinct().joinToString(",") { it.wireName }

    override fun decode(text: String): List<HintCode> {
        if (text.isEmpty()) return emptyList()
        return text
            .split(',')
            .map { HintCode.fromWire(it) ?: throw DataCorruptionException("unknown hint code '$it'") }
            .distinct()
    }
}

/**
 * `transfer.mime_histogram`: the `Offer` MIME histogram (N12), MIME type or bucket to file count, as
 * `type=count` pairs joined by commas in map order. `%`, `,`, `=` and the ASCII control characters in a type are
 * written as `%XX` (upper-case hex of the character code), and only those escapes are accepted back. The empty map is
 * the empty string (stored as NULL).
 */
object MimeHistogramColumn : ColumnCodec<Map<String, Int>> {
    override fun encode(value: Map<String, Int>): String =
        value.entries.joinToString(",") { (mime, count) ->
            require(mime.isNotEmpty()) { "a histogram MIME type must not be empty" }
            require(count >= 1) { "histogram counts must be positive" }
            "${escape(mime)}=$count"
        }

    override fun decode(text: String): Map<String, Int> {
        if (text.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (pair in text.split(',')) {
            val parts = pair.split('=')
            if (parts.size != 2) throw DataCorruptionException("malformed histogram entry '$pair'")
            val mime = unescape(parts[0])
            val count = parts[1].toIntOrNull()?.takeIf { it >= 1 && parts[1].all { c -> c in '0'..'9' } }
            if (mime.isEmpty() || count == null) throw DataCorruptionException("malformed histogram entry '$pair'")
            if (out.put(mime, count) != null) throw DataCorruptionException("histogram lists '$mime' twice")
        }
        return out
    }

    private fun needsEscape(c: Char): Boolean = c == '%' || c == ',' || c == '=' || c.code < 0x20 || c.code == 0x7F

    private fun escape(mime: String): String {
        val out = StringBuilder(mime.length)
        for (c in mime) {
            if (needsEscape(c)) {
                out.append('%').append(HEX[c.code ushr 4]).append(HEX[c.code and 0xF])
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    /** Reverses [escape]; only the canonical escapes [escape] writes are accepted. */
    private fun unescape(text: String): String {
        if ('%' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            if (i + 2 >= text.length) throw DataCorruptionException("truncated escape in histogram type '$text'")
            val hi = HEX.indexOf(text[i + 1])
            val lo = HEX.indexOf(text[i + 2])
            val decoded = if (hi < 0 || lo < 0) null else ((hi shl 4) or lo).toChar()
            if (decoded == null || !needsEscape(decoded)) throw DataCorruptionException("bad escape in histogram type '$text'")
            out.append(decoded)
            i += 3
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
