package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.protocol.TransferRole

/** Which end of the link a ladder decision refers to, seen from the device running the ladder. */
enum class Side {
    LOCAL,
    PEER,
    ;

    val other: Side get() = if (this == LOCAL) PEER else LOCAL
}

/**
 * On/off state of a device's radios. `null` means unknown: the ladder then assumes the radio is on and lets the
 * candidate's timeout decide (architecture §4). This device always knows its own state; for the peer only what it
 * told us is known.
 */
data class RadioState(
    val wifiEnabled: Boolean? = null,
    val bluetoothEnabled: Boolean? = null,
) {
    companion object {
        val UNKNOWN: RadioState = RadioState()
        val ALL_ON: RadioState = RadioState(wifiEnabled = true, bluetoothEnabled = true)
    }
}

/**
 * What the ladder knows about one device (architecture §4 inputs): the 16 capability bits of §5.2, the network hint of
 * §5.1 (N6), the platform, the battery level and whether it may host a group or hotspot right now.
 *
 * Both devices must plan from the **same** facts, or their elections can disagree: use the capabilities exchanged in
 * the verified handshake (N2) for both sides, including this device's own published value, not private knowledge the
 * peer lacks. A value only one side knows (for example this device's battery while the peer's is unknown) is skipped
 * by the election, so it cannot make the two sides diverge. [hostingAllowed] and this device's [RadioState] are such
 * private facts: they never change which device is elected, only drop a rung this device cannot play, and the
 * `Offer` / `Accept` exchange ([LadderNegotiation]) carries the consequences to the peer.
 *
 * @property batteryPercent 0–100, or null when unknown. The election compares battery only when both are known.
 * @property stationBand read from capability bits 11 and 13 unless given (spec change N9).
 * @property hostingAllowed false when this device cannot host now (the hotspot or a P2P group is in use, tethering is
 *   on); for the peer, true unless it said otherwise. Platform rules still apply on top: only phones host (N8, N10).
 *   The Wi-Fi Direct group owner is negotiated with it in mind (S5); for the hotspot it only drops the rung when the
 *   elected host may not host ([LadderPlanner]).
 */
data class LinkFacts(
    val capabilities: Capabilities,
    val platform: DevicePlatform,
    val networkHint: NetworkHint = NetworkHint.NONE,
    val batteryPercent: Int? = null,
    val stationBand: StationBand = StationBand.fromCapabilities(capabilities),
    val hostingAllowed: Boolean = true,
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100) { "battery percent $batteryPercent out of 0..100" }
    }

    /** Supports 5 GHz (or 6 GHz, which implies a 5 GHz radio on every shipping chipset). */
    val supportsFiveGhz: Boolean get() = Flag.WIFI_5GHZ in capabilities || Flag.WIFI_6GHZ in capabilities

    /** A wired desktop that publishes only an mDNS record (§5.2 bit 12): no radios the ladder can use besides LAN. */
    val isWiredOnly: Boolean get() = Flag.DESKTOP_WITHOUT_BLUETOOTH in capabilities

    /** Only phones run Wi-Fi Direct groups: macOS has no Wi-Fi Direct, Windows and Linux join as legacy clients (N8, N10). */
    internal val isWifiDirectPhone: Boolean get() = platform == DevicePlatform.PHONE && Flag.WIFI_DIRECT in capabilities

    companion object {
        /**
         * Facts for a computer that opens the browser receive page (F-D6): no app, no Bluetooth, joins the phone's
         * group or hotspot as a legacy WPA2 client (N8). 5 GHz is assumed, since nearly every laptop sold since 2013 has
         * it; pass other [capabilities] when the user said otherwise.
         *
         * The plan for a browser ([LadderPlan.isBrowserPlan]) orders the rungs the phone hosts, but [LadderRunner]
         * does not run it: no app answers `LinkReady`, and a person reads the QR code and joins the network by hand,
         * which takes far longer than the ladder's 6 s. The browser receive path (WP9) hosts the first rung directly
         * with [WifiLinkProvider.host], shows its credentials, waits for the first HTTP request with its own timeout,
         * and tears the link down itself.
         */
        fun browser(capabilities: Capabilities = Capabilities.of(Flag.WIFI_5GHZ)): LinkFacts =
            LinkFacts(capabilities = capabilities, platform = DevicePlatform.BROWSER_PROXY)
    }
}

/**
 * Everything the planner reads (architecture §4).
 *
 * @property localRole whether this device sends or receives; the election's last tie-break picks the receiver.
 * @property lanReachable the peer has a live mDNS record on this device's network (§5.4): for the verified peer,
 *   `NearbyDevice.lanEndpoints` is not empty. It is the real same-network test (N6) and always puts the LAN on the
 *   ladder; equal network hints alone put it there only when the probe cannot delay a joiner ([LadderPlanner]).
 * @property peerName the peer's nickname, for hint parameters (`{Name}`, design §8.2).
 */
data class LadderInput(
    val local: LinkFacts,
    val peer: LinkFacts,
    val localRole: TransferRole,
    val localRadio: RadioState = RadioState.ALL_ON,
    val peerRadio: RadioState = RadioState.UNKNOWN,
    val lanReachable: Boolean = false,
    val peerName: String? = null,
) {
    fun facts(side: Side): LinkFacts = if (side == Side.LOCAL) local else peer

    fun radio(side: Side): RadioState = if (side == Side.LOCAL) localRadio else peerRadio

    /** The side that sends the transfer; it listens on the LAN path (S5: the LAN option carries its address). */
    val senderSide: Side get() = if (localRole == TransferRole.SENDER) Side.LOCAL else Side.PEER

    val receiverSide: Side get() = senderSide.other

    internal fun wifiOn(side: Side): Boolean = radio(side).wifiEnabled != false && !facts(side).isWiredOnly

    internal fun bluetoothOn(side: Side): Boolean {
        val facts = facts(side)
        return radio(side).bluetoothEnabled != false && !facts.isWiredOnly && facts.platform != DevicePlatform.BROWSER_PROXY
    }

    /** Hosts a Wi-Fi Direct group: a phone with Wi-Fi Direct, Wi-Fi on and hosting allowed. */
    internal fun canHostP2p(side: Side): Boolean = facts(side).isWifiDirectPhone && facts(side).hostingAllowed && wifiOn(side)

    /** Joins a group with Wi-Fi Direct itself (`WifiP2pManager.connect`), so it stays on its station network. */
    internal fun canJoinP2pAsClient(side: Side): Boolean = facts(side).isWifiDirectPhone && wifiOn(side)

    /** Joins a WPA2 network by SSID and passphrase: every device with Wi-Fi (desktop OS join, `WifiNetworkSpecifier`). */
    internal fun canJoinAsLegacyClient(side: Side): Boolean = wifiOn(side)

    /**
     * Hosts a local-only hotspot by the facts both devices share (§8: phones only, desktops cannot), leaving out
     * [LinkFacts.hostingAllowed], which only the device itself knows ([LadderPlanner] applies it afterwards).
     */
    internal fun canHostHotspotBySharedFacts(side: Side): Boolean {
        val facts = facts(side)
        return facts.platform == DevicePlatform.PHONE && Flag.CAN_HOST_LOCAL_HOTSPOT in facts.capabilities && wifiOn(side)
    }
}
