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

    private fun point(seq: Long, cells: List<ObservedCell>) = TrackPoint(
        sequence = seq, timestampUtcMillis = 1_758_360_000_000 + seq * 1000,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = -95, sinrDb = null, rsrqDb = null,
        cellBand = "n25", rat = "5G SA",
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        cells = cells,
    )

    private fun servingOnly(n: Int) =
        (1L..n).map { point(it, listOf(cell(929, 396250, -95, serving = true))) }

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

    @Test
    fun `a Wi-Fi only survey makes no claim about cellular neighbours`() {
        val points = (1L..40L).map { point(it, emptyList()) }

        val r = SessionStats.neighbourVisibility(points)

        assertEquals(SessionStats.NeighbourVisibility.NO_CELLULAR, r.visibility)
        assertEquals(0, r.cellularSamples)
    }
}
