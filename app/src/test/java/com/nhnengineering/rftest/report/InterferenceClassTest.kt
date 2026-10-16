package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.ObservedCell
import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterferenceClassTest {

    private val t0 = 1_760_000_000_000L

    private fun cell(rsrp: Int?, serving: Boolean) =
        ObservedCell(pci = 1, channel = 650, rsrpDbm = rsrp, band = "n25", serving = serving, ageMs = 0)

    private fun pt(seq: Int, rsrp: Int?, sinr: Int?, cells: List<ObservedCell> = emptyList()) = TrackPoint(
        sequence = seq.toLong(), timestampUtcMillis = t0 + seq * 1000L,
        latitudeDeg = 26.05, longitudeDeg = -80.14, accuracyM = 4f, speedMps = 1f,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = rsrp, sinrDb = sinr, rsrqDb = null, cellBand = "n25", rat = "5G SA",
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        cells = cells,
    )

    @Test
    fun `classify separates coverage from interference`() {
        // Good RSRP, bad SINR -> interference.
        assertEquals(InterferenceClass.Klass.INTERFERENCE_LIMITED,
            InterferenceClass.classify(-90, 0, -100, 3))
        // Weak RSRP -> coverage (regardless of SINR).
        assertEquals(InterferenceClass.Klass.COVERAGE_LIMITED,
            InterferenceClass.classify(-110, 15, -100, 3))
        // Both fine -> clean.
        assertEquals(InterferenceClass.Klass.CLEAN,
            InterferenceClass.classify(-85, 18, -100, 3))
        // Missing SINR -> not classifiable.
        assertNull(InterferenceClass.classify(-85, null, -100, 3))
    }

    @Test
    fun `analyse tallies the three classes over a session`() {
        val pts = listOf(
            pt(1, -85, 18),   // clean
            pt(2, -88, 1),    // interference
            pt(3, -92, 0),    // interference
            pt(4, -112, 10),  // coverage
            pt(5, -90, null), // not classified
        )
        val r = InterferenceClass.analyse(pts)
        assertTrue(r.hasData)
        assertEquals(4, r.classified)
        assertEquals(1, r.clean)
        assertEquals(2, r.interferenceLimited)
        assertEquals(1, r.coverageLimited)
    }

    @Test
    fun `pilot pollution counts cells within the window and the SINR penalty`() {
        val pts = listOf(
            // 3 cells within 6 dB of the strongest -> polluted; poor SINR.
            pt(1, -90, 2, listOf(cell(-90, true), cell(-93, false), cell(-95, false))),
            // Only the serving cell has a clear lead (others >6 dB down) -> not polluted; good SINR.
            pt(2, -88, 16, listOf(cell(-88, true), cell(-99, false))),
        )
        val r = InterferenceClass.analyse(pts)
        assertEquals(2, r.pollutionAssessable)
        assertEquals(1, r.pollutedSamples)
        assertEquals(3, r.maxPolluters)
        assertEquals(2, r.medianSinrPolluted)
        assertEquals(16, r.medianSinrClear)
    }

    @Test
    fun `pollution not assessable when no sample carries two levelled cells`() {
        // Pure 5G SA public-API: serving cell only.
        val pts = (1..5).map { pt(it, -90, 5, listOf(cell(-90, true))) }
        val r = InterferenceClass.analyse(pts)
        assertEquals(0, r.pollutionAssessable)
        assertEquals(0, r.pollutedSamples)
        assertTrue(r.hasData) // classification still works on 5G SA
    }

    @Test
    fun `empty when no serving quality`() {
        val r = InterferenceClass.analyse(listOf(pt(1, null, null)))
        assertFalse(r.hasData)
    }
}
