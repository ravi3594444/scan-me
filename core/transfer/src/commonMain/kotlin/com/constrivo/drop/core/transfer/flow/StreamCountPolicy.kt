package com.constrivo.drop.core.transfer.flow

import com.constrivo.drop.core.protocol.ProtocolConstants
import com.constrivo.drop.core.transfer.ThermalLevel

/**
 * How many Wi-Fi data streams to use (architecture §7.4, F-E8, F-F6): [ProtocolConstants.DEFAULT_STREAMS] (4) at first;
 * [ProtocolConstants.MAX_STREAMS] (8) once a one-second throughput measurement exceeds
 * [ProtocolConstants.RAISE_STREAMS_ABOVE_BPS] (40 MB/s), and it stays raised; [ProtocolConstants.THERMAL_STREAMS] (2)
 * while this device, or the peer by its `thermal` hint, is at [ThermalLevel.SEVERE] or hotter. Never more than
 * [maxStreams] (`Accept.stream_count`, the receiver's limit) and never fewer than one.
 *
 * Both devices run it: the side that opens the data connections (the link's joiner) opens up to its target, which
 * signals a raise to the peer through the new `StreamOpen` connections; the sender schedules chunks on at most its own
 * target of the connected streams. Not thread-safe; the engine calls it from its ticker.
 */
class StreamCountPolicy(
    val maxStreams: Int = ProtocolConstants.MAX_STREAMS,
) {
    init {
        require(maxStreams in 1..ProtocolConstants.MAX_STREAMS) { "maxStreams out of range" }
    }

    private var raised = false

    /** True once the throughput rule raised the count. */
    val isRaised: Boolean get() = raised

    /** The stream count for a measured [lastSecondBytesPerSecond], the local [thermal] level and the [peerHot] hint. */
    fun target(
        lastSecondBytesPerSecond: Long,
        thermal: ThermalLevel,
        peerHot: Boolean = false,
    ): Int {
        if (lastSecondBytesPerSecond > ProtocolConstants.RAISE_STREAMS_ABOVE_BPS) raised = true
        val wanted =
            when {
                thermal >= THERMAL_LEVEL || peerHot -> ProtocolConstants.THERMAL_STREAMS
                raised -> ProtocolConstants.MAX_STREAMS
                else -> ProtocolConstants.DEFAULT_STREAMS
            }
        return wanted.coerceIn(1, maxStreams)
    }

    companion object {
        /** From this level on the stream count drops to 2 and the `thermal` hint shows (§7.8). */
        val THERMAL_LEVEL: ThermalLevel = ThermalLevel.SEVERE
    }
}
