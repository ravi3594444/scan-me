package com.constrivo.drop.platform.desktop.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The LAN watcher of architecture §10.2's note: the app follows the machine's network (F‑H5, a Wi‑Fi switch). */
class LanWatcherTest {
    private val wifi = LanSelection("wlan0", InetAddress.getByName("192.168.1.20"))
    private val dhcp = LanSelection("wlan0", InetAddress.getByName("192.168.1.57"))
    private val office = LanSelection("eth0", InetAddress.getByName("10.0.0.7"))

    @Test
    fun `the selection follows the interfaces from no network to one, to a new address and to another network`() =
        runBlocking<Unit> {
            val current = AtomicReference<LanSelection?>(null)
            val watcher = LanWatcher({ current.get() }, intervalMillis = 20, io = Dispatchers.Unconfined)
            assertNull(watcher.selection.value, "started before Wi-Fi was up (the login entry)")
            assertFalse(watcher.available.value)

            current.set(wifi)
            assertEquals(wifi, watcher.refresh())
            assertEquals(wifi, watcher.selection.value)
            assertTrue(watcher.available.value)

            val polling = watcher.launchIn(this)
            try {
                withTimeout(5_000) {
                    current.set(dhcp)
                    watcher.selection.first { it == dhcp }
                    current.set(office)
                    watcher.selection.first { it == office }
                    current.set(null)
                    watcher.available.first { !it }
                }
                assertNull(watcher.selection.value)
            } finally {
                polling.cancel()
            }
        }

    @Test
    fun `a listing that fails keeps the last answer and is reported`() =
        runBlocking<Unit> {
            val fail = AtomicBoolean(false)
            val errors = ArrayList<String>()
            val watcher =
                LanWatcher(
                    { if (fail.get()) throw SocketException("no interfaces") else wifi },
                    io = Dispatchers.Unconfined,
                    onError = { message, _ -> errors += message },
                )
            assertEquals(wifi, watcher.selection.value)
            fail.set(true)
            assertEquals(wifi, watcher.refresh())
            assertTrue(watcher.available.value)
            assertEquals(1, errors.size)
        }

    @Test
    fun `a failing first listing starts without a network`() {
        val errors = ArrayList<String>()
        val watcher = LanWatcher({ throw SocketException("no interfaces") }, onError = { message, _ -> errors += message })
        assertNull(watcher.selection.value)
        assertFalse(watcher.available.value)
        assertEquals(1, errors.size)
    }
}
