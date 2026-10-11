package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint

/**
 * FirstNet (Band 14 / n14) **retainability & continuity** across a recorded walk — the Track-B
 * complement to [SessionStats.publicSafetyCoverage], which answers "what % of FirstNet samples met the
 * level" but not "did the FirstNet connection *hold*." For an ERCES/FirstNet acceptance walk the two
 * failures this surfaces matter as much as raw level:
 *
 *  - **Fell off FirstNet to commercial** — the serving cell left Band 14/n14 for a commercial band
 *    mid-walk. A strong commercial reading is not FirstNet coverage, so these are coverage gaps even
 *    though the phone still "has signal."
 *  - **Weak FirstNet** — still on Band 14/n14 but below the ERRCS floor (a FirstNet coverage hole).
 *
 * Soundness (same discipline as the rest of this module):
 *  - Only samples with a **known serving cellular band** ([TrackPoint.cellBand] != null) are counted,
 *    so a Wi-Fi-only row is never mistaken for a FirstNet dropout.
 *  - FirstNet detection is delegated to [SessionStats.isFirstNetBand14] (the authoritative check:
 *    `"B14"` for LTE, `"n14"` as one `/`-delimited NR element — never a bare substring).
 *  - A sample on FirstNet with **no level** is counted but never graded pass/fail.
 *  - `onFirstNetPct` is null (not 0.0) when nothing was served, so an empty walk never reads as a
 *    measured 0%.
 */
object FirstNetContinuity {

    /** What a serving sample was doing, among samples that had a serving cellular band. */
    enum class State { GOOD_FIRSTNET, WEAK_FIRSTNET, FIRSTNET_NO_LEVEL, COMMERCIAL_FALLBACK }

    /** A weak-FirstNet or fell-to-commercial sample, with where/when it happened. */
    data class ProblemPoint(
        val state: State,
        val atUtcMillis: Long,
        val rsrpDbm: Int?,
        val cellBand: String?,
        val latitudeDeg: Double?,
        val longitudeDeg: Double?,
        val floorplanId: String?,
        val floorplanX: Float?,
        val floorplanY: Float?,
        val floor: String?,
    )

    data class Result(
        /** Samples with a serving cellular band — the denominator for continuity. */
        val servingSamples: Int,
        /** Serving on FirstNet Band 14/n14 (any level). */
        val onFirstNet: Int,
        /** On FirstNet and at/above the floor. */
        val goodFirstNet: Int,
        /** On FirstNet but below the floor (a FirstNet coverage hole). */
        val weakFirstNet: Int,
        /** On FirstNet but the sample carried no level. */
        val firstNetNoLevel: Int,
        /** Serving a non-FirstNet cellular band (fell off FirstNet to commercial). */
        val commercialFallback: Int,
        /** Transitions from on-FirstNet to not-on-FirstNet in walk order — "lost FirstNet" events. */
        val dropEvents: Int,
        /** Longest unbroken run of on-FirstNet samples. */
        val longestOnRun: Int,
        val thresholdDbm: Int,
        /** A bounded sample of the weak / commercial-fallback locations for the report. */
        val problems: List<ProblemPoint>,
    ) {
        /** Percent of served samples that were on FirstNet; null when nothing was served. */
        val onFirstNetPct: Double? = if (servingSamples == 0) null else 100.0 * onFirstNet / servingSamples

        /** Percent of on-FirstNet samples with a level that met the floor; null when none had a level. */
        val gradedOnFirstNet: Int get() = goodFirstNet + weakFirstNet
        val firstNetMetPct: Double? = if ((goodFirstNet + weakFirstNet) == 0) {
            null
        } else {
            100.0 * goodFirstNet / (goodFirstNet + weakFirstNet)
        }

        /** This was a FirstNet survey (worth reporting continuity for) only if FirstNet was seen. */
        val hasData: Boolean get() = onFirstNet > 0
    }

    fun analyse(points: List<TrackPoint>, thresholdDbm: Int = -95, maxProblems: Int = 25): Result {
        val serving = points.filter { it.cellBand != null }.sortedBy { it.sequence }
        var onFirstNet = 0
        var good = 0
        var weak = 0
        var noLevel = 0
        var commercial = 0
        var drops = 0
        var longest = 0
        var run = 0
        var prevOn = false
        var first = true
        val problems = ArrayList<ProblemPoint>()

        for (p in serving) {
            val isFn = SessionStats.isFirstNetBand14(p)
            if (isFn) {
                onFirstNet++
                run++
                if (run > longest) longest = run
                when {
                    p.rsrpDbm == null -> noLevel++
                    p.rsrpDbm >= thresholdDbm -> good++
                    else -> {
                        weak++
                        if (problems.size < maxProblems) problems += problemPoint(State.WEAK_FIRSTNET, p)
                    }
                }
            } else {
                commercial++
                run = 0
                if (problems.size < maxProblems) problems += problemPoint(State.COMMERCIAL_FALLBACK, p)
            }
            if (!first && prevOn && !isFn) drops++
            prevOn = isFn
            first = false
        }

        return Result(
            servingSamples = serving.size,
            onFirstNet = onFirstNet,
            goodFirstNet = good,
            weakFirstNet = weak,
            firstNetNoLevel = noLevel,
            commercialFallback = commercial,
            dropEvents = drops,
            longestOnRun = longest,
            thresholdDbm = thresholdDbm,
            problems = problems,
        )
    }

    private fun problemPoint(state: State, p: TrackPoint) = ProblemPoint(
        state = state,
        atUtcMillis = p.timestampUtcMillis,
        rsrpDbm = p.rsrpDbm,
        cellBand = p.cellBand,
        latitudeDeg = p.latitudeDeg,
        longitudeDeg = p.longitudeDeg,
        floorplanId = p.floorplanId,
        floorplanX = p.floorplanX,
        floorplanY = p.floorplanY,
        floor = p.floor,
    )
}
