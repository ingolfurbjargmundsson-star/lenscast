package com.raulshma.lenscast.core.push

import android.content.Context
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import com.squareup.moshi.JsonClass
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * Which transport a stored target belongs to. Both are Web Push at the wire
 * level — an RFC 8030 endpoint plus RFC 8291 key material — but they are
 * managed by different API families and gated by different settings:
 * browsers subscribe themselves through `/api/push/subscriptions`, while a
 * UnifiedPush receiver registers its distributor endpoint through
 * `/api/push/unifiedpush` (token-writable, so headless receivers can pair).
 *
 * The wire names are persisted, so they are part of the store's on-disk
 * contract: `browser` for rows written before UnifiedPush existed (the field
 * is absent and Moshi's constructor default fills it in).
 */
enum class PushTargetKind(val wireName: String) {
    BROWSER("browser"),
    UNIFIEDPUSH("unifiedpush"),
    ;

    companion object {
        fun fromWireNameOrNull(value: String?): PushTargetKind? {
            val normalized = value?.trim()?.lowercase(Locale.US) ?: return null
            return entries.firstOrNull { it.wireName == normalized }
        }

        /** Unknown/absent values are browser rows: the pre-UnifiedPush schema. */
        fun fromStoredOrDefault(value: String?): PushTargetKind =
            fromWireNameOrNull(value) ?: BROWSER
    }
}

/**
 * One persisted Web Push target: the push service endpoint URL and the client
 * key material RFC 8291 encryption needs. `p256dh`/`auth` are base64url (the
 * browser's or distributor's own wire encoding, forwarded verbatim);
 * `createdAtMs` drives nothing today but keeps the store self-describing for
 * debugging; `labels` is optional free-form browser labeling, present for
 * forward compatibility.
 *
 * UnifiedPush rows add [instance] (the receiver's distributor instance name),
 * [label] (a human name for the target), and [distributor] (the distributor
 * package, informational). The endpoint is the store key for both kinds: two
 * receivers may both use the instance name `default`, and a receiver's
 * endpoint rotates while its instance stays put. Registration therefore
 * carries an optional `replacedEndpoint` so a receiver can retire its old
 * endpoint in the same atomic write; 404/410 pruning is the fallback.
 *
 * These are device-local secrets — an endpoint plus key material lets the
 * holder deliver notifications to that device — so the store file is
 * excluded from cloud backup / device transfer in `data_extraction_rules.xml`
 * and `backup_rules.xml`, the same treatment as the auth session tokens.
 */
@JsonClass(generateAdapter = true)
data class StoredPushSubscription(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val createdAtMs: Long = 0,
    val labels: List<String> = emptyList(),
    /** [PushTargetKind] wire name; absent in pre-UnifiedPush files (defaults to browser). */
    val kind: String = PushTargetKind.BROWSER.wireName,
    /** The UnifiedPush distributor instance this target registered from. */
    val instance: String? = null,
    /** A human-readable target name (never part of the capability itself). */
    val label: String? = null,
    /** The distributor package name, informational for the settings readout. */
    val distributor: String? = null,
) {
    val targetKind: PushTargetKind
        get() = PushTargetKind.fromStoredOrDefault(kind)
}

/** The admission outcome of a UnifiedPush registration. */
enum class PushAddStatus { ADDED, UPDATED, INVALID, CONFLICT, LIMIT_REACHED, PERSIST_FAILED }

/** [count] is the UnifiedPush target count after the attempted mutation. */
data class UnifiedPushAddResult(val status: PushAddStatus, val count: Int)

/** The store's per-kind counts, read once per dispatch. */
data class PushTargetCounts(val browser: Int, val unifiedPush: Int)

/**
 * File-backed store of Web Push targets, the [com.raulshma.lenscast.streaming.AuthSessionStore]
 * shape: `push_subscriptions.json` in app-private filesDir, written atomically
 * (tmp file + rename), decoded through the one App Json Moshi instance, a
 * corrupt or absent file starting clean. Dedupe is by endpoint — a browser or
 * receiver re-registering (same endpoint, rotated keys) updates in place
 * rather than multiplying rows; every mutation is serialized through one
 * monitor because the atomic write uses a fixed `.tmp` name.
 */
class PushSubscriptionStore {

    private val file: File

    private val listAdapter by lazy {
        com.squareup.moshi.Types.newParameterizedType(List::class.java, StoredPushSubscription::class.java)
            .let { AppJson.moshi.adapter<List<StoredPushSubscription>>(it) }
    }

    @Volatile
    private var cache: List<StoredPushSubscription>? = null

    /** The production home: `push_subscriptions.json` in app-private filesDir. */
    constructor(context: Context) : this(File(context.filesDir, "push_subscriptions.json"))

    /** The file seam the JVM tests inject (the AuditLog pattern). */
    constructor(file: File) {
        this.file = file
    }

    /** The current targets, endpoint-deduped, oldest first. */
    @Synchronized
    fun all(): List<StoredPushSubscription> =
        cache ?: file.readJsonOrDefault(listAdapter, emptyList(), warn = "Failed to read push subscriptions; starting clean")
            .filter { it.endpoint.isNotBlank() }
            .distinctBy { it.endpoint }
            .also { cache = it }

    /** Both counts in one store read — the dispatch verdict's input. */
    fun counts(): PushTargetCounts {
        val all = all()
        return PushTargetCounts(
            browser = all.count { it.targetKind == PushTargetKind.BROWSER },
            unifiedPush = all.count { it.targetKind == PushTargetKind.UNIFIEDPUSH },
        )
    }

    /** Count of all targets (both kinds). */
    fun count(): Int = all().size

    fun count(kind: PushTargetKind): Int = all().count { it.targetKind == kind }

    /**
     * Adds (or endpoint-replaces) a browser subscription after validating its
     * key shapes — the receiver's public point and auth secret must be exactly
     * what RFC 8291 encryption consumes, and a bad key would only be
     * discovered at the first dispatch. True when the store changed.
     */
    @Synchronized
    fun add(endpoint: String, p256dh: String, auth: String, labels: List<String>, createdAtMs: Long): Boolean {
        val trimmed = endpoint.trim()
        if (!PushSubscriptionPolicy.isSubscribable(trimmed, p256dh, auth)) return false
        val entry = StoredPushSubscription(
            endpoint = trimmed,
            p256dh = p256dh.trim(),
            auth = auth.trim(),
            createdAtMs = createdAtMs,
            labels = labels,
            kind = PushTargetKind.BROWSER.wireName,
        )
        val current = all()
        // The endpoint is the store key across both kinds: a browser cannot
        // silently take over a receiver row (or vice versa). Reject rather
        // than leave two rows that `distinctBy` would hide.
        if (current.any { it.endpoint == entry.endpoint && it.targetKind != PushTargetKind.BROWSER }) {
            return false
        }
        val next = current.filterNot { it.endpoint == entry.endpoint } + entry
        return persist(next)
    }

    /**
     * Adds or endpoint-replaces a UnifiedPush target. [replacedEndpoint] is
     * the receiver's rotation bridge: the old row is removed in the same
     * atomic write, but only when it is itself a UnifiedPush row — a receiver
     * that guesses a browser endpoint must never be able to delete it. The
     * 16-target cap counts UnifiedPush rows only (browser subscriptions have
     * their own session-managed population); an update of an existing
     * endpoint is always admitted.
     */
    @Synchronized
    fun addUnifiedPush(
        endpoint: String,
        p256dh: String,
        auth: String,
        instance: String?,
        label: String?,
        distributor: String?,
        replacedEndpoint: String?,
        createdAtMs: Long,
    ): UnifiedPushAddResult {
        val trimmed = endpoint.trim()
        val normalizedInstance = PushSubscriptionPolicy.normalizeInstance(instance)
        if (!PushSubscriptionPolicy.isSubscribable(trimmed, p256dh, auth) || normalizedInstance == null) {
            return UnifiedPushAddResult(PushAddStatus.INVALID, count(PushTargetKind.UNIFIEDPUSH))
        }

        val current = all()
        val existing = current.firstOrNull { it.endpoint == trimmed }
        // A token-writable receiver route must never convert (and thereby
        // delete) a browser subscription that happens to share the URL.
        // Each endpoint belongs to exactly one transport.
        if (existing != null && existing.targetKind != PushTargetKind.UNIFIEDPUSH) {
            return UnifiedPushAddResult(PushAddStatus.CONFLICT, count(PushTargetKind.UNIFIEDPUSH))
        }
        val oldEndpoint = replacedEndpoint?.trim()?.takeIf { it.isNotEmpty() && it != trimmed }
        val withoutReplaced = if (oldEndpoint == null) {
            current
        } else {
            current.filterNot {
                it.endpoint == oldEndpoint && it.targetKind == PushTargetKind.UNIFIEDPUSH
            }
        }
        val otherTargets = withoutReplaced.count {
            it.targetKind == PushTargetKind.UNIFIEDPUSH && it.endpoint != trimmed
        }
        if (existing?.targetKind != PushTargetKind.UNIFIEDPUSH &&
            otherTargets >= PushSubscriptionPolicy.MAX_UNIFIEDPUSH_TARGETS
        ) {
            return UnifiedPushAddResult(PushAddStatus.LIMIT_REACHED, otherTargets)
        }

        val entry = StoredPushSubscription(
            endpoint = trimmed,
            p256dh = p256dh.trim(),
            auth = auth.trim(),
            createdAtMs = createdAtMs,
            labels = emptyList(),
            kind = PushTargetKind.UNIFIEDPUSH.wireName,
            instance = normalizedInstance,
            label = PushSubscriptionPolicy.normalizeLabel(label),
            distributor = PushSubscriptionPolicy.normalizeDistributor(distributor),
        )
        val next = withoutReplaced.filterNot { it.endpoint == entry.endpoint } + entry
        val persisted = persist(next)
        val newCount = next.count { it.targetKind == PushTargetKind.UNIFIEDPUSH }
        return UnifiedPushAddResult(
            status = when {
                !persisted -> PushAddStatus.PERSIST_FAILED
                existing == null -> PushAddStatus.ADDED
                else -> PushAddStatus.UPDATED
            },
            count = newCount,
        )
    }

    /** Removes an endpoint only when it still belongs to [kind]. */
    @Synchronized
    fun remove(endpoint: String, kind: PushTargetKind): Boolean =
        removeWhere { it.endpoint == endpoint.trim() && it.targetKind == kind }

    /**
     * The sender's race-safe prune: remove the endpoint only when its key
     * material is still the copy the failed dispatch used. A rotation landing
     * between the dispatch snapshot and the 404 must survive.
     */
    @Synchronized
    fun removeIfSame(endpoint: String, p256dh: String, auth: String): Boolean {
        val trimmed = endpoint.trim()
        val trimmedP256dh = p256dh.trim()
        val trimmedAuth = auth.trim()
        return removeWhere {
            it.endpoint == trimmed && it.p256dh == trimmedP256dh && it.auth == trimmedAuth
        }
    }

    /**
     * The shared removal path: one snapshot, one write, and a false verdict
     * when nothing matched (no empty persistence cycle). Callers hold the
     * monitor through their public wrapper.
     */
    private fun removeWhere(predicate: (StoredPushSubscription) -> Boolean): Boolean {
        val current = all()
        val next = current.filterNot(predicate)
        if (next.size == current.size) return false
        return persist(next)
    }

    private fun persist(subscriptions: List<StoredPushSubscription>): Boolean {
        cache = subscriptions
        // Losing the persisted copy only means browsers/receivers re-register.
        return file.writeAtomicallyOrWarn(
            listAdapter.toJson(subscriptions),
            warn = "Failed to persist push subscriptions",
        )
    }
}

/**
 * The admission verdicts, pure and JVM-tested: an endpoint is an https URL
 * (push services are always TLS), the p256dh field must decode to the
 * 65-byte uncompressed P-256 point, and auth must decode to the 16-byte
 * secret RFC 8291's HKDF consumes. UnifiedPush endpoints obey the same wire
 * rules — a distributor's endpoint is an RFC 8030 capability URL.
 */
object PushSubscriptionPolicy {

    /** The receiver population cap; browser subscriptions are uncapped. */
    const val MAX_UNIFIEDPUSH_TARGETS = 16

    const val DEFAULT_INSTANCE = "default"

    private const val MAX_ENDPOINT_LENGTH = 4096
    private const val MAX_INSTANCE_LENGTH = 64
    private const val MAX_LABEL_LENGTH = 64
    private const val MAX_DISTRIBUTOR_LENGTH = 128

    fun isSubscribable(endpoint: String, p256dh: String, auth: String): Boolean =
        isEndpoint(endpoint) && PushEncryption.receiverKeyOrNull(p256dh, auth) != null

    fun isEndpoint(endpoint: String): Boolean {
        val trimmed = endpoint.trim()
        if (trimmed.length < "https://h".length || trimmed.length > MAX_ENDPOINT_LENGTH) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        return uri.scheme?.equals("https", ignoreCase = true) == true &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }

    /**
     * Instance names are opaque distributor labels: whitespace-trimmed, blank
     * folding to `default`, control characters and over-long names rejected
     * (a truncated instance could collide with a different receiver).
     */
    fun normalizeInstance(instance: String?): String? {
        val trimmed = instance?.trim().orEmpty().ifEmpty { DEFAULT_INSTANCE }
        if (trimmed.length > MAX_INSTANCE_LENGTH) return null
        if (trimmed.any { it.isISOControl() }) return null
        return trimmed
    }

    fun normalizeLabel(label: String?): String? =
        label?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL_LENGTH)

    fun normalizeDistributor(distributor: String?): String? =
        distributor?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DISTRIBUTOR_LENGTH)
}
