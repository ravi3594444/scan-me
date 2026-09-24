package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.ladder.LadderException
import java.io.IOException

/**
 * Why an Android Wi-Fi link could not be brought up or kept (architecture §8, WP7c/d). The transfer service (WP7e) maps
 * these to hints and to the just-in-time permission sheets; the ladder only sees a failed rung
 * ([com.constrivo.drop.core.ladder.LinkEndReason.ERROR]) with the message.
 */
enum class WifiLinkError {
    /** A runtime permission is missing ([WifiLinkException.missingPermissions]); nothing was started (§11). */
    PERMISSION_MISSING,

    /** Android 12: the local-only hotspot needs location services switched on, and they are off (§11). */
    LOCATION_OFF,

    /** Wi-Fi (or Wi-Fi Direct) is switched off. */
    WIFI_OFF,

    /** This device or build cannot do it (`P2P_UNSUPPORTED`, no Wi-Fi Direct or Wi-Fi service). */
    UNSUPPORTED,

    /** The framework is busy with another operation, or another app or transfer holds the group or hotspot. */
    BUSY,

    /** The platform reported a generic error. */
    FAILED,

    /** A step did not finish within its timeout. */
    TIMEOUT,

    /**
     * The network request ended without a network (`NetworkCallback.onUnavailable`): nothing matched the SSID and
     * passphrase, or the user dismissed or declined the system dialog Android shows on the first join per SSID (N7).
     * Android does not say which.
     */
    JOIN_UNAVAILABLE,

    /**
     * Android refuses network-specifier requests from apps that are neither in the foreground nor running a foreground
     * service (N7): nothing was requested.
     */
    BACKGROUND,

    /** Local-only hotspot: no usable channel (`ERROR_NO_CHANNEL`). */
    NO_CHANNEL,

    /** Local-only hotspot: an incompatible mode is active, typically Wi-Fi tethering (`ERROR_INCOMPATIBLE_MODE`). */
    INCOMPATIBLE_MODE,

    /** Local-only hotspot: a user restriction or device policy forbids it (`ERROR_TETHERING_DISALLOWED`). */
    DISALLOWED,

    /** The link came up but its interface address could not be found, so no socket could be bound to it (T-15). */
    NO_ADDRESS,

    /** The group, hotspot or network went away while the link was being set up. */
    LOST,

    /** LAN: this device is on no Wi-Fi or Ethernet network, or on none that reaches the peer's address. */
    NO_NETWORK,
}

/**
 * An Android Wi-Fi link failed for [error] (a [LadderException], so the ladder reports it as the rung's error). The
 * message names the step and the platform code; it never contains a passphrase.
 *
 * @property missingPermissions for [WifiLinkError.PERMISSION_MISSING], the `android.permission.*` names to request.
 * @property platformCode the platform's failure code when there was one (an `ActionListener` reason, a
 *   `LocalOnlyHotspotCallback` error), else null.
 */
class WifiLinkException(
    val error: WifiLinkError,
    message: String,
    val missingPermissions: List<String> = emptyList(),
    val platformCode: Int? = null,
    cause: Throwable? = null,
) : LadderException(message, cause)

/**
 * A link refused to dial an address (architecture §13): the address a peer named in its `LinkReady` is not an IP
 * literal, is not on the link, or is not the peer. An [IOException], like any other failed connection of the link.
 */
class LinkAddressRefusedException(
    message: String,
) : IOException(message)
