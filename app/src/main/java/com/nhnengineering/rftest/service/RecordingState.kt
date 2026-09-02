package com.nhnengineering.rftest.service

import com.nhnengineering.rftest.model.Breach
import com.nhnengineering.rftest.model.CellularSample
import com.nhnengineering.rftest.model.GeoPoint
import com.nhnengineering.rftest.model.IndoorPosition
import com.nhnengineering.rftest.model.ThroughputSample
import com.nhnengineering.rftest.model.Thresholds
import com.nhnengineering.rftest.model.WifiSample
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared state between [RecordingService] and the UI.
 *
 * A process-scoped object rather than a bound service or a DI graph. Both of those are defensible;
 * this is the smallest thing that works, and the lifetimes genuinely are process-scoped — a
 * recording outlives every Activity and Composable in the app, which is the entire point of moving
 * it into a service.
 *
 * The service is the only writer for everything except [thresholds] and [pendingThroughput], which
 * the UI sets and the service consumes.
 */
object RecordingState {

    /** True while a session is being written. */
    val active = MutableStateFlow(false)

    /** Live values, published by the service so the dashboard renders from one source. */
    val wifi = MutableStateFlow<WifiSample?>(null)
    val cellular = MutableStateFlow<CellularSample?>(null)
    val fix = MutableStateFlow<GeoPoint?>(null)

    val rowCount = MutableStateFlow(0L)
    val elapsedMs = MutableStateFlow(0L)
    val distanceM = MutableStateFlow(0.0)
    val fixesWithVelocity = MutableStateFlow(0L)
    val fixesWithoutVelocity = MutableStateFlow(0L)

    val lastSavedFile = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    /** Breaches on the most recent sample. Empty when thresholds are off or nothing is breached. */
    val breaches = MutableStateFlow<List<Breach>>(emptyList())

    /** Set by the UI, read by the service on each sample. */
    val thresholds = MutableStateFlow(Thresholds())

    /**
     * Operator-placed indoor position. Sticky by design: set by tapping the floorplan and carried
     * by every subsequent sample until moved or cleared, because a thirty-second dwell should
     * produce thirty located samples rather than one located sample and twenty-nine orphans.
     */
    val indoorPosition = MutableStateFlow<IndoorPosition?>(null)

    /**
     * Operator-set area label, applied to every subsequent sample until changed or cleared.
     *
     * Sticky for the same reason [indoorPosition] is: an operator marks "Indoor" once on the way
     * in, not once per sample. It needs no floorplan, which is the point — it is what makes the
     * report's per-area breakdown reachable on an ordinary GPS walk.
     */
    val areaLabel = MutableStateFlow<String?>(null)

    /**
     * Current floor, applied to every sample until changed.
     *
     * Sticky like [areaLabel], and for the same reason: an operator marks the floor once on
     * stepping out of the lift, not once per sample.
     */
    val floor = MutableStateFlow<String?>(null)

    /**
     * Positions logged this session, paired with the serving KPI colour recorded there, for
     * drawing on the floorplan. Capped so a long session cannot grow this without bound — the
     * authoritative record is the CSV, this is only what the plan draws.
     */
    val placedPositions = MutableStateFlow<List<Pair<IndoorPosition, Int?>>>(emptyList())

    /** True while a walk throughput burst is transferring, so the UI can say the radio is loaded. */
    val throughputBusy = MutableStateFlow(false)

    /**
     * True when the last burst was refused for sending too many requests.
     *
     * Surfaced separately from a failure because it is not one: it says nothing about the network
     * under test, and reporting it as a coverage finding would be wrong.
     */
    val throughputRateLimited = MutableStateFlow(false)

    /** Most recent walk throughput result, for the live display. */
    val lastThroughput = MutableStateFlow<ThroughputSample?>(null)

    /** Set by the UI before recording starts; read by the service when it launches the burst loop. */
    val walkThroughputEnabled = MutableStateFlow(false)

    /**
     * Throughput endpoint, shared by the one-off speed test and the walk bursts.
     *
     * Previously the URL lived in Compose state on the dashboard, so the walk bursts silently used
     * the default public endpoint no matter what the operator had typed. On a venue engagement
     * that means measuring the internet when the operator believed they were measuring the LAN.
     */
    val speedTestBaseUrl = MutableStateFlow<String?>(null)

    /** Set by the UI; the service starts and stops the loopback live-view server to match. */
    val liveViewEnabled = MutableStateFlow(false)

    /** Non-null when the live-view server could not bind, so the failure is visible rather than
     *  presenting as a laptop that simply never connects. */
    val liveServerError = MutableStateFlow<String?>(null)

    /**
     * Recent GPS-located samples, for the live map on a connected laptop.
     *
     * Capped and thinned rather than unbounded: this is a display aid, and the CSV remains the
     * authoritative record. A viewer that joins late still receives the whole retained trail, which
     * is the point — the operator needs to see where they have already walked in order not to walk
     * it twice.
     */
    val liveTrack = MutableStateFlow<List<LiveFix>>(emptyList())

    /**
     * Handed over by the UI when a speed test finishes; consumed by the service on its next tick.
     *
     * Routed through the service rather than written directly so the file has exactly one writer
     * and rows cannot interleave.
     */
    val pendingThroughput = MutableStateFlow<ThroughputSample?>(null)

    /** One point on the live trail. Deliberately flat and small — it is serialised per poll. */
    data class LiveFix(
        val lat: Double,
        val lon: Double,
        val rsrpDbm: Int?,
        val rssiDbm: Int?,
        val timestampUtcMillis: Long,
    )

    fun resetCounters() {
        rowCount.value = 0
        elapsedMs.value = 0
        distanceM.value = 0.0
        fixesWithVelocity.value = 0
        fixesWithoutVelocity.value = 0
        breaches.value = emptyList()
        error.value = null
        placedPositions.value = emptyList()
        areaLabel.value = null
        floor.value = null
        liveTrack.value = emptyList()
        lastThroughput.value = null
        throughputBusy.value = false
    }
}
