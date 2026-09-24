package com.constrivo.drop.platform.android.capability

import com.constrivo.drop.core.discovery.IpAddress
import com.constrivo.drop.core.discovery.NetworkLinkInfo

/** One `RouteInfo` of `LinkProperties.getRoutes()`, reduced to what the network hint needs. */
class RouteFacts(
    /** `RouteInfo.isDefaultRoute()`: the destination is `0.0.0.0/0` or `::/0`. */
    val isDefault: Boolean,
    /** `RouteInfo.getGateway().getAddress()` when `hasGateway()`, else null. */
    val gateway: ByteArray?,
)

/** One Wi-Fi station network as the connectivity callbacks describe it. */
data class WifiNetworkSnapshot(
    /** `Network.getNetworkHandle()`, stable while the network lives. */
    val handle: Long,
    /** `WifiInfo.getFrequency()` from the network's transport info, or `-1` when unknown. */
    val frequencyMhz: Int,
    /** From `LinkProperties`; null until the first `onLinkPropertiesChanged`. */
    val link: NetworkLinkInfo?,
)

/**
 * Turns Android link properties into [NetworkLinkInfo] for the network hint (spec change N6), and picks the station
 * network. Pure functions over raw address bytes, so the extraction is unit-tested without Android classes.
 */
object NetworkLinkExtraction {
    /**
     * The hint inputs of one link: the gateways of its default routes, the DHCP server (`getDhcpServerAddress()`,
     * API 30+) and its link addresses (IPv4 ones are ignored by [com.constrivo.drop.core.discovery.NetworkHint.derive]).
     * Addresses that are not 4 or 16 bytes are skipped, never thrown on: link properties come from the system, but a
     * vendor bug must not take capability detection down.
     */
    fun linkInfo(
        routes: List<RouteFacts>,
        dhcpServer: ByteArray?,
        linkAddresses: List<ByteArray>,
    ): NetworkLinkInfo =
        NetworkLinkInfo(
            gateways = routes.filter { it.isDefault }.mapNotNull { it.gateway?.let(::address) },
            dhcpServer = dhcpServer?.let(::address),
            ipv6Addresses = linkAddresses.mapNotNull(::address).filter { !it.isIpv4 },
        )

    /**
     * The station network among [networks] (every connected Wi-Fi network with internet capability): the system's
     * default network when it is one of them, else the one with the lowest handle, so the choice is stable while the
     * set does not change. Null when there is none.
     */
    fun chooseStation(
        networks: Collection<WifiNetworkSnapshot>,
        defaultHandle: Long?,
    ): WifiNetworkSnapshot? = networks.firstOrNull { it.handle == defaultHandle } ?: networks.minByOrNull { it.handle }

    /** [StationFacts] for [snapshot]; a network whose link properties have not arrived yet contributes no hint input. */
    fun stationFacts(snapshot: WifiNetworkSnapshot?): StationFacts? =
        snapshot?.let { StationFacts(it.frequencyMhz, it.link ?: NetworkLinkInfo()) }

    private fun address(bytes: ByteArray): IpAddress? = if (bytes.size == 4 || bytes.size == 16) IpAddress.of(bytes) else null
}
