package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.ObservedCell
import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PciConflictsTest {

    private fun cell(pci: Int, band: String?, channel: Int?, rsrp: Int?, serving: Boolean = false) =
        ObservedCell(pci = pci, channel = channel, rsrpDbm = rsrp, band = band, serving = serving, ageMs = 0)

    private fun tp(seq: Long, cells: List<ObservedCell>) = TrackPoint(
        sequence = seq, timestampUtcMillis = seq,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = null, sinrDb = null, rsrqDb = null,
        cellBand = null, rat = null,
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        cells = cells,
    )

    @Test
    fun `co-channel LTE mod-3 only conflict`() {
        val s = PciConflicts.analyse(listOf(tp(1, listOf(cell(1, "B2", 100, -80), cell(4, "B2", 100, -82)))))
        val c = s.conflicts.single()
        assertTrue(c.mod3)
        assertFalse(c.mod6)
        assertFalse(c.mod30)
        assertEquals(0, c.severity)
        assertEquals(2, c.minMarginDb)
    }

    @Test
    fun `co-channel LTE mod-3 plus mod-6 plus mod-30`() {
        val s = PciConflicts.analyse(listOf(tp(1, listOf(cell(1, "B2", 100, -80), cell(31, "B2", 100, -84)))))
        val c = s.conflicts.single()
        assertTrue(c.mod3 && c.mod6 && c.mod30)
        assertEquals(2, c.severity)
        assertEquals(4, c.minMarginDb)
    }

    @Test
    fun `cross-channel pair is not a conflict`() {
        val s = PciConflicts.analyse(listOf(tp(1, listOf(cell(1, "B2", 100, -80), cell(4, "B2", 200, -82)))))
        assertFalse(s.hasConflicts)
    }

    @Test
    fun `NR co-channel mod-3 conflict has no LTE-only flags`() {
        val s = PciConflicts.analyse(listOf(tp(1, listOf(cell(1, "n41", 500, -80), cell(4, "n41", 500, -82)))))
        val c = s.conflicts.single()
        assertTrue(c.mod3)
        assertFalse(c.mod6)
        assertFalse(c.mod30)
    }

    @Test
    fun `different mod-3 is not a conflict`() {
        val s = PciConflicts.analyse(listOf(tp(1, listOf(cell(1, "B2", 100, -80), cell(2, "B2", 100, -82)))))
        assertFalse(s.hasConflicts)
    }

    @Test
    fun `cells below the detectable floor are ignored`() {
        val s = PciConflicts.analyse(listOf(tp(1, listOf(cell(1, "B2", 100, -120), cell(4, "B2", 100, -118)))))
        assertFalse(s.hasConflicts)
    }
}
