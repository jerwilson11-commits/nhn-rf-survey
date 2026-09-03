package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.ObservedCell
import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SSB layout analysis.
 *
 * The distinction it draws is one a field engineer otherwise needs a scanner for: whether several
 * SSB positions on a band are **carriers** or **sectors**. In a log both look like "more than one
 * channel number on this band", and they mean opposite things — the first is aggregation over the
 * same physical cells, the second is a deliberate per-sector plan of the kind used in stadiums for
 * capacity and to reduce interference between adjacent sectors.
 *
 * Getting it backwards would have an engineer either chasing a plan that does not exist, or
 * missing one that does.
 */
class SsbLayoutTest {

    private fun point(seq: Long, cells: List<ObservedCell>) = TrackPoint(
        sequence = seq, timestampUtcMillis = 1_756_000_000_000 + seq * 1000,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = null, sinrDb = null, rsrqDb = null, cellBand = null, rat = "5G SA",
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        cells = cells,
    )

    private fun cell(pci: Int, channel: Int, band: String = "n41") =
        ObservedCell(pci, channel, -95, band, serving = false, ageMs = 0)

    @Test
    fun `one SSB position on a band is the ordinary case`() {
        val points = listOf(
            point(0, listOf(cell(206, 501390), cell(216, 501390), cell(340, 501390))),
        )

        val layout = SessionStats.ssbLayout(points).single()

        assertEquals("n41", layout.band)
        assertEquals(1, layout.positions.size)
        assertEquals(SessionStats.SsbArrangement.SINGLE, layout.arrangement)
    }

    @Test
    fun `positions sharing a PCI plan are carriers, not sectors`() {
        // The real case from the 2026-09-02 walks: n41 on two channels with the same cells on
        // both. Two carriers over the same sectors, which is aggregation rather than a plan.
        val shared = listOf(206, 216, 256, 340, 673, 865)
        val points = listOf(
            point(0, shared.map { cell(it, 501390) } + shared.map { cell(it, 521310) }),
        )

        val layout = SessionStats.ssbLayout(points).single()

        assertEquals(2, layout.positions.size)
        assertEquals(SessionStats.SsbArrangement.SHARED_PCI_PLAN, layout.arrangement)
        assertTrue(layout.sufficientEvidence)
    }

    @Test
    fun `positions with entirely separate cells are a per-sector plan`() {
        // The stadium case: each sector given its own SSB position.
        val points = listOf(
            point(0, listOf(cell(101, 501390), cell(102, 501390), cell(103, 501390))),
            point(1, listOf(cell(201, 521310), cell(202, 521310), cell(203, 521310))),
        )

        val layout = SessionStats.ssbLayout(points).single()

        assertEquals(SessionStats.SsbArrangement.PER_SECTOR, layout.arrangement)
        assertTrue(layout.sufficientEvidence)
        assertTrue(
            "the meaning must name the deliberate choice",
            layout.arrangement.meaning.contains("deliberately"),
        )
    }

    @Test
    fun `too few cells per position is not enough to tell the arrangements apart`() {
        // With one cell each, disjointness is as likely to mean the walk missed the other sectors
        // as it is to mean a planning decision. The classification is still offered; the evidence
        // flag is what stops it being quoted as a finding.
        val points = listOf(
            point(0, listOf(cell(101, 501390))),
            point(1, listOf(cell(201, 521310))),
        )

        val layout = SessionStats.ssbLayout(points).single()

        assertEquals(SessionStats.SsbArrangement.PER_SECTOR, layout.arrangement)
        assertFalse("one cell per position proves nothing", layout.sufficientEvidence)
    }

    @Test
    fun `partial overlap is reported as mixed rather than forced into a category`() {
        val points = listOf(
            point(0, listOf(cell(101, 501390), cell(102, 501390), cell(103, 501390))),
            point(1, listOf(cell(103, 521310), cell(201, 521310), cell(202, 521310))),
        )

        val layout = SessionStats.ssbLayout(points).single()

        assertEquals(SessionStats.SsbArrangement.MIXED, layout.arrangement)
    }

    @Test
    fun `GSCN is computed for each position`() {
        // The number the sync vendor asks for, filled in per SSB position rather than once.
        val points = listOf(
            point(0, listOf(cell(206, 501390), cell(216, 521310))),
        )

        val layout = SessionStats.ssbLayout(points).single()
        val byChannel = layout.positions.associateBy { it.channel }

        assertEquals(6267, byChannel.getValue(501390).gscn)
        assertEquals(6516, byChannel.getValue(521310).gscn)
        assertEquals(2506.95, byChannel.getValue(501390).freqMhz!!, 0.001)
    }

    @Test
    fun `bands are analysed separately`() {
        // A site with low band and mid band has two independent SSB plans, and merging them would
        // report a per-sector arrangement that does not exist.
        val points = listOf(
            point(0, listOf(cell(206, 501390, "n41"), cell(262, 124590, "n71"))),
        )

        val layouts = SessionStats.ssbLayout(points)

        assertEquals(2, layouts.size)
        assertTrue(layouts.all { it.arrangement == SessionStats.SsbArrangement.SINGLE })
    }

    @Test
    fun `a session with no identified cells produces nothing rather than an empty band`() {
        val points = listOf(point(0, emptyList()))

        assertTrue(SessionStats.ssbLayout(points).isEmpty())
    }
}
