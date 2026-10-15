package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.SessionSummary
import com.nhnengineering.rftest.session.TrackPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A-B benchmark (roadmap E3) — compares two recorded sessions KPI by KPI and names a winner on each.
 * Field tier, no root. The two sessions are whatever the operator wants to put side by side: two
 * carriers over the same route, before vs after a DAS remediation, or two handsets.
 *
 * Nothing new is measured — each side is run through the same analysers a single-session report uses
 * ([SessionStats], [AreaRfAudit], [VoiceMosEstimate], [MobilityEvents], [PciConflicts]) and the two
 * results are placed next to each other. Each metric carries which direction is "better", so the
 * winner is decided by the metric, not by hard-coded assumptions, and a metric one side cannot supply
 * is marked not-comparable rather than scored as a loss.
 *
 * Signal-level rows (median / cell-edge dBm) are only compared when both sides measured the **same**
 * KPI — comparing an RSRP to an RSSI would be a category error — and are omitted with a note when
 * they do not. Percentage, score and MOS rows are comparable across that boundary.
 */
object BenchmarkComparison {

    enum class Direction { HIGHER_BETTER, LOWER_BETTER }
    enum class Winner { A, B, TIE, NA }

    data class Side(val label: String, val summary: SessionSummary, val points: List<TrackPoint>)

    data class Metric(
        val name: String,
        val unit: String,
        val aValue: Double?,
        val bValue: Double?,
        val direction: Direction,
        val decimals: Int,
        /** Values closer than this count as a tie — avoids a "winner" on measurement noise. */
        val tie: Double,
    ) {
        val winner: Winner
            get() {
                val a = aValue; val b = bValue
                if (a == null || b == null) return Winner.NA
                if (abs(a - b) <= tie) return Winner.TIE
                val aBetter = if (direction == Direction.HIGHER_BETTER) a > b else a < b
                return if (aBetter) Winner.A else Winner.B
            }

        private fun fmt(v: Double?): String =
            v?.let { if (decimals == 0) it.roundToInt().toString() else "%.${decimals}f".format(it) } ?: "—"

        fun aText() = fmt(aValue)
        fun bText() = fmt(bValue)
        /** B minus A, with a +/- sign, in the metric's own units; blank when either side is missing. */
        fun deltaText(): String {
            val a = aValue; val b = bValue
            if (a == null || b == null) return "—"
            val d = b - a
            val s = if (decimals == 0) "%+d".format(d.roundToInt()) else "%+.${decimals}f".format(d)
            return s
        }
    }

    data class Result(
        val labelA: String,
        val labelB: String,
        val metrics: List<Metric>,
        val sameKpi: Boolean,
        val kpiA: String,
        val kpiB: String,
    ) {
        val aWins get() = metrics.count { it.winner == Winner.A }
        val bWins get() = metrics.count { it.winner == Winner.B }
        val ties get() = metrics.count { it.winner == Winner.TIE }
        val comparable get() = metrics.count { it.winner != Winner.NA }
        val hasData get() = comparable > 0

        /** One-line headline. A clear lead is >= 2 metrics; otherwise "mixed / even". */
        val verdict: String
            get() = when {
                !hasData -> "Not enough overlapping data to compare these sessions."
                aWins - bWins >= 2 -> "$labelA is the stronger network across the measured KPIs ($aWins–$bWins, $ties tied)."
                bWins - aWins >= 2 -> "$labelB is the stronger network across the measured KPIs ($bWins–$aWins, $ties tied)."
                else -> "Mixed result — the two are close ($aWins–$bWins, $ties tied); see the per-KPI rows."
            }
    }

    fun analyse(a: Side, b: Side): Result {
        val ra = SessionStats.analyse(a.points)
        val rb = SessionStats.analyse(b.points)
        val sameKpi = ra.kpi == rb.kpi

        val metrics = mutableListOf<Metric>()

        // Coverage — comparable regardless of KPI (each against its own threshold).
        metrics += Metric(
            "Coverage (% meeting threshold)", "%",
            ra.compliancePct, rb.compliancePct, Direction.HIGHER_BETTER, 1, tie = 1.0,
        )

        // Signal level — only when both sides measured the same KPI.
        if (sameKpi) {
            metrics += Metric("Median signal", "dBm", ra.stats.median?.toDouble(), rb.stats.median?.toDouble(), Direction.HIGHER_BETTER, 0, tie = 1.0)
            metrics += Metric("Cell edge (10th pct)", "dBm", ra.stats.p10?.toDouble(), rb.stats.p10?.toDouble(), Direction.HIGHER_BETTER, 0, tie = 1.0)
        }

        // Area RF-audit score (C4) — 0–100, comparable across KPIs.
        val auditA = AreaRfAudit.analyse(a.points, ra)
        val auditB = AreaRfAudit.analyse(b.points, rb)
        metrics += Metric(
            "Area RF-audit score", "/100",
            auditA.takeIf { it.hasData }?.overallScore?.toDouble(),
            auditB.takeIf { it.hasData }?.overallScore?.toDouble(),
            Direction.HIGHER_BETTER, 0, tie = 2.0,
        )

        // Estimated voice MOS (E2) — cellular only; NA for a Wi-Fi side.
        val voiceA = VoiceMosEstimate.analyse(a.points)
        val voiceB = VoiceMosEstimate.analyse(b.points)
        metrics += Metric(
            "Est. voice MOS (median)", "",
            voiceA.medianMos, voiceB.medianMos, Direction.HIGHER_BETTER, 1, tie = 0.1,
        )

        // Throughput where sampled — higher is better.
        metrics += Metric("Download (median)", "Mbps", medianOf(a.points.mapNotNull { it.downloadMbps }), medianOf(b.points.mapNotNull { it.downloadMbps }), Direction.HIGHER_BETTER, 1, tie = 1.0)
        metrics += Metric("Upload (median)", "Mbps", medianOf(a.points.mapNotNull { it.uploadMbps }), medianOf(b.points.mapNotNull { it.uploadMbps }), Direction.HIGHER_BETTER, 1, tie = 1.0)

        // Stability — service losses per minute. Lower is better.
        metrics += Metric(
            "Service losses / min", "",
            lossesPerMin(a.points), lossesPerMin(b.points), Direction.LOWER_BETTER, 2, tie = 0.02,
        )

        // PCI planning conflicts — lower is better.
        metrics += Metric(
            "PCI conflicts", "",
            PciConflicts.analyse(a.points).conflicts.size.toDouble(),
            PciConflicts.analyse(b.points).conflicts.size.toDouble(),
            Direction.LOWER_BETTER, 0, tie = 0.0,
        )

        return Result(a.label, b.label, metrics, sameKpi, ra.kpi.label, rb.kpi.label)
    }

    private fun medianOf(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return s[s.size / 2]
    }

    private fun lossesPerMin(points: List<TrackPoint>): Double? {
        if (points.size < 2) return null
        val span = points.maxOf { it.timestampUtcMillis } - points.minOf { it.timestampUtcMillis }
        val minutes = span / 60_000.0
        if (minutes < 0.5) return null
        val losses = MobilityEvents.analyse(points).events.count { it.kind == MobilityEvents.Kind.SERVICE_LOST }
        return losses / minutes
    }
}
