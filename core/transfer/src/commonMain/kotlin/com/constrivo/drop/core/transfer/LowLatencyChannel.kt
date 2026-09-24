package com.constrivo.drop.core.transfer

/**
 * A `DataChannel` whose latency can be tuned (architecture §7.4): a TCP socket turns `TCP_NODELAY` on for the control
 * stream only, so small control frames (acks, heartbeats) are not held back by Nagle's algorithm while bulk data streams
 * keep coalescing. The engine calls [setLowLatency] on the connections that carry control.
 */
interface LowLatencyChannel {
    fun setLowLatency(enabled: Boolean)
}
