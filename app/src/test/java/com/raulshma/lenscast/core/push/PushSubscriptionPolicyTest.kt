package com.raulshma.lenscast.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push admission policy in isolation: endpoint shape, RFC 8291 key
 * material, and the UnifiedPush metadata normalizers. The store test covers
 * how these verdicts land in the file; these pin the pure boundaries.
 */
class PushSubscriptionPolicyTest {

    /** A shape-valid 65-byte point and 16-byte auth secret. */
    private val p256dh = PushEncryption.base64Url(byteArrayOf(0x04) + ByteArray(64) { 1 })
    private val auth = PushEncryption.base64Url(ByteArray(16) { 2 })

    private fun endpoint(name: String) = "https://push.example.com/send/$name"

    @Test
    fun `isSubscribable accepts a valid https endpoint and both key shapes`() {
        assertTrue(PushSubscriptionPolicy.isSubscribable(endpoint("a"), p256dh, auth))
        assertFalse(PushSubscriptionPolicy.isSubscribable("ftp://push.example.com", p256dh, auth))
        assertFalse(PushSubscriptionPolicy.isSubscribable(endpoint("a"), "%%%", auth))
    }

    @Test
    fun `endpoint length boundary is inclusive at 4096 and user info is rejected`() {
        val prefix = "https://push.example.com/"
        fun padded(length: Int) = prefix + "a".repeat(length - prefix.length)

        assertTrue(PushSubscriptionPolicy.isEndpoint(padded(4096)))
        assertFalse(PushSubscriptionPolicy.isEndpoint(padded(4097)))
        assertFalse(PushSubscriptionPolicy.isEndpoint("https://user:pass@push.example.com/send/a"))
        assertFalse(PushSubscriptionPolicy.isEndpoint("https://user@push.example.com/send/a"))
    }

    @Test
    fun `instance names fold blank to default and reject over-long values`() {
        assertEquals("default", PushSubscriptionPolicy.normalizeInstance(null))
        assertEquals("default", PushSubscriptionPolicy.normalizeInstance("  "))
        assertEquals("work", PushSubscriptionPolicy.normalizeInstance(" work "))
        assertEquals("x".repeat(64), PushSubscriptionPolicy.normalizeInstance("x".repeat(64)))
        assertEquals(null, PushSubscriptionPolicy.normalizeInstance("x".repeat(65)))
        assertEquals(null, PushSubscriptionPolicy.normalizeInstance("bad\u0000instance"))
    }

    @Test
    fun `label and distributor normalizers trim, drop blanks, and cap length`() {
        assertEquals("Pixel 8", PushSubscriptionPolicy.normalizeLabel(" Pixel 8 "))
        assertEquals(null, PushSubscriptionPolicy.normalizeLabel("  "))
        assertEquals(64, PushSubscriptionPolicy.normalizeLabel("x".repeat(65))?.length)

        assertEquals(
            "org.unifiedpush.distributor.nextpush",
            PushSubscriptionPolicy.normalizeDistributor(" org.unifiedpush.distributor.nextpush "),
        )
        assertEquals(null, PushSubscriptionPolicy.normalizeDistributor(" "))
        assertEquals(128, PushSubscriptionPolicy.normalizeDistributor("x".repeat(129))?.length)
    }
}
