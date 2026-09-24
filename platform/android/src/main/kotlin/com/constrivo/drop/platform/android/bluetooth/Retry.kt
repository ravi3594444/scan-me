package com.constrivo.drop.platform.android.bluetooth

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Retries [attempt] until it succeeds, or until the caller is cancelled. After a failure it waits [firstDelayMillis],
 * doubling per further failure up to [maxDelayMillis]. A change of [retryNow] (the owner saw something that may let the
 * next attempt succeed, such as a permission grant) cuts the wait short and starts the back-off over; a change during an
 * attempt counts too.
 *
 * @param attempt is told the attempt's number (from 1) and returns whether it succeeded.
 */
internal suspend fun retryWithBackoff(
    firstDelayMillis: Long,
    maxDelayMillis: Long,
    retryNow: StateFlow<Long>,
    attempt: suspend (number: Int) -> Boolean,
) {
    var number = 0
    var wait = firstDelayMillis
    while (true) {
        val seen = retryNow.value
        if (attempt(++number)) return
        val woken = awaitRetry(wait, retryNow, seen)
        wait = if (woken) firstDelayMillis else minOf(maxDelayMillis, wait * 2)
    }
}

/** Waits up to [millis], or until [retryNow] has moved on from [seen]; true in the second case. */
internal suspend fun awaitRetry(
    millis: Long,
    retryNow: StateFlow<Long>,
    seen: Long,
): Boolean = withTimeoutOrNull(millis) { retryNow.first { it != seen } } != null
