# UnifiedPush alerts

LensCast can push detection alerts to any **UnifiedPush** receiver: an Android
app backed by a UnifiedPush distributor (ntfy, NextPush, …) or any client that
can hand the camera a Web Push endpoint. The camera is a **publisher only** —
it does not ship a receiver, and no Google/FCM account or extra Android
dependency is involved. UnifiedPush is wire-identical to Web Push, so the
camera reuses the same RFC 8030 / RFC 8291 / RFC 8292 stack as browser
notifications.

This feature is opt-in and independent from browser Web Push. Enabling one
never enables the other.

```
detection ──▶ camera WebPushSender ──▶ UnifiedPush endpoint ──▶ distributor ──▶ receiver app
             RFC 8291 aes128gcm          (capability URL)        (ntfy, …)       shows the alert
             RFC 8292 VAPID
```

## What you need

- A **distributor** installed on the Android device (for example ntfy or
  NextPush) and a receiver app that supports UnifiedPush. The receiver can be
  your own app (see the recipe below) or an existing one that accepts
  LensCast's payload.
- A distributor that exposes **Web Push keys** (`PushEndpoint.pubKeySet`).
  That is the common UnifiedPush mode; endpoints without a `p256dh`/`auth`
  pair cannot be encrypted for and are rejected by the camera.
- The camera's **HTTPS dashboard mode** (a self-signed certificate is fine if
  the receiver trusts or pins it). The API token and pairing JSON must never
  travel over plain HTTP. Browser Web Push also requires HTTPS; UnifiedPush
  itself only requires the receiver-to-camera leg to be secured.

## Enable it on the camera

1. Open **Detection settings** in the LensCast app.
2. Turn on **UnifiedPush alerts** (`unifiedPushEnabled`). This is a separate
   master gate from **Web Push (browser dashboards)** (`pushEnabled`).
3. Keep the **VAPID contact** field valid (for example `mailto:you@example.com`).
   The same RFC 8292 subject is used by both transports; every push service
   rejects a malformed subject.
4. Pair a receiver (below). Registration is accepted even while the gate is
   off, but nothing is delivered until it is on.

The dashboard shows the same gate in the **UnifiedPush** card, along with the
camera's VAPID public key, the registered targets, and a remove button.

## Pairing a receiver

### Option A — pairing JSON from the dashboard

The camera can only show the API token once. In **Settings → Authentication**
(the admin-only card), generate a token; the card then shows a copyable pairing
document:

```json
{
  "type": "lenscast-pairing",
  "version": 1,
  "camera": "192.168.1.50:8443",
  "baseUrl": "https://192.168.1.50:8443",
  "apiToken": "…",
  "vapidPublicKey": "B…"
}
```

`baseUrl` is the camera origin; `vapidPublicKey` is the exact 87-character
value `UnifiedPush.register` expects. Import or paste this into the receiver.
If the VAPID read fails while generating the token, the JSON is omitted but
the token still works — read the key from
`GET /api/push/unifiedpush` instead.

The dashboard only emits this document in a secure context (HTTPS or
localhost). On plain HTTP it shows the token but hides the pairing JSON and
explains why: the document bundles the token with the VAPID key, and the
requirement above forbids sending either over cleartext. Use Option B or
enable HTTPS first.

### Option B — manual registration

Read the camera's key and current targets:

```bash
curl -H "Authorization: Bearer $TOKEN" \
  https://PHONE_IP:8443/api/push/unifiedpush
```

```json
{
  "targets": [
    {
      "endpoint": "https://push.example.com/send/abc",
      "instance": "lenscast-kitchen",
      "label": "Pixel 8",
      "distributor": "org.unifiedpush.distributor.nextpush",
      "createdAtMs": 1729500000000
    }
  ],
  "count": 1,
  "maxTargets": 16,
  "vapidPublicKey": "B…"
}
```

Register an endpoint with the key material the distributor issued:

```bash
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "endpoint": "https://push.example.com/send/abc",
    "p256dh": "BASE64URL_P256_POINT",
    "auth": "BASE64URL_16_BYTE_SECRET",
    "instance": "lenscast-kitchen",
    "label": "Pixel 8",
    "distributor": "org.unifiedpush.distributor.nextpush",
    "replacedEndpoint": null
  }' \
  https://PHONE_IP:8443/api/push/unifiedpush
```

A successful answer is
`{"success":true,"count":1}`. Validation failures return `success:false`
with a human-readable `error`; the target limit is **16**.

An endpoint belongs to exactly one transport. If it is already stored as a
browser subscription, this API answers `success:false` with a conflict error;
the browser subscribe route answers the mirror collision with its plain
`{"success":false}`. Only this API distinguishes the conflict — the browser
route keeps its boolean contract.

Remove a target (idempotent, token-writable):

```bash
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"endpoint":"https://push.example.com/send/abc"}' \
  https://PHONE_IP:8443/api/push/unifiedpush/remove
```

The answer is `{"success":true,"removed":true}`; repeating it returns
`removed:false` and still succeeds.

`X-Api-Token: $TOKEN` works everywhere `Authorization: Bearer $TOKEN` does.
The two UnifiedPush POSTs are on the API token's write allow-list. The
browser routes (`/api/push/subscriptions`) remain session-cookie-only — a
token cannot list or delete those, even on GET/HEAD — and they only see
browser subscriptions.

## Receiver recipe (android-connector 3.3.5)

Add the connector (it is independent of LensCast; the camera itself does not
depend on it):

```kotlin
dependencies {
    implementation("org.unifiedpush.android:connector:3.3.5")
}
```

Declare the event service. It must not be exported and must handle
`org.unifiedpush.android.connector.PUSH_EVENT`:

```xml
<service
    android:name=".push.LensCastPushService"
    android:exported="false">
    <intent-filter>
        <action android:name="org.unifiedpush.android.connector.PUSH_EVENT" />
    </intent-filter>
</service>
```

Implement it. `endpoint.url` is the capability URL, and
`endpoint.pubKeySet` carries the RFC 8291 `p256dh`/`auth` pair:

```kotlin
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class LensCastPushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet ?: return   // not a Web Push distributor
        val camera = CameraRegistry.forInstance(instance) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            camera.registerOrRotate(
                endpoint = endpoint.url,
                p256dh = keys.pubKey,
                auth = keys.auth,
                instance = instance,
                distributor = UnifiedPush.getSavedDistributor(this@LensCastPushService),
            )
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) return
        val payload = JSONObject(message.content.decodeToString())
        val title = payload.optString("title", "LensCast alert")
        val body = payload.optString("body")
        // Show the notification; use payload.optString("tag") as the
        // notification tag so alerts of the same type replace each other.
    }

    override fun onUnregistered(instance: String) {
        val camera = CameraRegistry.forInstance(instance) ?: return
        CoroutineScope(Dispatchers.IO).launch { camera.removeLastEndpoint() }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        // The distributor refused the registration; surface it and retry
        // with backoff. Do not delete the last-known camera endpoint here.
    }
}
```

Register once the user has chosen a distributor and pasted the pairing
document. Pick from `getDistributors`, persist the choice with
`saveDistributor`, then register:

```kotlin
val distributors = UnifiedPush.getDistributors(context)   // package names for the picker
val chosen = UnifiedPush.getSavedDistributor(context)
    ?: distributors.firstOrNull()
    ?: error("Install a UnifiedPush distributor first")
UnifiedPush.saveDistributor(context, chosen)              // required before register
UnifiedPush.register(
    context = context,
    instance = "lenscast-kitchen",          // one instance per camera
    messageForDistributor = "LensCast kitchen",
    vapid = pairing.vapidPublicKey,         // exactly 87 base64url chars
)
```

`UnifiedPush.register` throws `VapidNotValidException` when the key is not in
uncompressed P-256 form (87 characters). Store the last endpoint per instance
so `onNewEndpoint` can send it as `replacedEndpoint`:

```kotlin
val body = JSONObject()
    .put("endpoint", endpoint)
    .put("p256dh", p256dh)
    .put("auth", auth)
    .put("instance", instance)
    .put("label", Build.MODEL)
    .put("distributor", distributor)
    .put("replacedEndpoint", previousEndpoint.takeIf { it != null && it != endpoint })

val request = Request.Builder()
    .url("${pairing.baseUrl}/api/push/unifiedpush")
    .header("Authorization", "Bearer ${pairing.apiToken}")
    .post(body.toString().toRequestBody("application/json".toMediaType()))
    .build()
```

Use a real TLS trust path for the camera (a trusted certificate, Tailscale,
or a network-security-config pin for the self-signed certificate). A stock
OkHttp client rejects the self-signed certificate.

### One distributor instance per camera on a receiver

The android-connector keeps **one key pair and one VAPID binding per
`instance`** inside a receiver app on one device. Every LensCast camera has
its own VAPID key, so a receiver that pairs with more than one camera must use
a distinct instance per camera (for example `"lenscast-<cameraId>"`):
otherwise the second `register` call overwrites the first camera's binding and
one camera's pushes fail authentication. A receiver that pairs with a single
camera can stay on `"default"`.

Instance names are local to the receiver app. On the camera side the
**endpoint** is the store key, so two receiver devices may both use the
instance name `"default"` — their distributor endpoints differ, so the rows do
not collide. The instance string sent to the camera is metadata, not an
identity key.

If the camera's VAPID key changes (app data cleared, reinstall), call
`UnifiedPush.unregister(context, instance)` and then `register` again with the
new key. Rotating an endpoint alone keeps the same keys; just POST the new
endpoint with `replacedEndpoint` set to the old one.

## Payload

The app-side UnifiedPush connector decrypts the RFC 8291 body and hands the
receiver UTF-8 JSON (the distributor only routes ciphertext):

```json
{
  "title": "LensCast Motion alert",
  "body": "Front door · person · battery 87%",
  "tag": "lenscast-motion",
  "eventId": "3f0c…",
  "type": "motion",
  "timestampMs": 1729500000000,
  "clipAvailable": true,
  "url": "#/events"
}
```

- `title` / `body` are display strings; `body` joins the triggered zones, ML
  labels, and battery level with ` · `.
- `tag` is `lenscast-<type>`; use it as the Android notification tag to
  replace an older alert of the same type instead of stacking.
- `eventId` is the persisted detection event's UUID and is `null` for the
  synthetic test alert.
- `type` is `motion`, `sound`, `tamper`, or `test`.
- `timestampMs` is the event moment (Unix epoch milliseconds).
- `clipAvailable` is true when the event dispatched a bounded recording; the
  clip appears in the gallery shortly after.
- `url` is the dashboard deep link (`#/events` at dispatch); prefix it with
  `baseUrl` if the receiver opens it.

The whole cleartext is capped at **3993 bytes**. If a body would exceed the
cap, only `body` is shortened (by UTF-8 bytes, then emptied); `title`, `tag`,
`type`, `eventId`, `timestampMs`, and `clipAvailable` are never truncated.

The camera also sends the usual Web Push headers: `Content-Encoding:
aes128gcm`, `TTL: 3600`, `Urgency: high` for motion/tamper and `normal` for
sound/test, `Topic: lenscast-<type>`, and an RFC 8292 `Authorization` header.
The distributor consumes these; the receiver only sees the decrypted JSON.

## Delivery and lifecycle

- **Background delivery**: while the camera web server is running, LensCast
  holds a CONNECTED_DEVICE foreground service even when no stream is live.
  Android shows a persistent "Camera server active" notification. This keeps
  the server process and its outbound Web Push/MQTT/webhook traffic outside
  the background-network firewall. If Android deferred the foreground start
  (for example after a boot), opening LensCast once retries it on resume.
- **Registration is idempotent** by endpoint. Re-posting the same endpoint
  updates its label/instance/distributor and timestamp. An endpoint belongs to
  exactly one transport: registering a URL that is already a browser
  subscription is rejected rather than converting (and deleting) that row.
- **Rotation**: send the new endpoint with `replacedEndpoint` set to the old
  one in the same request. The camera stores the new row and removes the old
  one atomically. If the receiver cannot do that, registering the new endpoint
  alone is enough; the old one is pruned on its first 404/410.
- **Pruning**: a `404` or `410` from the push service removes that subscription
  — but only if the stored `p256dh`/`auth` still match the row that was
  dispatched, so a rotation landing mid-dispatch is not lost.
- **Retries**: one in-flight fan-out per camera. While it runs, a newer
  detection replaces the pending one (notification semantics, not a queue of
  stale alerts). Each request has 5 s connect and read timeouts. Three
  consecutive transport failures abort the rest of that fan-out; the next
  detection tries again. Other non-2xx answers are logged as rejected but do
  not prune.
- **Limits**: at most 16 UnifiedPush targets. Re-registering an existing
  endpoint still works at the cap; a new endpoint requires removing one first.
- **Quiet hours** on the camera do not suppress remote push. Notification
  policy on the receiving device (distributor/receiver DND) governs.
- The detection event log records the dispatch as action `push` for both
  transports; there is no separate UnifiedPush action.
- Removing a target on the camera does **not** unregister it from the
  distributor. To issue fresh keys, call `UnifiedPush.unregister` on the
  receiver and register again.

## Security notes

- A UnifiedPush endpoint is a **capability URL**: anyone who knows it can
  deliver encrypted notifications. The camera lists endpoints only to
  authenticated readers, logs only the origin (never the path), and never
  exposes the VAPID private key or the receiver's `auth` secret in any API
  response.
- The two UnifiedPush POSTs are deliberately token-writable so a headless
  receiver can pair without a browser session. Treat the API token as a
  push-enrollment credential; rotate it from the Authentication card if it
  leaks. Browser subscription routes are neither token-readable nor
  token-writable.
- Prefer a trusted TLS certificate. If you pin the camera's self-signed
  certificate, pin the leaf you verified at pairing time and plan for
  rotation.
- The camera never contacts a distributor on its own; it only POSTs to the
  endpoints that receivers registered.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `success:false`, "invalid endpoint, key material, or instance" | Endpoint must be `https`, `p256dh` must decode to a 65-byte uncompressed P-256 point, `auth` to 16 bytes, the endpoint must be under the length cap, and the instance must be at most 64 characters with no control characters. |
| `success:false`, "target limit reached" | `GET /api/push/unifiedpush`; remove stale endpoints (16 max). |
| Target listed but no alerts | `unifiedPushEnabled` must be on, the distributor must be connected, and the receiver must be exempt from battery optimization. Also check that the camera's persistent "Camera server active" foreground notification is present; open LensCast once if it is missing. |
| Push rejected with 403/401 | VAPID mismatch: the receiver registered with a key from a different camera, or the camera's key changed. Unregister/re-register that instance. |
| Endpoint disappears after a while | The distributor rotated it without letting the receiver report `replacedEndpoint`; re-register from `onNewEndpoint`. A 404/410 pruned a dead endpoint on purpose. |
| Receiver validates `p256dh` but decryption fails | The receiver is using the wrong distributor keys for that instance, usually because two cameras share one instance. Give each camera its own instance. |
| Self-signed certificate errors during pairing | Trust or pin the camera certificate in the receiver's network security config. |
| Test alert arrives but real events do not | Check the detection gate (arm schedule, ML gate, cooldowns) — the test route bypasses the motion/ML verdict but uses the same push fan-out. |

The synthetic test alert is available to tokens and sessions:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  https://PHONE_IP:8443/api/detection/test
```
