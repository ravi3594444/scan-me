package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Network hint from link properties (spec change N6). */
class NetworkHintTest {
    private val crypto = JcaCryptoProvider()

    private fun ip(text: String) = IpAddress.parse(text)

    private val home =
        NetworkLinkInfo(
            gateways = listOf(ip("192.168.1.1"), ip("fe80::1")),
            dhcpServer = ip("192.168.1.1"),
            ipv6Addresses = listOf(ip("2001:db8:1:2::abcd"), ip("fd12:3456:789a:1::5"), ip("fe80::1234"), ip("192.168.1.20")),
        )

    @Test
    fun n6_goldenVectors() {
        // SHA-256("drop-net-v1" ‖ items)[0..4], computed with Python hashlib.
        assertEquals("5c452326", NetworkHint.derive(crypto, home).toHex())
        assertEquals("91eb8bd4", NetworkHint.derive(crypto, NetworkLinkInfo(gateways = listOf(ip("192.168.1.1")))).toHex())
        assertEquals("8fb82615", NetworkHint.derive(crypto, NetworkLinkInfo(dhcpServer = ip("192.168.1.1"))).toHex())
    }

    @Test
    fun n6_orderCanonicalAndStable() {
        val shuffled =
            NetworkLinkInfo(
                gateways = listOf(ip("fe80::1"), ip("192.168.1.1"), ip("::ffff:192.168.1.1")),
                dhcpServer = ip("::ffff:192.168.1.1"),
                ipv6Addresses =
                    listOf(
                        ip("192.168.1.20"),
                        ip("fd12:3456:789a:1::99"),
                        ip("fe80::9"),
                        ip("2001:0db8:0001:0002:0000:0000:0000:0001"),
                        ip("2001:db8:1:2::abcd%wlan0"),
                    ),
            )
        assertEquals(NetworkHint.derive(crypto, home), NetworkHint.derive(crypto, shuffled))
        assertEquals(NetworkHint.derive(crypto, home), NetworkHint.derive(crypto, home))
    }

    @Test
    fun n6_differentNetworksGiveDifferentHints() {
        val other = NetworkLinkInfo(gateways = listOf(ip("192.168.1.1")), dhcpServer = ip("192.168.1.254"))
        assertNotEquals(
            NetworkHint.derive(crypto, NetworkLinkInfo(gateways = listOf(ip("192.168.1.1")))),
            NetworkHint.derive(crypto, other),
        )
        // The same address in a different role is a different input.
        assertNotEquals(
            NetworkHint.derive(crypto, NetworkLinkInfo(gateways = listOf(ip("10.0.0.1")))),
            NetworkHint.derive(crypto, NetworkLinkInfo(dhcpServer = ip("10.0.0.1"))),
        )
    }

    @Test
    fun n6_zeroWhenNotConnectedOrNothingUsable() {
        assertTrue(NetworkHint.derive(crypto, null).isNone)
        assertTrue(NetworkHint.derive(crypto, NetworkLinkInfo()).isNone)
        val useless =
            NetworkLinkInfo(
                gateways = listOf(ip("0.0.0.0"), ip("::"), ip("127.0.0.1"), ip("224.0.0.1")),
                dhcpServer = ip("0.0.0.0"),
                ipv6Addresses = listOf(ip("fe80::1"), ip("::1"), ip("ff02::1"), ip("10.0.0.5")),
            )
        assertEquals(NetworkHint.NONE, NetworkHint.derive(crypto, useless))
    }

    @Test
    fun aZeroDigestStillMeansConnected() {
        val zero = FixedDigestCrypto(ByteArray(32))
        assertEquals(NetworkHint(1), NetworkHint.derive(zero, NetworkLinkInfo(gateways = listOf(ip("10.0.0.1")))))
    }

    @Test
    fun hintBytesAreBigEndian() {
        val hint = NetworkHint(0xA1B2C3D4.toInt())
        assertEquals("a1b2c3d4", hint.toHex())
        assertEquals(hint, NetworkHint.fromBytes(hint.toByteArray()))
        assertEquals("a1b2c3d4", Bytes.hex(hint.toByteArray()))
    }
}
