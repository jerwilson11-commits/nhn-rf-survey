package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CbrsCoverageTest {

    private fun tp(seq: Long, cellBand: String?, rsrp: Int?) = TrackPoint(
        sequence = seq, timestampUtcMillis = seq,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = rsrp, sinrDb = null, rsrqDb = null,
        cellBand = cellBand, rat = null,
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
    )

    @Test
    fun `detects B48 and n48, splits LTE vs NR, counts over served`() {
        val r = CbrsCoverage.analyse(
            listOf(
                tp(1, "B48", -90),       // CBRS LTE
                tp(2, "n41/n48", -95),   // CBRS NR (n48 as a slash element)
                tp(3, "B66", -80),       // not CBRS
                tp(4, "B48", -100),      // CBRS LTE
            ),
        )
        assertEquals(4, r.servingSamples)
        assertEquals(3, r.cbrsSamples)
        assertEquals(2, r.lteB48Samples)
        assertEquals(1, r.nrN48Samples)
        assertEquals(75.0, r.cbrsPct!!, 1e-9)      // 3 of 4 served
        assertEquals(-100, r.worstRsrpDbm)         // weakest CBRS level
        assertTrue(r.hasData)
    }

    @Test
    fun `B480 is not CBRS, n4 is not n48`() {
        val r = CbrsCoverage.analyse(listOf(tp(1, "B480", -90), tp(2, "n41/n4", -90)))
        assertFalse(r.hasData)
        assertEquals(0, r.cbrsSamples)
    }

    @Test
    fun `no served samples yields a null percentage, not zero`() {
        val r = CbrsCoverage.analyse(listOf(tp(1, null, null)))
        assertEquals(0, r.servingSamples)
        assertNull(r.cbrsPct)
        assertFalse(r.hasData)
    }
}
