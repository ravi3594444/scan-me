package com.constrivo.drop.core.ladder

import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.Capabilities.Flag
import com.constrivo.drop.core.protocol.Hint
import com.constrivo.drop.core.protocol.HintCode
import com.constrivo.drop.core.protocol.LinkKind
import com.constrivo.drop.core.transfer.ThermalLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Speed hints of F-F3 and design §8.2: which one shows, and never a false one. */
class HintRulesTest {
    private val fiveGhz = Caps.FLAGSHIP
    private val band24 = Caps.BAND24_ONLY

    private fun inputs(
        link: LinkKind? = LinkKind.P2P,
        freq: Int? = 5180,
        local: Capabilities = fiveGhz,
        peer: Capabilities = fiveGhz,
        host: Side? = Side.PEER,
        pinned: Boolean = false,
        thermal: ThermalLevel = ThermalLevel.NONE,
        sdcard: Boolean = false,
        bundled: Int = 0,
        btFallback: Boolean = false,
        lanSlow: Boolean = false,
    ) = HintInputs(link, freq, local, peer, "Asha", host, pinned, thermal, sdcard, bundled, btFallback, lanSlow)

    @Test
    fun fF3_pixelPairNeverGetsAHint() {
        // Two 5 GHz phones, cool, internal storage, large files: nothing at any stage of the ladder.
        val stages =
            listOf(
                inputs(link = LinkKind.BLUETOOTH, freq = null, host = null), // head start
                inputs(link = LinkKind.P2P, freq = null), // group up, channel not yet reported
                inputs(link = LinkKind.P2P, freq = 5180),
                inputs(link = LinkKind.P2P, freq = 5745),
                inputs(link = LinkKind.P2P, freq = 5955),
                inputs(link = LinkKind.LAN, freq = null, host = null),
                inputs(link = null, freq = null, host = null),
            )
        for (stage in stages) {
            assertNull(HintRules.select(stage), "$stage")
            assertEquals(emptyList(), HintRules.active(stage), "$stage")
        }
        assertNull(HintRules.select(inputs(thermal = ThermalLevel.MODERATE, bundled = HintRules.BUNDLING_HINT_MIN_FILES - 1)))
    }

    @Test
    fun fF3_everyConditionFiresItsHint() {
        assertEquals(HintCode.BAND24, HintRules.select(inputs(freq = 2437))?.code)
        assertEquals(HintCode.PEER_BAND24_ONLY, HintRules.select(inputs(freq = 2437, peer = band24))?.code)
        assertEquals(HintCode.STATION_BAND24, HintRules.select(inputs(freq = 2437, pinned = true))?.code)
        assertEquals(HintCode.THERMAL, HintRules.select(inputs(thermal = ThermalLevel.SEVERE))?.code)
        assertEquals(HintCode.THERMAL, HintRules.select(inputs(thermal = ThermalLevel.EMERGENCY))?.code)
        assertEquals(HintCode.SDCARD, HintRules.select(inputs(sdcard = true))?.code)
        assertEquals("Bundling 10 small files", HintRules.select(inputs(bundled = 10))?.englishText)
        assertEquals(HintCode.BT_FALLBACK, HintRules.select(inputs(link = LinkKind.BLUETOOTH, freq = null, btFallback = true))?.code)
        assertEquals(HintCode.LAN_SLOW, HintRules.select(inputs(link = LinkKind.LAN, freq = null, lanSlow = true))?.code)
    }

    @Test
    fun fF3_bandHintsAreOnlyTrueOnes() {
        // This device is the 2.4 GHz-only one: the peer shows the hint, not us.
        assertNull(HintRules.select(inputs(freq = 2437, local = band24)))
        // A hotspot's band is not selectable (N8): "move closer" would not help.
        assertNull(HintRules.select(inputs(link = LinkKind.HOTSPOT, freq = 2437)))
        assertEquals(HintCode.PEER_BAND24_ONLY, HintRules.select(inputs(link = LinkKind.HOTSPOT, freq = 2437, peer = band24))?.code)
        // LAN and Bluetooth have no band hint.
        assertNull(HintRules.select(inputs(link = LinkKind.LAN, freq = 2437)))
        assertNull(HintRules.select(inputs(link = LinkKind.BLUETOOTH, freq = 2437)))
        // "Slow mode" only while Bluetooth carries the data.
        assertNull(HintRules.select(inputs(btFallback = true)))
        // The 6 GHz radio implies 5 GHz.
        assertEquals(HintCode.BAND24, HintRules.select(inputs(freq = 2437, peer = Caps.BAND24_ONLY + Flag.WIFI_6GHZ))?.code)
    }

    @Test
    fun fF3_priorityWhenSeveralApply() {
        val all =
            inputs(
                link = LinkKind.BLUETOOTH,
                freq = null,
                host = null,
                thermal = ThermalLevel.CRITICAL,
                sdcard = true,
                bundled = 500,
                btFallback = true,
                lanSlow = true,
            )
        assertEquals(
            listOf(HintCode.BT_FALLBACK, HintCode.LAN_SLOW, HintCode.THERMAL, HintCode.SDCARD, HintCode.BUNDLING),
            HintRules.active(all).map { it.code },
        )
        val onWifi = inputs(freq = 2412, pinned = true, thermal = ThermalLevel.SEVERE, sdcard = true, bundled = 20)
        assertEquals(
            listOf(HintCode.STATION_BAND24, HintCode.THERMAL, HintCode.SDCARD, HintCode.BUNDLING),
            HintRules.active(onWifi).map {
                it.code
            },
        )
        assertEquals(HintCode.THERMAL, HintRules.select(inputs(thermal = ThermalLevel.SEVERE, sdcard = true, bundled = 20))?.code)
        assertEquals(HintCode.SDCARD, HintRules.select(inputs(sdcard = true, bundled = 20))?.code)
        assertEquals(HintCode.entries.toSet(), HintRules.PRIORITY.toSet())
    }

    @Test
    fun fF3_hintInputsComeFromTheRunnerState() {
        val offPlan = plan(phone(), phone(), localRadio = RadioState(wifiEnabled = false, bluetoothEnabled = true))
        val state = LadderState.initial(offPlan).copy(started = true, phase = LadderPhase.BLUETOOTH_ONLY)
        val inputs = HintInputs.from(state, thermal = ThermalLevel.SEVERE)
        assertEquals(LinkKind.BLUETOOTH, inputs.linkKind)
        assertEquals(true, inputs.bluetoothFallback)
        assertEquals(HintCode.BT_FALLBACK, HintRules.select(inputs)?.code)

        val mobile = plan(phone(), phone())
        val onP2p =
            LadderState.initial(mobile).copy(
                started = true,
                phase = LadderPhase.ACTIVE,
                attempts =
                    mobile.candidates.mapIndexed { i, c ->
                        LinkAttempt(
                            c,
                            if (i ==
                                0
                            ) {
                                AttemptStatus.ACTIVE
                            } else {
                                AttemptStatus.PENDING
                            },
                            freqMhz = 5180.takeIf { i == 0 },
                        )
                    },
                dataIndex = 0,
            )
        val p2pInputs = HintInputs.from(onP2p)
        assertEquals(LinkKind.P2P, p2pInputs.linkKind)
        assertEquals(5180, p2pInputs.freqMhz)
        assertEquals(Side.PEER, p2pInputs.host)
        assertNull(HintRules.select(p2pInputs))
    }

    @Test
    fun hintMessagesRoundTripWithTheSideFlipped() {
        val mine = LadderHint.stationBand24(Side.LOCAL, null)
        val message = mine.toMessage()
        assertEquals(Hint(HintCode.STATION_BAND24, mapOf(LadderHint.PARAM_SIDE to LadderHint.SIDE_LOCAL)), message)
        // On the peer's screen it is the other device's network.
        val theirs =
            LadderHint.fromMessage(
                Hint(
                    HintCode.STATION_BAND24,
                    mapOf(
                        LadderHint.PARAM_SIDE to LadderHint.SIDE_LOCAL,
                        LadderHint.PARAM_NAME to "Ravi",
                    ),
                ),
            )
        assertEquals("Ravi's Wi\u2011Fi network is on 2.4 GHz", theirs?.englishText)
        assertEquals("The other device's Wi\u2011Fi network is on 2.4 GHz", LadderHint.stationBand24(Side.PEER, null).englishText)
        assertEquals("The other device supports 2.4 GHz only", LadderHint.peerBand24Only(null).englishText)
        assertEquals(LadderHint.sdcard(), LadderHint.fromMessage(LadderHint.sdcard().toMessage()))
        assertNull(LadderHint.fromMessage(Hint("turbo_mode")))
        assertEquals("hint.peer_band24_only", LadderHint.peerBand24Only("x").copyKey)
    }

    @Test
    fun hintParamsAreValidatedAgainstTheWireLimits() {
        assertFailsWith<IllegalArgumentException> { LadderHint.peerBand24Only("n".repeat(300)) }
        assertFailsWith<IllegalArgumentException> { LadderHint.bundling(0) }
        assertFailsWith<IllegalArgumentException> { inputs(bundled = -1) }
    }
}
