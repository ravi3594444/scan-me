package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.ladder.LinkCredentialsException
import com.constrivo.drop.core.ladder.P2pCredentials
import com.constrivo.drop.core.ladder.WifiBand
import com.constrivo.drop.core.protocol.WifiCredentials

/** The security of a local-only hotspot (`SoftApConfiguration.getSecurityType()`). */
enum class HotspotSecurity(
    val platformValue: Int,
) {
    OPEN(0),
    WPA2_PSK(1),
    WPA3_SAE_TRANSITION(2),
    WPA3_SAE(3),
    WPA3_OWE_TRANSITION(4),
    WPA3_OWE(5),
    UNKNOWN(-1),
    ;

    /** A WPA2 client (a desktop, a browser's computer, a `setWpa2Passphrase` specifier) can join. */
    val joinableWithWpa2: Boolean get() = this == WPA2_PSK || this == WPA3_SAE_TRANSITION

    /** The specifier security a phone joins with. */
    val specifierSecurity: SpecifierSecurity get() = if (this == WPA3_SAE) SpecifierSecurity.WPA3_SAE else SpecifierSecurity.WPA2_PSK

    companion object {
        fun fromPlatform(value: Int): HotspotSecurity = entries.firstOrNull { it.platformValue == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * What `LocalOnlyHotspotReservation.getSoftApConfiguration()` reports, as plain values (N8, N15: all of it is
 * system-chosen).
 *
 * @property ssidBytes `getWifiSsid().getBytes()` (API 33+), the SSID's raw bytes; null where unavailable.
 * @property ssidText `getSsid()`, the older text form (deprecated in API 33); used when [ssidBytes] is null.
 * @property securityType `getSecurityType()`, a [HotspotSecurity.platformValue].
 * @property bssid `getBssid()` in `aa:bb:cc:dd:ee:ff` form, when the system fixed it.
 * @property channels `getChannels()` (API 36+): `SoftApConfiguration.BAND_*` bit → channel (0 = automatic); null
 *   where the band cannot be read (API 30–35 keep it hidden).
 */
data class HotspotConfigValues(
    val ssidBytes: ByteArray?,
    val ssidText: String?,
    val passphrase: String?,
    val securityType: Int,
    val bssid: String?,
    val channels: Map<Int, Int>?,
) {
    override fun equals(other: Any?): Boolean =
        other is HotspotConfigValues && other.ssidBytes contentEquals ssidBytes && other.ssidText == ssidText &&
            other.passphrase == passphrase && other.securityType == securityType && other.bssid == bssid && other.channels == channels

    override fun hashCode(): Int = listOf(ssidBytes?.contentHashCode(), ssidText, passphrase, securityType, bssid, channels).hashCode()

    override fun toString(): String =
        "HotspotConfigValues(ssid=${ssidText ?: ssidBytes?.decodeToString()}, passphrase=<redacted>, securityType=$securityType, " +
            "bssid=$bssid, channels=$channels)"
}

/**
 * The local-only hotspot this device hosts, as the ladder, the peer and the UI's "computer without the app" hint need it
 * (N8, N15): the real, system-generated [credentials], its [security], the [bands] it may use and the channel
 * [frequencyMhz] when the system fixed one. [toString] never shows the passphrase.
 *
 * @property bands empty when the build does not say (API 30–35); the joiner measures the real channel either way.
 * @property frequencyMhz only when exactly one band with a fixed channel is configured; null otherwise (usually).
 */
data class HotspotDetails(
    val credentials: WifiCredentials,
    val security: HotspotSecurity,
    val bands: Set<WifiBand>,
    val frequencyMhz: Int?,
    val bssid: String?,
) {
    /** A WPA2 client can join (the browser path and desktops need this; an SAE-only hotspot is phone-to-phone). */
    val joinableWithWpa2: Boolean get() = security.joinableWithWpa2

    override fun toString(): String =
        "HotspotDetails(ssid=${credentials.ssid}, passphrase=<redacted>, security=$security, bands=$bands, frequencyMhz=$frequencyMhz, bssid=$bssid)"
}

/** Reads a local-only hotspot's configuration (a pure mapping of [HotspotConfigValues]). */
object HotspotConfigReading {
    /** `SoftApConfiguration.BAND_2GHZ`, `BAND_5GHZ`, `BAND_6GHZ` (API 36 constants; the values are stable since API 30). */
    const val BAND_2GHZ: Int = 1
    const val BAND_5GHZ: Int = 2
    const val BAND_6GHZ: Int = 4

    /**
     * The details of a started hotspot.
     *
     * @throws WifiLinkException [WifiLinkError.FAILED] for a hotspot a passphrase cannot join (open or OWE), without an
     *   SSID, or whose SSID and passphrase break the WPA2 rules ([P2pCredentials.requireValidHotspot]).
     */
    fun details(values: HotspotConfigValues): HotspotDetails {
        val security = HotspotSecurity.fromPlatform(values.securityType)
        if (security == HotspotSecurity.OPEN || security == HotspotSecurity.WPA3_OWE || security == HotspotSecurity.WPA3_OWE_TRANSITION) {
            throw WifiLinkException(WifiLinkError.FAILED, "the system started a hotspot without a passphrase ($security)")
        }
        val ssid = ssidOf(values) ?: throw WifiLinkException(WifiLinkError.FAILED, "the hotspot reports no SSID")
        val passphrase = values.passphrase ?: throw WifiLinkException(WifiLinkError.FAILED, "the hotspot reports no passphrase")
        val credentials =
            try {
                P2pCredentials.requireValidHotspot(WifiCredentials(ssid, passphrase))
            } catch (e: LinkCredentialsException) {
                throw WifiLinkException(WifiLinkError.FAILED, "the hotspot's credentials cannot be joined: ${e.message}", cause = e)
            } catch (e: IllegalArgumentException) {
                throw WifiLinkException(WifiLinkError.FAILED, "the hotspot's credentials cannot be joined: ${e.message}", cause = e)
            }
        val channels = values.channels.orEmpty()
        val bands = channels.keys.mapNotNull(::bandOf).toSet()
        val frequency =
            channels.entries.singleOrNull()?.let { (bit, channel) ->
                bandOf(bit)?.let { WifiFrequencies.channelToFrequencyMhz(it, channel) }
            }
        return HotspotDetails(credentials, security, bands, frequency, values.bssid?.takeIf { SpecifierRequestSpec.isValidBssid(it) })
    }

    /**
     * The SSID as text: the raw bytes decoded as UTF-8 when they are valid UTF-8, else the older text form; null when
     * neither is there. Surrounding quotes of the older form are removed.
     */
    fun ssidOf(values: HotspotConfigValues): String? {
        val bytes = values.ssidBytes
        if (bytes != null && bytes.isNotEmpty()) {
            val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            if (text != null) return text
        }
        return values.ssidText?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
    }

    private fun bandOf(bit: Int): WifiBand? =
        when (bit) {
            BAND_2GHZ -> WifiBand.BAND_2_4_GHZ
            BAND_5GHZ -> WifiBand.BAND_5_GHZ
            BAND_6GHZ -> WifiBand.BAND_6_GHZ
            else -> null
        }
}

/** `LocalOnlyHotspotCallback.onFailed` reasons. */
object HotspotFailureCodes {
    const val ERROR_NO_CHANNEL: Int = 1
    const val ERROR_GENERIC: Int = 2
    const val ERROR_INCOMPATIBLE_MODE: Int = 3
    const val ERROR_TETHERING_DISALLOWED: Int = 4

    /** The typed error for [reason]; unknown codes are [WifiLinkError.FAILED]. */
    fun error(reason: Int): WifiLinkError =
        when (reason) {
            ERROR_NO_CHANNEL -> WifiLinkError.NO_CHANNEL
            ERROR_INCOMPATIBLE_MODE -> WifiLinkError.INCOMPATIBLE_MODE
            ERROR_TETHERING_DISALLOWED -> WifiLinkError.DISALLOWED
            else -> WifiLinkError.FAILED
        }

    fun describe(reason: Int): String =
        when (reason) {
            ERROR_NO_CHANNEL -> "ERROR_NO_CHANNEL"
            ERROR_GENERIC -> "ERROR_GENERIC"
            ERROR_INCOMPATIBLE_MODE -> "ERROR_INCOMPATIBLE_MODE"
            ERROR_TETHERING_DISALLOWED -> "ERROR_TETHERING_DISALLOWED"
            else -> "reason $reason"
        }
}

/** A started hotspot's reservation (`LocalOnlyHotspotReservation`); [close] stops it when no other app shares it. */
interface HotspotReservation {
    val config: HotspotConfigValues

    /** Idempotent; never throws. */
    fun close()
}

/** `LocalOnlyHotspotCallback`, delivered on any thread. */
interface HotspotCallback {
    fun onStarted(reservation: HotspotReservation)

    /** The hotspot stopped without a [HotspotReservation.close] (the user, the system). */
    fun onStopped()

    /** Starting failed with one of [HotspotFailureCodes]. */
    fun onFailed(reason: Int)
}

/**
 * Starts local-only hotspots (`WifiManager.startLocalOnlyHotspot`), a seam so [AndroidHotspotLinkProvider] runs in JVM
 * tests; [AndroidHotspotRadio] is the platform implementation.
 */
interface HotspotRadio {
    /**
     * Requests the hotspot; [callback] answers once with started or failed, then may report stopped.
     *
     * @throws SecurityException at once without the permission, or on Android 12 with location services off.
     * @throws IllegalStateException at once when this app already has a request running.
     */
    fun start(callback: HotspotCallback)

    /**
     * The interfaces of the networks Android knows (station, cellular, joined networks): never the hotspot's, which is
     * not such a network. Used to tell the hotspot's interface apart ([LinkInterfaces.hotspotInterface]).
     */
    fun knownNetworkInterfaces(): Set<String>
}
