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

    // ---- Dominance / best server -----------------------------------------

    /**
     * One cell's showing across the survey.
     *
     * Identified by PCI **and channel**, because a PCI is unique only within a carrier — 504
     * values for LTE, 1008 for NR. The same PCI on two channels is two different physical cells,
     * and merging them would attribute one cell's coverage to another.
     */
    data class ServerRow(
        val pci: Int,
        val channel: Int?,
        val band: String?,
        /**
         * Samples in which this cell was seen with a usable level — samples, not observations.
         * A cell can legitimately appear more than once in one sample; counting each occurrence
         * produced a detection count larger than the number of samples in the survey, which is
         * the kind of figure that ends an argument with a client badly.
         */
        val detectedIn: Int,
        val detectionPct: Double,
        /** Samples in which it was the strongest cell seen. */
        val bestServerIn: Int,
        val bestServerPct: Double,
        val stats: Stats,
    )

    data class Dominance(
        val windowDb: Int,
        /** Samples that contributed: at least one cell with a level. */
        val samples: Int,
        /**
         * Samples that saw cells but none with a usable level, so could not be ranked — whether
         * the level was absent or outside the physically possible range. Reported rather than
         * dropped silently: an analysis that quietly discards a tenth of a survey and does not
         * say so is not an analysis.
         */
        val excluded: Int,
        /** Dominant-sector count -> number of samples, ascending by count. */
        val countHistogram: List<Pair<Int, Int>>,
        val meanCount: Double,
        /** Share of contributing samples with two or more dominant sectors, 0-100. */
        val overlapPct: Double,
        val servers: List<ServerRow>,
    )

    /**
     * Dominant-sector count, overlap and per-cell best-server share.
     *
     * A sample's dominant sectors are the cells within [windowDb] of its strongest. Two or more
     * means pilot pollution: the handset has no clear server and will hand back and forth. This is
     * the metric that drives DAS remediation, and it is the one the competitor package reports
     * incorrectly by a factor of 100.
     *
     * **This is a lower bound, and the report must say so.** A scanner decodes every cell on air
     * simultaneously; a handset reports its serving cell plus whatever partial neighbour list the
     * modem chose to surface. Cells the modem did not report are invisible here, so a real
     * overlap problem can only be understated, never overstated. That asymmetry is useful — a
     * handset finding overlap is evidence; a handset finding none is not.
     *
     * @param maxAgeMs how stale a neighbour may be and still count. Defaults to 0: only cells in
     *   that sample's own measurement report. Retained neighbours keep the live display readable
     *   but counting them here would invent simultaneity that was never observed.
     */
    /**
     * Physically possible RSRP, generously bounded.
     *
     * 3GPP reportable ranges are −140..−44 dBm for LTE RSRP and −156..−31 for NR SS-RSRP; this
     * window is wider than both so it rejects only values that cannot be measurements at all.
     *
     * It exists because a level of exactly 0 dBm — one milliwatt at the antenna — would be the
     * strongest reading in any file and would win every ranking it entered. Earlier builds wrote
     * that as the sentinel for "the modem gave no level". No session on disk turned out to contain
     * one, so this guards the analysis rather than repairing data; the point is that the ranking
     * should not depend on every upstream writer having got its sentinel right.
     */
    private fun plausibleRsrp(dbm: Int?): Boolean = dbm != null && dbm in -160..-30

    fun dominance(
        points: List<TrackPoint>,
        windowDb: Int = 6,
        maxAgeMs: Long = 0L,
    ): Dominance {
        val withCells = points.filter { it.cells.isNotEmpty() }
        val ranked = withCells.map { p ->
            p.cells.filter { plausibleRsrp(it.rsrpDbm) && (it.serving || it.ageMs <= maxAgeMs) }
        }
        val contributing = ranked.filter { it.isNotEmpty() }
        if (contributing.isEmpty()) {
            return Dominance(windowDb, 0, withCells.size, emptyList(), 0.0, 0.0, emptyList())
        }

        val counts = contributing.map { cells ->
            val best = cells.maxOf { it.rsrpDbm!! }
            cells.count { it.rsrpDbm!! >= best - windowDb }
        }
        val histogram = counts.groupingBy { it }.eachCount().toList().sortedBy { it.first }

        // Per-cell rows, keyed by PCI and channel. A cell without a PCI gets no row -- it would
        // be indistinguishable from every other unidentified cell.
        data class Key(val pci: Int, val channel: Int?)

        val byCell = mutableMapOf<Key, MutableList<Int>>()
        val bands = mutableMapOf<Key, String>()
        val bestCount = mutableMapOf<Key, Int>()
        for (cells in contributing) {
            cells.maxByOrNull { it.rsrpDbm!! }?.let { best ->
                best.pci?.let { bestCount.merge(Key(it, best.channel), 1, Int::plus) }
            }
            // One entry per cell per sample, taking its strongest observation. A cell can appear
            // twice in one sample -- the same PCI on two channels, or the serving cell also
            // present in the neighbour list -- and counting both would report it as detected in
            // more samples than the survey contains.
            cells.filter { it.pci != null }
                .groupBy { Key(it.pci!!, it.channel) }
                .forEach { (key, obs) ->
                    byCell.getOrPut(key) { mutableListOf() } += obs.maxOf { it.rsrpDbm!! }
                    obs.firstNotNullOfOrNull { it.band }?.let { bands.putIfAbsent(key, it) }
                }
        }
        val servers = byCell.map { (key, values) ->
            ServerRow(
                pci = key.pci,
                channel = key.channel,
                band = bands[key],
                detectedIn = values.size,
                detectionPct = 100.0 * values.size / contributing.size,
                bestServerIn = bestCount[key] ?: 0,
                bestServerPct = 100.0 * (bestCount[key] ?: 0) / contributing.size,
                stats = stats(values, values.size),
            )
        }.sortedWith(compareByDescending<ServerRow> { it.bestServerIn }.thenByDescending { it.detectedIn })

        return Dominance(
            windowDb = windowDb,
            samples = contributing.size,
            excluded = withCells.size - contributing.size,
            countHistogram = histogram,
            meanCount = counts.sum().toDouble() / counts.size,
            overlapPct = 100.0 * counts.count { it >= 2 } / counts.size,
            servers = servers,
        )
    }

    // ---- Update cadence --------------------------------------------------

    /**
     * How often a reported field actually changes across a session.
     *
     * Two fields sampled at the same rate are not necessarily *measured* at the same rate. The
     * handset publishes a value every sample whether or not the modem refreshed it, so a field
     * that updates slowly looks identical to one that updates quickly until you count the changes.
     */
    data class Cadence(
        val samples: Int,
        /** Transitions between consecutive differing values. */
        val changes: Int,
        val longestRunSamples: Int,
        /** Wall-clock duration of the longest unchanged run, where timestamps allow it. */
        val longestRunSeconds: Double?,
    )

    /**
     * Measures [Cadence] over a series, ignoring samples where the field was absent.
     *
     * Nulls are skipped rather than treated as a value: a field that is missing and then present
     * has not "changed", and counting that as a transition would flatter a slow field.
     */
    fun cadence(points: List<TrackPoint>, value: (TrackPoint) -> Int?): Cadence {
        val present = points.filter { value(it) != null }
        if (present.isEmpty()) return Cadence(0, 0, 0, null)

        var changes = 0
        var runStart = 0
        var longest = 1
        var longestSeconds: Double? = null
        for (i in 1 until present.size) {
            if (value(present[i]) != value(present[i - 1])) {
                changes++
                val runLength = i - runStart
                if (runLength > longest) {
                    longest = runLength
                    longestSeconds = runSeconds(present, runStart, i - 1)
                }
                runStart = i
            }
        }
        val tailLength = present.size - runStart
        if (tailLength > longest) {
            longest = tailLength
            longestSeconds = runSeconds(present, runStart, present.size - 1)
        }
        return Cadence(present.size, changes, longest, longestSeconds)
    }

    private fun runSeconds(points: List<TrackPoint>, from: Int, to: Int): Double? {
        val a = points[from].timestampUtcMillis
        val b = points[to].timestampUtcMillis
        return if (a > 0 && b > a) (b - a) / 1000.0 else null
    }

    // ---- Stronger neighbours -----------------------------------------------

    /** One cell that was, at times, stronger than the cell actually serving the handset. */
    data class StrongerNeighbour(
        val pci: Int,
        val channel: Int?,
        val band: String?,
        /** Samples in which this cell exceeded the serving cell by the margin. */
        val samples: Int,
        val sharePct: Double,
        val maxMarginDb: Int,
        val medianMarginDb: Int,
        /** Longest unbroken run, in samples — the number that separates fading from a fault. */
        val longestRunSamples: Int,
        val longestRunSeconds: Double?,
        /**
         * True when this cell was on a different band from the serving cell.
         *
         * The discriminator that decides how the finding should be read. A **different band** —
         * low band beating mid band, say — is usually deliberate: carriers hold devices on mid
         * band for capacity even when a low-band cell is stronger, because more signal on a
         * narrower, busier carrier is not a better connection. That is policy, not a fault.
         *
         * The **same band** is the one worth chasing. A neighbour on the same carrier beating the
         * server for several seconds should have triggered a handover, and did not.
         */
        val interBand: Boolean,
        /**
         * True when this cell reports the serving cell's PCI on a different channel.
         *
         * Almost always the same site's other carrier seen through carrier aggregation, not a
         * handover candidate at all. Reporting it as a missed handover would send an engineer
         * looking for a neighbour relation between a cell and itself.
         */
        val samePciAsServing: Boolean,
    ) {
        /**
         * True when the overshoot lasted long enough that ordinary fading does not explain it.
         *
         * A neighbour beating the server for a sample or two is normal — multipath does that
         * constantly. Sustained for several seconds while the handset stays put is a different
         * statement: the network had a better cell available and did not use it.
         */
        val sustained: Boolean
            get() = longestRunSamples >= SUSTAINED_RUN_SAMPLES && !samePciAsServing

        /**
         * The finding worth acting on: same band, sustained, and not the serving cell's own
         * other carrier. Everything else is policy or aggregation.
         */
        val likelyHandoverIssue: Boolean get() = sustained && !interBand
    }

    data class HandoverFindings(
        /** Samples where the serving level and at least one fresh neighbour level were both known. */
        val analysed: Int,
        val samplesWithStronger: Int,
        val sharePct: Double,
        val neighbours: List<StrongerNeighbour>,
        val marginDb: Int,
    ) {
        val hasSustained: Boolean get() = neighbours.any { it.sustained }
        val hasLikelyHandoverIssue: Boolean get() = neighbours.any { it.likelyHandoverIssue }
    }

    /** Below this many consecutive samples, a stronger neighbour is fading rather than a finding. */
    const val SUSTAINED_RUN_SAMPLES = 5

    /**
     * Finds cells that were stronger than the serving cell.
     *
     * This is the analysis that tells an engineer something the handset display does not. A phone
     * shows its serving cell and its bars; it does not say *there was a better cell available and
     * the network stayed on this one*. Sustained, that has a short list of causes and every one of
     * them is actionable:
     *
     * - a **missing neighbour relation**, so the network cannot hand over to a cell it can see;
     * - **handover hysteresis or time-to-trigger set too high**, so it hands over late;
     * - on an in-building system, a **sector or donor assignment that does not match the floor
     *   plan**, which is a commissioning error rather than a design one.
     *
     * None of those is visible in a coverage plot, and all of them show up here.
     *
     * @param marginDb how much stronger a neighbour must be before it counts. A neighbour 1 dB up
     *   is inside measurement noise; the default of 3 dB is the smallest difference worth
     *   attributing to geometry rather than to the receiver.
     * @param maxAgeMs freshness limit, defaulting to this sample's own report — a retained
     *   neighbour compared against a current serving level compares two different instants.
     */
    fun strongerNeighbours(
        points: List<TrackPoint>,
        marginDb: Int = 3,
        maxAgeMs: Long = 0L,
    ): HandoverFindings {
        data class Key(val pci: Int, val channel: Int?)

        val margins = mutableMapOf<Key, MutableList<Int>>()
        val bands = mutableMapOf<Key, String>()
        val interBandSeen = mutableMapOf<Key, Boolean>()
        val samePciSeen = mutableMapOf<Key, Boolean>()
        val runs = mutableMapOf<Key, Int>()
        val bestRun = mutableMapOf<Key, Int>()
        val bestRunSeconds = mutableMapOf<Key, Double>()
        val runStartTime = mutableMapOf<Key, Long>()

        var analysed = 0
        var withStronger = 0

        for (p in points) {
            val servingCell = p.cells.firstOrNull { it.serving }
            val serving = servingCell?.rsrpDbm
            val fresh = p.cells.filter {
                !it.serving && it.rsrpDbm != null && it.pci != null && it.ageMs <= maxAgeMs
            }
            if (serving == null || fresh.isEmpty()) {
                // A sample that cannot be judged breaks every run rather than silently extending
                // it, so a gap in the data is never reported as continuous evidence.
                runs.keys.toList().forEach { runs[it] = 0 }
                continue
            }
            analysed++

            val beating = fresh.filter { it.rsrpDbm!! - serving >= marginDb }
            if (beating.isNotEmpty()) withStronger++

            val beatingKeys = beating.map { Key(it.pci!!, it.channel) }.toSet()
            for (c in beating) {
                val key = Key(c.pci!!, c.channel)
                margins.getOrPut(key) { mutableListOf() } += c.rsrpDbm!! - serving
                c.band?.let { bands.putIfAbsent(key, it) }
                if (c.band != null && servingCell.band != null && c.band != servingCell.band) {
                    interBandSeen[key] = true
                }
                if (servingCell.pci != null && c.pci == servingCell.pci &&
                    c.channel != servingCell.channel
                ) {
                    samePciSeen[key] = true
                }
                val run = (runs[key] ?: 0) + 1
                runs[key] = run
                if (run == 1) runStartTime[key] = p.timestampUtcMillis
                if (run > (bestRun[key] ?: 0)) {
                    bestRun[key] = run
                    val start = runStartTime[key]
                    if (start != null && p.timestampUtcMillis > start) {
                        bestRunSeconds[key] = (p.timestampUtcMillis - start) / 1000.0
                    }
                }
            }
            // Any cell not beating the server this sample has its run broken.
            runs.keys.filter { it !in beatingKeys }.forEach { runs[it] = 0 }
        }

        val list = margins.map { (key, values) ->
            val sorted = values.sorted()
            StrongerNeighbour(
                pci = key.pci,
                channel = key.channel,
                band = bands[key],
                samples = values.size,
                sharePct = if (analysed > 0) 100.0 * values.size / analysed else 0.0,
                maxMarginDb = sorted.last(),
                medianMarginDb = sorted[(sorted.size - 1) / 2],
                longestRunSamples = bestRun[key] ?: 0,
                longestRunSeconds = bestRunSeconds[key],
                interBand = interBandSeen[key] == true,
                samePciAsServing = samePciSeen[key] == true,
            )
        }.sortedWith(
            compareByDescending<StrongerNeighbour> { it.longestRunSamples }
                .thenByDescending { it.samples },
        )

        return HandoverFindings(
            analysed = analysed,
            samplesWithStronger = withStronger,
            sharePct = if (analysed > 0) 100.0 * withStronger / analysed else 0.0,
            neighbours = list,
            marginDb = marginDb,
        )
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
