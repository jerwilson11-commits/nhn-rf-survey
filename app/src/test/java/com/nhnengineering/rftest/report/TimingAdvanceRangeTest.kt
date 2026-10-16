package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingAdvanceRangeTest {

    private val t0 = 1_760_000_000_000L

    private fun pt(seq: Int, ta: Int?, pci: Int? = 100, earfcn: Int? = 650) = TrackPoint(
        sequence = seq.toLong(),
        timestampUtcMillis = t0 + seq * 1000L,
        latitudeDeg = 26.0500, longitudeDeg = -80.1397,
        accuracyM = 4f, speedMps = 1.3f,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = -100, sinrDb = 5, rsrqDb = -10,
        cellBand = "B2", rat = "LTE",
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        servingPci = pci, lteServingPci = pci, lteServingEarfcn = earfcn,
        lteTimingAdvance = ta,
    )

    @Test
    fun `TA converts to one-way distance at 78 metres a unit`() {
        assertEquals(0.0, TimingAdvanceRange.distanceMetres(0), 0.001)
        assertEquals(78.125, TimingAdvanceRange.distanceMetres(1), 0.001)
        assertEquals(781.25, TimingAdvanceRange.distanceMetres(10), 0.001)
    }

    @Test
    fun `summary reports distance range and flags zero-TA samples`() {
        val pts = listOf(pt(1, 0), pt(2, 2), pt(3, 4), pt(4, 10), pt(5, null)) // one row without TA
        val r = TimingAdvanceRange.analyse(pts)

        assertTrue(r.hasData)
        assertEquals(4, r.samples)               // the null-TA row is excluded
        assertEquals(0, r.minDistanceM)          // TA 0
        assertEquals(781, r.maxDistanceM)        // TA 10 -> 781 m
        assertEquals(1, r.zeroTaSamples)
    }

    @Test
    fun `cells are grouped and ordered by distance, furthest first (overshoot on top)`() {
        val pts = buildList {
            // Near DAS-ish cell, PCI 100, small TA.
            repeat(5) { add(pt(it + 1, ta = 1, pci = 100, earfcn = 650)) }
            // Overshooting macro, PCI 288, large TA.
            repeat(3) { add(pt(it + 6, ta = 30, pci = 288, earfcn = 66536)) }
        }
        val r = TimingAdvanceRange.analyse(pts)

        assertEquals(2, r.byCell.size)
        // Furthest (overshoot) first.
        assertEquals(288, r.byCell.first().pci)
        assertEquals(2344, r.byCell.first().medianDistanceM) // 30 * 78.125 = 2343.75 -> 2344
        assertEquals(100, r.byCell.last().pci)
        assertEquals(78, r.byCell.last().medianDistanceM)    // 1 * 78.125 -> 78
    }

    @Test
    fun `no LTE timing advance means no data`() {
        val r = TimingAdvanceRange.analyse(listOf(pt(1, null), pt(2, null)))
        assertFalse(r.hasData)
        assertEquals(0, r.samples)
    }
}
