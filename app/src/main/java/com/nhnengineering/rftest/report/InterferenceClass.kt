package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint

/**
 * Interference vs coverage, and pilot pollution (roadmap C2, the public-API half). Field tier, no
 * root.
 *
 * ## The question this answers
 *
 * A weak-signal problem and an interference problem look the same on a coverage map — both are "bad
 * here" — but the fixes are opposite. Weak signal (low RSRP) needs *more* coverage: another antenna,
 * more power. Interference (adequate RSRP but low SINR) needs *less* overlap: down-tilt, power trim,
 * better handover borders — adding coverage makes it worse. Telling the two apart is the single most
 * useful thing a survey can say about *why* a spot is bad, and RSRP alone cannot say it.
 *
 * So every serving sample that carries both RSRP and SINR is classified:
 *  - **coverage-limited** — RSRP below the floor: not enough signal.
 *  - **interference-limited** — RSRP fine, SINR poor: too many cells, none clean. This is the
 *    footprint of overlap / pilot pollution.
 *  - **clean** — both adequate.
 *
 * This works on **5G SA**, where the neighbour-based overlap analysis cannot: Android exposes no NR
 * neighbours through `CellInfo`, so pilot pollution can only be *counted* on LTE (or from the modem
 * feed, a Pro path). Where neighbours do exist, the **pilot-pollution penalty** is reported — the
 * median SINR when three or more cells sit within a few dB of the strongest, against when they do
 * not — which quantifies what the existing dominance/overlap section only counts.
 *
 * The thresholds are stated and are the usual working values; they are a convention the report prints,
 * not a standard.
 */
object InterferenceClass {

    enum class Klass { CLEAN, INTERFERENCE_LIMITED, COVERAGE_LIMITED }

    /** Classifies one sample. Null when it lacks a serving RSRP or SINR (cannot be told apart). */
    fun classify(rsrpDbm: Int?, sinrDb: Int?, rsrpFloorDbm: Int, sinrFloorDb: Int): Klass? {
        if (rsrpDbm == null || sinrDb == null) return null
        return when {
            rsrpDbm < rsrpFloorDbm -> Klass.COVERAGE_LIMITED
            sinrDb < sinrFloorDb -> Klass.INTERFERENCE_LIMITED
            else -> Klass.CLEAN
        }
    }

    data class Result(
        val classified: Int,
        val clean: Int,
        val interferenceLimited: Int,
        val coverageLimited: Int,
        /** Samples that offered two or more cells with a level — where pollution can be counted. */
        val pollutionAssessable: Int,
        /** Of those, how many had [polluterThreshold]+ cells within [windowDb] of the strongest. */
        val pollutedSamples: Int,
        val maxPolluters: Int,
        /** Median SINR over polluted vs non-polluted assessable samples — the pilot-pollution penalty. */
        val medianSinrPolluted: Int?,
        val medianSinrClear: Int?,
        val rsrpFloorDbm: Int,
        val sinrFloorDb: Int,
        val windowDb: Int,
        val polluterThreshold: Int,
    ) {
        val hasData: Boolean get() = classified > 0
        fun pct(n: Int): Double = if (classified == 0) 0.0 else 100.0 * n / classified
    }

    fun analyse(
        points: List<TrackPoint>,
        rsrpFloorDbm: Int = -100,
        sinrFloorDb: Int = 3,
        windowDb: Int = 6,
        polluterThreshold: Int = 3,
    ): Result {
        var clean = 0; var interf = 0; var cov = 0
        for (p in points) {
            when (classify(p.rsrpDbm, p.sinrDb, rsrpFloorDbm, sinrFloorDb)) {
                Klass.CLEAN -> clean++
                Klass.INTERFERENCE_LIMITED -> interf++
                Klass.COVERAGE_LIMITED -> cov++
                null -> {}
            }
        }

        var assessable = 0; var polluted = 0; var maxPolluters = 0
        val sinrPolluted = mutableListOf<Int>()
        val sinrClear = mutableListOf<Int>()
        for (p in points) {
            val levels = p.cells.mapNotNull { it.rsrpDbm }
            if (levels.size < 2) continue
            assessable++
            val strongest = levels.max()
            val polluters = levels.count { it >= strongest - windowDb }
            if (polluters > maxPolluters) maxPolluters = polluters
            val isPolluted = polluters >= polluterThreshold
            if (isPolluted) polluted++
            p.sinrDb?.let { if (isPolluted) sinrPolluted += it else sinrClear += it }
        }

        return Result(
            classified = clean + interf + cov,
            clean = clean, interferenceLimited = interf, coverageLimited = cov,
            pollutionAssessable = assessable,
            pollutedSamples = polluted,
            maxPolluters = maxPolluters,
            medianSinrPolluted = median(sinrPolluted),
            medianSinrClear = median(sinrClear),
            rsrpFloorDbm = rsrpFloorDbm, sinrFloorDb = sinrFloorDb,
            windowDb = windowDb, polluterThreshold = polluterThreshold,
        )
    }

    private fun median(xs: List<Int>): Int? =
        if (xs.isEmpty()) null else xs.sorted()[xs.size / 2]
}
