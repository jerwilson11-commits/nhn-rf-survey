package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.ObservedCell
import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the difference between "no neighbours here" and "this handset does not report neighbours".
 *
 * A neighbour count of zero answers two different questions and looks identical either way. One is
 * a measurement of the site; the other is a property of the instrument, and on the OnePlus it is
 * always the second — all three Android cell surfaces return the serving cell alone while a
 * diagnostic tool reading the modem lists PCI 531 and 729 beside the serving 929, and the handset
 * hands over between cells perfectly well.
 *
 * Getting this wrong has a direction. Reported as an absence, it tells a client their site has no
 * sector overlap, which is the finding that would have justified remediation.
 */
class NeighbourVisibilityTest {

    private fun cell(pci: Int, ch: Int, rsrp: Int, serving: Boolean = false) =
        ObservedCell(pci, ch, rsrp, "n25", serving = serving, ageMs = 0)

    private fun point(
        seq: Long,
        cells: List<ObservedCell>,
        modem: Boolean? = null,
    ) = TrackPoint(
        sequence = seq, timestampUtcMillis = 1_758_360_000_000 + seq * 1000,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = -95, sinrDb = null, rsrqDb = null,
        cellBand = "n25", rat = "5G SA",
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        cells = cells,
        modemNeighbours = modem,
    )

    private fun servingOnly(n: Int, modem: Boolean? = null) =
        (1L..n).map { point(it, listOf(cell(929, 396250, -95, serving = true)), modem) }

    @Test
    fun `a long survey that never saw a neighbour names the handset, not the site`() {
        // The 2026-09-20 drive: 355 samples, four serving cells, never a second cell in one sample.
        val r = SessionStats.neighbourVisibility(servingOnly(355))

        assertEquals(SessionStats.NeighbourVisibility.NEVER_REPORTED, r.visibility)
        assertEquals(355, r.cellularSamples)
        assertEquals(0, r.samplesWithNeighbour)
    }

    @Test
    fun `a short survey that saw none concludes nothing either way`() {
        // Ten samples in a quiet spot legitimately sees nothing. Claiming a handset limitation
        // from that would be the same overreach in the opposite direction.
        val r = SessionStats.neighbourVisibility(servingOnly(10))

        assertEquals(SessionStats.NeighbourVisibility.TOO_FEW_SAMPLES, r.visibility)
        assertEquals(10, r.cellularSamples)
    }

    @Test
    fun `the boundary is inclusive of the evidence threshold`() {
        val below = SessionStats.neighbourVisibility(
            servingOnly(SessionStats.NEIGHBOUR_EVIDENCE_SAMPLES - 1),
        )
        val at = SessionStats.neighbourVisibility(
            servingOnly(SessionStats.NEIGHBOUR_EVIDENCE_SAMPLES),
        )

        assertEquals(SessionStats.NeighbourVisibility.TOO_FEW_SAMPLES, below.visibility)
        assertEquals(SessionStats.NeighbourVisibility.NEVER_REPORTED, at.visibility)
    }

    @Test
    fun `neighbours seen at all makes every later zero a real measurement`() {
        // The Pixel session: neighbours present, so a zero elsewhere means the site, not the tool.
        val points = servingOnly(50) + (51L..57L).map {
            point(
                it,
                listOf(
                    cell(206, 501390, -88, serving = true),
                    cell(216, 501390, -98),
                    cell(256, 501390, -102),
                ),
            )
        }

        val r = SessionStats.neighbourVisibility(points)

        assertEquals(SessionStats.NeighbourVisibility.REPORTED, r.visibility)
        assertEquals(7, r.samplesWithNeighbour)
        assertEquals(57, r.cellularSamples)
    }

    @Test
    fun `the same PCI on two channels counts as two distinct neighbours`() {
        // Observed on the Pixel: PCI 216 on 501390 and on 521310. A PCI is unique only within a
        // carrier, so collapsing them would undercount the neighbourhood.
        val points = (1L..5L).map {
            point(
                it,
                listOf(
                    cell(206, 501390, -88, serving = true),
                    cell(216, 501390, -98),
                    cell(216, 521310, -99),
                ),
            )
        }

        assertEquals(2, SessionStats.neighbourVisibility(points).distinctNeighbours)
    }

    // ---- a measured absence is not the same as an unmeasurable one --------

    @Test
    fun `the modem reporting none across a long survey is a finding about the site`() {
        // The opposite conclusion to NEVER_REPORTED from an identical count of zero. The modem
        // was read on every sample and saw nothing, so this location really is served by one
        // cell -- and a report may say so.
        val r = SessionStats.neighbourVisibility(servingOnly(355, modem = true))

        assertEquals(SessionStats.NeighbourVisibility.MEASURED_NONE, r.visibility)
        assertEquals(355, r.modemReadSamples)
    }

    @Test
    fun `without the modem the same zero stays a statement about the instrument`() {
        val r = SessionStats.neighbourVisibility(servingOnly(355, modem = false))

        assertEquals(SessionStats.NeighbourVisibility.NEVER_REPORTED, r.visibility)
        assertEquals(0, r.modemReadSamples)
    }

    @Test
    fun `a session recorded before the column existed claims nothing extra`() {
        // Null means "we do not know which instrument this came from". Treating it as a modem
        // read would let every old session be reported as a measured absence of neighbours.
        val r = SessionStats.neighbourVisibility(servingOnly(355, modem = null))

        assertEquals(SessionStats.NeighbourVisibility.NEVER_REPORTED, r.visibility)
    }

    @Test
    fun `the strong claim needs as much evidence as the other one`() {
        // Modem-read, but only a handful of samples. Enough to be sure of nothing either way.
        val few = SessionStats.NEIGHBOUR_EVIDENCE_SAMPLES - 1
        val r = SessionStats.neighbourVisibility(servingOnly(few, modem = true))

        assertEquals(SessionStats.NeighbourVisibility.TOO_FEW_SAMPLES, r.visibility)
    }

    @Test
    fun `a survey only partly read from the modem does not claim a measured absence`() {
        // Root lost partway, or the helper failed. Most samples were never asked, so the
        // absence is not established even though the total sample count is ample.
        val points = servingOnly(300, modem = false) + servingOnly(10, modem = true)

        val r = SessionStats.neighbourVisibility(points)

        assertEquals(SessionStats.NeighbourVisibility.NEVER_REPORTED, r.visibility)
        assertEquals(10, r.modemReadSamples)
    }

    @Test
    fun `neighbours actually seen still outrank every other consideration`() {
        val points = servingOnly(40, modem = true) + (41L..45L).map {
            point(
                it,
                listOf(
                    cell(206, 501390, -88, serving = true),
                    cell(216, 501390, -98),
                ),
                modem = true,
            )
        }

        assertEquals(
            SessionStats.NeighbourVisibility.REPORTED,
            SessionStats.neighbourVisibility(points).visibility,
        )
    }

    @Test
    fun `a Wi-Fi only survey makes no claim about cellular neighbours`() {
        val points = (1L..40L).map { point(it, emptyList()) }

        val r = SessionStats.neighbourVisibility(points)

        assertEquals(SessionStats.NeighbourVisibility.NO_CELLULAR, r.visibility)
        assertEquals(0, r.cellularSamples)
    }
}
