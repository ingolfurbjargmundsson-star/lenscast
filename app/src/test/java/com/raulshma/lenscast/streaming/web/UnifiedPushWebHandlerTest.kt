package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.push.PushEncryption
import com.raulshma.lenscast.core.push.PushSubscriptionPolicy
import com.raulshma.lenscast.core.push.PushSubscriptionStore
import com.raulshma.lenscast.core.push.PushTargetKind
import com.raulshma.lenscast.core.push.VapidKeys
import com.raulshma.lenscast.streaming.model.UnifiedPushRegisterResponseDto
import com.raulshma.lenscast.streaming.model.UnifiedPushRemoveResponseDto
import com.raulshma.lenscast.streaming.model.UnifiedPushTargetsResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The UnifiedPush receiver API: registration/rotation/removal, the 16-target
 * cap, redacted listings, and the token-facing idempotent remove contract.
 * The store and VAPID identity are real — this is the handler seam the router
 * and the receiver integration docs both rely on.
 */
class UnifiedPushWebHandlerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var vapidKeys: VapidKeys

    private val p256dh = PushEncryption.base64Url(byteArrayOf(0x04) + ByteArray(64) { 1 })
    private val auth = PushEncryption.base64Url(ByteArray(16) { 2 })

    @Before
    fun setUp() {
        vapidKeys = VapidKeys.loadOrCreate(File(tmp.root, "vapid.key"))
    }

    private fun store() = PushSubscriptionStore(File(tmp.root, "push.json"))

    private fun handler(store: PushSubscriptionStore) = UnifiedPushWebHandler(
        subscriptions = store,
        vapidKeys = vapidKeys,
        nowMs = { 42L },
    )

    private fun request(
        endpoint: String = "https://up.example.com/send/a",
        p256dh: String = this.p256dh,
        auth: String = this.auth,
        instance: String? = null,
        label: String? = "Phone",
        distributor: String? = "org.unifiedpush.distributor.nextpush",
        replacedEndpoint: String? = null,
    ): String = AppJson.moshi.adapter(UnifiedPushWebHandler.RegisterRequest::class.java).toJson(
        UnifiedPushWebHandler.RegisterRequest(
            endpoint = endpoint,
            p256dh = p256dh,
            auth = auth,
            instance = instance,
            label = label,
            distributor = distributor,
            replacedEndpoint = replacedEndpoint,
        ),
    )

    private fun registerResponse(json: String) =
        AppJson.moshi.adapter(UnifiedPushRegisterResponseDto::class.java).fromJson(json)!!

    private fun removeResponse(json: String) =
        AppJson.moshi.adapter(UnifiedPushRemoveResponseDto::class.java).fromJson(json)!!

    private fun listResponse(json: String) =
        AppJson.moshi.adapter(UnifiedPushTargetsResponseDto::class.java).fromJson(json)!!

    @Test
    fun `register lists a redacted target plus the VAPID key`() {
        val subscriptions = store()
        val handler = handler(subscriptions)

        val registered = registerResponse(handler.register(request(instance = "default")))
        assertTrue(registered.success)
        assertEquals(1, registered.count)

        val json = handler.list()
        val list = listResponse(json)
        assertEquals(1, list.count)
        assertEquals(PushSubscriptionPolicy.MAX_UNIFIEDPUSH_TARGETS, list.maxTargets)
        assertEquals(vapidKeys.publicKeyBase64Url(), list.vapidPublicKey)
        val target = list.targets.single()
        assertEquals("https://up.example.com/send/a", target.endpoint)
        assertEquals("default", target.instance)
        assertEquals("Phone", target.label)
        assertEquals("org.unifiedpush.distributor.nextpush", target.distributor)
        assertEquals(42L, target.createdAtMs)
        // The encryption material never leaves the device.
        assertFalse(json.contains(p256dh))
        assertFalse(json.contains(auth))
    }

    @Test
    fun `registration is endpoint-keyed and updates metadata in place`() {
        val subscriptions = store()
        val handler = handler(subscriptions)

        assertTrue(registerResponse(handler.register(request(label = "Old"))).success)
        val rotated = PushEncryption.base64Url(byteArrayOf(0x04) + ByteArray(64) { 9 })
        val updated = registerResponse(
            handler.register(request(p256dh = rotated, label = "New")),
        )

        assertTrue(updated.success)
        assertEquals(1, updated.count)
        assertEquals(1, subscriptions.count(PushTargetKind.UNIFIEDPUSH))
        assertEquals(rotated, subscriptions.all().single().p256dh)
        assertEquals("New", subscriptions.all().single().label)
    }

    @Test
    fun `replacedEndpoint rotates a receiver target atomically`() {
        val subscriptions = store()
        val handler = handler(subscriptions)

        handler.register(request(endpoint = "https://up.example.com/send/old"))
        val rotated = registerResponse(
            handler.register(
                request(
                    endpoint = "https://up.example.com/send/new",
                    replacedEndpoint = "https://up.example.com/send/old",
                ),
            ),
        )

        assertTrue(rotated.success)
        assertEquals(1, rotated.count)
        assertEquals(
            "https://up.example.com/send/new",
            subscriptions.all().single { it.targetKind == PushTargetKind.UNIFIEDPUSH }.endpoint,
        )
    }

    @Test
    fun `the seventeenth receiver is rejected with a readable reason`() {
        val subscriptions = store()
        val handler = handler(subscriptions)
        repeat(PushSubscriptionPolicy.MAX_UNIFIEDPUSH_TARGETS) { index ->
            assertTrue(
                registerResponse(
                    handler.register(request(endpoint = "https://up.example.com/send/$index")),
                ).success,
            )
        }

        val overflow = registerResponse(
            handler.register(request(endpoint = "https://up.example.com/send/overflow")),
        )

        assertFalse(overflow.success)
        assertTrue(overflow.error.orEmpty().contains("limit"))
        assertEquals(PushSubscriptionPolicy.MAX_UNIFIEDPUSH_TARGETS, overflow.count)
    }

    @Test
    fun `invalid endpoints keys and JSON fail closed`() {
        val handler = handler(store())

        assertFalse(registerResponse(handler.register(request(endpoint = "http://up.example.com/x"))).success)
        assertFalse(registerResponse(handler.register(request(p256dh = "%%%"))).success)
        assertFalse(registerResponse(handler.register("{")).success)
    }

    @Test
    fun `a browser endpoint cannot be converted into a receiver target`() {
        val subscriptions = store()
        val handler = handler(subscriptions)
        val browser = "https://push.example.com/browser"
        subscriptions.add(browser, p256dh, auth, emptyList(), 1)

        val response = registerResponse(handler.register(request(endpoint = browser)))

        assertFalse(response.success)
        assertTrue(response.error.orEmpty().contains("browser"))
        assertEquals(PushTargetKind.BROWSER, subscriptions.all().single().targetKind)
        assertEquals(0, subscriptions.count(PushTargetKind.UNIFIEDPUSH))
    }

    @Test
    fun `remove is UnifiedPush-scoped and idempotent`() {
        val subscriptions = store()
        val handler = handler(subscriptions)
        subscriptions.add("https://push.example.com/browser", p256dh, auth, emptyList(), 1)
        handler.register(request())

        val removed = removeResponse(
            handler.remove("""{"endpoint":"https://up.example.com/send/a"}"""),
        )
        assertTrue(removed.success)
        assertTrue(removed.removed)
        assertEquals(1, subscriptions.count())
        assertEquals(1, subscriptions.count(PushTargetKind.BROWSER))

        // A retry succeeds without claiming a removal; the browser row is
        // untouched by the token-writable route.
        val retry = removeResponse(
            handler.remove("""{"endpoint":"https://up.example.com/send/a"}"""),
        )
        assertTrue(retry.success)
        assertFalse(retry.removed)
        assertEquals(1, subscriptions.count(PushTargetKind.BROWSER))
    }

    @Test
    fun `remove rejects a malformed body instead of guessing`() {
        val handler = handler(store())
        assertFalse(removeResponse(handler.remove("{")).success)
        assertFalse(removeResponse(handler.remove("""{"endpoint":""}""")).success)
    }

    @Test
    fun `the browser routes never list or delete UnifiedPush targets`() {
        val subscriptions = store()
        val unifiedPush = handler(subscriptions)
        unifiedPush.register(request())
        subscriptions.add("https://push.example.com/browser", p256dh, auth, emptyList(), 1)
        val browser = PushWebHandler(subscriptions, vapidKeys, nowMs = { 1L })

        val list = AppJson.moshi.adapter(com.raulshma.lenscast.streaming.model.PushSubscriptionsResponseDto::class.java)
            .fromJson(browser.list())!!
        assertEquals(1, list.count)
        assertEquals("https://push.example.com/browser", list.subscriptions.single().endpoint)

        // The session-only browser route is kind-scoped: it cannot retire a
        // receiver endpoint that a token paired.
        assertFalse(
            AppJson.moshi.adapter(com.raulshma.lenscast.streaming.model.SuccessResponse::class.java)
                .fromJson(browser.unsubscribe("https://up.example.com/send/a"))!!
                .success,
        )
        assertEquals(1, subscriptions.count(PushTargetKind.UNIFIEDPUSH))
    }
}
