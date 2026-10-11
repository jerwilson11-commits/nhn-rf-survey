package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint

/**
 * Mobility & retainability event log + "why did it drop" root-cause, **derived from the recorded
 * samples** (not modem-internal RLF/RACH counters — that is the hardware-gated follow-up; see
 * docs/pro-feature-roadmap.md). The point is what a coverage plot never shows: *where the connection
 * changed or broke, and the likeliest reason.*
 *
 * Events are transitions between consecutive samples (in sequence order):
 *  - **Serving-cell change** — the serving PCI changed (a handover / reselection happened).
 *  - **NR→LTE fallback** — serving dropped from a 5G NR band to LTE.
 *  - **Off FirstNet** — serving left Band 14/n14 for a commercial band (reuses the Track-B detector).
 *  - **Service lost** — had a serving cell, then none.
 *
 * Root-cause (for a service loss) uses the per-sample neighbour list the same way
 * [SessionStats.strongerNeighbours] does at session level: if, at the last good sample, a non-serving
 * neighbour on the **same band** was stronger than the server by [marginDb], the drop is a likely
 * **missed handover** (a better cell was there and unused); otherwise it is a **coverage hole**.
 *
 * Soundness: only transitions between samples that actually had the fields are counted (a Wi-Fi row,
 * all-null, never manufactures a "loss"); counts are exact, the event list is bounded for the report.
 */
object MobilityEvents {

    enum class Kind { SERVING_CELL_CHANGE, NR_FALLBACK, OFF_FIRSTNET, SERVICE_LOST }

    enum class Cause { MISSED_HANDOVER, COVERAGE_HOLE, FIRSTNET_GAP, NR_TO_LTE, RESELECTION, NONE }

    data class Event(
        val kind: Kind,
        val cause: Cause,
        val sequence: Long,
        val atUtcMillis: Long,
        /** Human-readable "from → to" for the report. */
        val detail: String,
        val rsrpDbm: Int?,
        val latitudeDeg: Double?,
        val longitudeDeg: Double?,
        val floorplanId: String?,
        val floorplanX: Float?,
        val floorplanY: Float?,
        val floor: String?,
    )

    data class Summary(
        val analysed: Int,
        val servingCellChanges: Int,
        val nrToLteFallbacks: Int,
        val offFirstNetEvents: Int,
        val serviceLosses: Int,
        val events: List<Event>,
    ) {
        val hasEvents: Boolean get() = events.isNotEmpty()
    }

    private fun hasService(p: TrackPoint) = p.rsrpDbm != null || p.cellBand != null
    private fun isNrBand(band: String?) = band?.startsWith("n", ignoreCase = true) == true
    private fun isLteBand(band: String?) = band?.startsWith("B", ignoreCase = false) == true

    /** True when a non-serving neighbour on the serving band beat the server by [marginDb]. */
    private fun strongerSameBandNeighbour(p: TrackPoint, marginDb: Int): Boolean {
        val servingRsrp = p.rsrpDbm ?: return false
        val servingBand = p.cellBand ?: return false
        return p.cells.any { c ->
            !c.serving && c.pci != p.servingPci && c.band == servingBand &&
                c.rsrpDbm != null && c.rsrpDbm >= servingRsrp + marginDb
        }
    }

    fun analyse(points: List<TrackPoint>, marginDb: Int = 3, maxEvents: Int = 100): Summary {
        val ordered = points.sortedBy { it.sequence }
        val events = ArrayList<Event>()
        var servingChanges = 0
        var nrFallbacks = 0
        var offFn = 0
        var losses = 0
        var prev: TrackPoint? = null

        for (p in ordered) {
            val pr = prev
            if (pr != null) {
                if (pr.servingPci != null && p.servingPci != null && pr.servingPci != p.servingPci) {
                    servingChanges++
                    add(events, maxEvents, Kind.SERVING_CELL_CHANGE, Cause.RESELECTION, p, "PCI ${pr.servingPci} → ${p.servingPci}")
                }
                if (isNrBand(pr.cellBand) && isLteBand(p.cellBand)) {
                    nrFallbacks++
                    add(events, maxEvents, Kind.NR_FALLBACK, Cause.NR_TO_LTE, p, "${pr.cellBand} → ${p.cellBand}")
                }
                if (SessionStats.isFirstNetBand14(pr) && !SessionStats.isFirstNetBand14(p) && hasService(p)) {
                    offFn++
                    add(events, maxEvents, Kind.OFF_FIRSTNET, Cause.FIRSTNET_GAP, p, "FirstNet → ${p.cellBand ?: "commercial"}")
                }
                if (hasService(pr) && !hasService(p)) {
                    losses++
                    val cause = if (strongerSameBandNeighbour(pr, marginDb)) Cause.MISSED_HANDOVER else Cause.COVERAGE_HOLE
                    add(events, maxEvents, Kind.SERVICE_LOST, cause, p, "Lost serving cell")
                }
            }
            prev = p
        }
        return Summary(ordered.size, servingChanges, nrFallbacks, offFn, losses, events)
    }

    private fun add(out: ArrayList<Event>, max: Int, kind: Kind, cause: Cause, p: TrackPoint, detail: String) {
        if (out.size >= max) return
        out += Event(
            kind = kind, cause = cause, sequence = p.sequence, atUtcMillis = p.timestampUtcMillis,
            detail = detail, rsrpDbm = p.rsrpDbm,
            latitudeDeg = p.latitudeDeg, longitudeDeg = p.longitudeDeg,
            floorplanId = p.floorplanId, floorplanX = p.floorplanX, floorplanY = p.floorplanY, floor = p.floor,
        )
    }
}
