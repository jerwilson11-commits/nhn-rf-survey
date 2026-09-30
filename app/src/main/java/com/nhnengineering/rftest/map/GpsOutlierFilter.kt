package com.nhnengineering.rftest.map

/**
 * Flags GPS fixes whose implied speed from the last trusted fix is physically impossible for a
 * survey walk -- the multipath "teleport" pattern: a cluster of points computed somewhere the
 * operator never walked, typically near a structure reflecting the signal. Caught on a real
 * session (2026-09-30, 108 SE 3rd Ave): a tight loop of points beside a neighbouring house, while
 * `accuracyM` reported a flat, unvarying 3 m for every sample in the session including the bad
 * ones -- the phone's own accuracy estimate did not distinguish them, so this compares consecutive
 * positions against elapsed time instead.
 *
 * ## Why the comparison is against the last *trusted* fix, not simply the previous one
 *
 * A naive previous-point comparison flags the first fix that jumps away from the real track, but
 * a cluster of several consecutively bad fixes near each other -- exactly the shape multipath
 * produces, confirmed by the real capture above -- would then look mutually plausible to each
 * other and stop being flagged after the first one. Comparing every candidate against the last
 * fix actually accepted keeps the whole cluster flagged until the track genuinely returns near
 * where it left off, which is what a real multipath excursion does.
 *
 * ## Known limitation, not hidden
 *
 * If the real track permanently jumps far from the trusted anchor and never returns (a long GPS
 * outage reacquiring somewhere genuinely distant, rather than multipath near one structure), this
 * keeps flagging every fix afterwards rather than recognising the jump as real and re-anchoring.
 * Not handled in this pass -- the anchor never advances past a flagged fix, so there is no
 * automatic recovery from a *genuine* large jump. A self-consistency check across a following
 * window of fixes (several in a row that agree with *each other*, not just individually plausible
 * from the anchor) would be the natural next step if this turns out to matter on a real session;
 * not built speculatively here.
 */
object GpsOutlierFilter {

    /** Brisk running, not just walking -- generous enough that a real burst of movement doesn't
     *  get flagged, while a multipath jump (many metres in under a second) still does. */
    const val DEFAULT_MAX_SPEED_MPS = 8.0

    /** One fix's position and fix time, deliberately not tied to either [com.nhnengineering.rftest.session.TrackPoint]'s
     *  or [com.nhnengineering.rftest.service.RecordingState.LiveFix]'s own field names -- both
     *  adapt into this. */
    data class Fix(val lat: Double, val lon: Double, val atMillis: Long)

    /**
     * One `Boolean` per input fix, `true` where that fix is flagged as a suspect GPS position.
     * The first fix is never flagged -- there is nothing to compare it to. A fix whose elapsed
     * time since the anchor is zero or negative (a clock anomaly, or a CSV row that failed to
     * parse a timestamp) is passed through unflagged rather than guessed at: speed is undefined
     * without a positive `dt`, not infinite.
     */
    fun flagOutliers(fixes: List<Fix>, maxSpeedMps: Double = DEFAULT_MAX_SPEED_MPS): List<Boolean> {
        if (fixes.isEmpty()) return emptyList()
        val flags = BooleanArray(fixes.size)
        var anchor = fixes[0]
        for (i in 1 until fixes.size) {
            val candidate = fixes[i]
            val dtSeconds = (candidate.atMillis - anchor.atMillis) / 1000.0
            if (dtSeconds <= 0) continue
            val distanceM = GeoMath.distanceMetres(anchor.lat, anchor.lon, candidate.lat, candidate.lon)
            val impliedSpeedMps = distanceM / dtSeconds
            if (impliedSpeedMps > maxSpeedMps) {
                flags[i] = true
                // Anchor stays put: the next candidate is judged against the same last-trusted
                // fix, which is what keeps a whole cluster of bad fixes flagged together.
            } else {
                anchor = candidate
            }
        }
        return flags.toList()
    }
}
