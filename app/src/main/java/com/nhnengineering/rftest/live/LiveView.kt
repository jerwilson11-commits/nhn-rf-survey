package com.nhnengineering.rftest.live

import com.nhnengineering.rftest.service.RecordingState

/**
 * Process-scoped owner of the [LiveServer].
 *
 * **The server deliberately does not follow the recording lifecycle.** It was tied to it at first,
 * which meant nothing listened on the port until a session was already running — so the operator
 * could only discover that the cable, the port forward or the browser was wrong *after* starting
 * the walk they were trying to observe. Setup has to be verifiable before it matters.
 *
 * So the toggle starts it, and the page renders immediately showing "connected — not recording".
 * That is the state in which an operator checks their laptop before setting off.
 *
 * Being loopback-only, a server left running costs nothing beyond an idle accept loop, and it stops
 * when the toggle is turned off or the process dies.
 */
object LiveView {

    private var server: LiveServer? = null

    val running: Boolean get() = server?.running == true

    /** Idempotent: enabling twice does not start a second server. */
    fun enable() {
        if (running) return
        RecordingState.liveServerError.value = null
        server = LiveServer().also { it.start() }
        RecordingState.liveViewEnabled.value = true
    }

    fun disable() {
        server?.stop()
        server = null
        RecordingState.liveViewEnabled.value = false
    }

    fun set(enabled: Boolean) = if (enabled) enable() else disable()
}
