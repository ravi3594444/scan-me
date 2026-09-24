package com.constrivo.drop.platform.android.bluetooth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure pieces of [BluetoothChannelConnector]: the channel-info fallback and the callback thread's lifetime. */
class ConnectorPiecesTest {
    @Test
    fun anUnusableChannelInfoMeansNoPsmNotAFailedAttempt() =
        runTest {
            val notes = ArrayList<Throwable>()
            assertEquals(0x0081, BluetoothChannelConnector.psmOrNull("A", notes) { ChannelInfo(0x0081) })
            assertNull(BluetoothChannelConnector.psmOrNull("A", notes) { ChannelInfo(null) })
            assertTrue(notes.isEmpty())
            // A peer whose value does not parse (the L2CAP flag with PSM 0, a short value), or a read that failed: the
            // GATT stream on the same connection is still tried, and the reason is kept for the attempts.
            assertNull(BluetoothChannelConnector.psmOrNull("A", notes) { ChannelInfo.decode(byteArrayOf(1, 1, 0, 0)) })
            assertNull(BluetoothChannelConnector.psmOrNull("A", notes) { ChannelInfo.decode(byteArrayOf(1)) })
            assertNull(BluetoothChannelConnector.psmOrNull("A", notes) { throw IOException("reading channel info failed (status 133)") })
            assertEquals(3, notes.size)
            assertIs<BluetoothProfileException>(notes[0].cause)
            // A cancelled connect stays cancelled.
            assertFailsWith<CancellationException> { BluetoothChannelConnector.psmOrNull("A", notes) { throw CancellationException() } }
        }

    @Test
    fun theCallbackThreadOutlivesCloseUntilTheLastLinkIsReleased() {
        var released = 0
        val thread = SharedResource<String> { released++ }
        assertTrue(thread.acquire("gatt-stream"))
        assertTrue(thread.acquire("connecting"))
        thread.done("connecting")
        thread.close()
        assertEquals(0, released, "a GATT-stream channel that was returned still needs its callbacks")
        assertFalse(thread.acquire("late"), "a closed connector makes no new links")
        thread.done("gatt-stream")
        assertEquals(1, released)
        thread.done("gatt-stream")
        thread.close()
        assertEquals(1, released)
    }

    @Test
    fun anIdleConnectorReleasesItsThreadAtClose() {
        var released = 0
        val thread = SharedResource<String> { released++ }
        thread.close()
        thread.close()
        assertEquals(1, released)
    }
}
