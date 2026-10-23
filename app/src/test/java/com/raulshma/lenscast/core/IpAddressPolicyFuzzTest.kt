package com.raulshma.lenscast.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [IpAddressPolicy] under hostile input: the device's address list is
 * external data (interfaces, VPNs, aliases), so the parser must answer
 * bytes/null for every prefix, truncation and seeded garbage — never throw,
 * never index past the 4/16-byte buffer.
 *
 * ── The contract ──
 * [IpAddressPolicy.toBytes] returns 4 or 16 bytes or null;
 * [IpAddressPolicy.canonical] returns a non-null string whenever the bytes
 * parse (the identity check relies on both sides of the comparison being
 * available).
 */
class IpAddressPolicyFuzzTest {

    @Test(timeout = 10_000)
    fun `corpus - malformed literals answer null, never a crash`() {
        val malformed = listOf(
            "", " ", "%", "%wlan0", ".", "..", "1.2.3", "1.2.3.4.5",
            "256.1.1.1", "-1.2.3.4", "+1.2.3.4", "1.2.3.4.5.6",
            ":", ":::", "1::2::3", "1:2:3:4:5:6:7",
            "1:2:3:4:5:6:7:8:9", "::ffff:192.168.1.256",
            "::ffff:1.2.3.4.5", "::ffff:1.2.3.4:", ":1.2.3.4",
            "12345::", "::12345", "g::1", "1:2:3:4:5:6:7:g",
        )
        for (value in malformed) {
            assertNull(value, IpAddressPolicy.toBytes(value))
            assertNull(value, IpAddressPolicy.canonical(value))
        }
    }

    @Test(timeout = 10_000)
    fun `valid literals always canonicalize when they encode`() {
        val valid = listOf(
            "127.0.0.1", "0.0.0.0", "255.255.255.255", "::", "::1",
            "fe80::1%wlan0", "2001:db8::a", "::ffff:192.168.1.19",
            "1:2:3:4:5:6:7:8",
        )
        for (value in valid) {
            val bytes = IpAddressPolicy.toBytes(value)
            assertTrue("$value must encode", bytes != null)
            assertTrue("$value must be 4 or 16 bytes", bytes!!.size == 4 || bytes.size == 16)
            assertNotNull("$value must canonicalize", IpAddressPolicy.canonical(value))
        }
    }

    @Test(timeout = 10_000)
    fun `every truncation of a valid IPv6 literal answers without throwing`() {
        val literal = "2001:db8::1"
        for (length in 0..literal.length) {
            val value = literal.substring(0, length)
            IpAddressPolicy.toBytes(value)
            IpAddressPolicy.canonical(value)
        }
    }

    @Test(timeout = 10_000)
    fun `seeded garbage over the address alphabet never throws or over-reads`() {
        val random = Random(0x5EED)
        val alphabet = "0123456789abcdefABCDEF:%.xyz -+"
        repeat(5_000) {
            val value = buildString {
                repeat(random.nextInt(24)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val bytes = IpAddressPolicy.toBytes(value)
            if (bytes != null) {
                assertTrue("$value encoded to ${bytes.size} bytes", bytes.size == 4 || bytes.size == 16)
            }
            IpAddressPolicy.canonical(value)
        }
    }
}
