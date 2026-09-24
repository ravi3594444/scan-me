package com.constrivo.drop.platform.android.wifi

import java.net.Inet4Address
import java.net.Inet6Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** IP literals, prefixes and the dial rule of the Wi-Fi links (architecture §13). */
class IpAddressesTest {
    @Test
    fun parsesIpv4DottedQuads() {
        assertEquals("192.168.49.1", IpLiteral.parse("192.168.49.1")?.hostAddress)
        assertEquals("0.0.0.0", IpLiteral.parse("0.0.0.0")?.hostAddress)
        assertIs<Inet4Address>(IpLiteral.parse(" 10.0.0.7 "))
    }

    @Test
    fun rejectsEverythingThatIsNotAnIpLiteral() {
        val bad =
            listOf(
                "",
                " ",
                "256.1.1.1",
                "1.2.3",
                "1.2.3.4.5",
                "1234.1.1.1",
                "a.b.c.d",
                "1.2.3.-4",
                "example.com",
                "drop.local",
                "localhost",
                "[]",
                "fe80::1%",
                "fe80::1%bad name",
                ":::::::::",
            )
        for (text in bad) assertNull(IpLiteral.parse(text), text)
    }

    @Test
    fun parsesIpv6LiteralsWithBracketsAndNumericScopes() {
        assertIs<Inet6Address>(IpLiteral.parse("fe80::1"))
        assertIs<Inet6Address>(IpLiteral.parse("[2001:db8::1]"))
        assertIs<Inet6Address>(IpLiteral.parse("::1"))
        assertIs<Inet6Address>(IpLiteral.parse("fe80::1%1"))
        // An unknown interface scope is no address, and it never throws.
        assertNull(IpLiteral.parse("fe80::1%nosuchif0"))
    }

    @Test
    fun comparesIpv4WithItsMappedIpv6Form() {
        assertTrue(IpLiteral.sameAddress(ip("192.168.49.1"), ip("::ffff:192.168.49.1")))
        assertFalse(IpLiteral.sameAddress(ip("192.168.49.1"), ip("192.168.49.2")))
    }

    @Test
    fun privateMeansOnlyLocalNetworks() {
        for (text in listOf(
            "10.1.2.3",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.0.1",
            "169.254.1.1",
            "fe80::1",
            "fd12:3456::1",
            "fc00::1",
        )) {
            assertTrue(IpLiteral.isPrivate(ip(text)), text)
        }
        for (text in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.64.0.1", "127.0.0.1", "2001:db8::1", "::1")) {
            assertFalse(IpLiteral.isPrivate(ip(text)), text)
        }
    }

    @Test
    fun prefixesContainTheirSubnetOnly() {
        val p24 = IpPrefix(ip("192.168.49.1"), 24)
        assertTrue(p24.contains(ip("192.168.49.200")))
        assertFalse(p24.contains(ip("192.168.50.1")))
        assertTrue(p24.contains(ip("::ffff:192.168.49.9")))
        assertFalse(p24.contains(ip("fe80::1")), "another family")

        val p25 = IpPrefix(ip("10.0.0.1"), 25)
        assertTrue(p25.contains(ip("10.0.0.127")))
        assertFalse(p25.contains(ip("10.0.0.128")))

        assertTrue(IpPrefix(ip("1.2.3.4"), 0).contains(ip("200.1.1.1")))
        assertTrue(IpPrefix(ip("1.2.3.4"), 32).contains(ip("1.2.3.4")))
        assertFalse(IpPrefix(ip("1.2.3.4"), 32).contains(ip("1.2.3.5")))

        val v6 = IpPrefix(ip("fd00:1:2:3::10"), 64)
        assertTrue(v6.contains(ip("fd00:1:2:3::99")))
        assertFalse(v6.contains(ip("fd00:1:2:4::99")))
        assertEquals("192.168.49.1/24", p24.toString())
    }

    @Test
    fun prefixLengthsOutsideTheFamilyAreRefused() {
        assertFailsWith<IllegalArgumentException> { IpPrefix(ip("10.0.0.1"), 33) }
        assertFailsWith<IllegalArgumentException> { IpPrefix(ip("fe80::1"), 129) }
        assertNull(IpPrefix.of(ip("10.0.0.1"), -1))
        assertEquals(IpPrefix(ip("10.0.0.1"), 8), IpPrefix.of(ip("10.0.0.1"), 8))
    }

    @Test
    fun aKnownPeerIsTheOnlyAddressAClientDials() {
        val policy = LinkDialPolicy(listOf(IpPrefix(ip("192.168.49.23"), 24)), expected = ip("192.168.49.1"))
        assertEquals(ip("192.168.49.1"), policy.check("192.168.49.1"))
        assertEquals(ip("192.168.49.1"), policy.check("::ffff:192.168.49.1"))
        assertFailsWith<LinkAddressRefusedException> { policy.check("192.168.49.7") }
    }

    @Test
    fun withoutAPeerOnlyTheLinksOwnPrefixesAreDialled() {
        val policy = LinkDialPolicy(listOf(IpPrefix(ip("192.168.43.17"), 24)))
        assertEquals(ip("192.168.43.1"), policy.check("192.168.43.1"))
        assertFailsWith<LinkAddressRefusedException> { policy.check("192.168.1.1") }
        assertFailsWith<LinkAddressRefusedException> { policy.check("8.8.8.8") }
    }

    @Test
    fun withNothingKnownOnlyPrivateAddressesPass() {
        val policy = LinkDialPolicy()
        assertEquals(ip("10.0.0.2"), policy.check("10.0.0.2"))
        assertFailsWith<LinkAddressRefusedException> { policy.check("1.1.1.1") }
        assertFailsWith<LinkAddressRefusedException> { policy.check("127.0.0.1") }
    }

    @Test
    fun namesWildcardsAndMulticastAreNeverDialled() {
        val policy = LinkDialPolicy(listOf(IpPrefix(ip("0.0.0.0"), 0)))
        for (text in listOf("example.com", "0.0.0.0", "224.0.0.251", "ff02::fb", "::")) {
            assertFailsWith<LinkAddressRefusedException>(text) { policy.check(text) }
        }
    }

    @Test
    fun loopbackIsDialledOnlyOnALoopbackLink() {
        assertFailsWith<LinkAddressRefusedException> { LinkDialPolicy(listOf(IpPrefix(ip("192.168.1.5"), 24))).check("127.0.0.1") }
        assertEquals(LOOPBACK, LinkDialPolicy(listOf(IpPrefix(LOOPBACK, 8))).check("127.0.0.1"))
        assertEquals(LOOPBACK, LinkDialPolicy(expected = LOOPBACK).check("127.0.0.1"))
    }
}
