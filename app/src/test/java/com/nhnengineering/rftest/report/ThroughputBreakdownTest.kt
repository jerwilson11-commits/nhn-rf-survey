package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins throughput segmentation by band and technology.
 *
 * This is the table a DAS upgrade argument is actually made from: which sector, on which band, on
 * which technology, is not delivering. It has to survive the awkward shapes real walks produce --
 * one band carrying two technologies, tests that succeed in one direction only, and sample counts
 * small enough that a single bad run would move a mean.
 */
class ThroughputBreakdownTest {

    private fun point(
        seq: Long,
        band: String? = "n41",
        rat: String? = "5G SA",
        dl: Double? = null,
        ul: Double? = null,
        error: String? = null,
    ) = TrackPoint(
        sequence = seq, timestampUtcMillis = 1_756_000_000_000 + seq * 1000,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = -90, sinrDb = null, rsrqDb = null,
        cellBand = band, rat = rat,
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        downloadMbps = dl, uploadMbps = ul, throughputError = error,
    )

    private fun byBandAndTech(p: TrackPoint) = SessionStats.bandOf(p, SessionStats.Kpi.CELL_RSRP)

    @Test
    fun `one band carrying two technologies splits into two rows`() {
        // The case that prompted this: a 1900 MHz band running LTE and 5G SA together. Reporting
        // one throughput figure for the band would average a 5G carrier with an LTE one and
        // describe neither.
        val points = listOf(
            point(1, band = "B2", rat = "LTE", dl = 20.0),
            point(2, band = "B2", rat = "LTE", dl = 30.0),
            point(3, band = "B2", rat = "LTE", dl = 40.0),
            point(4, band = "B2", rat = "5G SA", dl = 100.0),
            point(5, band = "B2", rat = "5G SA", dl = 200.0),
            point(6, band = "B2", rat = "5G SA", dl = 300.0),
        )

        val b = SessionStats.throughputBreakdown(points, ::byBandAndTech)

        assertEquals(listOf("B2  (LTE)", "B2  (5G SA)"), b.groups.map { it.label })
        assertEquals(30.0, b.groups[0].dlMedianMbps!!, 1e-9)
        assertEquals(200.0, b.groups[1].dlMedianMbps!!, 1e-9)
    }

    @Test
    fun `the same session grouped by band alone merges the technologies`() {
        val points = listOf(
            point(1, band = "B2", rat = "LTE", dl = 20.0),
            point(2, band = "B2", rat = "5G SA", dl = 300.0),
        )

        val b = SessionStats.throughputBreakdown(points) { it.cellBand }

        assertEquals(1, b.groups.size)
        assertEquals("B2", b.groups.single().label)
        assertEquals(2, b.groups.single().downloadCount)
    }

    @Test
    fun `the slowest median download leads, because that is the sector being argued about`() {
        val points = listOf(
            point(1, band = "n41", dl = 300.0),
            point(2, band = "n25", dl = 40.0),
            point(3, band = "n71", dl = 90.0),
        )

        val b = SessionStats.throughputBreakdown(points) { it.cellBand }

        assertEquals(listOf("n25", "n71", "n41"), b.groups.map { it.label })
    }

    @Test
    fun `an even number of samples interpolates the median`() {
        val points = listOf(10.0, 20.0, 30.0, 40.0).mapIndexed { i, v -> point(i.toLong(), dl = v) }

        // Hand-computed: k = (4-1) * 0.5 = 1.5, so 20 + (30 - 20) * 0.5.
        assertEquals(25.0, SessionStats.throughputBreakdown(points) { it.cellBand }
            .groups.single().dlMedianMbps!!, 1e-9)
    }

    @Test
    fun `minimum and maximum are carried so the spread is not averaged away`() {
        val points = listOf(5.0, 50.0, 100.0).mapIndexed { i, v -> point(i.toLong(), dl = v) }

        val g = SessionStats.throughputBreakdown(points) { it.cellBand }.groups.single()

        assertEquals(50.0, g.dlMedianMbps!!, 1e-9)
        assertEquals(5.0, g.dlMinMbps!!, 1e-9)
        assertEquals(100.0, g.dlMaxMbps!!, 1e-9)
    }

    @Test
    fun `an upload-only test does not inflate the download count`() {
        // The 2026-09-02 walk produced eight rows carrying an upload and no download. Counting one
        // "tests" figure for both directions would imply downloads that never ran.
        val points = listOf(
            point(1, dl = 100.0, ul = 10.0),
            point(2, ul = 12.0, error = "download timed out"),
            point(3, ul = 14.0, error = "download timed out"),
        )

        val g = SessionStats.throughputBreakdown(points) { it.cellBand }.groups.single()

        assertEquals(1, g.downloadCount)
        assertEquals(100.0, g.dlMedianMbps!!, 1e-9)
        assertEquals(3, g.uploadCount)
        assertEquals(12.0, g.ulMedianMbps!!, 1e-9)
    }

    @Test
    fun `a half-successful test counts as both a measurement and a failure`() {
        // Deliberate: the upload is real data and the failed download is a finding. A band where
        // most downloads failed must not be describable by the median of the one that worked.
        val points = listOf(point(1, ul = 12.0, error = "download timed out"))

        val g = SessionStats.throughputBreakdown(points) { it.cellBand }.groups.single()

        assertEquals(1, g.uploadCount)
        assertEquals(1, g.failedCount)
        assertEquals(0, g.downloadCount)
        assertNull(g.dlMedianMbps)
    }

    @Test
    fun `a test that produced nothing at all is still counted as an attempt`() {
        val points = listOf(point(1, error = "no route to host"))

        val g = SessionStats.throughputBreakdown(points) { it.cellBand }.groups.single()

        assertEquals(1, g.failedCount)
        assertEquals(0, g.downloadCount)
        assertEquals(0, g.uploadCount)
    }

    @Test
    fun `thin groups are flagged rather than suppressed`() {
        val thin = listOf(point(1, dl = 50.0), point(2, dl = 60.0))
        val solid = (1L..3L).map { point(it, dl = 50.0) }

        assertTrue(SessionStats.throughputBreakdown(thin) { it.cellBand }.groups.single().isThin)
        assertFalse(SessionStats.throughputBreakdown(solid) { it.cellBand }.groups.single().isThin)
        // Still reported: suppressing the figure would lose real information.
        assertEquals(55.0, SessionStats.throughputBreakdown(thin) { it.cellBand }
            .groups.single().dlMedianMbps!!, 1e-9)
    }

    @Test
    fun `tests with no band are counted rather than dropped`() {
        // A report that silently analyses six of ten tests overstates its own coverage.
        val points = listOf(
            point(1, band = "n41", dl = 100.0),
            point(2, band = null, dl = 90.0),
            point(3, band = null, dl = 80.0),
        )

        val b = SessionStats.throughputBreakdown(points, ::byBandAndTech)

        assertEquals(1, b.groups.size)
        assertEquals(2, b.unlabelled)
    }

    @Test
    fun `rows that carried no test at all are ignored entirely`() {
        val points = listOf(point(1), point(2), point(3, dl = 100.0))

        val b = SessionStats.throughputBreakdown(points) { it.cellBand }

        assertEquals(1, b.groups.single().downloadCount)
        assertEquals(0, b.unlabelled)
    }

    @Test
    fun `a session with no speed tests produces nothing rather than an empty table`() {
        val b = SessionStats.throughputBreakdown(listOf(point(1), point(2))) { it.cellBand }

        assertFalse(b.anyMeasured)
        assertEquals(0, b.unlabelled)
    }
}
