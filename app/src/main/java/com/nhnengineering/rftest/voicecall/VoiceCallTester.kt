package com.nhnengineering.rftest.voicecall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Call-state voice KPIs: setup time and a dropped-call heuristic, from a real placed call. Not
 * MOS/POLQA audio-quality scoring -- see the roadmap note this responds to: those are two
 * different features wearing the same "voice testing" label, and this is specifically the
 * call-*state* one (CSSR, dropped calls, setup time), the one RantCell's own pricing shows as
 * baseline-expected rather than a premium extra.
 *
 * ## This places a real call
 *
 * `ACTION_CALL`, not `ACTION_DIAL` -- it dials without the operator tapping Call. The default
 * target is the SIM's own voicemail number ([TelephonyManager.getVoiceMailNumber]), which is
 * carrier-agnostic and disturbs no one; a custom number is the operator's own choice, the same
 * way the automation engine's ping/HTTP/FTP targets are.
 *
 * ## What Android's call state actually tells us
 *
 * `TelephonyManager`'s call state is a 3-value model (IDLE / RINGING / OFFHOOK) with no separate
 * "answered" signal for an outgoing call -- OFFHOOK covers dialing, ringing-out and connected
 * alike. So "setup time" here is dial-to-OFFHOOK, a real and useful figure, but it is not the
 * same thing as "time until the far end answered" the way a full CSSR KPI from network signaling
 * would report. Stated plainly rather than overclaimed.
 *
 * ## Ending the call
 *
 * [TelecomManager.endCall] needs [Manifest.permission.MODIFY_PHONE_STATE] (already declared for
 * [com.nhnengineering.rftest.cellular.TechnologyLockController]) and is deprecated in the
 * platform in favour of `InCallService`, which this app does not implement -- there is no plan
 * to become the default dialer for one test card. Whether the already-privileged install can
 * still call it was not confirmed before writing this, so the attempt is wrapped and its result
 * reported rather than assumed: if it fails, the call is left running and the result says so
 * plainly rather than silently leaving the operator to notice a live call on their own.
 */
class VoiceCallTester(private val context: Context) {

    suspend fun run(config: VoiceCallConfig, onStage: (String) -> Unit = {}): VoiceCallResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return VoiceCallResult.failed("Call permission is not granted.")
        }
        if (config.number.isBlank()) {
            return VoiceCallResult.failed("No number to call. Enter one, or this SIM has no voicemail number to default to.")
        }
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return VoiceCallResult.failed("No telephony service on this device.")

        var offhookAt: Long? = null
        var idleAgainAt: Long? = null
        val reachedOffhook = CompletableDeferred<Unit>()
        val returnedToIdle = CompletableDeferred<Unit>()

        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val now = SystemClock.elapsedRealtime()
                when {
                    state == TelephonyManager.CALL_STATE_OFFHOOK && offhookAt == null -> {
                        offhookAt = now
                        if (!reachedOffhook.isCompleted) reachedOffhook.complete(Unit)
                    }
                    state == TelephonyManager.CALL_STATE_IDLE && offhookAt != null && idleAgainAt == null -> {
                        idleAgainAt = now
                        if (!returnedToIdle.isCompleted) returnedToIdle.complete(Unit)
                    }
                }
            }
        }

        val executor = ContextCompat.getMainExecutor(context)
        telephony.registerTelephonyCallback(executor, callback)
        try {
            onStage("Dialing")
            val dialAt = SystemClock.elapsedRealtime()
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", config.number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { return VoiceCallResult.failed("Could not place the call: ${it.message}") }

            val connected = withTimeoutOrNull(config.setupTimeoutMs) { reachedOffhook.await() } != null
            if (!connected) {
                return VoiceCallResult.failed(
                    "No answer within ${config.setupTimeoutMs / 1000}s (or the call failed immediately).",
                )
            }
            val setupTimeMs = offhookAt!! - dialAt
            onStage("Connected")

            withTimeoutOrNull(config.observeDurationMs) { returnedToIdle.await() }

            var autoHangupSucceeded: Boolean? = null
            if (idleAgainAt == null) {
                onStage("Ending call")
                autoHangupSucceeded = endCall()
                if (autoHangupSucceeded) withTimeoutOrNull(5_000) { returnedToIdle.await() }
            }

            val callDurationMs = (idleAgainAt ?: SystemClock.elapsedRealtime()) - offhookAt!!
            return VoiceCallResult(
                setupSucceeded = true,
                setupTimeMs = setupTimeMs,
                callDurationMs = callDurationMs,
                possibleDrop = isPossibleDrop(callDurationMs),
                autoHangupSucceeded = autoHangupSucceeded,
                error = null,
            )
        } finally {
            telephony.unregisterTelephonyCallback(callback)
        }
    }

    private fun endCall(): Boolean = runCatching {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        @Suppress("DEPRECATION")
        telecom.endCall()
    }.getOrDefault(false)
}

data class VoiceCallConfig(
    val number: String,
    val setupTimeoutMs: Long = 20_000,
    /** How long to let the call run once connected before this attempts to end it. */
    val observeDurationMs: Long = 8_000,
)

data class VoiceCallResult(
    val setupSucceeded: Boolean,
    val setupTimeMs: Long?,
    val callDurationMs: Long?,
    val possibleDrop: Boolean,
    /** Null when never attempted (the call had already ended on its own by then). */
    val autoHangupSucceeded: Boolean?,
    val error: String?,
) {
    companion object {
        fun failed(reason: String) = VoiceCallResult(
            setupSucceeded = false, setupTimeMs = null, callDurationMs = null,
            possibleDrop = false, autoHangupSucceeded = null, error = reason,
        )
    }
}

/**
 * A call that reached OFFHOOK and then returned to IDLE within a couple of seconds is more
 * likely a failed/dropped attempt than a real voicemail interaction -- greetings and prompts
 * essentially never finish that fast. A heuristic, not a network-diagnostic signal, and named as
 * one: `possibleDrop`, not `dropped`.
 */
internal fun isPossibleDrop(callDurationMs: Long, minExpectedMs: Long = 2_000): Boolean =
    callDurationMs in 1 until minExpectedMs
