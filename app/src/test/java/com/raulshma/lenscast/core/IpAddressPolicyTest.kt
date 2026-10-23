package com.raulshma.lenscast.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The literal parsing behind the TLS identity: SAN encoding needs the address
 * bytes, the identity check needs a canonical spelling. Zones are link-scope
 * metadata and must never reach either form.
 */
class IpAddressPolicyTest {

    @Test
    fun `IPv4 literals encode to their four address bytes`() {
        assertArrayEquals(
            byteArrayOf(192.toByte(), 168.toByte(), 1, 19),
            IpAddressPolicy.toBytes("192.168.1.19"),
        )
    }

    @Test
    fun `IPv6 compressed, expanded and embedded-IPv4 forms encode to sixteen bytes`() {
        assertEquals(16, IpAddressPolicy.toBytes("2001:db8::a")?.size)
        assertEquals(
            16,
            IpAddressPolicy.toBytes("2001:0db8:0000:0000:0000:0000:0000:000a")?.size,
        )
        assertArrayEquals(
            IpAddressPolicy.toBytes("::ffff:192.168.1.19"),
            IpAddressPolicy.toBytes("::ffff:c0a8:113"),
        )
    }

    @Test
    fun `zone suffixes are stripped before encoding`() {
        assertArrayEquals(
            IpAddressPolicy.toBytes("fe80::1"),
            IpAddressPolicy.toBytes("fe80::1%wlan0"),
        )
    }

    @Test
    fun `canonical form collapses equivalent IPv6 spellings`() {
        assertEquals(
            IpAddressPolicy.canonical("2001:db8::a"),
            IpAddressPolicy.canonical("2001:0db8:0:0:0:0:0:A"),
        )
        assertEquals(
            IpAddressPolicy.canonical("fe80::1"),
            IpAddressPolicy.canonical("fe80::1%wlan0"),
        )
    }

    @Test
    fun `non-IP values have no canonical form`() {
        for (value in listOf("lenscast.local", "not-an-ip", "1.2.3", "::zz", "")) {
            assertNull(value, IpAddressPolicy.canonical(value))
            assertNull(value, IpAddressPolicy.toBytes(value))
        }
    }
}
