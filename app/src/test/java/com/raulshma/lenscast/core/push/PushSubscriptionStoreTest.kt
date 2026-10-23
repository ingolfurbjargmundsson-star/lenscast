package com.raulshma.lenscast.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Web Push subscription store under JVM tests (file-seamed like
 * AuditLog): persistence across instances, endpoint dedupe (re-subscribe
 * updates in place), removal, and the admission verdicts that keep malformed
 * key material out of the store — a bad key would only ever surface as a
 * failed dispatch.
 */
class PushSubscriptionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A shape-valid 65-byte point and 16-byte auth secret. */
    private val p256dh = PushEncryption.base64Url(byteArrayOf(0x04) + ByteArray(64) { 1 })
    private val auth = PushEncryption.base64Url(ByteArray(16) { 2 })

    private fun store(): PushSubscriptionStore = PushSubscriptionStore(File(tmp.root, "push_subscriptions.json"))

    private fun endpoint(name: String) = "https://push.example.com/send/$name"

    // ── admission ──

    @Test
    fun `add accepts a valid https subscription`() {
        val subscriptions = store()
        assertTrue(subscriptions.add(endpoint("a"), p256dh, auth, labels = emptyList(), createdAtMs = 1))
        assertEquals(1, subscriptions.count())
        assertEquals(endpoint("a"), subscriptions.all().single().endpoint)
        assertEquals(p256dh, subscriptions.all().single().p256dh)
    }

    @Test
    fun `add rejects non-https endpoints and malformed key material`() {
        val subscriptions = store()
        assertFalse(subscriptions.add("http://push.example.com/send/a", p256dh, auth, emptyList(), 1))
        assertFalse(subscriptions.add(endpoint("a"), PushEncryption.base64Url(ByteArray(32)), auth, emptyList(), 1))
        assertFalse(subscriptions.add(endpoint("a"), p256dh, PushEncryption.base64Url(ByteArray(8)), emptyList(), 1))
        assertFalse(subscriptions.add("   ", p256dh, auth, emptyList(), 1))
        assertEquals(0, subscriptions.count())
    }

    // ── dedupe and removal ──

    @Test
    fun `re-subscribing the same endpoint updates in place instead of duplicating`() {
        val subscriptions = store()
        subscriptions.add(endpoint("a"), p256dh, auth, emptyList(), createdAtMs = 1)
        val rotated = PushEncryption.base64Url(byteArrayOf(0x04) + ByteArray(64) { 9 })

        assertTrue(subscriptions.add(endpoint("a"), rotated, auth, emptyList(), createdAtMs = 2))

        assertEquals(1, subscriptions.count())
        assertEquals(rotated, subscriptions.all().single().p256dh)
        assertEquals(2, subscriptions.all().single().createdAtMs)
    }

    @Test
    fun `remove drops the endpoint and reports absence honestly`() {
        val subscriptions = store()
        subscriptions.add(endpoint("a"), p256dh, auth, emptyList(), 1)
        subscriptions.add(endpoint("b"), p256dh, auth, emptyList(), 1)

        assertTrue(subscriptions.remove(endpoint("a"), PushTargetKind.BROWSER))
        assertFalse(subscriptions.remove(endpoint("a"), PushTargetKind.BROWSER))
        assertEquals(listOf(endpoint("b")), subscriptions.all().map { it.endpoint })
    }

    @Test
    fun `remove tolerates surrounding whitespace like add normalizes it`() {
        val subscriptions = store()
        subscriptions.add(endpoint("a"), p256dh, auth, emptyList(), 1)
        assertTrue(subscriptions.remove("  ${endpoint("a")} ", PushTargetKind.BROWSER))
    }

    // ── persistence ──

    @Test
    fun `the store survives a new instance over the same file`() {
        val file = File(tmp.root, "push_subscriptions.json")
        val first = PushSubscriptionStore(file)
        first.add(endpoint("a"), p256dh, auth, labels = listOf("dashboard"), createdAtMs = 42)

        val second = PushSubscriptionStore(file)
        val loaded = second.all().single()
        assertEquals(endpoint("a"), loaded.endpoint)
        assertEquals(p256dh, loaded.p256dh)
        assertEquals(auth, loaded.auth)
        assertEquals(42, loaded.createdAtMs)
        assertEquals(listOf("dashboard"), loaded.labels)
    }

    @Test
    fun `a corrupt store file starts clean instead of crashing`() {
        val file = File(tmp.root, "push_subscriptions.json")
        file.writeText("{\"endpoint\": half-written")

        val subscriptions = PushSubscriptionStore(file)

        assertEquals(0, subscriptions.count())
        // And a later add persists normally over the corrupt copy.
        assertTrue(subscriptions.add(endpoint("a"), p256dh, auth, emptyList(), 1))
        assertEquals(1, PushSubscriptionStore(file).count())
    }

    @Test
    fun `a blank endpoint row never survives the load filter`() {
        val file = File(tmp.root, "push_subscriptions.json")
        val good = endpoint("a")
        file.writeText(
            """[{"endpoint":"","p256dh":"x","auth":"y","createdAtMs":0},""" +
                """{"endpoint":"$good","p256dh":"$p256dh","auth":"$auth","createdAtMs":1}]""",
        )
        val subscriptions = PushSubscriptionStore(file)
        assertEquals(listOf(good), subscriptions.all().map { it.endpoint })
    }

    // ── UnifiedPush targets ──

    private fun upEndpoint(name: String) = "https://up.example.com/send/$name"

    private fun addUp(
        subscriptions: PushSubscriptionStore,
        name: String,
        instance: String = "default",
        replacedEndpoint: String? = null,
    ) = subscriptions.addUnifiedPush(
        endpoint = upEndpoint(name),
        p256dh = p256dh,
        auth = auth,
        instance = instance,
        label = "Phone $name",
        distributor = "org.unifiedpush.distributor.nextpush",
        replacedEndpoint = replacedEndpoint,
        createdAtMs = 1,
    )

    @Test
    fun `a pre-UnifiedPush row without a kind loads as a browser target`() {
        val file = File(tmp.root, "legacy.json")
        val good = endpoint("a")
        file.writeText(
            """[{"endpoint":"$good","p256dh":"$p256dh","auth":"$auth","createdAtMs":1}]""",
        )

        val loaded = PushSubscriptionStore(file).all().single()

        assertEquals(PushTargetKind.BROWSER, loaded.targetKind)
        assertEquals(null, loaded.instance)
    }

    @Test
    fun `UnifiedPush admission keeps metadata and keys the store by endpoint`() {
        val subscriptions = store()
        val first = addUp(subscriptions, "a", instance = "default")
        val second = addUp(subscriptions, "b", instance = "default")

        assertEquals(PushAddStatus.ADDED, first.status)
        assertEquals(PushAddStatus.ADDED, second.status)
        assertEquals(2, subscriptions.count(PushTargetKind.UNIFIEDPUSH))
        val loaded = subscriptions.all().first { it.endpoint == upEndpoint("a") }
        assertEquals("Phone a", loaded.label)
        assertEquals("default", loaded.instance)
        assertEquals("org.unifiedpush.distributor.nextpush", loaded.distributor)
    }

    @Test
    fun `UnifiedPush admission rejects bad endpoints and key material`() {
        val subscriptions = store()
        val http = subscriptions.addUnifiedPush(
            "http://up.example.com/send/a", p256dh, auth, "default", null, null, null, 1,
        )
        val badKey = subscriptions.addUnifiedPush(
            upEndpoint("a"), PushEncryption.base64Url(ByteArray(32)), auth, "default", null, null, null, 1,
        )
        assertEquals(PushAddStatus.INVALID, http.status)
        assertEquals(PushAddStatus.INVALID, badKey.status)
        assertEquals(0, subscriptions.count())
    }

    @Test
    fun `re-registering an endpoint updates it instead of consuming the cap`() {
        val subscriptions = store()
        addUp(subscriptions, "a")
        val rotated = PushEncryption.base64Url(byteArrayOf(0x04) + ByteArray(64) { 9 })
        val result = subscriptions.addUnifiedPush(
            upEndpoint("a"), rotated, auth, "default", "Renamed", null, null, 2,
        )

        assertEquals(PushAddStatus.UPDATED, result.status)
        assertEquals(1, subscriptions.count(PushTargetKind.UNIFIEDPUSH))
        assertEquals(rotated, subscriptions.all().single().p256dh)
        assertEquals("Renamed", subscriptions.all().single().label)
    }

    @Test
    fun `replacedEndpoint retires the rotated-away target atomically`() {
        val subscriptions = store()
        addUp(subscriptions, "old")
        val result = addUp(subscriptions, "new", replacedEndpoint = upEndpoint("old"))

        assertEquals(PushAddStatus.ADDED, result.status)
        assertEquals(listOf(upEndpoint("new")), subscriptions.all().map { it.endpoint })
    }

    @Test
    fun `replacedEndpoint never deletes a browser subscription`() {
        val subscriptions = store()
        subscriptions.add(endpoint("browser"), p256dh, auth, emptyList(), 1)
        addUp(subscriptions, "new", replacedEndpoint = endpoint("browser"))

        assertEquals(2, subscriptions.count())
        assertEquals(1, subscriptions.count(PushTargetKind.BROWSER))
        assertEquals(1, subscriptions.count(PushTargetKind.UNIFIEDPUSH))
    }

    @Test
    fun `the receiver population is capped at sixteen but rotation still works`() {
        val subscriptions = store()
        repeat(PushSubscriptionPolicy.MAX_UNIFIEDPUSH_TARGETS) { index ->
            assertEquals(PushAddStatus.ADDED, addUp(subscriptions, "r$index").status)
        }
        assertEquals(
            PushAddStatus.LIMIT_REACHED,
            addUp(subscriptions, "overflow").status,
        )
        // Updating an existing endpoint is always admitted.
        assertEquals(PushAddStatus.UPDATED, addUp(subscriptions, "r0").status)

        // A rotation removes one and adds one — the cap is not a dead end.
        val rotated = addUp(subscriptions, "rotated", replacedEndpoint = upEndpoint("r1"))
        assertEquals(PushAddStatus.ADDED, rotated.status)
        assertEquals(PushSubscriptionPolicy.MAX_UNIFIEDPUSH_TARGETS, rotated.count)
    }

    @Test
    fun `kind-scoped removal cannot cross the browser and receiver populations`() {
        val subscriptions = store()
        subscriptions.add(endpoint("browser"), p256dh, auth, emptyList(), 1)
        addUp(subscriptions, "receiver")

        assertFalse(subscriptions.remove(endpoint("browser"), PushTargetKind.UNIFIEDPUSH))
        assertFalse(subscriptions.remove(upEndpoint("receiver"), PushTargetKind.BROWSER))
        assertTrue(subscriptions.remove(upEndpoint("receiver"), PushTargetKind.UNIFIEDPUSH))
        assertEquals(1, subscriptions.count())
    }

    @Test
    fun `the sender prune keeps a subscription whose keys rotated mid-flight`() {
        val subscriptions = store()
        subscriptions.add(endpoint("a"), p256dh, auth, emptyList(), 1)
        val rotated = PushEncryption.base64Url(byteArrayOf(0x04) + ByteArray(64) { 9 })
        subscriptions.add(endpoint("a"), rotated, auth, emptyList(), 2)

        // The failed dispatch carried the old key material: the 404 must not
        // delete the browser's re-subscription.
        assertFalse(subscriptions.removeIfSame(endpoint("a"), p256dh, auth))
        assertEquals(rotated, subscriptions.all().single().p256dh)
        assertTrue(subscriptions.removeIfSame(endpoint("a"), rotated, auth))
        assertEquals(0, subscriptions.count())
    }

    // ── cross-population store rules ──

    @Test
    fun `an endpoint cannot cross the browser and receiver populations`() {
        val subscriptions = store()
        assertTrue(subscriptions.add(endpoint("shared"), p256dh, auth, emptyList(), 1))

        val converted = subscriptions.addUnifiedPush(
            endpoint("shared"), p256dh, auth, "default", null, null, null, 2,
        )
        assertEquals(PushAddStatus.CONFLICT, converted.status)
        assertEquals(PushTargetKind.BROWSER, subscriptions.all().single().targetKind)

        addUp(subscriptions, "shared")
        assertFalse(subscriptions.add(upEndpoint("shared"), p256dh, auth, emptyList(), 3))
        assertEquals(
            PushTargetKind.UNIFIEDPUSH,
            subscriptions.all().first { it.endpoint == upEndpoint("shared") }.targetKind,
        )
        assertEquals(2, subscriptions.count())
    }
}
