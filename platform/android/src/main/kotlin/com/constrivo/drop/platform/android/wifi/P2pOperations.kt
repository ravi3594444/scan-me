package com.constrivo.drop.platform.android.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Timeouts of the Wi-Fi Direct steps. The ladder's own deadline (6 s per formation, §7.8) usually ends a slow step
 * first and cancels it; these bound each step for every other caller and make a hung framework a typed
 * [WifiLinkError.TIMEOUT].
 *
 * @property actionMillis an `ActionListener` answer.
 * @property queryMillis a `requestGroupInfo` / `requestConnectionInfo` answer.
 * @property formationMillis the group formed after `createGroup` was accepted.
 * @property joinAttemptMillis one `connect` attempt of a client; then it is cancelled and tried again, because the
 *   owner may not have been up yet ([com.constrivo.drop.core.ladder.WifiLinkProvider.join] keeps retrying).
 * @property joinTotalMillis the client's retries end after this, for callers without a deadline of their own (the
 *   ladder cancels a join at its 6 s formation deadline long before).
 * @property addressMillis the client's DHCP address on the group interface.
 * @property removeMillis `removeGroup` until the group is gone.
 * @property pollMillis how often the framework is asked again when no broadcast arrives (some builds send them late).
 * @property retryMillis the pause before a busy framework or a missing group is tried again.
 */
data class P2pTimeouts(
    val actionMillis: Long = 3_000,
    val queryMillis: Long = 2_000,
    val formationMillis: Long = 8_000,
    val joinAttemptMillis: Long = 4_000,
    val joinTotalMillis: Long = 30_000,
    val addressMillis: Long = 2_000,
    val removeMillis: Long = 3_000,
    val pollMillis: Long = 250,
    val retryMillis: Long = 300,
) {
    init {
        require(
            listOf(
                actionMillis,
                queryMillis,
                formationMillis,
                joinAttemptMillis,
                joinTotalMillis,
                addressMillis,
                removeMillis,
                pollMillis,
                retryMillis,
            )
                .all { it > 0 },
        ) { "Wi-Fi Direct timeouts must be positive" }
    }
}

/** A formed group as both platform queries describe it. */
data class FormedGroup(
    val connection: P2pConnectionSnapshot,
    val group: P2pGroupSnapshot,
)

/**
 * The callback-to-suspend adapters over [P2pRadio]: each suspends until the framework answers, is bounded by a
 * [P2pTimeouts] value ([WifiLinkError.TIMEOUT]), and turns a `SecurityException` into
 * [WifiLinkError.PERMISSION_MISSING]. Cancelling a call only stops waiting; undoing what the framework started
 * (removing a half-formed group, cancelling a connect) is the caller's job, before its cancellation completes.
 */
internal class P2pOperations(
    private val radio: P2pRadio,
    private val timeouts: P2pTimeouts,
) {
    /** Runs an `ActionListener` call; returns its answer (a failure is a result, not an exception). */
    suspend fun action(
        name: String,
        call: P2pRadio.((P2pActionResult) -> Unit) -> Unit,
    ): P2pActionResult = await(name, timeouts.actionMillis) { done -> radio.call(done) }

    /**
     * Runs an `ActionListener` call that must succeed.
     *
     * @throws WifiLinkException typed by [P2pFailureCodes.error] when the framework refuses it.
     */
    suspend fun require(
        name: String,
        call: P2pRadio.((P2pActionResult) -> Unit) -> Unit,
    ) {
        val result = action(name, call)
        if (result is P2pActionResult.Failure) {
            throw WifiLinkException(
                P2pFailureCodes.error(result.reason),
                "$name failed: ${P2pFailureCodes.describe(result.reason)}",
                platformCode = result.reason,
            )
        }
    }

    suspend fun groupInfo(): P2pGroupSnapshot? = await("requestGroupInfo", timeouts.queryMillis) { done -> radio.requestGroupInfo(done) }

    suspend fun enabled(): Boolean? = await("requestP2pState", timeouts.queryMillis) { done -> radio.requestEnabled(done) }

    suspend fun connectionInfo(): P2pConnectionSnapshot? =
        await("requestConnectionInfo", timeouts.queryMillis) { done -> radio.requestConnectionInfo(done) }

    /**
     * Waits until a group is formed with this device as its owner ([asOwner]) or as a client. The broadcasts only wake
     * the wait; the answer always comes from the two queries, which some builds keep current where their broadcasts
     * come late.
     *
     * @throws WifiLinkException [WifiLinkError.TIMEOUT] after [timeoutMillis].
     */
    suspend fun awaitFormed(
        asOwner: Boolean,
        timeoutMillis: Long,
    ): FormedGroup =
        timed("the group to form", timeoutMillis) {
            var formed: FormedGroup? = null
            while (formed == null) {
                val seen = radio.state.value.sequence
                formed = probeFormed(asOwner)
                if (formed == null) awaitBroadcast(seen)
            }
            formed
        }

    /** One look at the current group; null when none is formed in the wanted role. */
    suspend fun probeFormed(asOwner: Boolean): FormedGroup? {
        val connection = connectionInfo()?.takeIf { it.groupFormed && it.isGroupOwner == asOwner } ?: return null
        val group = groupInfo()?.takeIf { it.isGroupOwner == asOwner } ?: return null
        return FormedGroup(connection, group)
    }

    /** Waits until no group is formed any more; false when it still is after [timeoutMillis]. Never throws a timeout. */
    suspend fun awaitNoGroup(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val seen = radio.state.value.sequence
                if (!groupPresent()) break
                awaitBroadcast(seen)
            }
            true
        } ?: false

    /**
     * Whether a group is still up, from both queries. A query that gets no answer counts as "still up" (keep waiting);
     * one refused for a missing permission tells nothing, so the other query decides.
     */
    private suspend fun groupPresent(): Boolean {
        val formed =
            try {
                connectionInfo()?.groupFormed ?: false
            } catch (e: WifiLinkException) {
                if (e.error == WifiLinkError.TIMEOUT) return true
                false
            }
        if (formed) return true
        return try {
            groupInfo() != null
        } catch (e: WifiLinkException) {
            e.error == WifiLinkError.TIMEOUT
        }
    }

    /** Waits for the next broadcast after [seen], at most [P2pTimeouts.pollMillis]. */
    suspend fun awaitBroadcast(seen: Long) {
        withTimeoutOrNull(timeouts.pollMillis) { radio.state.first { it.sequence != seen } }
    }

    /**
     * Calls [call] and suspends until its callback answers, at most [timeoutMillis]. A second answer is ignored, and so
     * is an answer after the wait ended.
     */
    private suspend fun <T> await(
        name: String,
        timeoutMillis: Long,
        call: ((T) -> Unit) -> Unit,
    ): T =
        timed(name, timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val answered = AtomicBoolean(false)
                call { value -> if (answered.compareAndSet(false, true)) continuation.resume(value) }
            }
        }

    private suspend fun <T> timed(
        name: String,
        timeoutMillis: Long,
        block: suspend () -> T,
    ): T =
        try {
            withLinkTimeout(name, timeoutMillis, block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw WifiLinkException(WifiLinkError.PERMISSION_MISSING, "$name was refused: ${e.message}", cause = e)
        } catch (e: RuntimeException) {
            // IllegalArgumentException from a config builder, IllegalStateException from a dead service: typed, not raw.
            throw WifiLinkException(WifiLinkError.FAILED, "$name failed: ${e.message ?: e::class.simpleName}", cause = e)
        }
}
