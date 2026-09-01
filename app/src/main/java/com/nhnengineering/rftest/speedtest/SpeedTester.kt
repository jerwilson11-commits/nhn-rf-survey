package com.nhnengineering.rftest.speedtest

import android.util.Log
import com.nhnengineering.rftest.model.ThroughputSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

/**
 * Configuration for a throughput test.
 *
 * The server is a setting rather than a constant on purpose. For DAS, Private 5G and CBRS
 * acceptance the meaningful test is usually against a server **on the venue LAN** — testing to the
 * internet measures the client's backhaul and their ISP, not the radio system being accepted.
 * Pointing this at a LAN-hosted LibreSpeed or a laptop running a simple HTTP server is the
 * intended workflow on site; the Cloudflare default is for general drive test.
 */
data class SpeedTestConfig(
    val downloadUrl: String = "https://speed.cloudflare.com/__down?bytes=",
    val uploadUrl: String = "https://speed.cloudflare.com/__up",
    val latencyUrl: String = "https://speed.cloudflare.com/__down?bytes=0",
    val pingHost: String = "speed.cloudflare.com",
    /**
     * Bytes requested per download request.
     *
     * Not arbitrary: Cloudflare's __down endpoint returns HTTP 403 above roughly 25 MB (verified
     * 2026-08-31 — 25 MB returns 200, 100 MB returns 403). Each stream therefore issues repeated
     * requests of this size until the test deadline rather than one huge one.
     */
    val downloadChunkBytes: Long = 25_000_000,
    val downloadStreams: Int = 4,
    val uploadStreams: Int = 3,
    val testDurationMs: Long = 8_000,
    val latencySamples: Int = 12,
) {
    val serverLabel: String
        get() = runCatching { URL(downloadUrl).host }.getOrNull() ?: downloadUrl
}

data class LatencyStats(
    val medianMs: Double?,
    val minMs: Double?,
    val maxMs: Double?,
    val jitterMs: Double?,
    val failed: Int,
)

/**
 * HTTP throughput, latency and jitter; ICMP loss where the OS allows it.
 *
 * Uses [HttpURLConnection] rather than adding OkHttp — this needs raw byte counting and timing,
 * not a feature-rich client, and it keeps the dependency list empty.
 */
class SpeedTester(private val config: SpeedTestConfig = SpeedTestConfig()) {

    private companion object {
        const val TAG = "SpeedTester"

        /**
         * Bytes transferred during this opening window are discarded.
         *
         * TCP slow start means the first second of a transfer is not representative — including it
         * understates throughput on a fast link, and the faster the link the worse the error.
         * Every credible speed test does this; a naive total-bytes/total-time figure is why
         * home-brew testers read low.
         */
        const val RAMP_UP_MS = 1_500L

        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val CHUNK = 64 * 1024
    }

    suspend fun measureLatency(): LatencyStats = withContext(Dispatchers.IO) {
        val samples = mutableListOf<Double>()
        var failed = 0
        repeat(config.latencySamples) {
            val t0 = System.nanoTime()
            val ok = runCatching {
                val c = (URL(config.latencyUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                }
                c.inputStream.use { it.readBytes() }
                c.disconnect()
                true
            }.getOrDefault(false)
            if (ok) samples += (System.nanoTime() - t0) / 1_000_000.0 else failed++
            delay(60)
        }
        if (samples.isEmpty()) return@withContext LatencyStats(null, null, null, null, failed)

        val sorted = samples.sorted()
        // Jitter as mean absolute difference between successive samples — the RFC 3550 idea,
        // computed on ordered arrivals rather than on the sorted list (sorting first would
        // destroy the very variation being measured).
        val jitter = if (samples.size >= 2) {
            samples.zipWithNext { a, b -> abs(b - a) }.average()
        } else null

        LatencyStats(
            medianMs = sorted[sorted.size / 2],
            minMs = sorted.first(),
            maxMs = sorted.last(),
            jitterMs = jitter,
            failed = failed,
        )
    }

    /**
     * Download throughput in Mbps, measured across parallel streams.
     *
     * Parallel streams are not padding: a single TCP stream is limited by window size and RTT
     * (roughly window/RTT), so on a high-bandwidth-delay link one connection cannot fill the pipe
     * regardless of how fast the link is. Real testers use several for this reason.
     */
    suspend fun measureDownload(onProgress: (Double) -> Unit = {}): Double? =
        transfer(config.downloadStreams, onProgress) { counter, deadline ->
            val buf = ByteArray(CHUNK)
            // Repeated bounded requests rather than one huge one — see downloadChunkBytes.
            while (System.currentTimeMillis() < deadline) {
                val url = URL(config.downloadUrl + config.downloadChunkBytes)
                val c = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                }
                try {
                    // Check the status. Omitting this is how a 403 became a silent null instead of
                    // an error naming the cause.
                    val code = c.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        error("download HTTP $code for ${config.downloadChunkBytes} bytes")
                    }
                    c.inputStream.use { input ->
                        while (System.currentTimeMillis() < deadline) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            counter.addAndGet(n.toLong())
                        }
                    }
                } finally {
                    runCatching { c.disconnect() }
                }
            }
        }

    suspend fun measureUpload(onProgress: (Double) -> Unit = {}): Double? =
        transfer(config.uploadStreams, onProgress) { counter, deadline ->
            val c = (URL(config.uploadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                setChunkedStreamingMode(CHUNK)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            try {
                // Random rather than zeroes: a compressing middlebox or CDN would squash a block
                // of zeroes and report a throughput the link cannot actually deliver.
                val buf = ByteArray(CHUNK).also { Random.nextBytes(it) }
                c.outputStream.use { out ->
                    while (System.currentTimeMillis() < deadline) {
                        out.write(buf)
                        counter.addAndGet(buf.size.toLong())
                    }
                }
                val code = runCatching { c.responseCode }.getOrNull()
                if (code != null && code !in 200..299) error("upload HTTP $code")
            } finally {
                runCatching { c.disconnect() }
            }
        }

    /**
     * Runs [streams] concurrent transfers for the configured duration and returns Mbps measured
     * over the post-ramp window only.
     */
    private suspend fun transfer(
        streams: Int,
        onProgress: (Double) -> Unit,
        body: suspend (AtomicLong, Long) -> Unit,
    ): Double? = coroutineScope {
        val counter = AtomicLong(0)
        val start = System.currentTimeMillis()
        val deadline = start + config.testDurationMs

        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val workers = (1..streams).map { n ->
            async(Dispatchers.IO) {
                runCatching { body(counter, deadline) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        // Log every stream failure. Silently swallowing these is what turned an
                        // HTTP 403 into an unexplained null result.
                        Log.w(TAG, "stream $n failed", it)
                        failures += (it.message ?: it::class.java.simpleName)
                    }
            }
        }

        var rampBytes = -1L
        var rampAt = 0L
        while (System.currentTimeMillis() < deadline) {
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
        workers.awaitAll()

        val end = System.currentTimeMillis()
        val total = counter.get()
        if (total <= 0L) {
            failures.firstOrNull()?.let { error(it) }
            return@coroutineScope null
        }
        // If the transfer never got past the ramp window, fall back to the whole window and accept
        // the underestimate rather than returning nothing.
        if (rampBytes < 0) return@coroutineScope mbps(total, end - start)
        mbps(total - rampBytes, end - rampAt)
    }

    private fun mbps(bytes: Long, millis: Long): Double =
        if (millis <= 0) 0.0 else (bytes * 8.0) / (millis / 1000.0) / 1_000_000.0

    /**
     * ICMP packet loss via the system ping binary, or null.
     *
     * Android has no public API for ICMP. `InetAddress.isReachable` falls back to a TCP connect on
     * most devices, which measures something entirely different and would be dishonest to label as
     * packet loss. Executing `/system/bin/ping` is the standard workaround, but SELinux policy
     * blocks it on some builds — hence the null return rather than a fabricated figure.
     *
     * HTTP request failures are deliberately NOT used as a substitute. A failed HTTP request means
     * a failed HTTP request; loss is a link-layer property and reporting one as the other would
     * put a wrong number in an acceptance report.
     */
    suspend fun measureLoss(count: Int = 20): Double? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(20_000) {
            runCatching {
                val proc = ProcessBuilder(
                    "/system/bin/ping", "-c", count.toString(), "-i", "0.2", "-W", "2",
                    config.pingHost,
                ).redirectErrorStream(true).start()

                val text = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
                proc.waitFor()
                // e.g. "20 packets transmitted, 19 received, 5% packet loss, time 3808ms"
                Regex("""(\d+(?:\.\d+)?)%\s*packet loss""").find(text)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
            }.onFailure { Log.w(TAG, "ping unavailable", it) }.getOrNull()
        }
    }

    /** Full sequence. [onStage] reports which phase is running plus a live Mbps figure. */
    suspend fun runAll(onStage: (String, Double?) -> Unit = { _, _ -> }): ThroughputSample {
        return try {
            onStage("Latency", null)
            val latency = measureLatency()

            onStage("Packet loss", null)
            val loss = measureLoss()

            onStage("Download", null)
            val down = measureDownload { onStage("Download", it) }

            onStage("Upload", null)
            val up = measureUpload { onStage("Upload", it) }

            ThroughputSample(
                downloadMbps = down,
                uploadMbps = up,
                latencyMedianMs = latency.medianMs,
                latencyMinMs = latency.minMs,
                latencyMaxMs = latency.maxMs,
                jitterMs = latency.jitterMs,
                lossPct = loss,
                server = config.serverLabel,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "speed test failed", e)
            ThroughputSample(
                null, null, null, null, null, null, null,
                server = config.serverLabel,
                error = e.message ?: e::class.java.simpleName,
            )
        }
    }
}
