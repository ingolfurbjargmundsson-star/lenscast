package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.push.PushEncryption
import com.raulshma.lenscast.core.push.PushSubscriptionStore
import com.raulshma.lenscast.core.push.VapidKeys
import com.raulshma.lenscast.streaming.model.UnifiedPushRegisterResponseDto
import com.raulshma.lenscast.streaming.model.UnifiedPushRemoveResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * The UnifiedPush receiver body parsers under hostile input: a deterministic
 * corpus fuzzer over [UnifiedPushWebHandler.register] and
 * [UnifiedPushWebHandler.remove].
 *
 * ── The contract ──
 * Both methods answer a parseable response envelope for every body — malformed
 * JSON, wrong-typed fields, 100k-deep containers, 1 MB strings, duplicate
 * keys, lone surrogates, and seeded garbage all fail closed with
 * `success:false` (or a clean no-op remove), never an exception leaking out of
 * the handler seam.
 */
class UnifiedPushWebHandlerFuzzTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var handler: UnifiedPushWebHandler

    private val p256dh = PushEncryption.base64Url(byteArrayOf(0x04) + ByteArray(64) { 1 })
    private val auth = PushEncryption.base64Url(ByteArray(16) { 2 })

    @Before
    fun setUp() {
        handler = UnifiedPushWebHandler(
            subscriptions = PushSubscriptionStore(File(tmp.root, "push.json")),
            vapidKeys = VapidKeys.loadOrCreate(File(tmp.root, "vapid.key")),
            nowMs = { 42L },
        )
    }

    private fun registerResponse(json: String) =
        AppJson.moshi.adapter(UnifiedPushRegisterResponseDto::class.java).fromJson(json)!!

    private fun removeResponse(json: String) =
        AppJson.moshi.adapter(UnifiedPushRemoveResponseDto::class.java).fromJson(json)!!

    private fun validRegisterBody() =
        """{"endpoint":"https://up.example.com/send/a","p256dh":"$p256dh","auth":"$auth"}"""

    @Test(timeout = 10_000)
    fun `a valid register is the positive control`() {
        val response = registerResponse(handler.register(validRegisterBody()))
        assertTrue(response.success)
        assertEquals(1, response.count)
    }

    @Test(timeout = 10_000)
    fun `corpus - 100k-deep nesting is rejected without leaking`() {
        val deepArrays = "[".repeat(100_000) + "]".repeat(100_000)
        val deepObjects = buildString {
            repeat(100_000) { append("""{"a":""") }
            append("1")
            repeat(100_000) { append("}") }
        }
        for (body in listOf(deepArrays, deepObjects, """{"endpoint":$deepArrays}""")) {
            assertFalse(registerResponse(handler.register(body)).success)
            assertFalse(removeResponse(handler.remove(body)).removed)
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - huge strings and wrong-typed fields stay in the response contract`() {
        val huge = "a".repeat(1_000_000)
        val hostile = listOf(
            """{"endpoint":"$huge"}""",
            """{"endpoint":"https://up.example.com/send/${"a".repeat(5000)}"}""",
            """{"endpoint":42,"p256dh":true,"auth":[],"instance":{}}""",
            """{"endpoint":null,"p256dh":null,"auth":null}""",
            """{"endpoint":"https://up.example.com/send/a","endpoint":null}""",
            """{"endpoint":"https://up.example.com/send/${"\uD800"}"}""",
            "null", "true", "42", "\"a string\"", "[]", "{}", "", "not json",
        )
        for (body in hostile) {
            val registered = registerResponse(handler.register(body))
            assertFalse(registered.success)
            assertTrue(registered.count >= 0)
            // A parsed non-blank endpoint is a clean no-op remove (the store
            // is empty); malformed body shapes fail closed. Neither may throw
            // past the seam.
            assertFalse(removeResponse(handler.remove(body)).removed)
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - seeded garbage never breaks either response envelope`() {
        val random = Random(0x5E77196)
        val alphabet = """{}[]",:0123456789.eE+-truefalsn\/ """
        repeat(500) {
            val body = buildString {
                repeat(random.nextInt(128)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val registered = registerResponse(handler.register(body))
            assertFalse(registered.success)
            assertTrue(registered.count >= 0)
            // The remove route answers a clean no-op for anything that is not
            // a registered endpoint, and it can never throw past the seam.
            assertFalse(removeResponse(handler.remove(body)).removed)
        }
    }
}
