package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.ObservedCell
import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the stronger-neighbour analysis.
 *
 * This is the part of the app that tells an engineer something a handset display cannot: that a
 * better cell was available and the network stayed put. It is also the part most able to waste
 * their afternoon, because two of the three ways a neighbour out-runs the server are entirely
 * normal — deliberate band retention, and the serving site's own second carrier seen through
 * aggregation. The tests exist mostly to keep those two out of the findings.
 */
class HandoverFindingsTest {

    private fun point(
        seq: Long,
        servingRsrp: Int,
        servingPci: Int = 206,
        servingChannel: Int = 521390,
        servingBand: String = "n41",
        neighbours: List<ObservedCell> = emptyList(),
    ) = TrackPoint(
        sequence = seq, timestampUtcMillis = 1_756_000_000_000 + seq * 1000,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = servingRsrp, sinrDb = null, rsrqDb = null,
        cellBand = servingBand, rat = "5G SA",
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        cells = listOf(
            ObservedCell(servingPci, servingChannel, servingRsrp, servingBand, serving = true, ageMs = 0),
        ) + neighbours,
    )

    private fun nb(pci: Int, rsrp: Int, channel: Int = 521390, band: String = "n41", age: Long = 0) =
        ObservedCell(pci, channel, rsrp, band, serving = false, ageMs = age)

    @Test
    fun `a same-band neighbour beating the server for several seconds is a finding`() {
        val points = (0L until 10L).map {
            point(it, servingRsrp = -100, neighbours = listOf(nb(300, -90)))
        }

        val f = SessionStats.strongerNeighbours(points)
        val n = f.neighbours.single()

        assertEquals(300, n.pci)
        assertEquals(10, n.samples)
        assertEquals(10, n.maxMarginDb)
        assertTrue("sustained", n.sustained)
        assertTrue("same band, so worth chasing", n.likelyHandoverIssue)
        assertTrue(f.hasLikelyHandoverIssue)
    }

    @Test
    fun `a low-band neighbour beating a mid-band server is policy, not a fault`() {
        // The real case from the 2026-09-02 walk: n71 up to 18 dB above the serving n41 cell for
        // eleven consecutive samples. Carriers hold devices on mid band for capacity, so more
        // signal on a narrower busier carrier is not a better connection. Reporting this as a
        // missed handover would send an engineer chasing a neighbour relation that is working as
        // designed.
        val points = (0L until 11L).map {
            point(it, servingRsrp = -100, neighbours = listOf(nb(262, -82, channel = 124590, band = "n71")))
        }

        val n = SessionStats.strongerNeighbours(points).neighbours.single()

        assertEquals(18, n.maxMarginDb)
        assertTrue("still reported as sustained", n.sustained)
        assertTrue("flagged as inter-band", n.interBand)
        assertFalse("must not be presented as a handover issue", n.likelyHandoverIssue)
    }

    @Test
    fun `the serving cell's own second carrier is aggregation, not a neighbour`() {
        // Same PCI, different channel. Almost always the same site seen through carrier
        // aggregation. Reporting it would have an engineer looking for a neighbour relation
        // between a cell and itself.
        val points = (0L until 8L).map {
            point(it, servingRsrp = -100, servingPci = 206, servingChannel = 521390,
                neighbours = listOf(nb(206, -95, channel = 501390)))
        }

        val n = SessionStats.strongerNeighbours(points).neighbours.single()

        assertTrue(n.samePciAsServing)
        assertFalse("aggregation is not a sustained finding", n.sustained)
        assertFalse(n.likelyHandoverIssue)
    }

    @Test
    fun `a brief overshoot is fading and is not called sustained`() {
        // Multipath does this constantly. Two samples is not evidence of anything.
        val points = listOf(
            point(0, -100, neighbours = listOf(nb(300, -90))),
            point(1, -100, neighbours = listOf(nb(300, -90))),
            point(2, -100, neighbours = listOf(nb(300, -115))),
            point(3, -100, neighbours = listOf(nb(300, -115))),
        )

        val n = SessionStats.strongerNeighbours(points).neighbours.single()

        assertEquals(2, n.longestRunSamples)
        assertFalse(n.sustained)
    }

    @Test
    fun `a margin inside measurement noise does not count`() {
        // 1 dB is inside the receiver's own uncertainty. Counting it would fill every survey with
        // findings that are indistinguishable from rounding.
        val points = (0L until 10L).map {
            point(it, servingRsrp = -100, neighbours = listOf(nb(300, -99)))
        }

        assertTrue(SessionStats.strongerNeighbours(points).neighbours.isEmpty())
    }

    @Test
    fun `a stale neighbour is not compared against a current serving level`() {
        // Comparing a retained neighbour with a live serving reading compares two instants and
        // manufactures an overshoot that never happened.
        val points = (0L until 10L).map {
            point(it, servingRsrp = -100, neighbours = listOf(nb(300, -85, age = 4_000)))
        }

        assertEquals(0, SessionStats.strongerNeighbours(points).analysed)
        assertTrue(SessionStats.strongerNeighbours(points).neighbours.isEmpty())
    }

    @Test
    fun `a gap in the data breaks the run rather than extending it`() {
        // Five good samples, a sample with no neighbour data, then five more. That is two runs of
        // five, not one of ten -- the gap is not evidence of continuation.
        val points = buildList {
            repeat(5) { add(point(it.toLong(), -100, neighbours = listOf(nb(300, -90)))) }
            add(point(5, -100))
            repeat(5) { add(point((it + 6).toLong(), -100, neighbours = listOf(nb(300, -90)))) }
        }

        val n = SessionStats.strongerNeighbours(points).neighbours.single()

        assertEquals(10, n.samples)
        assertEquals("the gap must break the run", 5, n.longestRunSamples)
    }

    @Test
    fun `the longest run is measured in seconds from the timestamps`() {
        val points = (0L until 6L).map {
            point(it, servingRsrp = -100, neighbours = listOf(nb(300, -90)))
        }

        val n = SessionStats.strongerNeighbours(points).neighbours.single()

        assertEquals(6, n.longestRunSamples)
        assertEquals(5.0, n.longestRunSeconds!!, 0.001)
    }

    @Test
    fun `a session with no neighbours produces no findings and no division by zero`() {
        val points = (0L until 5L).map { point(it, -95) }
        val f = SessionStats.strongerNeighbours(points)

        assertEquals(0, f.analysed)
        assertEquals(0.0, f.sharePct, 0.001)
        assertTrue(f.neighbours.isEmpty())
        assertFalse(f.hasSustained)
    }
}
