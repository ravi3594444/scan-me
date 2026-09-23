package com.constrivo.drop.core.discovery

import com.constrivo.drop.core.discovery.Fixtures.bytes
import com.constrivo.drop.core.discovery.Fixtures.hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [IpAddress] (network hint input, N6) and [BluetoothAddress] (S10). */
class AddressTest {
    @Test
    fun ipv4ParsesStrictly() {
        assertEquals("c0a80101", hex(IpAddress.parse("192.168.1.1").toByteArray()))
        assertEquals("192.168.1.1", IpAddress.parse("192.168.1.1").toString())
        for (bad in listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "1.2.3.-4", "1..2.3", "a.b.c.d", "1.2.3.4 ")) {
            assertFailsWith<DiscoveryFormatException>(bad) { IpAddress.parse(bad) }
        }
    }

    @Test
    fun ipv6ParsesAllTextForms() {
        val expected = "20010db8000100020000000000000abc"
        for (form in listOf(
            "2001:db8:1:2::abc",
            "2001:0DB8:0001:0002:0000:0000:0000:0ABC",
            "2001:db8:1:2:0:0:0:abc",
            "2001:db8:1:2::abc%wlan0",
        )) {
            assertEquals(expected, hex(IpAddress.parse(form).toByteArray()), form)
        }
        assertEquals("00000000000000000000000000000000", hex(IpAddress.parse("::").toByteArray()))
        assertEquals("00000000000000000000000000000001", hex(IpAddress.parse("::1").toByteArray()))
        assertEquals("fe800000000000000000000000000001", hex(IpAddress.parse("fe80::1").toByteArray()))
        assertEquals("00010000000000000000000000000000", hex(IpAddress.parse("1::").toByteArray()))
        assertEquals("0064ff9b0000000000000000c0000201", hex(IpAddress.parse("64:ff9b::192.0.2.1").toByteArray()))
        assertEquals("2001:db8:1:2::abc", IpAddress.parse("2001:0db8:1:2:0:0:0:abc").toString())
        assertEquals("fe80::1", IpAddress.parse("fe80:0:0:0:0:0:0:1").toString())
        assertEquals("1:0:1:0:1:0:1:0", IpAddress.parse("1:0:1:0:1:0:1:0").toString())
    }

    @Test
    fun ipv4MappedBecomesIpv4() {
        assertEquals(IpAddress.parse("192.168.1.1"), IpAddress.parse("::ffff:192.168.1.1"))
        assertEquals(IpAddress.parse("192.168.1.1"), IpAddress.of(bytes("00000000000000000000ffffc0a80101")))
        assertTrue(IpAddress.parse("::ffff:c0a8:101").isIpv4)
    }

    @Test
    fun ipv6RejectsMalformedText() {
        val bad =
            listOf(
                ":",
                ":::",
                "1::2::3",
                ":1:2:3:4:5:6:7",
                "1:2:3:4:5:6:7:",
                "1:2:3:4:5:6:7:8:9",
                "1:2:3:4:5:6:7",
                "12345::",
                "g::1",
                "1.2.3.4::",
                "::1.2.3.4:5",
                "1:2:3:4:5:6:7::8",
                "%eth0",
                "::1%",
                "1:2:3:4:5:6:1.2.3",
                "::1.2.3.256",
            )
        for (text in bad) assertFailsWith<DiscoveryFormatException>(text) { IpAddress.parse(text) }
        assertFailsWith<IllegalArgumentException> { IpAddress.of(ByteArray(5)) }
    }

    @Test
    fun ipv6TextRoundTripsForRandomAddresses() {
        val random = Random(6)
        repeat(2_000) {
            val raw = ByteArray(16)
            // Sparse addresses exercise "::" compression.
            for (i in 0 until 8) {
                if (random.nextInt(3) != 0) continue
                raw[2 * i] = random.nextInt(256).toByte()
                raw[2 * i + 1] = random.nextInt(256).toByte()
            }
            val address = IpAddress.of(raw)
            assertEquals(address, IpAddress.parse(address.toString()), address.toString())
        }
    }

    @Test
    fun addressClassesAndOrdering() {
        assertTrue(IpAddress.parse("0.0.0.0").isUnspecified)
        assertTrue(IpAddress.parse("127.0.0.1").isLoopback)
        assertTrue(IpAddress.parse("::1").isLoopback)
        assertTrue(IpAddress.parse("224.0.0.251").isMulticast)
        assertTrue(IpAddress.parse("ff02::fb").isMulticast)
        assertTrue(IpAddress.parse("169.254.3.4").isLinkLocal)
        assertTrue(IpAddress.parse("fe80::1").isLinkLocal)
        assertFalse(IpAddress.parse("fec0::1").isLinkLocal)
        val sorted = listOf("fe80::1", "10.0.0.1", "192.168.1.1", "::2").map(IpAddress::parse).sorted().map { it.toString() }
        assertEquals(listOf("10.0.0.1", "192.168.1.1", "::2", "fe80::1"), sorted)
    }

    @Test
    fun s10_bluetoothAddressFormats() {
        val a = BluetoothAddress.parse("AA:BB:CC:DD:EE:FF")
        assertEquals(0xAABBCCDDEEFFL, a.value)
        assertEquals("AA:BB:CC:DD:EE:FF", a.toString())
        assertEquals("aabbccddeeff", hex(a.toByteArray()))
        assertEquals(a, BluetoothAddress.parse("aa-bb-cc-dd-ee-ff"))
        assertEquals(a, BluetoothAddress.fromBytes(bytes("aabbccddeeff")))
        for (bad in listOf(
            "AA:BB:CC:DD:EE",
            "AA:BB:CC:DD:EE:FG",
            "AA:BB-CC:DD:EE:FF",
            "AABBCCDDEEFF",
            "AA:BB:CC:DD:EE:FF:00",
            "A:BB:CC:DD:EE:FFF",
        )) {
            assertFailsWith<DiscoveryFormatException>(bad) { BluetoothAddress.parse(bad) }
        }
        assertFalse(BluetoothAddress.parse("02:00:00:00:00:00").isUsable)
        assertFalse(BluetoothAddress(0).isUsable)
        assertFalse(BluetoothAddress.parse("FF:FF:FF:FF:FF:FF").isUsable)
        assertTrue(a.isUsable)
    }
}
