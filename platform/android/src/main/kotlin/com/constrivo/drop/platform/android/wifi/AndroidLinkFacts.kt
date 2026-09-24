package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.ladder.LinkFacts
import com.constrivo.drop.core.ladder.RadioState
import com.constrivo.drop.core.ladder.StationBand
import com.constrivo.drop.platform.android.capability.LocalRadioFacts

/**
 * What keeps this phone from hosting a group or hotspot right now: private facts that only drop rungs, never change the
 * election (architecture §4 note, [LinkFacts.hostingAllowed]).
 *
 * @property hotspotInUse this app's local-only hotspot serves something else (the browser receive page, another transfer).
 * @property groupInUse this app's Wi-Fi Direct group serves another transfer.
 * @property tetheringOn the user's Wi-Fi tethering is on (the hotspot would fail with `ERROR_INCOMPATIBLE_MODE`, and a
 *   group would share the radio with it); known from an earlier failure, since apps cannot read the tethering state.
 */
data class HostingState(
    val hotspotInUse: Boolean = false,
    val groupInUse: Boolean = false,
    val tetheringOn: Boolean = false,
) {
    val busy: Boolean get() = hotspotInUse || groupInUse || tetheringOn
}

/** How a local-only hotspot this phone hosts would affect its station (from `isStaApConcurrencySupported`). */
enum class HotspotHosting {
    /** Not on Wi-Fi: nothing to displace. */
    NO_STATION,

    /** On Wi-Fi with STA/AP concurrency: the hotspot runs beside the station. */
    CONCURRENT,

    /** On Wi-Fi without STA/AP concurrency: the station drops while the hotspot runs and returns on teardown (F-E11). */
    DISPLACES_STATION,
}

/**
 * This phone's [LinkFacts] for the ladder (architecture §4 inputs), a pure function so the transfer service can call
 * `LadderPlanner.plan` (WP7e): the capability bits this device published in its verified handshake, platform
 * [DevicePlatform.PHONE], the network hint (N6), the battery when known, and [LinkFacts.hostingAllowed] from
 * [HostingState].
 *
 * Both devices must plan from the same facts ([LinkFacts]): the station band is therefore derived from the published
 * capability bits 11 and 13 ([StationBand.fromCapabilities], N9), exactly as the peer derives it, not from the latest
 * detection, which can differ after a network change or in Trusted-only mode (bits cleared). Pass the capabilities of
 * the handshake that the peer saw, which WP7a's detection ([LocalRadioFacts.capabilities]) produced.
 *
 * STA/AP concurrency ([LocalRadioFacts.staApConcurrency]) is not a [LinkFacts] input: [LinkFacts.hostingAllowed] would
 * drop the Wi-Fi Direct rung with the hotspot, although only the hotspot displaces the station. It is reported by
 * [hotspotHosting] and used by [AndroidHotspotLinkProvider] (restore wait, optional refusal).
 */
object AndroidLinkFacts {
    /**
     * This phone's facts.
     *
     * @param publishedCapabilities the capabilities this device sent in the handshake the peer verified.
     * @param networkHint the hint this device published (N6); by default the detected one.
     * @param batteryPercent 0–100, or null when unknown (the election uses it only when both devices know theirs).
     */
    fun local(
        publishedCapabilities: Capabilities,
        radio: LocalRadioFacts,
        batteryPercent: Int? = null,
        hosting: HostingState = HostingState(),
        networkHint: NetworkHint = radio.networkHint,
    ): LinkFacts =
        LinkFacts(
            capabilities = publishedCapabilities,
            platform = DevicePlatform.PHONE,
            networkHint = networkHint,
            batteryPercent = batteryPercent?.coerceIn(0, 100),
            stationBand = StationBand.fromCapabilities(publishedCapabilities),
            hostingAllowed = !hosting.busy,
        )

    /** This phone's radio state: Wi-Fi and Bluetooth switched on or off, which it always knows ([RadioState]). */
    fun radioState(radio: LocalRadioFacts): RadioState =
        RadioState(wifiEnabled = radio.wifiEnabled, bluetoothEnabled = radio.bluetoothEnabled)

    /** What a hotspot hosted now would do to the station (connected: detection's bit 11, `CONNECTED_TO_WIFI`). */
    fun hotspotHosting(radio: LocalRadioFacts): HotspotHosting =
        when {
            Capabilities.Flag.CONNECTED_TO_WIFI !in radio.capabilities -> HotspotHosting.NO_STATION
            radio.staApConcurrency -> HotspotHosting.CONCURRENT
            else -> HotspotHosting.DISPLACES_STATION
        }
}
