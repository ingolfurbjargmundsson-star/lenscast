package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.push.PushAddStatus
import com.raulshma.lenscast.core.push.PushSubscriptionPolicy
import com.raulshma.lenscast.core.push.PushSubscriptionStore
import com.raulshma.lenscast.core.push.PushTargetKind
import com.raulshma.lenscast.core.push.StoredPushSubscription
import com.raulshma.lenscast.core.push.VapidKeys
import com.raulshma.lenscast.streaming.model.UnifiedPushRegisterResponseDto
import com.raulshma.lenscast.streaming.model.UnifiedPushRemoveResponseDto
import com.raulshma.lenscast.streaming.model.UnifiedPushTargetDto
import com.raulshma.lenscast.streaming.model.UnifiedPushTargetsResponseDto
import com.squareup.moshi.JsonClass

/**
 * The `/api/push/unifiedpush` route family — UnifiedPush receiver
 * registration:
 *
 * - `GET  /api/push/unifiedpush` → the registered targets plus the camera's
 *   VAPID public key (the `vapid` value `UnifiedPush.register` takes).
 * - `POST /api/push/unifiedpush` → register or update one distributor
 *   endpoint. The receiver supplies the same `p256dh`/`auth` pair as a
 *   browser; an optional `replacedEndpoint` atomically retires the endpoint
 *   the distributor just rotated away from.
 * - `POST /api/push/unifiedpush/remove` → remove one endpoint. POST rather
 *   than DELETE so the route can sit on [TokenWritePolicy]'s allow-list: a
 *   headless receiver has no session cookie, and `DELETE` is never
 *   token-writable in this API.
 *
 * Auth contract: GET rides the normal read gate (session or token). Both
 * POSTs are token-writable by design — pairing a receiver is exactly the
 * headless automation the API token exists for. The browser routes in
 * [PushWebHandler] stay session-cookie-only (token GET/HEAD included): a
 * browser subscription is the subscribing browser's own service-worker state,
 * not an automation surface.
 *
 * UnifiedPush is wire-identical to Web Push (RFC 8030 endpoint, RFC 8291
 * key material), so admission reuses [PushSubscriptionPolicy]. The
 * endpoint is the store key; two receivers may both use the instance name
 * `default`.
 */
class UnifiedPushWebHandler(
    private val subscriptions: PushSubscriptionStore,
    private val vapidKeys: VapidKeys,
    /** The registration timestamp source. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val registerAdapter by lazy { AppJson.moshi.adapter(RegisterRequest::class.java) }
    private val removeAdapter by lazy { AppJson.moshi.adapter(RemoveRequest::class.java) }
    private val listAdapter by lazy { AppJson.moshi.adapter(UnifiedPushTargetsResponseDto::class.java) }
    private val registerResponseAdapter by lazy {
        AppJson.moshi.adapter(UnifiedPushRegisterResponseDto::class.java)
    }
    private val removeResponseAdapter by lazy {
        AppJson.moshi.adapter(UnifiedPushRemoveResponseDto::class.java)
    }

    /** GET /api/push/unifiedpush */
    fun list(): String {
        val targets = subscriptions.all()
            .filter { it.targetKind == PushTargetKind.UNIFIEDPUSH }
            .map { it.toDto() }
        return listAdapter.toJson(
            UnifiedPushTargetsResponseDto(
                targets = targets,
                count = targets.size,
                maxTargets = PushSubscriptionPolicy.MAX_UNIFIEDPUSH_TARGETS,
                vapidPublicKey = vapidKeys.publicKeyBase64Url(),
            ),
        )
    }

    /** POST /api/push/unifiedpush */
    fun register(body: String): String {
        val request = runCatching { registerAdapter.fromJson(body) }.getOrNull()
            ?: return registerResponseAdapter.toJson(
                UnifiedPushRegisterResponseDto(
                    success = false,
                    count = subscriptions.count(PushTargetKind.UNIFIEDPUSH),
                    error = "invalid JSON",
                ),
            )
        val result = subscriptions.addUnifiedPush(
            endpoint = request.endpoint.orEmpty(),
            p256dh = request.p256dh.orEmpty(),
            auth = request.auth.orEmpty(),
            instance = request.instance,
            label = request.label,
            distributor = request.distributor,
            replacedEndpoint = request.replacedEndpoint,
            createdAtMs = nowMs(),
        )
        val response = when (result.status) {
            PushAddStatus.ADDED, PushAddStatus.UPDATED ->
                UnifiedPushRegisterResponseDto(success = true, count = result.count)
            PushAddStatus.INVALID ->
                UnifiedPushRegisterResponseDto(
                    success = false,
                    count = result.count,
                    error = "invalid endpoint, key material, or instance",
                )
            PushAddStatus.CONFLICT ->
                UnifiedPushRegisterResponseDto(
                    success = false,
                    count = result.count,
                    error = "endpoint is already registered as a browser subscription",
                )
            PushAddStatus.LIMIT_REACHED ->
                UnifiedPushRegisterResponseDto(
                    success = false,
                    count = result.count,
                    error = "UnifiedPush target limit reached (${PushSubscriptionPolicy.MAX_UNIFIEDPUSH_TARGETS})",
                )
            PushAddStatus.PERSIST_FAILED ->
                UnifiedPushRegisterResponseDto(
                    success = false,
                    count = result.count,
                    error = "failed to persist the UnifiedPush target",
                )
        }
        return registerResponseAdapter.toJson(response)
    }

    /** POST /api/push/unifiedpush/remove */
    fun remove(body: String): String {
        val request = runCatching { removeAdapter.fromJson(body) }.getOrNull()
        val endpoint = request?.endpoint?.trim().orEmpty()
        if (endpoint.isEmpty()) {
            return removeResponseAdapter.toJson(
                UnifiedPushRemoveResponseDto(success = false, removed = false),
            )
        }
        // Kind-scoped: a token-writable route must never be able to delete a
        // browser session's subscription that happens to share a URL.
        val removed = subscriptions.remove(endpoint, PushTargetKind.UNIFIEDPUSH)
        return removeResponseAdapter.toJson(
            UnifiedPushRemoveResponseDto(success = true, removed = removed),
        )
    }

    private fun StoredPushSubscription.toDto() = UnifiedPushTargetDto(
        endpoint = endpoint,
        instance = instance.orEmpty().ifEmpty { PushSubscriptionPolicy.DEFAULT_INSTANCE },
        label = label,
        distributor = distributor,
        createdAtMs = createdAtMs,
    )

    @JsonClass(generateAdapter = true)
    data class RegisterRequest(
        val endpoint: String? = null,
        val p256dh: String? = null,
        val auth: String? = null,
        val instance: String? = null,
        val label: String? = null,
        val distributor: String? = null,
        val replacedEndpoint: String? = null,
    )

    @JsonClass(generateAdapter = true)
    data class RemoveRequest(val endpoint: String? = null)
}
