package com.nhnengineering.rftest.speedtest

import android.util.Log
import com.nhnengineering.rftest.model.ThroughputSample
import com.nhnengineering.rftest.service.RecordingState
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
class WalkThroughput(
    private val config: Config = Config(),
    private val tester: SpeedTester = SpeedTester(
        SpeedTestConfig(
            testDurationMs = config.burstMs,
            downloadStreams = config.streams,
            uploadStreams = config.streams,
        ),
    ),
) {

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
        /** Measure upload as well as download. Upload costs the same time again. */
        val includeUpload: Boolean = true,
    )

    private var job: Job? = null

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
                delay(config.intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runBurst() {
        RecordingState.throughputBusy.value = true
        try {
            val down = runCatching { tester.measureDownload() }
                .onFailure { Log.w(TAG, "download burst failed", it) }
                .getOrNull()
            val up = if (config.includeUpload) {
                runCatching { tester.measureUpload() }
                    .onFailure { Log.w(TAG, "upload burst failed", it) }
                    .getOrNull()
            } else {
                null
            }

            // A burst where both directions failed is recorded as a failure, not skipped. A gap in
            // the throughput series where the network was unusable is a finding; an absent row
            // reads as "not tested here", which is a different and much weaker statement.
            val sample = ThroughputSample(
                downloadMbps = down,
                uploadMbps = up,
                latencyMedianMs = null,
                latencyMinMs = null,
                latencyMaxMs = null,
                jitterMs = null,
                lossPct = null,
                server = tester.serverLabel,
                error = if (down == null && (up == null && config.includeUpload)) {
                    "both directions failed"
                } else {
                    null
                },
            )
            RecordingState.pendingThroughput.value = sample
            RecordingState.lastThroughput.value = sample
        } finally {
            RecordingState.throughputBusy.value = false
        }
    }

    companion object {
        private const val TAG = "WalkThroughput"
    }
}
