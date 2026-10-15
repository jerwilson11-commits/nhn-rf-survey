package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Area RF-audit score (roadmap C4) — one graded verdict for a surveyed area, built from the KPIs the
 * report already computes. Field tier, pure derived analysis, no root.
 *
 * ## Why a single score, done carefully
 *
 * A client asks "is this area good?" and an acceptance report answers with a dozen tables. The tables
 * are the evidence and must stay, but the question deserves a headline, and the competitor tools (NSG,
 * G-Mon) don't give one. The risk with any single score is that it launders a judgement into a number
 * nobody can audit. So this one is built to be auditable:
 *
 *  - Every component is one of the measurements already on the report (strength, threshold compliance,
 *    quality, stability) — nothing new is measured here, it is only combined.
 *  - The weights are stated, and a component with no data is **dropped and its weight redistributed**
 *    over the rest, rather than scored zero. A Wi-Fi session has no SINR; it is not penalised for the
 *    absence. (Same discipline as [CbrsCoverage]/[FirstNetContinuity]: only measured things count.)
 *  - Each component carries the raw figure it was scored from, so the grade can always be traced back
 *    to the measurement, never read as an oracle.
 *
 * The mapping from a KPI to a 0–100 sub-score is a convention, not a measurement, and the report says
 * so. The thresholds follow the same RF conventions the rest of the app uses (the pass/fail threshold
 * is the operator's own, carried in from [SessionStats.Report.thresholdDbm]).
 */
object AreaRfAudit {

    /** Nominal component weights (percent). Renormalised over the components that actually have data. */
    private const val W_STRENGTH = 35
    private const val W_RELIABILITY = 30
    private const val W_QUALITY = 20
    private const val W_STABILITY = 15

    data class Component(
        val name: String,
        /** 0..100. */
        val score: Int,
        /** Effective weight after renormalisation (percent, sums to 100 across [Result.components]). */
        val weightPct: Int,
        /** The raw figure this score came from, so a reader can check it. */
        val detail: String,
    )

    data class Result(
        val kpiLabel: String,
        val overallScore: Int,
        val grade: String,
        val components: List<Component>,
        val verdict: String,
        val assessedSamples: Int,
    ) {
        val hasData: Boolean get() = assessedSamples > 0 && components.isNotEmpty()
    }

    /** Letter grade from the 0–100 score. Standard academic banding; F is anything below 60. */
    fun gradeFor(score: Int): String = when {
        score >= 90 -> "A"
        score >= 80 -> "B"
        score >= 70 -> "C"
        score >= 60 -> "D"
        else -> "F"
    }

    /**
     * Piecewise-linear map from a metric to a 0–100 score. [knots] are (metric, score) pairs sorted
     * ascending by metric; values outside the range clamp to the end scores.
     */
    private fun curve(value: Double, knots: List<Pair<Double, Double>>): Double {
        if (value <= knots.first().first) return knots.first().second
        if (value >= knots.last().first) return knots.last().second
        for (i in 1 until knots.size) {
            val (x1, y1) = knots[i - 1]
            val (x2, y2) = knots[i]
            if (value <= x2) {
                val t = (value - x1) / (x2 - x1)
                return y1 + t * (y2 - y1)
            }
        }
        return knots.last().second
    }

    // RSRP (dBm) → score. Anchored on the usual cellular coverage conventions: ≥ -80 excellent,
    // -100 marginal-but-usable, ≤ -120 effectively unusable.
    private val RSRP_CURVE = listOf(-120.0 to 0.0, -110.0 to 30.0, -100.0 to 60.0, -90.0 to 85.0, -80.0 to 100.0)

    // RSSI (dBm) → score for Wi-Fi. ≥ -55 excellent, -70 usable, ≤ -90 unusable.
    private val RSSI_CURVE = listOf(-90.0 to 0.0, -80.0 to 30.0, -70.0 to 65.0, -60.0 to 90.0, -55.0 to 100.0)

    // SINR (dB) → score. ≥ 20 excellent, 0 poor, ≤ -10 unusable.
    private val SINR_CURVE = listOf(-10.0 to 0.0, -5.0 to 15.0, 0.0 to 40.0, 6.0 to 60.0, 13.0 to 85.0, 20.0 to 100.0)

    /**
     * Grades [points] using the figures already in [report]. Cellular sessions get all four
     * components; Wi-Fi gets strength, reliability and stability (no SINR), with the quality weight
     * redistributed.
     */
    fun analyse(points: List<TrackPoint>, report: SessionStats.Report): Result {
        val cellular = report.kpi == SessionStats.Kpi.CELL_RSRP
        val kpiLabel = if (cellular) "Cellular (RSRP)" else "Wi-Fi (RSSI)"

        val raw = mutableListOf<Triple<Int, Int, Component>>() // weight, score, component (weight filled later)

        // 1) Signal strength — from the median the report already computed.
        report.stats.median?.let { med ->
            val score = curve(med.toDouble(), if (cellular) RSRP_CURVE else RSSI_CURVE).roundToInt()
            raw += Triple(W_STRENGTH, score, Component("Signal strength", score, 0, "median $med dBm"))
        }

        // 2) Reliability — the operator's own pass/fail compliance, used directly as a 0–100 score.
        if (report.measured > 0) {
            val score = report.compliancePct.roundToInt().coerceIn(0, 100)
            raw += Triple(
                W_RELIABILITY, score,
                Component(
                    "Reliability", score, 0,
                    "${score}% at/above ${report.thresholdDbm} dBm (${report.measured - report.failing} of ${report.measured})",
                ),
            )
        }

        // 3) Quality — median SINR, cellular only, over samples that carried one.
        if (cellular) {
            val sinrs = points.mapNotNull { it.sinrDb }.sorted()
            if (sinrs.isNotEmpty()) {
                val medSinr = sinrs[sinrs.size / 2]
                val score = curve(medSinr.toDouble(), SINR_CURVE).roundToInt()
                raw += Triple(W_QUALITY, score, Component("Quality (SINR)", score, 0, "median $medSinr dB"))
            }
        }

        // 4) Stability — service losses and NR→LTE fallbacks per minute (routine serving-cell changes
        //    are not penalised; they are normal mobility, not a defect).
        val mob = MobilityEvents.analyse(points)
        val losses = mob.events.count { it.kind == MobilityEvents.Kind.SERVICE_LOST }
        val fallbacks = mob.events.count { it.kind == MobilityEvents.Kind.NR_FALLBACK }
        val minutes = sessionMinutes(points)
        if (minutes >= 0.5) {
            val perMin = (losses * 25.0 + fallbacks * 10.0) / minutes
            val score = (100.0 - perMin).roundToInt().coerceIn(0, 100)
            raw += Triple(
                W_STABILITY, score,
                Component("Stability", score, 0, "$losses service loss / $fallbacks NR→LTE over ${"%.1f".format(minutes)} min"),
            )
        }

        if (raw.isEmpty() || report.measured == 0) {
            return Result(kpiLabel, 0, "F", emptyList(), "Not enough measured data to score this area.", 0)
        }

        // Renormalise the nominal weights over the components that have data, so a missing component
        // neither counts as zero nor silently skews the others. The overall score uses the exact
        // weights; the displayed percentages are rounded, with the rounding remainder pushed onto the
        // largest so the column a client reads sums to exactly 100.
        val totalWeight = raw.sumOf { it.first }
        val rounded = raw.map { (100.0 * it.first / totalWeight).roundToInt() }.toMutableList()
        val drift = 100 - rounded.sum()
        if (drift != 0) {
            val biggest = rounded.indices.maxByOrNull { rounded[it] }!!
            rounded[biggest] = (rounded[biggest] + drift).coerceAtLeast(0)
        }
        val components = raw.mapIndexed { i, (_, _, comp) -> comp.copy(weightPct = rounded[i]) }
        val overall = (raw.sumOf { it.first * it.second }.toDouble() / totalWeight).roundToInt().coerceIn(0, 100)
        val grade = gradeFor(overall)

        val weakest = raw.minByOrNull { it.second }!!.third
        val verdict = buildString {
            append(
                when (grade) {
                    "A" -> "Excellent RF — comfortably meets coverage and quality targets."
                    "B" -> "Good RF — meets targets with modest margin."
                    "C" -> "Acceptable RF — usable, but with weak spots worth addressing."
                    "D" -> "Marginal RF — meets the floor unevenly; remediation likely needed."
                    else -> "Poor RF — fails coverage and/or quality targets across the area."
                },
            )
            if (grade != "A") append(" Weakest factor: ${weakest.name.lowercase()} (${weakest.score}/100).")
        }

        return Result(kpiLabel, overall, grade, components, verdict, report.measured)
    }

    private fun sessionMinutes(points: List<TrackPoint>): Double {
        if (points.size < 2) return 0.0
        val ts = points.map { it.timestampUtcMillis }
        val span = max(0L, ts.max() - ts.min())
        return min(span / 60_000.0, Double.MAX_VALUE)
    }
}
