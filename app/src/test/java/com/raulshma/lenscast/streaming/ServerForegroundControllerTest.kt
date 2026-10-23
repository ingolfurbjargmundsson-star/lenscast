package com.raulshma.lenscast.streaming

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test

class ServerForegroundControllerTest {

    private val isServerRunning = MutableStateFlow(false)
    private val session: StreamingSession = mockk(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = ServerForegroundController(session, isServerRunning)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `server lifecycle switches between foreground server mode and idle stop`() {
        controller.start(scope)
        eventually { verify { session.stopServerForegroundIfIdle() } }

        isServerRunning.value = true
        eventually { verify { session.ensureServerForeground() } }

        isServerRunning.value = false
        eventually { verify(atLeast = 2) { session.stopServerForegroundIfIdle() } }
    }

    @Test
    fun `sync retries the current server state without waiting for a new emission`() {
        isServerRunning.value = true

        controller.sync()

        verify(exactly = 1) { session.ensureServerForeground() }
        verify(exactly = 0) { session.stopServerForegroundIfIdle() }
    }

    private fun eventually(block: () -> Unit) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            try {
                block()
                return
            } catch (_: AssertionError) {
                Thread.sleep(10)
            }
        }
        block()
    }
}
