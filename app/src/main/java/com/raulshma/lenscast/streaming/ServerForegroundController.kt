package com.raulshma.lenscast.streaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Maps the web server's runtime state to the foreground service's server-only
 * mode. The server is started at app launch and can outlive every stream, so
 * this is the piece that keeps the process in the foreground (and its
 * outbound alert traffic outside Android's background-network firewall) even
 * when no camera output is live.
 *
 * The session remains the single owner of foreground-service intents and of
 * the stream-mode transitions; this class only observes the server lifecycle.
 * It is deliberately separate from SettingsApplier: server state is runtime
 * state, not a persisted setting.
 *
 * [start] is the one-shot wiring for the application scope. [sync] is the
 * retry hook for the cases Android denies a background start - call it while
 * the app is visible and the server is running.
 */
class ServerForegroundController(
    private val session: StreamingSession,
    private val isServerRunning: StateFlow<Boolean>,
) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            isServerRunning.collect { running -> apply(running) }
        }
    }

    /** Re-apply the current server state; safe to call from Activity.onResume. */
    fun sync() = apply(isServerRunning.value)

    private fun apply(running: Boolean) {
        if (running) {
            session.ensureServerForeground()
        } else {
            session.stopServerForegroundIfIdle()
        }
    }
}
