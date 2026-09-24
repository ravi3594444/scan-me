package com.constrivo.drop.platform.android.wifi

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.ladder.HostRequest
import com.constrivo.drop.core.ladder.JoinRequest
import com.constrivo.drop.core.ladder.LinkCredentialsException
import com.constrivo.drop.core.ladder.LinkMode
import com.constrivo.drop.core.ladder.P2pCredentials
import com.constrivo.drop.core.protocol.WifiCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Credentials to `WifiP2pConfig` (F-E2, F-F5; S5, N7, N9) and the framework's failure codes. */
class P2pConfigMappingTest {
    private val crypto = JcaCryptoProvider()
    private val random = P2pCredentials.random(crypto)
    private val trusted = P2pCredentials.forTrustedPair(crypto, ByteArray(32) { it.toByte() })

    @Test
    fun theOwnerAsksFor5GhzOnlyWhenBothDevicesSupportIt() {
        val five = P2pConfigMapping.forHost(HostRequest(LinkMode.P2P, random, requestFiveGhz = true))
        assertEquals(P2pBand.GHZ_5, five.band)
        assertEquals(random.ssid, five.networkName)
        assertEquals(random.passphrase, five.passphrase)
        assertFalse(five.persistent)

        val two = P2pConfigMapping.forHost(HostRequest(LinkMode.P2P_LEGACY, random, requestFiveGhz = false))
        assertEquals(P2pBand.GHZ_2_4, two.band, "a 2.4 GHz-only joiner must see the group")
    }

    @Test
    fun theReFormAsksFor5GhzAgainAndTrustedPairsArePersistent() {
        val reform = P2pConfigMapping.forHost(HostRequest(LinkMode.P2P, trusted, requestFiveGhz = true, persistent = true, attempt = 1))
        assertEquals(P2pBand.GHZ_5, reform.band)
        assertTrue(reform.persistent)
        assertEquals(trusted, reform.credentials)
    }

    @Test
    fun aClientScansEveryBand() {
        val spec = P2pConfigMapping.forClient(JoinRequest(LinkMode.P2P, trusted, requestFiveGhz = true, persistent = true))
        assertEquals(P2pBand.AUTO, spec.band)
        assertTrue(spec.persistent)
        assertEquals(trusted.ssid, spec.networkName)
    }

    @Test
    fun credentialsThatBreakTheWifiDirectRulesAreRefusedBeforeThePlatform() {
        val badNames = listOf("DIRECT-a", "direct-ab-Drop-wxyz", "AndroidShare_1234", "DIRECT-!!-x")
        for (name in badNames) {
            assertFailsWith<LinkCredentialsException>(name) {
                P2pConfigMapping.forHost(HostRequest(LinkMode.P2P, WifiCredentials(name, "abcdefgh")))
            }
        }
        assertFailsWith<LinkCredentialsException> {
            P2pConfigMapping.forClient(JoinRequest(LinkMode.P2P, WifiCredentials("DIRECT-ab-x", "abcdefg\u0001")))
        }
        assertFailsWith<LinkCredentialsException> {
            P2pConfigMapping.forClient(JoinRequest(LinkMode.P2P, WifiCredentials("DIRECT-ab-x", "a".repeat(64))))
        }
    }

    @Test
    fun onlyWifiDirectRequestsAreMapped() {
        assertFailsWith<IllegalArgumentException> { P2pConfigMapping.forHost(HostRequest(LinkMode.HOTSPOT)) }
        assertFailsWith<IllegalArgumentException> { P2pConfigMapping.forClient(JoinRequest(LinkMode.P2P_LEGACY, random)) }
    }

    @Test
    fun theSpecNeverPrintsThePassphrase() {
        val spec = P2pConfigMapping.forHost(HostRequest(LinkMode.P2P, random))
        assertFalse(spec.toString().contains(random.passphrase))
        assertTrue(spec.toString().contains(random.ssid))
    }

    @Test
    fun ownGroupNamesAreRecognisedAndOthersAreNot() {
        assertTrue(P2pConfigMapping.isOwnGroupName(random.ssid))
        assertTrue(P2pConfigMapping.isOwnGroupName(trusted.ssid))
        assertTrue(P2pConfigMapping.isOwnGroupName("DIRECT-k7-Drop-m3xq"))
        for (name in listOf(
            null,
            "",
            "DIRECT-k7-Other-m3xq",
            "DIRECT-k7-Drop-m3x",
            "DIRECT-10-Drop-m3xq",
            "DIRECT-xy-Android_1f2e",
            "AndroidShare_1234",
        )) {
            assertFalse(P2pConfigMapping.isOwnGroupName(name), "$name")
        }
    }

    @Test
    fun failureCodesAreTyped() {
        assertEquals(WifiLinkError.FAILED, P2pFailureCodes.error(P2pFailureCodes.ERROR))
        assertEquals(WifiLinkError.UNSUPPORTED, P2pFailureCodes.error(P2pFailureCodes.P2P_UNSUPPORTED))
        assertEquals(WifiLinkError.BUSY, P2pFailureCodes.error(P2pFailureCodes.BUSY))
        assertEquals(WifiLinkError.FAILED, P2pFailureCodes.error(P2pFailureCodes.NO_SERVICE_REQUESTS))
        assertEquals(WifiLinkError.PERMISSION_MISSING, P2pFailureCodes.error(P2pFailureCodes.NO_PERMISSION))
        assertEquals(WifiLinkError.FAILED, P2pFailureCodes.error(42))
        assertEquals("BUSY", P2pFailureCodes.describe(2))
        assertEquals("reason 42", P2pFailureCodes.describe(42))
    }
}
