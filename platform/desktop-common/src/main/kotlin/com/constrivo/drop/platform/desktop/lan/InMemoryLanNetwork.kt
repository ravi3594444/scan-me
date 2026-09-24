package com.constrivo.drop.platform.desktop.lan

import com.constrivo.drop.core.discovery.LanDiscovery
import com.constrivo.drop.core.discovery.LanEvent
import com.constrivo.drop.core.discovery.LanService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * A multicast-free stand-in for mDNS: every [LanDiscovery] made by [participant] sees what the others announce, as
 * JmDNS would on a real network (its own announcement included, like a real browse). For tests that run several
 * [com.constrivo.drop.platform.desktop.node.DesktopNode]s in one JVM over loopback, and for containers without
 * multicast. Thread-safe.
 */
class InMemoryLanNetwork {
    private val ids = AtomicInteger()
    private val announced = MutableStateFlow<Map<Int, LanService>>(emptyMap())

    /** Every live announcement, by instance name. */
    val services: Map<String, LanService> get() = announced.value.values.associateBy { it.instanceName }

    /** A new participant with its own announcement slot. */
    fun participant(): LanDiscovery = Participant(ids.incrementAndGet())

    private inner class Participant(
        private val id: Int,
    ) : LanDiscovery {
        override suspend fun announce(service: LanService) {
            require(service.port in 1..65535) { "port ${service.port} out of range" }
            announced.update { it + (id to service) }
        }

        override suspend fun withdraw() {
            announced.update { it - id }
        }

        /**
         * Found for every live announcement and each new or changed one; Lost when one is withdrawn or replaced under
         * another instance name. Cold and never completes, like a browse.
         */
        override fun browse(): Flow<LanEvent> =
            flow {
                var known = emptyMap<String, LanService>()
                announced.collect { all ->
                    val current = all.values.associateBy { it.instanceName }
                    for ((name, _) in known) if (name !in current) emit(LanEvent.Lost(name))
                    for ((name, service) in current) if (known[name] != service) emit(LanEvent.Found(service))
                    known = current
                }
            }

        override fun toString(): String = "InMemoryLanNetwork.participant($id)"
    }
}
