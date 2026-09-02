package com.nhnengineering.rftest.speedtest

import android.util.Log
import com.nhnengineering.rftest.model.ThroughputSample
import com.nhnengineering.rftest.service.RecordingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Repeated throughput measurement during a recording session.
 *
 * ## Why bursts rather than continuous load
 *
 * A commercial drive-test tool will often saturate the link for the whole run. That is defensible
 * when the question is "what does this network deliver under load", but it has two costs that
 * matter here: it consumes a great deal of data on a metered SIM, and it keeps the radio loaded
 * continuously, so **every other KPI in the session is then measured under load** rather than under
 * the conditions a normal user would see. RSRP is unaffected by that, but SINR, RSRQ and the
 * serving-cell choice are not.
 *
 * So the default is a short burst on a fixed interval. Between bursts the radio is idle and the RF
 * measurements are ordinary. The interval and burst length are both configurable, and setting a
 * long burst with a short interval gets you back to near-continuous load if that is what a
 * particular engagement needs.
 *
 * ## What the resulting number is, and is not
 *
 * Throughput to a public endpoint measures the whole path — radio, transport, backhaul, peering and
 * the far server. It is **not** a measurement of the radio system alone, and on a venue walk that
 * distinction decides whether a slow reading is the client's problem or the carrier's. Point the
 * server at a LAN host on site whenever one exists.
 *
 * ## Position smear
 *
 * A burst takes several seconds, during which the tester keeps walking. The sample is written on
 * the row where the burst *finished*, so its position is the end of the burst rather than its
 * middle — at normal walking pace a down-and-up pair covers roughly ten metres of ground. That is
 * recorded in the report's methodology rather than smoothed over, because a throughput point
 * plotted on a map looks exactly as precise as an RSRP point beside it, and it is not.
 */
class WalkThroughput(private val config: Config = Config()) {

    /**
     * Built per burst so the operator's endpoint is honoured.
     *
     * The endpoint used to be captured once at construction from the defaults, which meant a LAN
     * server typed into the throughput card was used by the one-off test and ignored by the walk.
     */
    private fun tester(): SpeedTester {
        val base = RecordingState.speedTestBaseUrl.value?.takeIf { it.isNotBlank() }
            ?: SpeedTestConfig().downloadUrl
        val resolved = SpeedTestConfig.fromDownloadUrl(base)
        return SpeedTester(
            resolved.copy(
                testDurationMs = config.burstMs,
                downloadStreams = config.downloadStreams,
                uploadStreams = config.streams,
            ),
        )
    }


    data class Config(
        /**
         * Idle time between the end of one burst and the start of the next.
         *
         * Not the period. A burst runs down then up, so the observed spacing is this plus roughly
         * twice [burstMs] — measured at 38-47 s with these defaults, against a 30 s setting.
         */
        val intervalMs: Long = 30_000,
        /** Duration of each direction's transfer. Down and up run separately, so a burst is ~2x. */
        val burstMs: Long = 4_000,
        /**
         * Parallel streams per direction. Fewer than the one-off speed test uses: the goal here is
         * a representative reading at many points, not the highest number the link can produce.
         */
        val streams: Int = 2,
        /**
         * Download streams, kept to one.
         *
         * Each stream issues repeated bounded requests, so stream count multiplies request volume
         * against the endpoint. On the 2026-09-02 walk two streams over eight bursts was enough to
         * earn an HTTP 429 from the public endpoint, and every download in the session failed.
         */
        val downloadStreams: Int = 1,
        /** Extra idle time added after a rate-limited burst, doubling up to a ceiling. */
        val backoffStepMs: Long = 60_000,
        val maxBackoffMs: Long = 300_000,
        /** Measure upload as well as download. Upload costs the same time again. */
        val includeUpload: Boolean = true,
    )

    private var job: Job? = null
    private var backoffMs: Long = 0

    val running: Boolean get() = job?.isActive == true

    /**
     * Starts the burst loop on [scope].
     *
     * The first burst runs immediately rather than after one interval, so a short walk still
     * produces at least one reading and the operator gets confirmation that it works before
     * committing to the route.
     */
    fun start(scope: CoroutineScope) {
        if (running) return
        job = scope.launch {
            while (isActive && RecordingState.active.value) {
                runBurst()
                delay(config.intervalMs + backoffMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Runs one burst.
     *
     * Cancellation is rethrown rather than recorded. A burst still transferring when the operator
     * presses Stop is not a network fault, and the first build wrote it into the data as one --
     * the last row of a session read "down: StandaloneCoroutine was cancelled", which in a client
     * report is a failure at the wrong address and an unreadable one at that.
     */
    private suspend fun runBurst() {
        RecordingState.throughputBusy.value = true
        val tester = tester()
        val problems = mutableListOf<String>()
        var rateLimited = false
        try {
            val down = runCatching { tester.measureDownload() }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "download burst failed", it)
                    if (it is SpeedTester.RateLimited) rateLimited = true
                    problems += "down: " + (it.message ?: it::class.java.simpleName)
                }
                .getOrNull()

            val up = if (config.includeUpload) {
                runCatching { tester.measureUpload() }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "upload burst failed", it)
                        if (it is SpeedTester.RateLimited) rateLimited = true
                        problems += "up: " + (it.message ?: it::class.java.simpleName)
                    }
                    .getOrNull()
            } else {
                null
            }

            // Every failure is recorded, including a partial one. The first version only set this
            // when *both* directions failed, so a walk whose downloads were all rejected wrote
            // eight upload-only rows with nothing to explain the gap -- which in a client report
            // reads as "not measured here" rather than "the endpoint refused us".
            val sample = ThroughputSample(
                downloadMbps = down,
                uploadMbps = up,
                latencyMedianMs = null,
                latencyMinMs = null,
                latencyMaxMs = null,
                jitterMs = null,
                lossPct = null,
                server = tester.serverLabel,
                error = problems.joinToString("; ").ifBlank { null },
            )
            RecordingState.pendingThroughput.value = sample
            RecordingState.lastThroughput.value = sample

            // Back off when the endpoint is throttling us, rather than hammering it for the rest
            // of the walk and filling the session with failures that say nothing about the venue.
            backoffMs = if (rateLimited) {
                (if (backoffMs == 0L) config.backoffStepMs else backoffMs * 2)
                    .coerceAtMost(config.maxBackoffMs)
            } else {
                0
            }
            RecordingState.throughputRateLimited.value = rateLimited
        } finally {
            RecordingState.throughputBusy.value = false
        }
    }

    companion object {
        private const val TAG = "WalkThroughput"
    }
}
