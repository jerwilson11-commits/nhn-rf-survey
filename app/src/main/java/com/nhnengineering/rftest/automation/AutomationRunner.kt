package com.nhnengineering.rftest.automation

import android.util.Log
import com.nhnengineering.rftest.speedtest.SpeedTester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** One probe an [AutomationRunner] can execute, in the order a script lists them. */
sealed interface AutomationStep {
    val label: String

    data class Ping(val host: String, val count: Int = 4) : AutomationStep {
        override val label get() = "Ping $host"
    }

    data class HttpGet(val url: String) : AutomationStep {
        override val label get() = "HTTP GET $url"
    }

    data class Download(val config: com.nhnengineering.rftest.speedtest.SpeedTestConfig) : AutomationStep {
        override val label get() = "Download (${config.serverLabel})"
    }

    data class Upload(val config: com.nhnengineering.rftest.speedtest.SpeedTestConfig) : AutomationStep {
        override val label get() = "Upload (${config.serverLabel})"
    }

    data class Ftp(val config: FtpConfig) : AutomationStep {
        override val label get() = "FTP upload to ${config.host}"
    }
}

data class AutomationStepResult(
    val label: String,
    val success: Boolean,
    /** A short human-readable outcome -- "loss 0%, avg 12 ms", "HTTP 204 in 88ms", or the error. */
    val detail: String,
    val elapsedMs: Long,
    val timestampUtcMillis: Long,
)

data class AutomationScript(val steps: List<AutomationStep>, val intervalMs: Long = 30_000)

/**
 * Runs a script of network probes -- ping, an HTTP GET, throughput, an FTP upload -- in order, once
 * or on a loop, and reports every step's own result rather than stopping at the first failure: the
 * point of a multi-step script is to say *which* of several things is broken, and a script that
 * aborts on step one can never answer that for steps two through four.
 *
 * Deliberately standalone rather than tied to [com.nhnengineering.rftest.service.RecordingState] the
 * way [com.nhnengineering.rftest.speedtest.WalkThroughput] is: this is a health-check tool an
 * operator points at whatever an engagement needs and reads live, not a per-sample series destined
 * for the session CSV/PDF. Wiring a script's mixed step types (a ping RTT, an HTTP status, a
 * throughput figure, an FTP figure) into that pipeline is real design work of its own -- the report
 * schema, the map overlay, the PDF section -- and is deliberately left for when a specific engagement
 * actually needs it rather than guessed at now.
 */
class AutomationRunner {

    private companion object {
        const val TAG = "AutomationRunner"
        const val PING_TIMEOUT_S = 15L
        const val HTTP_TIMEOUT_MS = 8_000
    }

    private var job: Job? = null
    val running: Boolean get() = job?.isActive == true

    suspend fun runOnce(script: AutomationScript): List<AutomationStepResult> =
        script.steps.map { runStep(it) }

    /** Starts the loop on [scope]; [onResults] is called after every full pass. */
    fun start(scope: CoroutineScope, script: AutomationScript, onResults: (List<AutomationStepResult>) -> Unit) {
        if (running) return
        job = scope.launch {
            while (isActive) {
                onResults(runOnce(script))
                delay(script.intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runStep(step: AutomationStep): AutomationStepResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val detail = when (step) {
                is AutomationStep.Ping -> ping(step.host, step.count)
                is AutomationStep.HttpGet -> httpGet(step.url)
                is AutomationStep.Download -> {
                    val mbps = SpeedTester(step.config).measureDownload()
                        ?: error("no bytes received")
                    "%.2f Mbps".format(mbps)
                }
                is AutomationStep.Upload -> {
                    val mbps = SpeedTester(step.config).measureUpload()
                        ?: error("no bytes sent")
                    "%.2f Mbps".format(mbps)
                }
                is AutomationStep.Ftp -> "%.2f Mbps".format(FtpClient(step.config).upload())
            }
            AutomationStepResult(step.label, true, detail, System.currentTimeMillis() - start, start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${step.label} failed", e)
            AutomationStepResult(
                step.label, false, e.message ?: e::class.java.simpleName,
                System.currentTimeMillis() - start, start,
            )
        }
    }

    private fun ping(host: String, count: Int): String {
        val p = ProcessBuilder("/system/bin/ping", "-c", count.toString(), "-W", "2", host)
            .redirectErrorStream(true).start()
        val text = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        if (!p.waitFor(PING_TIMEOUT_S, TimeUnit.SECONDS)) p.destroyForcibly()
        return formatPingSummary(text)
            ?: throw IllegalStateException("ping produced no result (no permission, or host unreachable)")
    }

    private fun httpGet(url: String): String {
        val start = System.currentTimeMillis()
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            val code = c.responseCode
            val elapsed = System.currentTimeMillis() - start
            if (code !in 200..399) throw IllegalStateException("HTTP $code")
            return "HTTP $code in ${elapsed}ms"
        } finally {
            runCatching { c.disconnect() }
        }
    }
}

/**
 * "loss 0%, avg 12.3 ms" from raw `ping -c N` output, or null if it carries no packet-loss summary
 * at all -- the one line every implementation of `ping` prints whether the host answered or not.
 */
internal fun formatPingSummary(pingOutput: String): String? {
    val loss = Regex("""(\d+(?:\.\d+)?)%\s*packet loss""").find(pingOutput)?.groupValues?.get(1)
        ?: return null
    val avg = Regex("""=\s*[\d.]+/([\d.]+)/""").find(pingOutput)?.groupValues?.get(1)
    return "loss $loss%, avg ${avg ?: "?"} ms"
}
