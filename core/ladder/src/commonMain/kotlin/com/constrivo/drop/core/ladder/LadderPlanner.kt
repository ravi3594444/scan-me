package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.protocol.LinkKind

/**
 * The transport ladder planner of architecture §4 with spec changes N6, N8, N9 and N10: a pure function from both
 * devices' facts to an ordered [LadderPlan].
 *
 * Rungs, in order:
 * 1. **LAN** when both network hints are equal and non-zero, or the peer's mDNS record is live on this network
 *    ([LadderInput.lanReachable]). The hint is only a weak prior (N6), so either signal is enough. Different or zero
 *    hints without an mDNS record skip the LAN at once: two phones on mobile data go straight to Wi-Fi Direct (T-15).
 * 2. **Wi-Fi Direct** when one device can host a group (a phone with Wi-Fi Direct, Wi-Fi on, hosting allowed) and the
 *    other can join it. A phone with Wi-Fi Direct joins as a Wi-Fi Direct client ([LinkMode.P2P]); every other device
 *    with Wi-Fi (macOS, Windows, Linux, a browser's computer, a phone without Wi-Fi Direct) joins the phone's group as a
 *    legacy WPA2 client ([LinkMode.P2P_LEGACY], N8, N10). Desktops and browsers never host. 5 GHz is requested when both
 *    devices support it.
 * 3. **Local-only hotspot** as the last Wi-Fi resort, hosted by a phone that can host one and joined by any device
 *    with Wi-Fi (N8: its band is not app-selectable).
 * 4. **Bluetooth** when both devices have Bluetooth on (not towards a browser or a wired desktop).
 *
 * Host election ([electHost]) for the group and the hotspot, first rule that decides:
 * 1. never a device whose station interface is on 2.4 GHz when the other can host (N9);
 * 2. the device that can host 5 GHz: verified (capability bit 4) over 5 GHz-capable over 2.4 GHz only;
 * 3. Wi-Fi 6 or newer (bit 2);
 * 4. more battery, when both levels are known;
 * 5. otherwise the receiver.
 *
 * The LAN probe runs in parallel with Wi-Fi Direct formation when the joiner stays on its network
 * ([LadderPlan.parallelLanProbe], N9).
 */
object LadderPlanner {
    /** Plans the ladder for [input]. Never throws; an empty plan means no path exists ([LadderPlan.isUnreachable]). */
    fun plan(input: LadderInput): LadderPlan = plan(input, PlanConstraints.NONE)

    internal fun plan(
        input: LadderInput,
        constraints: PlanConstraints,
    ): LadderPlan {
        val candidates = ArrayList<LinkCandidate>(4)

        val hint = input.local.networkHint
        val sameHint = !hint.isNone && hint == input.peer.networkHint
        if ((sameHint || input.lanReachable) && constraints.allows(LinkKind.LAN)) {
            candidates += LinkCandidate(LinkMode.LAN, host = input.senderSide)
        }

        val groupOwner =
            if (!constraints.allows(LinkKind.P2P)) {
                null
            } else {
                val canHost = { side: Side -> input.canHostP2p(side) && canJoinGroup(input, side.other) }
                val forced = constraints.groupOwner
                if (forced != null) Election(forced, ElectionReason.AGREED).takeIf { canHost(forced) } else electHost(input, canHost)
            }
        if (groupOwner != null) {
            val joiner = groupOwner.host.other
            val mode = if (input.canJoinP2pAsClient(joiner)) LinkMode.P2P else LinkMode.P2P_LEGACY
            candidates +=
                LinkCandidate(
                    mode = mode,
                    host = groupOwner.host,
                    requestFiveGhz = input.local.supportsFiveGhz && input.peer.supportsFiveGhz,
                    hostStationOn24 = input.facts(groupOwner.host).stationBand == StationBand.BAND_2_4_GHZ,
                )
        }

        val hotspotHost =
            if (!constraints.allows(LinkKind.HOTSPOT)) {
                null
            } else {
                electHost(input) { side -> input.canHostHotspot(side) && input.canJoinAsLegacyClient(side.other) }
            }
        if (hotspotHost != null) {
            candidates +=
                LinkCandidate(
                    mode = LinkMode.HOTSPOT,
                    host = hotspotHost.host,
                    hostStationOn24 = input.facts(hotspotHost.host).stationBand == StationBand.BAND_2_4_GHZ,
                )
        }

        val bluetooth = input.bluetoothOn(Side.LOCAL) && input.bluetoothOn(Side.PEER) && constraints.allows(LinkKind.BLUETOOTH)
        if (bluetooth) candidates += LinkCandidate(LinkMode.BLUETOOTH)

        val wifiOff = input.localRadio.wifiEnabled == false || input.peerRadio.wifiEnabled == false
        val initialHints =
            if (bluetooth && candidates.none { it.mode.isWifi } && wifiOff) listOf(LadderHint.btFallback()) else emptyList()
        return LadderPlan(input, candidates, groupOwner, hotspotHost, initialHints)
    }

    /**
     * Elects the host among the sides for which [canHost] holds, by the rules in the class comment. Returns null when
     * neither can host. Symmetric: two devices that plan from the same facts elect the same device.
     */
    fun electHost(
        input: LadderInput,
        canHost: (Side) -> Boolean,
    ): Election? {
        val localCan = canHost(Side.LOCAL)
        val peerCan = canHost(Side.PEER)
        if (!localCan && !peerCan) return null
        if (localCan != peerCan) return Election(if (localCan) Side.LOCAL else Side.PEER, ElectionReason.ONLY_HOST)

        val local = input.local
        val peer = input.peer
        val local24 = local.stationBand == StationBand.BAND_2_4_GHZ
        val peer24 = peer.stationBand == StationBand.BAND_2_4_GHZ
        if (local24 != peer24) return Election(if (local24) Side.PEER else Side.LOCAL, ElectionReason.STATION_BAND)

        val localFive = fiveGhzHostScore(local)
        val peerFive = fiveGhzHostScore(peer)
        if (localFive != peerFive) return Election(if (localFive > peerFive) Side.LOCAL else Side.PEER, ElectionReason.FIVE_GHZ_HOST)

        val localSix = Flag.WIFI_6_OR_NEWER in local.capabilities
        val peerSix = Flag.WIFI_6_OR_NEWER in peer.capabilities
        if (localSix != peerSix) return Election(if (localSix) Side.LOCAL else Side.PEER, ElectionReason.WIFI_6)

        val localBattery = local.batteryPercent
        val peerBattery = peer.batteryPercent
        if (localBattery != null && peerBattery != null && localBattery != peerBattery) {
            return Election(if (localBattery > peerBattery) Side.LOCAL else Side.PEER, ElectionReason.BATTERY)
        }
        return Election(input.receiverSide, ElectionReason.RECEIVER_TIE_BREAK)
    }

    private fun canJoinGroup(
        input: LadderInput,
        side: Side,
    ): Boolean = input.canJoinP2pAsClient(side) || input.canJoinAsLegacyClient(side)

    /** 2: hosted a 5 GHz group before (bit 4); 1: supports 5 GHz; 0: 2.4 GHz only. */
    private fun fiveGhzHostScore(facts: LinkFacts): Int =
        when {
            Flag.CAN_HOST_P2P_5GHZ in facts.capabilities -> 2
            facts.supportsFiveGhz -> 1
            else -> 0
        }
}

/**
 * Limits a re-plan to what both devices agreed on (S5, [LadderNegotiation]): only rungs of [kinds] (null: all), and
 * the Wi-Fi Direct group hosted by [groupOwner] when set.
 */
internal data class PlanConstraints(
    val kinds: Set<LinkKind>? = null,
    val groupOwner: Side? = null,
) {
    fun allows(kind: LinkKind): Boolean = kinds == null || kind in kinds

    companion object {
        val NONE = PlanConstraints()
    }
}
