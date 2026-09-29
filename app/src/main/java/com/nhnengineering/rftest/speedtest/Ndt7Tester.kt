package com.nhnengineering.rftest.speedtest

import android.util.Log
import com.nhnengineering.rftest.model.ThroughputSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Throughput against M-Lab's NDT7, the measurement Ookla's own methodology is modelled on and the
 * infrastructure many regulators (including the FCC) use for exactly this kind of test.
 *
 * ## Why this exists alongside [SpeedTester]
 *
 * [SpeedTester] pointed at Cloudflare answers "what does the internet look like from here", but
 * Cloudflare's anycast can hand a session to a distant point of presence — on 2026-09-29 that was a
 * ~187 ms median to a server that turned out to be in New York, on a link an iPhone at the same desk
 * measured at 617/47 Mbps over 10 ms to a nearby server. A high-RTT path caps a single-session
 * TCP-ish transfer's ability to fill a fast link regardless of how fast the radio actually is, which
 * makes a distant server look like a slow radio. NDT7's locate service (`locate.measurementlab.net`)
 * answers with the nearest low-latency measurement servers instead of one fixed global endpoint, so
 * this is the low-effort fix for that specific failure mode. [SpeedTester] is not replaced by this —
 * a LAN server on site is still the right instrument for a DAS/Private 5G/CBRS acceptance test, where
 * the internet path (NDT7 included) is deliberately the wrong thing to measure.
 *
 * ## Protocol
 *
 * NDT7 is WebSocket, not plain HTTP — see the ndt7 spec at
 * https://github.com/m-lab/ndt-server/blob/main/spec/ndt7-protocol.md. `java.net.http`'s WebSocket
 * support needs API 34 and this app's minSdk is 31, and hand-rolling RFC 6455 framing for a
 * measurement that reports a number to a client is the wrong place to risk a subtle protocol bug —
 * see `build.gradle.kts` for why OkHttp is the one dependency this project carries for it.
 *
 * Locate returns several candidate servers in preference order; this tries them in order and uses
 * the first one that completes a download. A message on the wire is binary (payload, counted toward
 * throughput) or text (a JSON measurement from the server, logged but not the number this class
 * reports — see [runWebSocketTransfer] for why the client's own byte count is used instead).
 *
 * Latency, jitter and packet loss are measured with one `ping` run against the chosen server's host
 * rather than [SpeedTester]'s repeated zero-byte-download trick: NDT7 has no lightweight HTTP
 * endpoint to hit twelve times, and opening twelve WebSocket handshakes just to time them would cost
 * more than the measurement is worth.
 */
class Ndt7Tester(private val config: Ndt7Config = Ndt7Config()) {

    private companion object {
        const val TAG = "Ndt7Tester"
        const val SUBPROTOCOL = "net.measurementlab.ndt.v7"

        /** Bytes discarded from the front of a transfer -- see [SpeedTester]'s identical constant. */
        const val RAMP_UP_MS = 1_500L

        /** ndt7's recommended sender ramp: start small, double, cap at 1 MiB. */
        const val MIN_MESSAGE_BYTES = 1 shl 13
        const val MAX_MESSAGE_BYTES = 1 shl 20
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Unbounded: this is a long-lived streaming socket for a fixed duration this class
        // controls itself, not a request/response call with a sensible read deadline.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    /** The server actually used, once one has answered -- null before [runAll] picks one. */
    var chosenServer: Ndt7Server? = null
        private set

    suspend fun runAll(onStage: (String, Double?) -> Unit = { _, _ -> }): ThroughputSample {
        return try {
            onStage("Finding nearest server", null)
            val candidates = locate()
            if (candidates.isEmpty()) {
                return ThroughputSample(
                    null, null, null, null, null, null, null,
                    server = "M-Lab NDT7",
                    error = "The locate service returned no candidate servers.",
                )
            }

            var lastError: Throwable? = null
            for (server in candidates) {
                try {
                    onStage("Latency", null)
                    val latency = measureLatencyLoss(server.host)

                    onStage("Download", null)
                    val down = runWebSocketTransfer(server.downloadUrl, isUpload = false, config.testDurationMs) {
                        onStage("Download", it)
                    }

                    onStage("Upload", null)
                    val up = runWebSocketTransfer(server.uploadUrl, isUpload = true, config.testDurationMs) {
                        onStage("Upload", it)
                    }

                    if (down == null && up == null) {
                        error("no bytes transferred against ${server.host}")
                    }

                    chosenServer = server
                    return ThroughputSample(
                        downloadMbps = down,
                        uploadMbps = up,
                        latencyMedianMs = latency.medianMs,
                        latencyMinMs = latency.minMs,
                        latencyMaxMs = latency.maxMs,
                        jitterMs = latency.jitterMs,
                        lossPct = latency.lossPct,
                        server = server.label,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "candidate ${server.host} failed, trying the next", e)
                    lastError = e
                }
            }
            ThroughputSample(
                null, null, null, null, null, null, null,
                server = "M-Lab NDT7",
                error = "Every candidate server failed" +
                    (lastError?.message?.let { ": $it" } ?: "."),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "NDT7 test failed", e)
            ThroughputSample(
                null, null, null, null, null, null, null,
                server = "M-Lab NDT7",
                error = e.message ?: e::class.java.simpleName,
            )
        }
    }

    private suspend fun locate(): List<Ndt7Server> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val c = (URL(config.locateUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
        }
        try {
            val body = c.inputStream.bufferedReader().use { it.readText() }
            parseLocateServers(body)
        } finally {
            runCatching { c.disconnect() }
        }
    }

    /**
     * One `ping` run doing the work [SpeedTester.measureLatency] and [SpeedTester.measureLoss] do
     * separately, against the NDT7 server's own host rather than a fixed endpoint.
     */
    private suspend fun measureLatencyLoss(host: String): LatencyLoss = kotlinx.coroutines.withContext(Dispatchers.IO) {
        withTimeoutOrNull(20_000) {
            runCatching {
                val proc = ProcessBuilder(
                    "/system/bin/ping", "-c", config.pingCount.toString(), "-i", "0.2", "-W", "2", host,
                ).redirectErrorStream(true).start()
                val text = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
                proc.waitFor()
                parsePingOutput(text)
            }.getOrDefault(LatencyLoss(null, null, null, null, null))
        } ?: LatencyLoss(null, null, null, null, null)
    }

    /**
     * Streams [durationMs] worth of binary frames over a WebSocket in [direction], counting bytes
     * client-side rather than trusting the server's own periodic JSON measurement message.
     *
     * The ndt7 spec's reference clients prefer the *receiver's* count for exactly the direction that
     * receiver is measuring -- a downloader trusts its own byte count, an uploader can additionally
     * cross-check against what the server says it received. This class only ever trusts its own
     * count, on both directions, which for upload is an upper bound (bytes handed to the socket, not
     * confirmed delivered) rather than the server's authoritative figure. That mirrors exactly what
     * [SpeedTester.measureUpload] already does against Cloudflare, so the two testers stay
     * comparable rather than one being quietly more honest than the other.
     */
    private suspend fun runWebSocketTransfer(
        url: String,
        isUpload: Boolean,
        durationMs: Long,
        onProgress: (Double) -> Unit,
    ): Double? = coroutineScope {
        val counter = AtomicLong(0)
        val closed = CompletableDeferred<Throwable?>()

        val request = Request.Builder()
            .url(url)
            .addHeader("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isUpload) counter.addAndGet(bytes.size.toLong())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // The server's own JSON measurement -- see the class doc for why this reports its
                // own byte count instead.
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed.isCompleted) closed.complete(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed.isCompleted) closed.complete(null)
            }
        }

        val webSocket = client.newWebSocket(request, listener)

        val uploadJob = if (isUpload) {
            launch(Dispatchers.IO) {
                var size = MIN_MESSAGE_BYTES
                val deadline = System.currentTimeMillis() + durationMs
                while (isActive && System.currentTimeMillis() < deadline) {
                    val payload = ByteArray(size).also { Random.nextBytes(it) }
                    if (webSocket.send(payload.toByteString())) {
                        counter.addAndGet(size.toLong())
                        size = nextNdt7MessageSize(size)
                    } else {
                        // Outbound queue is full -- back off rather than spinning against it.
                        delay(20)
                    }
                }
            }
        } else {
            null
        }

        val start = System.currentTimeMillis()
        val deadline = start + durationMs
        var rampBytes = -1L
        var rampAt = 0L
        while (System.currentTimeMillis() < deadline && !closed.isCompleted) {
            delay(200)
            val now = System.currentTimeMillis()
            val bytes = counter.get()
            if (rampBytes < 0 && now - start >= RAMP_UP_MS) {
                rampBytes = bytes
                rampAt = now
            }
            if (rampBytes >= 0 && now > rampAt) {
                onProgress(mbps(bytes - rampBytes, now - rampAt))
            }
        }

        uploadJob?.cancelAndJoin()
        webSocket.close(1000, null)
        withTimeoutOrNull(2_000) { closed.await() }

        val end = System.currentTimeMillis()
        val total = counter.get()
        if (total <= 0L) return@coroutineScope null
        if (rampBytes < 0) return@coroutineScope mbps(total, end - start)
        mbps(total - rampBytes, end - rampAt)
    }

    private fun mbps(bytes: Long, millis: Long): Double =
        if (millis <= 0) 0.0 else (bytes * 8.0) / (millis / 1000.0) / 1_000_000.0
}

data class Ndt7Config(
    val locateUrl: String = "https://locate.measurementlab.net/v2/nearest/ndt/ndt7",
    val testDurationMs: Long = 10_000,
    val pingCount: Int = 10,
)

data class Ndt7Server(
    val machine: String,
    val city: String?,
    val downloadUrl: String,
    val uploadUrl: String,
) {
    val host: String get() = hostOf(downloadUrl) ?: machine
    val label: String get() = city?.let { "$machine ($it)" } ?: machine
}

internal data class LatencyLoss(
    val medianMs: Double?,
    val minMs: Double?,
    val maxMs: Double?,
    val jitterMs: Double?,
    val lossPct: Double?,
)

/** `wss://host/path?query` -> `host`. Not `java.net.URL`: it has no handler for the `wss` scheme. */
internal fun hostOf(wssUrl: String): String? =
    Regex("""wss://([^/:?]+)""").find(wssUrl)?.groupValues?.get(1)

/**
 * Parses a `locate.measurementlab.net` v2 response into the candidates worth trying, in the order
 * the service returned them -- that order is already nearest-first.
 *
 * A candidate missing either WebSocket URL is dropped rather than kept with a null: a download-only
 * or upload-only NDT7 server is not a server this class can use, and carrying it forward would only
 * surface as a confusing failure two network calls later.
 */
internal fun parseLocateServers(json: String): List<Ndt7Server> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val results = root.optJSONArray("results") ?: return emptyList()
    return buildList {
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val urls = r.optJSONObject("urls") ?: continue
            val download = urls.optString("wss:///ndt/v7/download").takeIf { it.isNotBlank() } ?: continue
            val upload = urls.optString("wss:///ndt/v7/upload").takeIf { it.isNotBlank() } ?: continue
            val machine = r.optString("machine").takeIf { it.isNotBlank() } ?: hostOf(download) ?: continue
            val city = r.optJSONObject("location")?.optString("city")?.takeIf { it.isNotBlank() }
            add(Ndt7Server(machine = machine, city = city, downloadUrl = download, uploadUrl = upload))
        }
    }
}

/** The next ndt7 sender message size: double, capped at 1 MiB. Pure so the ramp shape is pinned. */
internal fun nextNdt7MessageSize(current: Int): Int =
    (current * 2).coerceAtMost(1 shl 20)

/**
 * Parses `ping -c N`'s own output for every round-trip time and the summary loss line, rather than
 * opening N separate connections the way [SpeedTester.measureLatency] does over HTTP.
 */
internal fun parsePingOutput(text: String): LatencyLoss {
    val samples = Regex("""time[=<]([\d.]+)\s*ms""").findAll(text)
        .mapNotNull { it.groupValues[1].toDoubleOrNull() }
        .toList()
    val loss = Regex("""(\d+(?:\.\d+)?)%\s*packet loss""").find(text)
        ?.groupValues?.get(1)?.toDoubleOrNull()
    if (samples.isEmpty()) return LatencyLoss(null, null, null, null, loss)
    val sorted = samples.sorted()
    val jitter = if (samples.size >= 2) samples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average() else null
    return LatencyLoss(
        medianMs = sorted[sorted.size / 2],
        minMs = sorted.first(),
        maxMs = sorted.last(),
        jitterMs = jitter,
        lossPct = loss,
    )
}
