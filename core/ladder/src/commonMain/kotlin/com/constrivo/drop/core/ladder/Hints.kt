package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.protocol.Hint
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.ThermalLevel

/**
 * A one-line speed hint (F-F3, design §8.2): a [code] and its [params], ready for a `Hint` message ([toMessage]) or
 * for the UI ([copyKey], [englishText]). Params use the keys [PARAM_NAME], [PARAM_COUNT] and [PARAM_SIDE].
 *
 * @throws IllegalArgumentException from the constructor when [params] break the `Hint` message limits (§7.2).
 */
data class LadderHint(
    val code: HintCode,
    val params: Map<String, String> = emptyMap(),
) {
    init {
        Hint(code, params) // validates the keys and values against the wire limits
    }

    /** String resource key for the UI (WP8): `hint.` + the wire name, for example `hint.band24`. */
    val copyKey: String get() = "hint.${code.wireName}"

    /**
     * The English copy of design §8.2 with the params filled in. `sta_band24` uses the N9 text, and the forms without a
     * known name (and the peer form of `sta_band24`) are new copy added by WP5 for design review.
     */
    val englishText: String
        get() {
            val name = params[PARAM_NAME]
            return when (code) {
                HintCode.BAND24 -> {
                    "Move closer for full speed"
                }

                HintCode.PEER_BAND24_ONLY -> {
                    if (name !=
                        null
                    ) {
                        "$name's device supports 2.4 GHz only"
                    } else {
                        "The other device supports 2.4 GHz only"
                    }
                }

                HintCode.SDCARD -> {
                    "Saving to SD card is limiting speed"
                }

                HintCode.THERMAL -> {
                    "Phone is warm and slowing down"
                }

                HintCode.BUNDLING -> {
                    "Bundling ${params[PARAM_COUNT] ?: "many"} small files"
                }

                HintCode.BT_FALLBACK -> {
                    "Slow mode: Wi\u2011Fi is off on one device"
                }

                HintCode.LAN_SLOW -> {
                    "Switching to a direct link\u2026"
                }

                HintCode.STATION_BAND24 -> {
                    when {
                        params[PARAM_SIDE] != SIDE_PEER -> "Your Wi\u2011Fi network is on 2.4 GHz"
                        name != null -> "$name's Wi\u2011Fi network is on 2.4 GHz"
                        else -> "The other device's Wi\u2011Fi network is on 2.4 GHz"
                    }
                }
            }
        }

    /** The `Hint` control message for this hint (§7.2). */
    fun toMessage(transferId: TransferId? = null): Hint = Hint(code, params, transferId)

    companion object {
        /** The other device's nickname (`{Name}` in design §8.2). */
        const val PARAM_NAME: String = "name"

        /** A count (`{N}` in "Bundling {N} small files"). */
        const val PARAM_COUNT: String = "count"

        /** Whose station pins the channel for `sta_band24`: [SIDE_LOCAL] or [SIDE_PEER], seen from the hint's owner. */
        const val PARAM_SIDE: String = "side"
        const val SIDE_LOCAL: String = "local"
        const val SIDE_PEER: String = "peer"

        /**
         * The hint of a received `Hint` message, or null for a code this build does not know (the UI skips those,
         * §7.2 forward compatibility). A message's `side` is from the sender's point of view, so it is flipped.
         */
        fun fromMessage(message: Hint): LadderHint? {
            val code = message.hintCode ?: return null
            val params =
                when (message.params[PARAM_SIDE]) {
                    SIDE_LOCAL -> message.params + (PARAM_SIDE to SIDE_PEER)
                    SIDE_PEER -> message.params + (PARAM_SIDE to SIDE_LOCAL)
                    else -> message.params
                }
            return LadderHint(code, params)
        }

        fun band24(): LadderHint = LadderHint(HintCode.BAND24)

        fun peerBand24Only(peerName: String?): LadderHint = LadderHint(HintCode.PEER_BAND24_ONLY, nameParam(peerName))

        /** `sta_band24` (N9): the station of [side] ([Side.LOCAL]: "Your Wi-Fi network...") pins the group to 2.4 GHz. */
        fun stationBand24(
            side: Side,
            peerName: String?,
        ): LadderHint =
            if (side == Side.LOCAL) {
                LadderHint(HintCode.STATION_BAND24, mapOf(PARAM_SIDE to SIDE_LOCAL))
            } else {
                LadderHint(HintCode.STATION_BAND24, nameParam(peerName) + (PARAM_SIDE to SIDE_PEER))
            }

        fun lanSlow(): LadderHint = LadderHint(HintCode.LAN_SLOW)

        fun btFallback(): LadderHint = LadderHint(HintCode.BT_FALLBACK)

        fun thermal(): LadderHint = LadderHint(HintCode.THERMAL)

        fun sdcard(): LadderHint = LadderHint(HintCode.SDCARD)

        fun bundling(files: Int): LadderHint {
            require(files > 0) { "bundling needs a positive file count" }
            return LadderHint(HintCode.BUNDLING, mapOf(PARAM_COUNT to files.toString()))
        }

        private fun nameParam(name: String?): Map<String, String> = if (name.isNullOrEmpty()) emptyMap() else mapOf(PARAM_NAME to name)
    }
}

/**
 * The band hint for a link that is up (design §8.2 with N9), shared by [LinkLifecycle] and [HintRules] so that both say
 * the same thing. Only a 2.4 GHz link gets one, and only a true one:
 * - the peer does not support 5 GHz: `peer_band24_only` (Wi-Fi Direct and hotspot);
 * - this device does not support 5 GHz: none (the peer shows `peer_band24_only`);
 * - Wi-Fi Direct whose host's station is on 2.4 GHz: `sta_band24` (the station pins the channel, N9);
 * - other Wi-Fi Direct: `band24` ("Move closer for full speed");
 * - a hotspot otherwise gets none, because apps cannot choose its band (N8), so moving closer would not help.
 *
 * LAN and Bluetooth links never get a band hint.
 */
object BandHints {
    fun forLink(
        kind: LinkKind,
        freqMhz: Int?,
        localSupportsFiveGhz: Boolean,
        peerSupportsFiveGhz: Boolean,
        host: Side?,
        hostStationOn24: Boolean,
        peerName: String?,
    ): LadderHint? {
        if (kind != LinkKind.P2P && kind != LinkKind.HOTSPOT) return null
        if (WifiBand.fromFrequency(freqMhz) != WifiBand.BAND_2_4_GHZ) return null
        return when {
            !peerSupportsFiveGhz -> LadderHint.peerBand24Only(peerName)
            !localSupportsFiveGhz -> null
            kind == LinkKind.HOTSPOT -> null
            hostStationOn24 && host != null -> LadderHint.stationBand24(host, peerName)
            else -> LadderHint.band24()
        }
    }
}

/**
 * Inputs of [HintRules] (F-F3): the link data flows on, both devices' capabilities, and the engine's conditions.
 *
 * @property linkKind the link carrying data now; null before any link, [LinkKind.BLUETOOTH] during the head start.
 * @property freqMhz its measured channel (null or 0 when unknown).
 * @property host who hosts [linkKind] when it is a group or hotspot; [hostStationOn24] as in [LinkCandidate].
 * @property thermal this device's thermal level; `SEVERE` and above lowers the streams to 2 and shows the hint (§7.8).
 * @property destinationRemovable the transfer is saved to removable storage: this device's `FileStore` when receiving,
 *   the receiver's capability bit 10 (or its `sdcard` hint) when sending.
 * @property bundledFiles how many small files are being bundled (0 when none).
 * @property bluetoothFallback the transfer is on Bluetooth because Wi-Fi is off on one device.
 * @property switchingFromSlowLan the LAN measured under 10 MB/s and a direct link is being set up.
 */
data class HintInputs(
    val linkKind: LinkKind?,
    val freqMhz: Int? = null,
    val local: Capabilities,
    val peer: Capabilities,
    val peerName: String? = null,
    val host: Side? = null,
    val hostStationOn24: Boolean = false,
    val thermal: ThermalLevel = ThermalLevel.NONE,
    val destinationRemovable: Boolean = false,
    val bundledFiles: Int = 0,
    val bluetoothFallback: Boolean = false,
    val switchingFromSlowLan: Boolean = false,
) {
    init {
        require(bundledFiles >= 0) { "bundled file count must be non-negative" }
    }

    companion object {
        /** The inputs for [state] of a [LadderRunner], plus the engine's conditions. */
        fun from(
            state: LadderState,
            thermal: ThermalLevel = ThermalLevel.NONE,
            destinationRemovable: Boolean = false,
            bundledFiles: Int = 0,
        ): HintInputs {
            val plan = state.plan
            val candidate = state.dataCandidate
            return HintInputs(
                linkKind = candidate?.kind ?: if (plan.hasBluetooth) LinkKind.BLUETOOTH else null,
                freqMhz = state.freqMhz,
                local = plan.input.local.capabilities,
                peer = plan.input.peer.capabilities,
                peerName = plan.input.peerName,
                host = candidate?.host?.takeIf { candidate.mode.isDirect },
                hostStationOn24 = candidate?.hostStationOn24 ?: false,
                thermal = thermal,
                destinationRemovable = destinationRemovable,
                bundledFiles = bundledFiles,
                bluetoothFallback = state.hints.any { it.code == HintCode.BT_FALLBACK },
                switchingFromSlowLan = state.hints.any { it.code == HintCode.LAN_SLOW },
            )
        }
    }
}

/**
 * Which one-line hint to show (F-F3, design §8.2). Every condition that holds becomes a hint; the UI shows the first
 * by [PRIORITY]:
 * 1. `bt_fallback`: a hundred times slower than Wi-Fi, and fixed by turning Wi-Fi on;
 * 2. `lan_slow`: explains the badge change happening right now, and passes within seconds;
 * 3. `sta_band24`, `band24`, `peer_band24_only`: the link band (at most one holds, see [BandHints]);
 * 4. `thermal`: this phone is throttling (streams lowered to 2);
 * 5. `sdcard`: storage is the bottleneck;
 * 6. `bundling`: informational only.
 *
 * Nothing fires for a healthy pair: two 5 GHz phones on a 5 GHz link, cool, saving to internal storage, sending large
 * files, get no hint at any stage (T-01 on the Pixel pair).
 */
object HintRules {
    /** Display priority, most important first. */
    val PRIORITY: List<HintCode> =
        listOf(
            HintCode.BT_FALLBACK,
            HintCode.LAN_SLOW,
            HintCode.STATION_BAND24,
            HintCode.BAND24,
            HintCode.PEER_BAND24_ONLY,
            HintCode.THERMAL,
            HintCode.SDCARD,
            HintCode.BUNDLING,
        )

    /** Thermal level from which the hint shows; the stream count drops to 2 at the same level (§7.8). */
    val THERMAL_HINT_LEVEL: ThermalLevel = ThermalLevel.SEVERE

    /** Bundling this many small files or more shows "Bundling {N} small files" ("many", design §8.2). Tunable in the lab. */
    const val BUNDLING_HINT_MIN_FILES: Int = 10

    /** The hint to show, or null when none applies. */
    fun select(inputs: HintInputs): LadderHint? = active(inputs).firstOrNull()

    /** Every hint that holds, in [PRIORITY] order. */
    fun active(inputs: HintInputs): List<LadderHint> {
        val hints = ArrayList<LadderHint>(4)
        if (inputs.bluetoothFallback && inputs.linkKind == LinkKind.BLUETOOTH) hints += LadderHint.btFallback()
        if (inputs.switchingFromSlowLan) hints += LadderHint.lanSlow()
        val kind = inputs.linkKind
        if (kind != null) {
            BandHints
                .forLink(
                    kind = kind,
                    freqMhz = inputs.freqMhz,
                    localSupportsFiveGhz = supportsFiveGhz(inputs.local),
                    peerSupportsFiveGhz = supportsFiveGhz(inputs.peer),
                    host = inputs.host,
                    hostStationOn24 = inputs.hostStationOn24,
                    peerName = inputs.peerName,
                )?.let { hints += it }
        }
        if (inputs.thermal >= THERMAL_HINT_LEVEL) hints += LadderHint.thermal()
        if (inputs.destinationRemovable) hints += LadderHint.sdcard()
        if (inputs.bundledFiles >= BUNDLING_HINT_MIN_FILES) hints += LadderHint.bundling(inputs.bundledFiles)
        return hints.sortedBy { PRIORITY.indexOf(it.code) }
    }

    private fun supportsFiveGhz(capabilities: Capabilities): Boolean = Flag.WIFI_5GHZ in capabilities || Flag.WIFI_6GHZ in capabilities
}
