package com.nhnengineering.rftest.model

/**
 * Steadies the **displayed** verdict without touching the recorded measurement.
 *
 * ## The problem, measured
 *
 * Sampling a stationary handset for fifty seconds on 2026-09-02 gave SS-RSRP spanning **−88 to −95
 * dBm, a 7 dB spread, with the phone untouched on a desk**. That is ordinary radio behaviour —
 * multipath and scheduling, not a fault — but the verdict thresholds are hard edges, and −95 sits
 * exactly on the boundary between good and marginal. A 1 dB wobble therefore flips the stated
 * conclusion while nothing physical has changed.
 *
 * The operator sees a stable signal that "sporadically bounces to poor". They are right, and the
 * bouncing is in the presentation rather than in the network.
 *
 * ## Where smoothing is allowed, and where it is not
 *
 * **The recorded data is never smoothed.** Every sample reaches the CSV exactly as the modem
 * reported it, including the outliers, because the analysis, the percentiles and the coverage-hole
 * detection all depend on the real distribution. Smoothing the record would flatter every survey
 * this app produces and quietly destroy the thing it is being sold on.
 *
 * Only the on-screen conclusion is steadied, by two mechanisms that fail in opposite directions:
 *
 * - a **median** over a short window, which discards a single anomalous sample without letting it
 *   drag an average the way a mean would;
 * - a **confirmation count**, so a genuine change is shown only once it has persisted. This is what
 *   stops flapping at a threshold, which a median alone does not fix when the true value sits on
 *   the boundary.
 *
 * The cost is stated plainly: the displayed verdict lags a real change by up to
 * [confirmSamples] samples. For a conclusion that is read while walking, a second or two of lag is
 * a far smaller error than a conclusion that changes every time the operator blinks.
 */
class VerdictStabiliser(
    private val windowSize: Int = 8,
    private val confirmSamples: Int = 3,
) {

    private val levels = ArrayDeque<Int>()
    private val qualities = ArrayDeque<Int>()

    private var shown: Verdict? = null
    private var candidate: Verdict? = null
    private var candidateStreak = 0

    /** Level spread across the current window, or null before there is one. */
    var spreadDb: Int? = null
        private set

    /** Median level over the window, which is what the displayed verdict is computed from. */
    var medianDbm: Int? = null
        private set

    /**
     * Feeds one raw sample and returns the verdict to display.
     *
     * @param wifiCoChannel supplied when the reading is Wi-Fi rather than cellular, so the same
     *   stabiliser serves both surfaces instead of duplicating the logic.
     */
    fun update(levelDbm: Int?, qualityDb: Int?, wifiCoChannel: Int? = null): Verdict {
        if (levelDbm != null) {
            levels.addLast(levelDbm)
            while (levels.size > windowSize) levels.removeFirst()
        }
        if (qualityDb != null) {
            qualities.addLast(qualityDb)
            while (qualities.size > windowSize) qualities.removeFirst()
        }

        // A reading that has gone away is reported immediately rather than held: showing a
        // remembered "good coverage" after service has actually dropped is the one lag that
        // matters, and the first version got this wrong -- it checked whether the *history* was
        // empty, so a phone that had been fine a second ago kept reporting fine.
        if (levelDbm == null) {
            reset()
            return if (wifiCoChannel != null) Verdict.wifi(null, null) else Verdict.cellular(null, null)
        }

        val medLevel = levels.median()
        val medQuality = qualities.median()
        medianDbm = medLevel
        spreadDb = if (levels.size >= 2) (levels.max() - levels.min()) else null

        val fresh = if (wifiCoChannel != null) {
            Verdict.wifi(medLevel, wifiCoChannel)
        } else {
            Verdict.cellular(medLevel, medQuality)
        }

        val current = shown
        if (current == null) {
            shown = fresh
            candidate = null
            candidateStreak = 0
            return fresh
        }

        // Only a change of severity has to be confirmed. Wording that changes within the same
        // severity is a refinement of the same conclusion, not a contradiction of it.
        if (fresh.severity == current.severity) {
            shown = fresh
            candidate = null
            candidateStreak = 0
            return fresh
        }

        if (candidate?.severity == fresh.severity) {
            candidateStreak++
        } else {
            candidate = fresh
            candidateStreak = 1
        }

        if (candidateStreak >= confirmSamples) {
            shown = fresh
            candidate = null
            candidateStreak = 0
            return fresh
        }

        return current
    }

    fun reset() {
        levels.clear()
        qualities.clear()
        shown = null
        candidate = null
        candidateStreak = 0
        spreadDb = null
        medianDbm = null
    }

    /**
     * Median rather than mean.
     *
     * A mean lets one anomalous sample move the answer in proportion to how wrong it is, which is
     * exactly backwards: the more extreme the outlier, the more it should be discounted. On an even
     * count this takes the lower of the two middle values, so the result is always a level that was
     * actually measured rather than an interpolation between two that were not.
     */
    private fun ArrayDeque<Int>.median(): Int? {
        if (isEmpty()) return null
        val sorted = sorted()
        return sorted[(sorted.size - 1) / 2]
    }
}
