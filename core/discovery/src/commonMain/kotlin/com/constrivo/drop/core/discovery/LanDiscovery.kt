package com.constrivo.drop.core.discovery

import kotlinx.coroutines.flow.Flow

/** A DNS-SD service instance of type [AppIdentity.MDNS_SERVICE_TYPE] with its TXT keys (architecture §5.4). */
data class LanService(
    val instanceName: String,
    val host: String,
    val port: Int,
    val txt: Map<String, String>,
)

sealed interface LanEvent {
    data class Found(
        val service: LanService,
    ) : LanEvent

    data class Lost(
        val instanceName: String,
    ) : LanEvent
}

/** mDNS / DNS-SD announce and browse: NsdManager on Android, JmDNS / Bonjour / Avahi on desktop. */
interface LanDiscovery {
    suspend fun announce(service: LanService)

    suspend fun withdraw()

    fun browse(): Flow<LanEvent>
}
