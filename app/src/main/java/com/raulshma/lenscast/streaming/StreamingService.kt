package com.raulshma.lenscast.streaming

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.raulshma.lenscast.core.ForegroundNotifications

/**
 * The app runtime's one foreground service. Two modes share one notification
 * slot:
 *
 * - **server only** - a CONNECTED_DEVICE foreground service while the camera
 *   web server is running with no live output. This is what keeps the process
 *   and its outbound network (webhook/MQTT/Web Push) out of Android's
 *   background-network firewall between streams.
 * - **streaming** - the existing CAMERA/MICROPHONE foreground service owned by
 *   [StreamingSession] while an output is live.
 *
 * One service (rather than one per mode) means one notification, one
 * lifecycle, and no mode-transition race where a server notification and a
 * stream notification can outlive each other.
 */
class StreamingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ForegroundNotifications.createChannel(this, SERVER_CHANNEL_ID, "Camera server")
        ForegroundNotifications.createChannel(this, STREAMING_CHANNEL_ID, "Streaming")
        Log.d(TAG, "StreamingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreamingForeground(
                url = intent.getStringExtra(EXTRA_URL),
                includeAudio = intent.getBooleanExtra(EXTRA_AUDIO_ACTIVE, false),
            )
            ACTION_SERVER -> startServerForeground(
                url = intent.getStringExtra(EXTRA_URL),
                includeAudio = intent.getBooleanExtra(EXTRA_AUDIO_ACTIVE, false),
            )
            // A sticky restart has no intent. The server is the process's
            // durable mode; the application re-issues the current mode as
            // soon as the server comes up.
            null -> startServerForeground(url = null, includeAudio = false)
            else -> Log.w(TAG, "Ignoring unknown foreground action: ${intent.action}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeForegroundNotification()
        super.onDestroy()
        Log.d(TAG, "StreamingService destroyed")
    }

    private fun startStreamingForeground(url: String?, includeAudio: Boolean) {
        showForeground(
            channelId = STREAMING_CHANNEL_ID,
            title = "LensCast Streaming",
            message = ForegroundNotifications.streamingMessage(url, includeAudio),
            includeAudio = includeAudio,
            serverMode = false,
        )
        Log.d(TAG, "Streaming foreground service started")
    }

    private fun startServerForeground(url: String?, includeAudio: Boolean) {
        showForeground(
            channelId = SERVER_CHANNEL_ID,
            title = "LensCast Camera Server",
            message = ForegroundNotifications.serverMessage(url),
            includeAudio = includeAudio,
            serverMode = true,
        )
        Log.d(TAG, "Server foreground service started")
    }

    private fun showForeground(
        channelId: String,
        title: String,
        message: String,
        includeAudio: Boolean,
        serverMode: Boolean,
    ) {
        runCatching {
            val notification = ForegroundNotifications.build(
                this, channelId, title, message, ongoing = true,
            )
            if (serverMode) {
                ForegroundNotifications.startConnectedDeviceForeground(
                    this,
                    ForegroundNotifications.RUNTIME_NOTIFICATION_ID,
                    notification,
                    includeAudio,
                )
            } else {
                ForegroundNotifications.startCameraForeground(
                    this,
                    ForegroundNotifications.RUNTIME_NOTIFICATION_ID,
                    notification,
                    includeAudio,
                )
            }
        }.onFailure {
            // A denied background start or a missing type prerequisite must
            // not take the app down. The next foreground resume re-promotes.
            Log.e(TAG, "Foreground promotion failed: ${it.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.raulshma.lenscast.START_STREAMING"
        const val ACTION_SERVER = "com.raulshma.lenscast.START_SERVER"
        const val EXTRA_URL = "stream_url"
        const val EXTRA_AUDIO_ACTIVE = "stream_audio_active"
        private const val SERVER_CHANNEL_ID = "server_channel"
        private const val STREAMING_CHANNEL_ID = "streaming_channel"
        private const val TAG = "StreamingService"
    }
}
