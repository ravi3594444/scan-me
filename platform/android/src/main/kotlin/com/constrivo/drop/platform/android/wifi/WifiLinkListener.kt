package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.LinkRole
import com.constrivo.drop.core.protocol.LinkKind

/**
 * What the Android Wi-Fi providers tell the transfer service (WP7e) besides the [com.constrivo.drop.core.ladder.ActiveLink]
 * they hand to the ladder. Every method has a no-op default; calls come from any thread and must return quickly.
 *
 * Wiring: [onFrequencyChanged] → `LadderRunner.onFrequency`, [onLinkLost] → `LadderRunner.onLinkLost`,
 * [onJoinApprovalNeeded] → the "tap the network name" hint (N7), [onRestore] and [onEvent] → the ring-buffer log
 * (architecture §14).
 */
interface WifiLinkListener {
    /** The channel of a link that is up became known or changed (a group moved channel, T-04). */
    fun onFrequencyChanged(
        kind: LinkKind,
        frequencyMhz: Int,
    ) = Unit

    /**
     * A link that was up went away outside a teardown: the group was removed, the user stopped the hotspot, the joined
     * network was lost. The link itself stays owned by the ladder, which tears it down.
     */
    fun onLinkLost(kind: LinkKind) = Unit

    /**
     * A `WifiNetworkSpecifier` join of [ssid] starts that has not been approved on this device before, so Android shows
     * its system dialog (N7: a trusted pair's stable SSID makes this a one-time prompt). The UI should bring the app to
     * the front and tell the user to pick the network.
     */
    fun onJoinApprovalNeeded(ssid: String) = Unit

    /** A teardown gave the previous Wi-Fi back, or gave up waiting for it (F-E11, the 5 s budget). */
    fun onRestore(report: RestoreReport) = Unit

    /** A diagnostic event for the local log. */
    fun onEvent(event: WifiLinkEvent) = Unit

    companion object {
        /** Ignores everything. */
        val NONE: WifiLinkListener = object : WifiLinkListener {}
    }
}

/** Diagnostic events of the Android Wi-Fi providers, for the ring-buffer log (architecture §14). */
sealed interface WifiLinkEvent {
    val kind: LinkKind

    /** A link came up after [setupMillis] on the provider's clock. */
    data class Up(
        override val kind: LinkKind,
        val mode: LinkMode,
        val role: LinkRole,
        val frequencyMhz: Int?,
        val setupMillis: Long,
        val details: LinkDetails,
    ) : WifiLinkEvent

    /** Setting a link up failed with [error] (null: cancelled by the ladder); whatever was started was undone. */
    data class SetupFailed(
        override val kind: LinkKind,
        val mode: LinkMode,
        val role: LinkRole,
        val error: WifiLinkError?,
        val message: String?,
    ) : WifiLinkEvent

    /** A step is tried again, for [reason] (a busy framework, a group that is not up yet). */
    data class Retry(
        override val kind: LinkKind,
        val mode: LinkMode,
        val attempt: Int,
        val reason: String,
    ) : WifiLinkEvent

    /** A Wi-Fi Direct group left over by an earlier run (a killed process) was removed before a new one formed. */
    data class StaleGroupRemoved(
        val networkName: String?,
    ) : WifiLinkEvent {
        override val kind: LinkKind get() = LinkKind.P2P
    }

    /** The group came up with other credentials than requested; joiners get the real ones. */
    data class CredentialsDiffer(
        val requestedNetworkName: String,
        val actualNetworkName: String,
    ) : WifiLinkEvent {
        override val kind: LinkKind get() = LinkKind.P2P
    }

    /** Releasing a link's radio state (remove the group, close the hotspot, release the request) failed. */
    data class ReleaseFailed(
        override val kind: LinkKind,
        val message: String?,
    ) : WifiLinkEvent
}
