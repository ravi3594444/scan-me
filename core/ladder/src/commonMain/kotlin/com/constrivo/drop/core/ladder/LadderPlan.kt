package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.protocol.TransferRole

/**
 * How a rung of the ladder is brought up (architecture §4 and §8, with spec changes N8 and N10). [kind] is the link
 * kind that goes on the wire (`LinkReady.kind`) and into the badge.
 */
enum class LinkMode(
    val kind: LinkKind,
) {
    /** TCP over a network both devices are on (F-E4). The sender listens (S5), the receiver connects. */
    LAN(LinkKind.LAN),

    /**
     * A Wi-Fi Direct group joined with Wi-Fi Direct (`WifiP2pManager.connect(config)`, phone to phone, F-E2). The
     * joiner keeps its station network, so a LAN probe can run beside it (N9).
     */
    P2P(LinkKind.P2P),

    /**
     * The phone's Wi-Fi Direct group joined as a legacy WPA2 client by SSID and passphrase (N8, N10): macOS CoreWLAN,
     * Windows `WiFiAdapter.ConnectAsync`, Linux NetworkManager, a browser's computer, or a phone without Wi-Fi Direct
     * (`WifiNetworkSpecifier`). Unlike the local-only hotspot the band is app-selectable. The joiner leaves its network
     * for the transfer and gets it back on teardown (F-E11).
     */
    P2P_LEGACY(LinkKind.P2P),

    /** A local-only hotspot on the phone, joined in-app (F-E3). Last resort: its band is system-chosen (N8). */
    HOTSPOT(LinkKind.HOTSPOT),

    /** No Wi-Fi link: the Bluetooth head-start stream carries the whole transfer (§4 "stay on Bluetooth"). */
    BLUETOOTH(LinkKind.BLUETOOTH),
    ;

    /** A Wi-Fi Direct group or hotspot that one device hosts and the other joins. */
    val isDirect: Boolean get() = this == P2P || this == P2P_LEGACY || this == HOTSPOT

    val isWifi: Boolean get() = this != BLUETOOTH

    /** The joiner leaves its station network, so this rung cannot run beside a LAN probe (§4 note, WP5). */
    val joinerLeavesNetwork: Boolean get() = this == P2P_LEGACY || this == HOTSPOT
}

/**
 * One rung of the ladder.
 *
 * @property host the side that hosts: the group owner (P2P modes), the hotspot host, or the LAN listener (the sender,
 *   S5). Null only for [LinkMode.BLUETOOTH].
 * @property requestFiveGhz the host asks for a 5 GHz group (`GROUP_OWNER_BAND_5GHZ`); set when both devices support
 *   5 GHz, since a 2.4 GHz-only joiner cannot see a 5 GHz group. Never set for the hotspot, whose band apps cannot
 *   choose (N8).
 * @property hostStationOn24 the host's station interface is on 2.4 GHz, so the group is probably pinned there (N9).
 *   The election avoids this; it is set only when it was unavoidable, and the ladder then explains a 2.4 GHz link with
 *   `sta_band24`.
 */
data class LinkCandidate(
    val mode: LinkMode,
    val host: Side? = null,
    val requestFiveGhz: Boolean = false,
    val hostStationOn24: Boolean = false,
) {
    init {
        require((mode == LinkMode.BLUETOOTH) == (host == null)) { "$mode ${if (host == null) "needs" else "takes no"} host" }
        require(!requestFiveGhz || mode == LinkMode.P2P || mode == LinkMode.P2P_LEGACY) { "only Wi-Fi Direct groups request 5 GHz" }
        require(!hostStationOn24 || mode.isDirect) { "only hosted links have a host station band" }
    }

    val kind: LinkKind get() = mode.kind

    /** This device's role on the link; null for Bluetooth. */
    val localRole: LinkRole? get() = host?.let { if (it == Side.LOCAL) LinkRole.HOST else LinkRole.JOIN }
}

/** Why the group owner or hotspot host was chosen (architecture §4 rule order, with N9 first). */
enum class ElectionReason {
    /** Only one device can host. */
    ONLY_HOST,

    /** The other device's station is on 2.4 GHz and would pin the group there (N9). */
    STATION_BAND,

    /** Can host a 5 GHz group: verified (bit 4) beats merely 5 GHz-capable, which beats 2.4 GHz only. */
    FIVE_GHZ_HOST,

    /** Wi-Fi 6 (802.11ax) or newer (bit 2). */
    WIFI_6,

    /** More battery (only when both levels are known). */
    BATTERY,

    /** Nothing else decides: the receiver hosts. */
    RECEIVER_TIE_BREAK,

    /** Imposed by the receiver's `Accept` (S5: the receiver's choice is final). */
    AGREED,

    /**
     * Hotspot only: the Wi-Fi Direct group owner hosts the hotspot too. The group owner is settled by the `Offer` /
     * `Accept` exchange (S5), so both devices agree on the hotspot host without negotiating it separately.
     */
    GROUP_OWNER,
}

/** The outcome of an election: [host] hosts, for [reason]. */
data class Election(
    val host: Side,
    val reason: ElectionReason,
)

/**
 * The ordered ladder for one transfer (architecture §4), produced by [LadderPlanner] and executed by [LinkLifecycle]
 * and [LadderRunner].
 *
 * Candidate order is LAN, then one Wi-Fi Direct rung ([LinkMode.P2P] or [LinkMode.P2P_LEGACY]), then the local-only
 * hotspot, then Bluetooth; any of them may be missing. Bluetooth is not a link that is set up: it is the head-start
 * stream, listed last to say that it may carry the whole transfer.
 *
 * A plan whose peer is a browser ([isBrowserPlan], N8) is not run by [LadderRunner]: the browser receive path (WP9)
 * hosts its rungs itself through [WifiLinkProvider.host], shows the QR code, and waits for the first HTTP request.
 *
 * @property groupOwnerElection who hosts the Wi-Fi Direct group and why (null without a Wi-Fi Direct rung). The group
 *   owner generates the SSID and passphrase (S5).
 * @property hotspotElection who hosts the local-only hotspot and why (null without a hotspot rung).
 * @property initialHints hints that hold from the start: `bt_fallback` when Wi-Fi is off on one device and Bluetooth
 *   is all that is left.
 */
data class LadderPlan(
    val input: LadderInput,
    val candidates: List<LinkCandidate>,
    val groupOwnerElection: Election? = null,
    val hotspotElection: Election? = null,
    val initialHints: List<LadderHint> = emptyList(),
) {
    init {
        require(candidates.map { it.mode }.toSet().size == candidates.size) { "each mode appears at most once" }
        require(candidates.count { it.kind == LinkKind.P2P } <= 1) { "at most one Wi-Fi Direct rung" }
        val lan = candidates.indexOfFirst { it.mode == LinkMode.LAN }
        require(lan <= 0) { "the LAN rung comes first" }
        val bt = candidates.indexOfFirst { it.mode == LinkMode.BLUETOOTH }
        require(bt == -1 || bt == candidates.lastIndex) { "the Bluetooth rung comes last" }
        val p2p = candidates.indexOfFirst { it.kind == LinkKind.P2P }
        val hotspot = candidates.indexOfFirst { it.mode == LinkMode.HOTSPOT }
        require(p2p == -1 || hotspot == -1 || p2p < hotspot) { "the hotspot is the last Wi-Fi resort" }
        require((p2p >= 0) == (groupOwnerElection != null)) { "a Wi-Fi Direct rung needs a group owner and vice versa" }
        require((hotspot >= 0) == (hotspotElection != null)) { "a hotspot rung needs a host and vice versa" }
        if (p2p >= 0) require(candidates[p2p].host == groupOwnerElection?.host) { "the Wi-Fi Direct rung is hosted by the group owner" }
        if (hotspot >= 0) require(candidates[hotspot].host == hotspotElection?.host) { "the hotspot rung is hosted by its host" }
    }

    /**
     * The device that decides which link carries the data: the receiver, whose choice is final (S5) and whose meter
     * sees the bytes that actually arrive. It measures the LAN, accepts links and cancels the losers, and tells the
     * sender ([LinkEffect.AnnounceSelection]); the sender follows ([LinkLifecycle], "One authority").
     */
    val authority: Side get() = input.receiverSide

    /** This device is the [authority]. */
    val localIsAuthority: Boolean get() = input.localRole == TransferRole.RECEIVER

    /**
     * The peer is a browser (F-D6, N8): no app answers `LinkReady` and a person joins the network by hand, so the plan
     * is hosted by the browser receive path (WP9), not run by [LadderRunner].
     */
    val isBrowserPlan: Boolean get() = input.peer.platform == DevicePlatform.BROWSER_PROXY

    /** The side that hosts the Wi-Fi Direct group, if there is one; it generates the credentials (S5). */
    val groupOwner: Side? get() = groupOwnerElection?.host

    val hotspotHost: Side? get() = hotspotElection?.host

    /**
     * The LAN probe runs in parallel with Wi-Fi Direct formation and the loser is cancelled (N9). That needs a joiner
     * that stays on its network, so it holds only when a [LinkMode.P2P] rung follows the LAN rung; a legacy joiner or a
     * hotspot joiner would leave the LAN it is probing, so those wait for the LAN verdict.
     */
    val parallelLanProbe: Boolean
        get() =
            candidates.size >= 2 && candidates[0].mode == LinkMode.LAN && candidates[1].mode.isDirect &&
                !candidates[1].mode.joinerLeavesNetwork

    /** Both devices support 5 GHz, so a 2.4 GHz Wi-Fi Direct group is a failure to re-form once (§9). */
    val fiveGhzPossible: Boolean get() = input.local.supportsFiveGhz && input.peer.supportsFiveGhz

    /** Wi-Fi is known to be off on at least one device (the `bt_fallback` condition, design §8.2). */
    val wifiOffOnOneDevice: Boolean get() = input.localRadio.wifiEnabled == false || input.peerRadio.wifiEnabled == false

    val hasBluetooth: Boolean get() = candidates.any { it.mode == LinkMode.BLUETOOTH }

    /** No Wi-Fi rung at all: the transfer stays on Bluetooth (§4 "badge = Bluetooth"). */
    val isBluetoothOnly: Boolean get() = hasBluetooth && candidates.none { it.mode.isWifi }

    /** No way to reach the peer at all (for example Wi-Fi off towards a browser, or a wired desktop on another network). */
    val isUnreachable: Boolean get() = candidates.isEmpty()

    fun indexOf(mode: LinkMode): Int = candidates.indexOfFirst { it.mode == mode }

    fun candidate(mode: LinkMode): LinkCandidate? = candidates.firstOrNull { it.mode == mode }

    /** The Wi-Fi Direct rung, whichever join mode it uses. */
    val p2pCandidate: LinkCandidate? get() = candidates.firstOrNull { it.kind == LinkKind.P2P }
}
