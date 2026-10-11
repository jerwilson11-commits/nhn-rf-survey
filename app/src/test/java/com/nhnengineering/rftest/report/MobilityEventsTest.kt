package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.ObservedCell
import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilityEventsTest {

    private fun tp(
        seq: Long,
        cellBand: String?,
        rsrp: Int?,
        servingPci: Int? = null,
        cells: List<ObservedCell> = emptyList(),
    ) = TrackPoint(
        sequence = seq, timestampUtcMillis = seq,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = rsrp, sinrDb = null, rsrqDb = null,
        cellBand = cellBand, rat = null,
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        servingPci = servingPci, cells = cells,
    )

    private fun cell(pci: Int, band: String?, rsrp: Int?, serving: Boolean = false) =
        ObservedCell(pci = pci, channel = null, rsrpDbm = rsrp, band = band, serving = serving, ageMs = 0)

    @Test
    fun `serving cell change is detected`() {
        val s = MobilityEvents.analyse(listOf(tp(1, "B2", -80, servingPci = 100), tp(2, "B2", -82, servingPci = 200)))
        assertEquals(1, s.servingCellChanges)
        assertEquals(MobilityEvents.Kind.SERVING_CELL_CHANGE, s.events.single().kind)
    }

    @Test
    fun `NR to LTE fallback is detected`() {
        val s = MobilityEvents.analyse(listOf(tp(1, "n41", -90), tp(2, "B66", -85)))
        assertEquals(1, s.nrToLteFallbacks)
        assertEquals(MobilityEvents.Cause.NR_TO_LTE, s.events.single { it.kind == MobilityEvents.Kind.NR_FALLBACK }.cause)
    }

    @Test
    fun `off-FirstNet transition is detected with the FirstNet-gap cause`() {
        val s = MobilityEvents.analyse(listOf(tp(1, "B14", -85), tp(2, "B66", -80)))
        assertEquals(1, s.offFirstNetEvents)
        val e = s.events.single { it.kind == MobilityEvents.Kind.OFF_FIRSTNET }
        assertEquals(MobilityEvents.Cause.FIRSTNET_GAP, e.cause)
    }

    @Test
    fun `service loss with a stronger same-band neighbour is a missed handover`() {
        val lastGood = tp(1, "B2", -110, servingPci = 100, cells = listOf(cell(100, "B2", -110, serving = true), cell(200, "B2", -95)))
        val s = MobilityEvents.analyse(listOf(lastGood, tp(2, null, null)))
        assertEquals(1, s.serviceLosses)
        assertEquals(MobilityEvents.Cause.MISSED_HANDOVER, s.events.single { it.kind == MobilityEvents.Kind.SERVICE_LOST }.cause)
    }

    @Test
    fun `service loss with no better neighbour is a coverage hole`() {
        val lastGood = tp(1, "B2", -110, servingPci = 100, cells = emptyList())
        val s = MobilityEvents.analyse(listOf(lastGood, tp(2, null, null)))
        assertEquals(MobilityEvents.Cause.COVERAGE_HOLE, s.events.single { it.kind == MobilityEvents.Kind.SERVICE_LOST }.cause)
    }

    @Test
    fun `wifi-only samples produce no mobility events`() {
        val s = MobilityEvents.analyse(listOf(tp(1, null, null), tp(2, null, null)))
        assertFalse(s.hasEvents)
        assertEquals(0, s.serviceLosses)
        assertTrue(s.events.isEmpty())
    }
}
