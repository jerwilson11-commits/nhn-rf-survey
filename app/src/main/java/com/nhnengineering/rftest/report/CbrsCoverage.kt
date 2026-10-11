package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint

/**
 * CBRS / Band-48 coverage validation — how much of the walk ran on CBRS spectrum (LTE **B48** or NR
 * **n48**, 3.5 GHz) and how strong it was. The Field-tier half of the CBRS/private-5G feature: it is
 * pure sample analysis, no root needed. The Pro half — **network slice (S-NSSAI)** and **NPN/SNPN**
 * identification — comes from 5GMM NAS decode (the signaling capture) and is a separate, DIAG-gated
 * follow-up, not attempted here.
 *
 * Soundness (same discipline as [FirstNetContinuity]): only samples with a known serving band are the
 * denominator, CBRS band detection mirrors the FirstNet detector (`"B48"` exactly for LTE, `"n48"` as
 * one `/`-delimited NR element), levels are summarized only over CBRS samples that carried one, and
 * `cbrsPct` is null (not 0.0) when nothing was served.
 */
object CbrsCoverage {

    /** True when a serving band string is CBRS: LTE Band 48 or NR n48. */
    fun isCbrsBand(cellBand: String?): Boolean {
        val b = cellBand ?: return false
        return b == "B48" || b.split("/").any { it == "n48" }
    }

    data class Result(
        /** Samples with a serving cellular band — the denominator. */
        val servingSamples: Int,
        /** Samples served on CBRS (B48/n48). */
        val cbrsSamples: Int,
        /** Of those, how many carried a level. */
        val cbrsWithLevel: Int,
        val lteB48Samples: Int,
        val nrN48Samples: Int,
        /** Median RSRP over CBRS samples that had a level. */
        val medianRsrpDbm: Int?,
        /** Weakest (most negative) RSRP over CBRS samples that had a level. */
        val worstRsrpDbm: Int?,
    ) {
        val cbrsPct: Double? = if (servingSamples == 0) null else 100.0 * cbrsSamples / servingSamples
        val hasData: Boolean get() = cbrsSamples > 0
    }

    fun analyse(points: List<TrackPoint>): Result {
        val serving = points.filter { it.cellBand != null }
        val cbrs = serving.filter { isCbrsBand(it.cellBand) }
        val levels = cbrs.mapNotNull { it.rsrpDbm }.sorted()   // ascending: most-negative (weakest) first
        val median = if (levels.isEmpty()) null else levels[levels.size / 2]
        val worst = levels.firstOrNull()
        return Result(
            servingSamples = serving.size,
            cbrsSamples = cbrs.size,
            cbrsWithLevel = levels.size,
            lteB48Samples = cbrs.count { it.cellBand == "B48" },
            nrN48Samples = cbrs.count { it.cellBand?.split("/")?.any { e -> e == "n48" } == true },
            medianRsrpDbm = median,
            worstRsrpDbm = worst,
        )
    }
}
