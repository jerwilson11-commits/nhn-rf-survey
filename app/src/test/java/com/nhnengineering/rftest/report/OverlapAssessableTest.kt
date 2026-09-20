package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.ObservedCell
import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the difference between "no overlap" and "overlap not measured".
 *
 * The report told a client a 5G SA system had **0.0% sector overlap**. It had measured nothing of
 * the kind. Overlap is two or more cells within 6 dB of the strongest, and every sample carried
 * exactly one cell, so the comparison was never made against anything — the arithmetic was correct
 * and its meaning was the opposite of what the page said.
 *
 * The cause is not the venue and not the handset. Android does not pass NR neighbours to an
 * application through CellInfo. On 2026-09-20 the phone handed over between four cells across
 * 1.31 km — which is only possible if the modem was measuring neighbours and reporting them to the
 * network — while `cell_neighbor_count` read zero on all 355 samples, and a diagnostic tool
 * reading the modem directly listed the neighbours the whole time.
 *
 * Why it matters more than the other defects found that day: overlap is the usual driver of
 * remediation work on an in-building system. A confident zero retires a justified recommendation,
 * and it does so in the document the client pays for.
 */
class OverlapAssessableTest {

    private fun cell(pci: Int, rsrp: Int, serving: Boolean = false) =
        ObservedCell(pci, 396250, rsrp, "n25", serving = serving, ageMs = 0)

    private fun point(seq: Long, cells: List<ObservedCell>) = TrackPoint(
        sequence = seq, timestampUtcMillis = 1_758_360_000_000 + seq * 1000,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = cells.firstOrNull { it.serving }?.rsrpDbm, sinrDb = null, rsrqDb = null,
        cellBand = "n25", rat = "5G SA",
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        cells = cells,
    )

    @Test
    fun `a serving-cell-only session cannot assess overlap`() {
        // What every 5G SA survey looks like on Android today.
        val points = (1L..50L).map { point(it, listOf(cell(929, -95, serving = true))) }

        val dom = SessionStats.dominance(points)

        assertEquals(1, dom.maxCellsInSample)
        assertFalse(
            "One cell per sample is nothing to compare, so overlap must not be reported.",
            dom.overlapAssessable,
        )
    }

    @Test
    fun `handing over between several cells does not make overlap assessable`() {
        // The 2026-09-20 drive: four different serving cells, never two at once. Seeing a second
        // cell across the session is not the same as seeing two in one sample, and only the
        // latter can produce an overlap figure.
        val pcis = listOf(929, 422, 531, 751)
        val points = pcis.flatMapIndexed { i, pci ->
            (1L..10L).map { point(i * 10L + it, listOf(cell(pci, -95, serving = true))) }
        }

        val dom = SessionStats.dominance(points)

        assertEquals(4, dom.servers.size)
        assertEquals(1, dom.maxCellsInSample)
        assertFalse(dom.overlapAssessable)
    }

    @Test
    fun `a genuine absence of overlap is still reportable when neighbours were visible`() {
        // LTE does report neighbours, so a measured zero there is real and worth stating. The
        // check must be "was anything ever comparable", not "is the number zero".
        val points = (1L..20L).map {
            point(it, listOf(cell(929, -80, serving = true), cell(531, -105)))
        }

        val dom = SessionStats.dominance(points)

        assertEquals(2, dom.maxCellsInSample)
        assertTrue(dom.overlapAssessable)
        assertEquals("25 dB apart is not overlap.", 0.0, dom.overlapPct, 1e-9)
    }

    @Test
    fun `real overlap is measured when two cells sit within the window`() {
        val points = (1L..20L).map {
            point(it, listOf(cell(929, -95, serving = true), cell(531, -98)))
        }

        val dom = SessionStats.dominance(points)

        assertTrue(dom.overlapAssessable)
        assertEquals(100.0, dom.overlapPct, 1e-9)
        assertEquals(2.0, dom.meanCount, 1e-9)
    }

    @Test
    fun `a session with no cells at all is not assessable either`() {
        val dom = SessionStats.dominance(listOf(point(1, emptyList())))

        assertEquals(0, dom.maxCellsInSample)
        assertFalse(dom.overlapAssessable)
    }
}
