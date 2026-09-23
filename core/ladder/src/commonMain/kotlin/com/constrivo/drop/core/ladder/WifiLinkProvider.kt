package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.protocol.DataChannel
import com.constrivo.drop.core.protocol.LinkKind

/** Credentials for a link one side hosts and the other joins; they only travel inside the encrypted Offer/Accept. */
data class LinkCredentials(
    val kind: LinkKind,
    val ssid: String? = null,
    val passphrase: String? = null,
    val address: String? = null,
    val port: Int? = null,
    val bandHintGhz: Int? = null,
)

/** A Wi-Fi link that is up. [frequencyMhz] is the measured channel, reported in `LinkReady` and the badge. */
interface ActiveLink {
    val kind: LinkKind
    val frequencyMhz: Int?

    /** Opens a stream to the peer over this link (sockets bound to the link's network, never mobile data). */
    suspend fun connect(
        address: String,
        port: Int,
    ): DataChannel

    /** Accepts the next inbound stream on [port]. */
    suspend fun accept(port: Int): DataChannel

    /** Leaves the group / stops the hotspot / releases the network request so the previous Wi-Fi returns (F-E11). */
    suspend fun teardown()
}

/** One rung of the ladder: Wi-Fi Direct, local-only hotspot or LAN (architecture §4, §8). */
interface WifiLinkProvider {
    val kind: LinkKind

    /** Host the link (group owner or hotspot host). [preferFiveGhz] requests 5 GHz; the result reports the real band. */
    suspend fun host(preferFiveGhz: Boolean): Pair<LinkCredentials, ActiveLink>

    /** Join a link the peer hosts. */
    suspend fun join(credentials: LinkCredentials): ActiveLink
}
