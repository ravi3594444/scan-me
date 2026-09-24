package com.constrivo.drop.platform.desktop.lan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Follows the machine's LAN interface (architecture §10.2 note): [select] (normally [LanInterfaces.selectCurrent]) is
 * asked every [intervalMillis], and [selection] changes when its answer does, from no network to one (Wi‑Fi joined
 * after login, F‑H5), from one network to another, or to a new address (DHCP). The app moves the node with
 * `DesktopNode.setLanAddress` and the banner follows [available]. Listing interfaces is cheap and local; nothing is
 * sent. OS network-change events can replace the polling later without changing this contract.
 */
class LanWatcher(
    private val select: () -> LanSelection?,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {
    init {
        require(intervalMillis > 0) { "the interval must be positive" }
    }

    private val state =
        MutableStateFlow(
            try {
                select()
            } catch (e: Exception) {
                onError("the network interfaces could not be listed", e)
                null
            },
        )
    private val availableState = MutableStateFlow(state.value != null)

    /** The interface the LAN path should use now; null without a network. */
    val selection: StateFlow<LanSelection?> = state.asStateFlow()

    /** Whether there is a network now (the no-network banner, design §9). */
    val available: StateFlow<Boolean> = availableState.asStateFlow()

    /** Asks [select] once and publishes a changed answer; returns the current selection. */
    suspend fun refresh(): LanSelection? {
        val now =
            try {
                withContext(io) { select() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError("the network interfaces could not be listed", e)
                return state.value
            }
        state.value = now
        availableState.value = now != null
        return now
    }

    /** Polls until [scope] ends. */
    fun launchIn(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                delay(intervalMillis)
                refresh()
            }
        }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS: Long = 3_000
    }
}
