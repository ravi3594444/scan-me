package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.discovery.AppIdentity
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.JoinRequest
import com.constrivo.drop.core.ladder.LinkCredentialsException
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.P2pCredentials
import com.constrivo.drop.core.protocol.WifiCredentials

/** The band of `WifiP2pConfig.Builder.setGroupOperatingBand`, with the platform's constant as [platformValue]. */
enum class P2pBand(
    val platformValue: Int,
) {
    /** `GROUP_OWNER_BAND_AUTO`: the owner picks; a client scans every band. */
    AUTO(0),

    /** `GROUP_OWNER_BAND_2GHZ`. */
    GHZ_2_4(1),

    /** `GROUP_OWNER_BAND_5GHZ`. */
    GHZ_5(2),
}

/**
 * A `WifiP2pConfig` as plain values (architecture §8, Wi-Fi Direct row): `WifiP2pConfig.Builder()
 * .setNetworkName(networkName).setPassphrase(passphrase).setGroupOperatingBand(band).enablePersistentMode(persistent)`.
 * [toString] never shows the passphrase.
 */
data class P2pGroupSpec(
    val networkName: String,
    val passphrase: String,
    val band: P2pBand,
    val persistent: Boolean,
) {
    val credentials: WifiCredentials get() = WifiCredentials(networkName, passphrase)

    override fun toString(): String = "P2pGroupSpec(networkName=$networkName, passphrase=<redacted>, band=$band, persistent=$persistent)"
}

/**
 * From the ladder's requests to the Wi-Fi Direct configurations (F-E2, F-F5; spec changes S5, N7, N9), a pure mapping.
 *
 * - **Credentials.** The group owner's SSID and passphrase from core/ladder's [P2pCredentials] (random per transfer,
 *   stable per trusted pair, N7), checked with [P2pCredentials.requireValidGroup] before anything reaches the platform:
 *   `DIRECT-` and two letters or digits, at most 32 bytes; 8–63 printable ASCII.
 * - **Owner band.** [P2pBand.GHZ_5] when the ladder asks for 5 GHz (both devices support it), otherwise
 *   [P2pBand.GHZ_2_4]: without the request one device is 2.4 GHz-only and must be able to see the group, and a group
 *   re-formed after a loss at the edge of range asks for 2.4 GHz on purpose (T-04). The §9 re-form (attempt 1) asks for
 *   5 GHz again: a fixed channel could fail outright where the band request still forms a group, and the ladder
 *   accepts 2.4 GHz after it (`sta_band24`, `band24`).
 * - **Client band.** [P2pBand.AUTO] always: the client does not know where the group came up (a 5 GHz request can end
 *   on 2.4 GHz, which the ladder only learns once the client has joined and the first stream is open), and a client
 *   that scanned only 5 GHz would never find a 2.4 GHz group.
 * - **Persistence.** `enablePersistentMode(true)` for a trusted pair ([HostRequest.persistent]): the stable
 *   credentials make the remembered group reusable, so a second connection skips negotiation (F-F5).
 */
object P2pConfigMapping {
    /**
     * The group owner's configuration for [request].
     *
     * @throws IllegalArgumentException for a request that is not a Wi-Fi Direct rung.
     * @throws LinkCredentialsException when the credentials break the Wi-Fi Direct rules.
     */
    fun forHost(request: HostRequest): P2pGroupSpec {
        require(request.mode == LinkMode.P2P || request.mode == LinkMode.P2P_LEGACY) { "not a Wi-Fi Direct rung: ${request.mode}" }
        val credentials =
            P2pCredentials.requireValidGroup(request.credentials ?: throw LinkCredentialsException("no credentials to host with"))
        return P2pGroupSpec(
            networkName = credentials.ssid,
            passphrase = credentials.passphrase,
            band = if (request.requestFiveGhz) P2pBand.GHZ_5 else P2pBand.GHZ_2_4,
            persistent = request.persistent,
        )
    }

    /**
     * A Wi-Fi Direct client's configuration for [request] ([LinkMode.P2P]: `WifiP2pManager.connect(config)` with the
     * owner's credentials, so no peer discovery and no invitation dialog).
     *
     * @throws IllegalArgumentException for a request that is not a Wi-Fi Direct client join.
     * @throws LinkCredentialsException when the credentials break the Wi-Fi Direct rules.
     */
    fun forClient(request: JoinRequest): P2pGroupSpec {
        require(request.mode == LinkMode.P2P) { "not a Wi-Fi Direct client join: ${request.mode}" }
        val credentials =
            P2pCredentials.requireValidGroup(request.credentials ?: throw LinkCredentialsException("no credentials to join with"))
        return P2pGroupSpec(credentials.ssid, credentials.passphrase, P2pBand.AUTO, request.persistent)
    }

    /** The app's label in its group names ([P2pCredentials]: `DIRECT-xy-Drop-abcd`). */
    private val label: String = AppIdentity.DISPLAY_NAME.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(16)

    private val OWN_GROUP: Regex =
        if (label.isEmpty()) {
            Regex("^DIRECT-[${P2pCredentials.ALPHABET}]{2}-[${P2pCredentials.ALPHABET}]{4}$")
        } else {
            Regex("^DIRECT-[${P2pCredentials.ALPHABET}]{2}-${Regex.escape(label)}-[${P2pCredentials.ALPHABET}]{4}$")
        }

    /**
     * True when [networkName] has the form of this app's groups ([P2pCredentials]), so a group found up before hosting
     * is one this app left behind (a killed process, a lost teardown) and may be removed; another app's group is not.
     */
    fun isOwnGroupName(networkName: String?): Boolean = networkName != null && OWN_GROUP.matches(networkName)
}

/** `WifiP2pManager.ActionListener` failure reasons (`ERROR`, `P2P_UNSUPPORTED`, `BUSY`, `NO_SERVICE_REQUESTS`, `NO_PERMISSION`). */
object P2pFailureCodes {
    const val ERROR: Int = 0
    const val P2P_UNSUPPORTED: Int = 1
    const val BUSY: Int = 2
    const val NO_SERVICE_REQUESTS: Int = 3
    const val NO_PERMISSION: Int = 4

    /** The typed error for [reason]; unknown codes are [WifiLinkError.FAILED]. */
    fun error(reason: Int): WifiLinkError =
        when (reason) {
            P2P_UNSUPPORTED -> WifiLinkError.UNSUPPORTED
            BUSY -> WifiLinkError.BUSY
            NO_PERMISSION -> WifiLinkError.PERMISSION_MISSING
            else -> WifiLinkError.FAILED
        }

    /** The platform name of [reason], for messages. */
    fun describe(reason: Int): String =
        when (reason) {
            ERROR -> "ERROR"
            P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            BUSY -> "BUSY"
            NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
            NO_PERMISSION -> "NO_PERMISSION"
            else -> "reason $reason"
        }
}
