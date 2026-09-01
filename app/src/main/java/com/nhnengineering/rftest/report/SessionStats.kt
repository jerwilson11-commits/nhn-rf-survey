package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.model.RsrpBucket
import com.nhnengineering.rftest.model.RssiBucket
import com.nhnengineering.rftest.session.SessionSummary
import com.nhnengineering.rftest.session.TrackPoint
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Statistics for a recorded session, used by the PDF report.
 *
 * **On the duplication with `mcp-server/session_store.py`:** the same analysis exists in Python
 * because the MCP server runs off-device and cannot call into the app, and the app cannot reach the
 * server while standing in a basement. Two implementations of the same maths is a real cost and it
 * is accepted deliberately rather than overlooked.
 *
 * What keeps them honest: the bucket thresholds are **not** duplicated — they live in `RssiBucket`
 * and `RsrpBucket` and the Python mirrors those constants explicitly. And the tests below pin this
 * implementation to hand-computed values, so drift shows up as a failing test rather than as two
 * documents quoting different numbers at a client.
 */
object SessionStats {

    /** Which KPI a session is judged on. */
    enum class Kpi(val label: String, val defaultThresholdDbm: Int) {
        /** Wi-Fi RSSI. −75 dBm is a common design target; −67 where voice or roaming matters. */
        WIFI_RSSI("Wi-Fi RSSI", -75),

        /**
         * Cellular RSRP. The default is an order of magnitude below the Wi-Fi figure, which is not
         * a typo: −75 dBm RSRP would fail essentially every cellular sample ever recorded.
         */
        CELL_RSRP("Cellular RSRP", -105),
    }

    data class Stats(
        val samples: Int,
        val missing: Int,
        val min: Int?,
        val p10: Int?,
        val median: Int?,
        val p90: Int?,
        val max: Int?,
        val mean: Double?,
    )

    data class Hole(
        val samples: Int,
        val startSeq: Long,
        val endSeq: Long,
        val worstDbm: Int,
        val durationS: Double?,
        val waypoint: String?,
        val lat: Double?,
        val lon: Double?,
        val floorplanId: String?,
        val floorplanX: Float?,
        val floorplanY: Float?,
    )

    data class Report(
        val kpi: Kpi,
        val thresholdDbm: Int,
        val stats: Stats,
        val measured: Int,
        val failing: Int,
        val compliancePct: Double,
        val bucketCounts: List<Pair<String, Int>>,
        val holes: List<Hole>,
        val throughputRows: List<TrackPoint>,
    )

    fun kpiFor(points: List<TrackPoint>): Kpi =
        if (points.any { it.rsrpDbm != null }) Kpi.CELL_RSRP else Kpi.WIFI_RSSI

    private fun TrackPoint.value(kpi: Kpi): Int? =
        if (kpi == Kpi.CELL_RSRP) rsrpDbm else rssiDbm

    /**
     * Linear-interpolation percentile, rounded to the nearest dB.
     *
     * Percentiles rather than a mean alone because a mean hides the tail, and the tail is where
     * coverage arguments are actually won: a mean of −62 dBm says nothing about the 5% of a venue
     * sitting at −85.
     */
    fun percentile(sorted: List<Int>, p: Double): Int? {
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return sorted[0]
        val k = (sorted.size - 1) * (p / 100.0)
        val lo = floor(k).toInt()
        val hi = ceil(k).toInt()
        if (lo == hi) return sorted[lo]
        return Math.round(sorted[lo] + (sorted[hi] - sorted[lo]) * (k - lo)).toInt()
    }

    fun stats(values: List<Int>, total: Int): Stats {
        if (values.isEmpty()) return Stats(0, total, null, null, null, null, null, null)
        val sorted = values.sorted()
        return Stats(
            samples = values.size,
            missing = total - values.size,
            min = sorted.first(),
            p10 = percentile(sorted, 10.0),
            median = percentile(sorted, 50.0),
            p90 = percentile(sorted, 90.0),
            max = sorted.last(),
            mean = values.sum().toDouble() / values.size,
        )
    }

    /**
     * Full analysis for a session.
     *
     * Coverage holes are contiguous runs below the threshold, reported individually. A compliance
     * percentage alone cannot distinguish 8% failing scattered evenly across a venue from 8%
     * concentrated in one stairwell, and only the second tells an engineer where to walk back to.
     */
    fun analyse(
        points: List<TrackPoint>,
        thresholdDbm: Int? = null,
        kpi: Kpi = kpiFor(points),
        minHoleSamples: Int = 3,
    ): Report {
        val threshold = thresholdDbm ?: kpi.defaultThresholdDbm
        val measured = points.filter { it.value(kpi) != null }
        val values = measured.mapNotNull { it.value(kpi) }
        val failing = measured.filter { it.value(kpi)!! < threshold }

        val holes = mutableListOf<Hole>()
        var run = mutableListOf<TrackPoint>()

        fun closeRun() {
            if (run.size >= minHoleSamples) {
                val worst = run.minByOrNull { it.value(kpi)!! }!!
                val t0 = run.first().timestampUtcMillis
                val t1 = run.last().timestampUtcMillis
                holes += Hole(
                    samples = run.size,
                    startSeq = run.first().sequence,
                    endSeq = run.last().sequence,
                    worstDbm = worst.value(kpi)!!,
                    durationS = if (t0 > 0 && t1 > 0) (t1 - t0) / 1000.0 else null,
                    waypoint = run.firstNotNullOfOrNull { it.waypoint },
                    lat = worst.latitudeDeg,
                    lon = worst.longitudeDeg,
                    floorplanId = worst.floorplanId,
                    floorplanX = worst.floorplanX,
                    floorplanY = worst.floorplanY,
                )
            }
            run = mutableListOf()
        }

        for (p in measured) {
            if (p.value(kpi)!! < threshold) run += p else closeRun()
        }
        closeRun()

        // The bucket scale is radio-specific. Applying the Wi-Fi scale to RSRP would paint a
        // perfectly healthy DAS red, so each KPI uses its own.
        val buckets: List<Pair<String, Int>> = if (kpi == Kpi.CELL_RSRP) {
            RsrpBucket.entries.map { b -> b.label to values.count { RsrpBucket.of(it) == b } }
        } else {
            RssiBucket.entries.map { b -> b.label to values.count { RssiBucket.of(it) == b } }
        }

        return Report(
            kpi = kpi,
            thresholdDbm = threshold,
            stats = stats(values, points.size),
            measured = measured.size,
            failing = failing.size,
            compliancePct = if (measured.isEmpty()) {
                0.0
            } else {
                100.0 * (measured.size - failing.size) / measured.size
            },
            bucketCounts = buckets,
            holes = holes.sortedBy { it.worstDbm },
            throughputRows = emptyList(),
        )
    }

    /**
     * One row of a per-band or per-area breakdown.
     *
     * [sharePct] exists because of a specific failure seen in a competitor's deliverable: a cell
     * detected in 9 of 495 samples was given its own column alongside cells detected in nearly all
     * of them, with nothing to distinguish the two. A reader cannot judge a statistic without
     * knowing how much of the survey produced it, so the share is reported next to every group and
     * thin groups are marked rather than silently dropped.
     */
    data class Group(
        val label: String,
        val stats: Stats,
        val measured: Int,
        val failing: Int,
        val compliancePct: Double,
        /** This group's share of all measured samples, 0–100. */
        val sharePct: Double,
    ) {
        /** True when this group covers too little of the survey to support conclusions. */
        val thin: Boolean get() = sharePct < THIN_GROUP_PCT
    }

    data class Breakdown(
        val groups: List<Group>,
        /**
         * Measured samples whose group label was absent. Reported rather than dropped: samples
         * with a signal but no band are a real gap in what the handset told us, and folding them
         * into a labelled group would overstate that group's coverage.
         */
        val unlabelled: Int,
    )

    /** Below this share of the survey, a group's statistics are labelled unreliable. */
    const val THIN_GROUP_PCT = 2.0

    /**
     * Splits a session by any per-sample label — band, RAT, waypoint — and computes the same
     * statistics for each part that [analyse] computes for the whole.
     *
     * Percentages here are percentages: 0–100, not 0–1. That is stated because the competitor
     * package that prompted this work reports its headline overlap metric as `0.5757…` under a
     * `%` heading, which understates a 57.6% figure by a factor of 100. `compliancePctIsPercent`
     * in the test suite pins it.
     */
    fun breakdown(
        points: List<TrackPoint>,
        selector: (TrackPoint) -> String?,
        kpi: Kpi = kpiFor(points),
        thresholdDbm: Int? = null,
    ): Breakdown {
        val threshold = thresholdDbm ?: kpi.defaultThresholdDbm
        val measured = points.filter { it.value(kpi) != null }
        if (measured.isEmpty()) return Breakdown(emptyList(), 0)

        val labelled = measured.mapNotNull { p -> selector(p)?.let { it to p } }
        val groups = labelled.groupBy({ it.first }, { it.second }).map { (label, ps) ->
            val values = ps.mapNotNull { it.value(kpi) }
            val failing = values.count { it < threshold }
            Group(
                label = label,
                stats = stats(values, ps.size),
                measured = ps.size,
                failing = failing,
                compliancePct = 100.0 * (ps.size - failing) / ps.size,
                sharePct = 100.0 * ps.size / measured.size,
            )
        }
        // Largest first: the band or area carrying the survey should lead, not whichever sorts
        // first alphabetically.
        return Breakdown(
            groups = groups.sortedByDescending { it.measured },
            unlabelled = measured.size - labelled.size,
        )
    }

    /**
     * The band label for a sample, whichever radio the session is measuring.
     *
     * Cellular first: a session with both is a cellular session that happened to see Wi-Fi, and
     * mixing the two into one band column would produce a table where "n41" and "5 GHz" sit in the
     * same list as if they were comparable.
     */
    fun bandOf(p: TrackPoint, kpi: Kpi): String? =
        if (kpi == Kpi.CELL_RSRP) {
            p.cellBand?.let { b -> p.rat?.let { "$b  ($it)" } ?: b }
        } else {
            p.band
        }

    /** One-line-per-session statistics, for anyone who wants the numbers in a spreadsheet. */
    fun summaryCsv(summary: SessionSummary, report: Report): String {
        val header = listOf(
            "session", "kpi", "threshold_dbm", "samples", "measured", "missing",
            "min_dbm", "p10_dbm", "median_dbm", "p90_dbm", "max_dbm", "mean_dbm",
            "failing", "compliance_pct", "coverage_holes",
            "gps_points", "indoor_points", "duration_s",
        ).joinToString(",")
        val s = report.stats
        val row = listOf(
            summary.displayName,
            report.kpi.label,
            report.thresholdDbm.toString(),
            s.samples.plus(s.missing).toString(),
            report.measured.toString(),
            s.missing.toString(),
            s.min?.toString() ?: "",
            s.p10?.toString() ?: "",
            s.median?.toString() ?: "",
            s.p90?.toString() ?: "",
            s.max?.toString() ?: "",
            s.mean?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "",
            report.failing.toString(),
            String.format(java.util.Locale.US, "%.1f", report.compliancePct),
            report.holes.size.toString(),
            summary.pointCount.toString(),
            summary.indoorPointCount.toString(),
            (summary.durationMs / 1000).toString(),
        ).joinToString(",") { if (it.contains(',')) "\"$it\"" else it }
        return "$header\n$row\n"
    }
}
