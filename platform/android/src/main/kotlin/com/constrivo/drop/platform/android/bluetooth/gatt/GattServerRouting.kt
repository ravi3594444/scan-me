package com.constrivo.drop.platform.android.bluetooth.gatt

/**
 * How the GATT server routes one segment a client wrote ([GattServerHost]; the in-memory test link uses the same rules),
 * independent of Android classes.
 *
 * A client's stream starts with a fresh `OPEN` (sequence number 0). Two things make an `OPEN` arrive while the client
 * already has a session: a write the client's stack reported as not sent although it was, which the client then sent
 * again (a byte-identical repeat before anything else arrived: it belongs to the current session, whose sequence numbers
 * drop it), and a client that really started a new stream (it replaces the session).
 */
internal object GattServerRouting {
    enum class Route {
        /** Deliver to the client's current session. */
        EXISTING,

        /** A fresh `OPEN`: start a new session, replacing the client's current one if there is one. */
        NEW,

        /** A fresh `OPEN` while every session is in use: answer it with `RESET(refused)` from a session that is not kept. */
        REFUSE,

        /** A segment of a stream this server does not have (any more): dropped; the client's own timeouts end it. */
        DROP,
    }

    /**
     * The route of [value] from a client whose current session is [existing] (null when it has none), while the server
     * holds [sessionCount] sessions and takes at most [maxSessions].
     */
    fun route(
        value: ByteArray,
        existing: GattStreamChannel?,
        sessionCount: Int,
        maxSessions: Int,
    ): Route {
        val fresh = isFreshOpen(value)
        return when {
            existing != null && (!fresh || existing.isRepeatedOpen(value)) -> Route.EXISTING
            !fresh -> Route.DROP
            existing == null && sessionCount >= maxSessions -> Route.REFUSE
            else -> Route.NEW
        }
    }

    /**
     * Starts the server end of a new stream: offers [channel] to [onIncoming] first and answers the client's [open] with
     * `OPEN_ACK` only once the channel has an owner; a refused channel answers `RESET(refused)` instead, so the client fails
     * at once rather than writing into a stream nobody reads. Returns whether the channel was taken.
     */
    fun start(
        channel: GattStreamChannel,
        open: ByteArray,
        onIncoming: (GattStreamChannel) -> Boolean,
    ): Boolean {
        if (!onIncoming(channel)) {
            channel.refuse(open)
            return false
        }
        channel.accept(open)
        return true
    }

    /** An `OPEN` with sequence number 0: the first segment of a client's stream. */
    fun isFreshOpen(value: ByteArray): Boolean =
        value.size >= GattSegments.HEADER_SIZE &&
            (value[0].toInt() and 0xFF) == GattSegments.TYPE_OPEN &&
            value[1].toInt() == 0 &&
            value[2].toInt() == 0
}
