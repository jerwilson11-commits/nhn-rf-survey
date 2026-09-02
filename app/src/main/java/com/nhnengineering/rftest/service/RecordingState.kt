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
     * Positions logged this session, paired with the serving KPI colour recorded there, for
     * drawing on the floorplan. Capped so a long session cannot grow this without bound — the
     * authoritative record is the CSV, this is only what the plan draws.
     */
    val placedPositions = MutableStateFlow<List<Pair<IndoorPosition, Int?>>>(emptyList())

    /**
     * Handed over by the UI when a speed test finishes; consumed by the service on its next tick.
     *
     * Routed through the service rather than written directly so the file has exactly one writer
     * and rows cannot interleave.
     */
    val pendingThroughput = MutableStateFlow<ThroughputSample?>(null)

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
    }
}
