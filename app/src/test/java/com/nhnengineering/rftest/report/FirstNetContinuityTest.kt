package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstNetContinuityTest {

    /** Minimal TrackPoint — only the fields the continuity analysis reads. */
    private fun tp(seq: Long, cellBand: String?, rsrp: Int?) = TrackPoint(
        sequence = seq,
        timestampUtcMillis = seq,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = rsrp, sinrDb = null, rsrqDb = null,
        cellBand = cellBand, rat = null,
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
    )

    @Test
    fun `continuity, drop events and runs over a mixed walk`() {
        val r = FirstNetContinuity.analyse(
            listOf(
                tp(1, "B14", -80),   // good
                tp(2, "B14", -90),   // good
                tp(3, "B14", -100),  // weak (< -95)
                tp(4, "B66", -70),   // fell off FirstNet to commercial
                tp(5, "B14", -85),   // good again
            ),
        )
        assertEquals(5, r.servingSamples)
        assertEquals(4, r.onFirstNet)
        assertEquals(3, r.goodFirstNet)
        assertEquals(1, r.weakFirstNet)
        assertEquals(1, r.commercialFallback)
        assertEquals(1, r.dropEvents)           // B14(weak) -> B66
        assertEquals(3, r.longestOnRun)         // samples 1-3
        assertEquals(80.0, r.onFirstNetPct!!, 1e-9)   // 4/5
        assertEquals(75.0, r.firstNetMetPct!!, 1e-9)  // 3 good of 4 graded
        assertEquals(2, r.problems.size)        // the weak one + the commercial one
        assertTrue(r.hasData)
    }

    @Test
    fun `wifi-only samples (no serving band) are ignored, not counted as dropouts`() {
        val r = FirstNetContinuity.analyse(listOf(tp(1, null, null), tp(2, null, null)))
        assertEquals(0, r.servingSamples)
        assertNull(r.onFirstNetPct)
        assertFalse(r.hasData)
    }

    @Test
    fun `n14 is detected as a slash-delimited NR element, B140 is not`() {
        val r = FirstNetContinuity.analyse(listOf(tp(1, "n41/n14", -88), tp(2, "B140", -88)))
        assertEquals(2, r.servingSamples)
        assertEquals(1, r.onFirstNet)           // only the n14 one
        assertEquals(1, r.commercialFallback)   // B140 is not FirstNet
    }

    @Test
    fun `on-FirstNet with no level is counted but not graded`() {
        val r = FirstNetContinuity.analyse(listOf(tp(1, "B14", null)))
        assertEquals(1, r.onFirstNet)
        assertEquals(1, r.firstNetNoLevel)
        assertEquals(0, r.goodFirstNet)
        assertEquals(0, r.weakFirstNet)
        assertNull(r.firstNetMetPct)            // nothing graded
    }
}
