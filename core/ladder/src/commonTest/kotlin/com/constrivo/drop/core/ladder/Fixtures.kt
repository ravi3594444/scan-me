package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.protocol.TransferRole
import com.constrivo.drop.core.protocol.WifiCredentials

/** Devices of the lab matrix, as capability sets (architecture §5.2). */
internal object Caps {
    /** A current flagship (the "Pixel pair" of T-01): 5 GHz host verified, Wi-Fi 6, Wi-Fi Direct, hotspot. */
    val FLAGSHIP: Capabilities =
        Capabilities.of(
            Flag.WIFI_5GHZ,
            Flag.WIFI_6_OR_NEWER,
            Flag.WIFI_DIRECT,
            Flag.CAN_HOST_P2P_5GHZ,
            Flag.CAN_HOST_LOCAL_HOTSPOT,
            Flag.BLUETOOTH_RFCOMM,
        )

    /** A midrange phone: 5 GHz and Wi-Fi Direct, never verified hosting 5 GHz, Wi-Fi 5. */
    val MIDRANGE: Capabilities = Capabilities.of(Flag.WIFI_5GHZ, Flag.WIFI_DIRECT, Flag.CAN_HOST_LOCAL_HOTSPOT)

    /** A budget phone that only has 2.4 GHz. */
    val BAND24_ONLY: Capabilities = Capabilities.of(Flag.WIFI_DIRECT, Flag.CAN_HOST_LOCAL_HOTSPOT)

    /** A phone without Wi-Fi Direct that can still host a hotspot. */
    val NO_WIFI_DIRECT: Capabilities = Capabilities.of(Flag.WIFI_5GHZ, Flag.CAN_HOST_LOCAL_HOTSPOT)

    /** A phone with neither Wi-Fi Direct nor a hotspot. */
    val NOTHING: Capabilities = Capabilities.of(Flag.WIFI_5GHZ)

    /** A MacBook: 5 GHz, Wi-Fi 6, no Wi-Fi Direct. */
    val MAC: Capabilities = Capabilities.of(Flag.WIFI_5GHZ, Flag.WIFI_6_OR_NEWER, Flag.BLE_EXTENDED_ADVERTISING)

    /** A Windows laptop: publishes Wi-Fi Direct (it has it) but joins as a legacy client (N10). */
    val WINDOWS: Capabilities =
        Capabilities.of(
            Flag.WIFI_5GHZ,
            Flag.WIFI_6_OR_NEWER,
            Flag.WIFI_DIRECT,
            Flag.CAN_HOST_P2P_5GHZ,
            Flag.BLUETOOTH_RFCOMM,
        )

    /** A desktop PC on Ethernet without Bluetooth (mDNS only). */
    val WIRED_DESKTOP: Capabilities = Capabilities.of(Flag.DESKTOP_WITHOUT_BLUETOOTH, Flag.CONNECTED_TO_WIFI)

    fun Capabilities.onWifi(fiveGhz: Boolean): Capabilities {
        val connected = this + Flag.CONNECTED_TO_WIFI
        return if (fiveGhz) connected + Flag.STATION_ON_5GHZ else connected - Flag.STATION_ON_5GHZ
    }
}

internal val HOME_ROUTER = NetworkHint(0x1234_5678)
internal val OFFICE_ROUTER = NetworkHint(0x0BAD_CAFE)

internal fun phone(
    caps: Capabilities = Caps.FLAGSHIP,
    hint: NetworkHint = NetworkHint.NONE,
    battery: Int? = null,
    hostingAllowed: Boolean = true,
): LinkFacts = LinkFacts(caps, DevicePlatform.PHONE, hint, battery, hostingAllowed = hostingAllowed)

internal fun laptop(
    caps: Capabilities = Caps.MAC,
    hint: NetworkHint = NetworkHint.NONE,
): LinkFacts = LinkFacts(caps, DevicePlatform.LAPTOP, hint)

internal fun input(
    local: LinkFacts,
    peer: LinkFacts,
    role: TransferRole = TransferRole.SENDER,
    localRadio: RadioState = RadioState.ALL_ON,
    peerRadio: RadioState = RadioState.UNKNOWN,
    lanReachable: Boolean = false,
    peerName: String? = "Asha",
): LadderInput = LadderInput(local, peer, role, localRadio, peerRadio, lanReachable, peerName)

internal fun plan(
    local: LinkFacts,
    peer: LinkFacts,
    role: TransferRole = TransferRole.SENDER,
    localRadio: RadioState = RadioState.ALL_ON,
    peerRadio: RadioState = RadioState.UNKNOWN,
    lanReachable: Boolean = false,
): LadderPlan = LadderPlanner.plan(input(local, peer, role, localRadio, peerRadio, lanReachable))

internal val TEST_CREDENTIALS = WifiCredentials("DIRECT-ab-Drop-cdef", "abcdefghijkm")
internal val OTHER_CREDENTIALS = WifiCredentials("DIRECT-zz-Drop-yyyy", "zzzzzzzzzzzz")
