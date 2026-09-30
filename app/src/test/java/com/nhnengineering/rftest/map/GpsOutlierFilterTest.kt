package com.nhnengineering.rftest.map

import com.nhnengineering.rftest.map.GpsOutlierFilter.Fix
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsOutlierFilterTest {

    private fun fix(lat: Double, lon: Double, atSeconds: Long) =
        Fix(lat, lon, atSeconds * 1000)

    @Test
    fun `an empty list produces no flags`() {
        assertEquals(emptyList<Boolean>(), GpsOutlierFilter.flagOutliers(emptyList()))
    }

    @Test
    fun `the first fix is never flagged`() {
        val flags = GpsOutlierFilter.flagOutliers(listOf(fix(40.0, -74.0, 0)))
        assertEquals(listOf(false), flags)
    }

    @Test
    fun `a plausible walking pace is not flagged`() {
        // About 1.1 m per second between consecutive fixes -- an ordinary walking pace.
        val fixes = listOf(
            fix(40.00000, -74.00000, 0),
            fix(40.00001, -74.00000, 1),
            fix(40.00002, -74.00000, 2),
        )
        assertEquals(listOf(false, false, false), GpsOutlierFilter.flagOutliers(fixes))
    }

    @Test
    fun `a single teleported fix is flagged, and the walk resumes normally after it`() {
        val fixes = listOf(
            fix(40.00000, -74.00000, 0),
            // ~1.1 km away in 1 second -- far past any walking or running speed.
            fix(40.01000, -74.00000, 1),
            // Back to a plausible pace from the *original* point -- the anchor never moved.
            fix(40.00001, -74.00000, 2),
        )
        assertEquals(listOf(false, true, false), GpsOutlierFilter.flagOutliers(fixes))
    }

    @Test
    fun `a cluster of consecutively-bad fixes near each other is all flagged, not just the first`() {
        // The real failure shape this was built for: several points bunched somewhere the
        // operator never walked. Mutually close to each other (would look "plausible" against
        // a naive previous-point-only comparison), but all far from the last trusted fix.
        val fixes = listOf(
            fix(40.00000, -74.00000, 0),   // trusted anchor
            fix(40.01000, -74.00000, 1),   // jumps far away
            fix(40.01001, -74.00000, 2),   // close to the previous point, but still far from anchor
            fix(40.01002, -74.00000, 3),   // same
            fix(40.00001, -74.00000, 4),   // returns near the real anchor
        )
        assertEquals(listOf(false, true, true, true, false), GpsOutlierFilter.flagOutliers(fixes))
    }

    @Test
    fun `a non-positive elapsed time is passed through unflagged rather than guessed at`() {
        val fixes = listOf(
            fix(40.0, -74.0, 5),
            fix(40.01, -74.0, 5), // same timestamp as the anchor -- dt is zero, not infinite speed
            fix(40.0, -74.0, 3),  // earlier than the anchor -- dt is negative
        )
        assertEquals(listOf(false, false, false), GpsOutlierFilter.flagOutliers(fixes))
    }

    @Test
    fun `a custom speed ceiling is honoured`() {
        val fixes = listOf(
            fix(40.00000, -74.00000, 0),
            fix(40.00010, -74.00000, 1), // ~11 m/s -- fine for the default ceiling, not a strict one
        )
        assertEquals(listOf(false, false), GpsOutlierFilter.flagOutliers(fixes, maxSpeedMps = 20.0))
        assertEquals(listOf(false, true), GpsOutlierFilter.flagOutliers(fixes, maxSpeedMps = 2.0))
    }
}
