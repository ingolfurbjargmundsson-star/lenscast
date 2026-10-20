# USB streaming

LensCast serves everything from the phone itself, so anything that can open a
TCP connection to the web port can watch — including a connection that rides
the phone's own USB cable. One `adb forward` maps the web port onto the
connected computer's `127.0.0.1`, and the dashboard, the MJPEG stream, the
RTSP server and the API all answer there: no Wi-Fi network involved, and the
phone does not need to be on any LAN at all.

## Why the cable

- **No network needed.** The phone can be in airplane mode with Wi-Fi off;
  the stream still flows over the cable. Useful on lockdown networks, in the
  field, or anywhere you don't want the camera on a shared network.
- **Cable latency and jitter.** The path skips the Wi-Fi radio entirely, so
  there is no channel-access delay, no retransmission bursts and no
  congestion from other devices.
- **Full link capacity, reliably.** Wi-Fi throughput varies with signal,
  band and neighbours; a USB 2.0 connection carries hundreds of Mbps.

One honest caveat, so expectations are right: with the default MJPEG
settings the phone's JPEG *encoder* is the bottleneck, not either transport —
measured side by side, a 6-second pull of the default stream moved roughly
the same bytes over USB and over a healthy Wi-Fi LAN. The cable pays off
when you push the settings up (higher JPEG quality, or the H.264 outputs
toward their 8 Mbps ceiling), when the Wi-Fi is weak or congested, or when
there is no Wi-Fi at all. For the default-quality casual viewer, treat USB
as the reliability and privacy win rather than a speed one.

## Setup

1. On the phone, enable **Developer options → USB debugging**.
2. Plug the phone into the computer with USB and accept the debugging
   fingerprint prompt (once per computer).
3. Forward the web port — the default is 8080 (`core/StreamDefaults.kt`;
   configurable in the app, range 1024–65535):

   ```bash
   adb forward tcp:8080 tcp:8080
   ```

4. Open `http://127.0.0.1:8080/` on the computer — the full dashboard, or
   `http://127.0.0.1:8080/stream` for just the M-JPEG stream.

The two sides of the forward are independent: `adb forward tcp:18080
tcp:8080` serves the phone's 8080 on the computer's 18080. With several
phones attached, target each by serial (`adb devices` lists them):

```bash
adb forward -s SERIAL tcp:8080 tcp:8080
```

A forward lives as long as that USB session: unplug, replug, or restart
adbd and it is gone — re-run the command. A shell alias keeps it to one
word. Start the stream first, from the dashboard or the API (below); a
forward alone does not start anything.

## Watch it

Any player that accepts an HTTP M-JPEG URL works against
`http://127.0.0.1:8080/stream` exactly as it would against the LAN URL:

```bash
# ffplay, low-latency flags
ffplay -fflags nobuffer -flags low_delay http://127.0.0.1:8080/stream

# VLC: Media → Open Network Stream → the same URL

# ffmpeg: re-encode into a Linux v4l2loopback device so the phone becomes a
# webcam for other apps (the user-visible /dev/video must exist first —
# modprobe v4l2loopback)
ffmpeg -i http://127.0.0.1:8080/stream \
    -vf format=yuv420p \
    -fflags nobuffer \
    -flags low_delay \
    -f v4l2 \
    /dev/video0
```

The RTSP output rides a forward too, with one flag: RTP's default delivery
is UDP, which cannot cross a TCP forward, so force interleaved TCP on the
client side:

```bash
adb forward tcp:8554 tcp:8554
ffplay -rtsp_transport tcp rtsp://127.0.0.1:8554/stream
```

## Scripting the API through a forward

Reads need nothing while stream auth is off:

```bash
curl -s http://127.0.0.1:8080/api/auth/status   # {"required":false}
curl -s http://127.0.0.1:8080/snapshot -o frame.jpg
```

State-changing POSTs (`/api/stream/start`, `/api/capture`, …) hit the CSRF
gate: a request with an `Origin` header that doesn't match one of the
phone's own addresses and port is rejected. Through a forward it never can
match — the phone sees port 8080 while your curl line says 18080, and the
 Origin check has no idea a forward is in the middle. Send the browser-style
 `X-Requested-With` header instead, which the gate accepts from any value:

```bash
curl -s -X POST -H "X-Requested-With: curl" \
    http://127.0.0.1:8080/api/stream/start
```

With **stream auth enabled**, either log in once and reuse the session
cookie, or arm the **API token** in settings and send it as
`Authorization: Bearer <token>` (or `X-Api-Token:`) — it authorizes the
protected reads plus the start/stop allow-list, and token requests skip the
CSRF origin check entirely, so no `X-Requested-With` is needed. The token's
coverage is described in [Remote access](remote-access.md).

## Caveats

- **HTTPS mode** applies to the forwarded URL too: use
  `https://127.0.0.1:8080/` with the self-signed certificate (curl `-k`, or
  verify the SHA-256 fingerprint shown on the in-app Connect sheet and
  accept the browser exception).
- **The viewer is the computer the phone is plugged into.** A forward
  exposes the port on that machine's loopback only — other devices on any
  network see nothing, which is the privacy feature, not a bug. Share
  further with the usual LAN/VPN tools ([Remote access](remote-access.md)).
- **The cable must stay plugged in**, and each reconnect needs a fresh
  `adb forward`. For a permanent headless setup, Wi-Fi, Tailscale or a
  powered USB hub with a stable connection are the better fit.
- The **8-client M-JPEG fan-out cap** (`MAX_HTTP_CLIENTS`) counts viewers,
  not transports — each browser tab or ffmpeg process through the forward
  is one client, same as over Wi-Fi.
