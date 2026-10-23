package com.raulshma.lenscast.core.push

import android.util.Log
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.DetectionAlert
import com.raulshma.lenscast.core.EventKind
import com.squareup.moshi.JsonClass
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The Web Push half of the detection-event dispatch, beside
 * [com.raulshma.lenscast.core.WebhookNotifier] and the MQTT publisher: every
 * subscribed browser and every registered UnifiedPush receiver gets one
 * RFC 8291-encrypted JSON event payload, authenticated per RFC 8292 (VAPID).
 * The browser's service worker (the dashboard's `/push-sw.js`) or the
 * receiver's distributor shows the notification even with the app closed —
 * the phone is the publisher; the push service (FCM/Mozilla autopush/a
 * self-hosted UnifiedPush distributor) is free infrastructure and no account.
 *
 * UnifiedPush is wire-identical to browser Web Push: a distributor endpoint
 * is an RFC 8030 capability URL and the receiver's key material is the same
 * `p256dh`/`auth` pair. The two populations only differ in their admission
 * route and their settings gate ([Config.browserEnabled] vs
 * [Config.unifiedPushEnabled]), so one sender, one encrypted fan-out, and one
 * event-log `push` action cover both.
 *
 * The webhook's contracts carry over: one daemon worker serializes dispatches
 * (never blocking the detection path), the go/no-go verdict ([willDispatch],
 * claimed by the caller into the event log) is decided at dispatch time, and
 * every failure is logged, never thrown — fail-open like every other sink.
 * A 404/410 answer means the subscription expired or the browser/receiver
 * unregistered: the endpoint is pruned from the store so later events stop
 * paying for it (key-material-matched, so a rotation racing the response is
 * never deleted). One ephemeral ECDH pair is generated per fan-out, the VAPID
 * JWT is cached per origin, and three consecutive transport failures abort
 * the remaining fan-out — the network is down, not the subscribers.
 *
 * The payload is capped at [MAX_PLAINTEXT_BYTES] before encryption (one
 * aes128gcm record), the RFC 8030 `Topic` header coalesces replacement
 * notifications at the push service, and log lines carry origins, never
 * endpoint capability URLs.
 */
class WebPushSender(
    private val configProvider: () -> Config,
    private val store: PushSubscriptionStore,
    private val vapidKeys: VapidKeys,
    private val transport: Transport = HttpsTransport(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * The live settings snapshot read per dispatch. The two populations have
     * independent gates: turning browser Web Push off must not silence a
     * paired UnifiedPush receiver, and vice versa.
     */
    data class Config(
        val browserEnabled: Boolean,
        val unifiedPushEnabled: Boolean,
        val subject: String,
    )

    /**
     * The one POST per target. Returns the status code, or [NO_RESPONSE] on
     * any IO failure; implementors must not throw. HTTP-level only — this
     * seam is what the JVM tests fake.
     */
    fun interface Transport {
        fun post(endpoint: String, headers: Map<String, String>, body: ByteArray): Int
    }

    /**
     * The default transport: HttpsURLConnection with tight timeouts, the
     * webhook notifier's failure cost (one log line), and no retry ladder —
     * push services are reliable, the next event retries for free. The log
     * line names the endpoint's origin, never its capability URL.
     */
    class HttpsTransport(private val timeoutMs: Int = TIMEOUT_MS) : Transport {
        override fun post(endpoint: String, headers: Map<String, String>, body: ByteArray): Int = try {
            val connection = (java.net.URI(endpoint).toURL().openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                connection.outputStream.use { it.write(body) }
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Push POST to ${originForLog(endpoint)} failed: ${failureLabel(e)}")
            NO_RESPONSE
        }

        companion object {
            private const val TIMEOUT_MS = 5_000
        }
    }

    private data class Dispatch(
        val plaintext: ByteArray,
        val config: Config,
        val topic: String,
        val urgency: String,
    )

    private data class VapidCacheKey(val audience: String, val subject: String)

    private data class CachedVapid(val authorization: String, val refreshAfterMs: Long)

    private enum class Outcome { DELIVERED, GONE, REJECTED, TRANSPORT_FAILURE }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WebPushSender").apply { isDaemon = true }
    }

    /**
     * The one-slot coalescing queue: a burst of detection events replaces the
     * pending payload instead of stacking, exactly the notification-tag
     * semantics the service worker applies. The in-flight payload always
     * completes; the newest queued event then dispatches.
     */
    private val pending = AtomicReference<Dispatch?>(null)
    private val draining = AtomicBoolean(false)

    /** VAPID tokens are expensive and valid for 12 h; one per origin + subject. */
    private val vapidCache = ConcurrentHashMap<VapidCacheKey, CachedVapid>()

    /**
     * Queue a fan-out to every enabled stored target. True when the event was
     * accepted for dispatch — the caller claims "push" in the event log at
     * dispatch time (the webhook's exact contract); false when both gates are
     * off, nothing relevant is subscribed, or the payload exceeds the
     * plaintext cap.
     * Acceptance is not a per-event delivery guarantee: while a fan-out runs,
     * a newer event replaces the pending one (notification-tag semantics), and
     * a transport failure still ends in a logged, fail-open dispatch. [deepLink]
     * is the dashboard deep link the notification's click opens (the payload's
     * `url` field; the service worker reads it defensively, so null keeps its
     * own fallback).
     */
    fun notifyEvent(
        alert: DetectionAlert,
        eventId: String?,
        clipAvailable: Boolean,
        deepLink: String? = null,
    ): Boolean {
        val config = configProvider()
        val counts = store.counts()
        if (!willDispatch(config, counts)) return false
        val payload = fitPayload(buildPayload(alert, eventId, clipAvailable, deepLink))
        val plaintext = payloadAdapter.toJson(payload).toByteArray(Charsets.UTF_8)
        if (plaintext.size > MAX_PLAINTEXT_BYTES) {
            // fitPayload trims the body; this only guards an unforeseen field
            // (a pathological event id) from turning into a crypto failure.
            Log.w(TAG, "Dropping push payload ${plaintext.size} B over the $MAX_PLAINTEXT_BYTES B cap")
            return false
        }
        pending.set(
            Dispatch(
                plaintext = plaintext,
                config = config,
                topic = payload.tag,
                urgency = urgencyFor(payload.type),
            ),
        )
        scheduleDrain()
        return true
    }

    private fun scheduleDrain() {
        if (!draining.compareAndSet(false, true)) return
        executor.execute { drain() }
    }

    private fun drain() {
        try {
            while (true) {
                val dispatch = pending.getAndSet(null) ?: break
                try {
                    dispatchAll(dispatch)
                } catch (e: Exception) {
                    // Fail-open: no failure may reach the executor as a crash.
                    Log.w(TAG, "Push fan-out failed: ${failureLabel(e)}")
                }
            }
        } finally {
            draining.set(false)
            // Close the enqueue-between-drain-and-reset race: if a payload
            // landed while this drain was finishing, run once more.
            if (pending.get() != null && draining.compareAndSet(false, true)) {
                executor.execute { drain() }
            }
        }
    }

    private fun dispatchAll(dispatch: Dispatch) {
        val sender = ephemeralKeyPair()
        var consecutiveTransportFailures = 0
        for (subscription in store.all()) {
            if (!isEnabled(subscription.targetKind, dispatch.config)) continue
            when (dispatchOne(subscription, dispatch, sender)) {
                Outcome.TRANSPORT_FAILURE -> {
                    consecutiveTransportFailures++
                    if (consecutiveTransportFailures >= MAX_CONSECUTIVE_TRANSPORT_FAILURES) {
                        Log.w(
                            TAG,
                            "Aborting push fan-out after $consecutiveTransportFailures consecutive transport failures",
                        )
                        return
                    }
                }
                else -> consecutiveTransportFailures = 0
            }
        }
    }

    private fun dispatchOne(
        subscription: StoredPushSubscription,
        dispatch: Dispatch,
        sender: KeyPair,
    ): Outcome {
        val origin = originForLog(subscription.endpoint)
        val receiver = PushEncryption.receiverKeyOrNull(subscription.p256dh, subscription.auth)
        if (receiver == null) {
            Log.w(TAG, "Dropping target with malformed key material at $origin")
            store.removeIfSame(subscription.endpoint, subscription.p256dh, subscription.auth)
            return Outcome.GONE
        }

        val body = try {
            PushEncryption.encrypt(
                payload = dispatch.plaintext,
                receiver = receiver,
                sender = sender,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Push encryption failed for $origin: ${failureLabel(e)}")
            return Outcome.REJECTED
        }

        val headers = try {
            mapOf(
                "Content-Encoding" to PushEncryption.CONTENT_ENCODING,
                "TTL" to TTL_SECONDS.toString(),
                "Urgency" to dispatch.urgency,
                "Topic" to dispatch.topic,
                "Authorization" to vapidAuthorization(subscription.endpoint, dispatch.config),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Push header build failed for $origin: ${failureLabel(e)}")
            return Outcome.REJECTED
        }

        val code = try {
            transport.post(subscription.endpoint, headers, body)
        } catch (e: Exception) {
            Log.w(TAG, "Push transport failed for $origin: ${failureLabel(e)}")
            return Outcome.TRANSPORT_FAILURE
        }

        return when (code) {
            NO_RESPONSE -> {
                Log.w(TAG, "Push POST to $origin got no response")
                Outcome.TRANSPORT_FAILURE
            }
            // 404/410: the subscription is gone (browser unsubscribe, receiver
            // unregistration, or push-service expiry). Key-matched prune so a
            // rotation that landed mid-dispatch survives.
            GONE, NOT_FOUND -> {
                Log.d(TAG, "Push endpoint gone ($code); pruning: $origin")
                store.removeIfSame(subscription.endpoint, subscription.p256dh, subscription.auth)
                Outcome.GONE
            }
            in 200..299 -> Outcome.DELIVERED
            else -> {
                Log.w(TAG, "Push POST answered HTTP $code from $origin")
                Outcome.REJECTED
            }
        }
    }

    private fun isEnabled(kind: PushTargetKind, config: Config): Boolean = when (kind) {
        PushTargetKind.BROWSER -> config.browserEnabled
        PushTargetKind.UNIFIEDPUSH -> config.unifiedPushEnabled
    }

    /**
     * The RFC 8292 header for one origin, cached until shortly before the
     * token's 12-hour `exp`. ES256 signatures are randomized, so a cache hit
     * also keeps every target behind the same push service on one JWT.
     */
    private fun vapidAuthorization(endpoint: String, config: Config): String {
        val audience = PushEncryption.audienceOf(endpoint)
        val key = VapidCacheKey(audience, config.subject)
        val now = nowMs()
        val cached = vapidCache[key]
        if (cached != null && now < cached.refreshAfterMs) return cached.authorization
        val authorization = PushEncryption.vapidAuthorization(
            endpoint = endpoint,
            keyPair = vapidKeys.keyPair(),
            subject = config.subject,
            nowMs = now,
        )
        // The cache is bounded by the target population in practice; clear it
        // on an implausible burst of origins rather than grow without limit.
        if (vapidCache.size > VAPID_CACHE_MAX) vapidCache.clear()
        vapidCache[key] = CachedVapid(
            authorization = authorization,
            refreshAfterMs = now + PushEncryption.VAPID_TOKEN_TTL_MS - VAPID_REFRESH_MARGIN_MS,
        )
        return authorization
    }

    /** A fresh ECDH P-256 pair per encrypted fan-out (RFC 8291's sender role). */
    private fun ephemeralKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return generator.generateKeyPair()
    }

    companion object {
        private const val TAG = "WebPushSender"
        private const val GONE = 410
        private const val NOT_FOUND = 404
        private const val NO_RESPONSE = -1
        private const val TTL_SECONDS = 3600
        private const val MAX_CONSECUTIVE_TRANSPORT_FAILURES = 3

        /**
         * RFC 8188's 4096-byte record leaves 4079 cleartext bytes after the
         * GCM tag and padding delimiter, but push services commonly cap the
         * whole body at 4096; the 86-byte aes128gcm header then leaves 3993.
         */
        const val MAX_PLAINTEXT_BYTES = 3993

        private const val VAPID_REFRESH_MARGIN_MS = 5L * 60 * 1000
        private const val VAPID_CACHE_MAX = 64

        private val payloadAdapter by lazy { AppJson.moshi.adapter(PushEventWire::class.java) }

        /**
         * The would-this-actually-dispatch decision behind [notifyEvent]: at
         * least one enabled transport with at least one target on that
         * transport. Kept pure and tested so the event log never claims an
         * action the sender no-ops.
         */
        fun willDispatch(config: Config?, counts: PushTargetCounts): Boolean =
            config != null &&
                ((config.browserEnabled && counts.browser > 0) ||
                    (config.unifiedPushEnabled && counts.unifiedPush > 0))

        /**
         * Caps the wire payload at [maxBytes] by shortening the body. The
         * other fields are fixed-size (title/tag/type/timestamp) and an event
         * id is a UUID, so the human summary is always the part that can grow
         * past one aes128gcm record when zones/labels are many.
         */
        fun fitPayload(payload: PushEventWire, maxBytes: Int = MAX_PLAINTEXT_BYTES): PushEventWire {
            fun encodedSize(candidate: PushEventWire): Int =
                payloadAdapter.toJson(candidate).toByteArray(Charsets.UTF_8).size
            if (encodedSize(payload) <= maxBytes) return payload
            var body = payload.body
            while (body.isNotEmpty()) {
                val candidate = payload.copy(body = body.trimEnd())
                val over = encodedSize(candidate) - maxBytes
                if (over <= 0) return candidate
                // UTF-8 bytes are >= chars, so dropping over+1 characters
                // converges without undershooting into the empty body.
                body = body.dropLast((over + 1).coerceAtMost(body.length))
            }
            return payload.copy(body = "")
        }

        /**
         * The wire payload the service worker renders: a notification title,
         * a one-line body summary, a replace-per-type tag, the event id (null
         * for the synthetic test alert), whether the event dispatched a
         * recording (the clip lands in the gallery moments later), and the
         * dashboard deep link the notification's click opens ([deepLink]).
         */
        fun buildPayload(
            alert: DetectionAlert,
            eventId: String?,
            clipAvailable: Boolean,
            deepLink: String? = null,
        ): PushEventWire {
            // The tag is the notification-replace key — stable lowercase wire
            // casing; the title/body capitalize for display only.
            val kindName = alert.kind.name.lowercase(Locale.US)
            val kindLabel = kindName.replaceFirstChar { it.titlecase(Locale.US) }
            val bodyParts = buildList {
                if (alert.zones.isNotEmpty()) add(alert.zones.joinToString(", "))
                if (alert.labels.isNotEmpty()) add(alert.labels.joinToString(", "))
                alert.batteryPercent?.let { add("battery $it%") }
            }
            return PushEventWire(
                title = "LensCast $kindLabel alert",
                body = bodyParts.joinToString(" · ").ifEmpty { "$kindLabel event detected" },
                tag = "lenscast-$kindName",
                eventId = eventId,
                type = alert.kind.wireName,
                timestampMs = alert.timestampMs,
                clipAvailable = clipAvailable,
                url = deepLink,
            )
        }

        /**
         * The RFC 8030 Urgency header: tamper (a camera losing power) and
         * motion page immediately; sound and the synthetic test ride the
         * normal tier. Pure and tested — the header is part of the wire
         * contract the tests pin.
         */
        fun urgencyFor(type: String): String = when (type) {
            EventKind.MOTION.wireName, EventKind.TAMPER.wireName -> "high"
            else -> "normal"
        }

        /** Origins only in logs — an endpoint is a deliver-to capability URL. */
        internal fun originForLog(endpoint: String): String =
            runCatching { PushEncryption.audienceOf(endpoint) }.getOrDefault("(invalid endpoint)")
    }
}

/**
 * Exception classes only: URL and TLS exception messages can embed the full
 * capability endpoint, so the message text never reaches the log.
 */
private fun failureLabel(e: Exception): String = e.javaClass.simpleName

/** The event JSON the browser's service worker receives (phone encrypts, browser decrypts). */
@JsonClass(generateAdapter = true)
data class PushEventWire(
    val title: String,
    val body: String,
    /** Notifications with the same tag replace each other instead of stacking. */
    val tag: String,
    /** The detection event's UUID; null for the synthetic test alert. */
    val eventId: String?,
    /** The event wire name (`motion`/`sound`/`tamper`/`test`). */
    val type: String,
    val timestampMs: Long,
    /** True when the event dispatched a bounded recording (a clip follows). */
    val clipAvailable: Boolean,
    /**
     * The dashboard deep link the notification click opens (`#/events` at
     * dispatch — the clip id only exists once the recording finalizes); the
     * worker falls back to its own derivation when absent.
     */
    val url: String? = null,
)
