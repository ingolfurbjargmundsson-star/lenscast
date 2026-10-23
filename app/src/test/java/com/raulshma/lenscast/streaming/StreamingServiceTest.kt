package com.raulshma.lenscast.streaming

import android.app.Notification
import android.content.Intent
import com.raulshma.lenscast.core.ForegroundNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The runtime service's two modes share one notification slot. The session
 * tests pin which intent is sent; these pin what the service does with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingServiceTest {

    @Test
    fun `server action posts the server notification`() {
        val service = Robolectric.buildService(StreamingService::class.java).create().get()
        val intent = Intent(service, StreamingService::class.java).apply {
            action = StreamingService.ACTION_SERVER
            putExtra(StreamingService.EXTRA_URL, "http://192.168.1.10:8080/stream")
        }

        val result = service.onStartCommand(intent, 0, 1)

        assertEquals(android.app.Service.START_STICKY, result)
        val shadow = shadowOf(service)
        assertEquals(
            ForegroundNotifications.RUNTIME_NOTIFICATION_ID,
            shadow.lastForegroundNotificationId,
        )
        assertEquals(
            "Camera server active at http://192.168.1.10:8080/stream",
            shadow.lastForegroundNotification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString(),
        )
        assertFalse(shadow.isForegroundStopped)
    }

    @Test
    fun `stream action keeps the streaming notification`() {
        val service = Robolectric.buildService(StreamingService::class.java).create().get()
        val intent = Intent(service, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_URL, "http://192.168.1.10:8080/stream")
        }

        service.onStartCommand(intent, 0, 1)

        val shadow = shadowOf(service)
        assertEquals(
            "Streaming to http://192.168.1.10:8080/stream",
            shadow.lastForegroundNotification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString(),
        )
        assertFalse(shadow.isForegroundStopped)
    }

    @Test
    fun `a sticky restart without an intent falls back to server mode`() {
        val service = Robolectric.buildService(StreamingService::class.java).create().get()

        service.onStartCommand(null, 0, 1)

        val shadow = shadowOf(service)
        assertEquals(
            "Camera server active",
            shadow.lastForegroundNotification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString(),
        )
    }
}
