package com.constrivo.drop.platform.android.wifi

import kotlinx.coroutines.withTimeoutOrNull

/** Carries a result through [withTimeoutOrNull], whose null otherwise means both "timed out" and "returned null". */
private class Box<T>(
    val value: T,
)

/**
 * Runs [block] for at most [timeoutMillis]; on this call's own timeout throws [onTimeout]'s exception. A timeout of an
 * enclosing scope stays a cancellation (only this call's timeout is converted, which a `catch` of
 * `TimeoutCancellationException` could not tell apart), and so does a cancellation of the caller.
 */
internal suspend fun <T> withTimeoutOrThrow(
    timeoutMillis: Long,
    onTimeout: () -> Exception,
    block: suspend () -> T,
): T {
    val box = withTimeoutOrNull(timeoutMillis) { Box(block()) } ?: throw onTimeout()
    return box.value
}

/** [withTimeoutOrThrow] with a [WifiLinkError.TIMEOUT] naming [what]. */
internal suspend fun <T> withLinkTimeout(
    what: String,
    timeoutMillis: Long,
    block: suspend () -> T,
): T = withTimeoutOrThrow(timeoutMillis, { WifiLinkException(WifiLinkError.TIMEOUT, "no answer to $what within $timeoutMillis ms") }, block)
