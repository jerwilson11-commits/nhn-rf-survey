package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the statistics to hand-computed values.
 *
 * The same analysis also exists in `mcp-server/session_store.py`, because the MCP server runs
 * off-device and the app cannot reach it from inside a building. Two implementations of one
 * calculation is a real cost; these tests are what stops the two drifting into quoting different
 * numbers at the same client.
 *
 * Expected values below are computed by hand, not taken from the implementation.
 */
class SessionStatsTest {

    private fun pt(
        seq: Long,
        rssi: Int? = null,
        rsrp: Int? = null,
        waypoint: String? = null,
        cellBand: String? = null,
    ) =
        TrackPoint(
            sequence = seq, timestampUtcMillis = 1_756_000_000_000 + seq * 1000,
            latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
            rssiDbm = rssi, ssid = null, bssid = null, channel = null, band = null,
            coChannel = null, adjacentChannel = null,
            rsrpDbm = rsrp, cellBand = cellBand, rat = null,
            floorplanId = "plan.png", floorplanX = 0.5f, floorplanY = 0.5f, waypoint = waypoint,
        )

    @Test
    fun `percentiles interpolate as expected`() {
        // 1..5: p50 = 3. p10 = 1 + (5-1)*0.1 = index 0.4 -> 1 + (2-1)*0.4 = 1.4 -> rounds to 1.
        val v = listOf(1, 2, 3, 4, 5)
        assertEquals(3, SessionStats.percentile(v, 50.0))
        assertEquals(1, SessionStats.percentile(v, 10.0))
        assertEquals(5, SessionStats.percentile(v, 100.0))
        assertEquals(1, SessionStats.percentile(v, 0.0))
    }

    @Test
    fun `percentile of a single value is that value`() {
        assertEquals(7, SessionStats.percentile(listOf(7), 50.0))
    }

    @Test
    fun `percentile of nothing is null, not zero`() {
        assertNull(SessionStats.percentile(emptyList(), 50.0))
    }

    @Test
    fun `missing measurements are counted, never treated as zero`() {
        val pts = listOf(pt(0, rssi = -50), pt(1), pt(2, rssi = -60))
        val s = SessionStats.stats(pts.mapNotNull { it.rssiDbm }, pts.size)
        assertEquals(2, s.samples)
        assertEquals(1, s.missing)
        assertEquals(-55.0, s.mean!!, 0.001)
        // A zero-filled mean would be -36.67, which would read as excellent coverage.
    }

    @Test
    fun `kpi selection prefers cellular when present`() {
        assertEquals(SessionStats.Kpi.WIFI_RSSI, SessionStats.kpiFor(listOf(pt(0, rssi = -50))))
        assertEquals(SessionStats.Kpi.CELL_RSRP, SessionStats.kpiFor(listOf(pt(0, rsrp = -95))))
    }

    @Test
    fun `default thresholds differ by an order of magnitude between radios`() {
        // Not a typo. -75 dBm RSRP would fail essentially every cellular sample ever recorded.
        assertEquals(-75, SessionStats.Kpi.WIFI_RSSI.defaultThresholdDbm)
        assertEquals(-105, SessionStats.Kpi.CELL_RSRP.defaultThresholdDbm)
    }

    @Test
    fun `compliance counts samples at the threshold as passing`() {
        // Threshold -75: -75 passes, -76 fails. Off-by-one here would move a borderline venue
        // across a pass/fail line in a client report.
        val pts = listOf(pt(0, rssi = -74), pt(1, rssi = -75), pt(2, rssi = -76))
        val r = SessionStats.analyse(pts, thresholdDbm = -75)
        assertEquals(3, r.measured)
        assertEquals(1, r.failing)
        assertEquals(66.7, r.compliancePct, 0.05)
    }

    @Test
    fun `contiguous failing runs become holes, isolated dips do not`() {
        // One run of 4 below -75, and a single isolated dip that must not count.
        val pts = listOf(
            pt(0, rssi = -60), pt(1, rssi = -90), pt(2, rssi = -60),   // isolated dip
            pt(3, rssi = -80), pt(4, rssi = -85), pt(5, rssi = -95), pt(6, rssi = -82),
            pt(7, rssi = -60),
        )
        val r = SessionStats.analyse(pts, thresholdDbm = -75, minHoleSamples = 3)
        assertEquals(1, r.holes.size)
        val h = r.holes.first()
        assertEquals(4, h.samples)
        assertEquals(-95, h.worstDbm)
        assertEquals(3L, h.startSeq)
        assertEquals(6L, h.endSeq)
    }

    @Test
    fun `holes carry an indoor position when there is no GPS`() {
        val pts = listOf(
            pt(0, rssi = -90, waypoint = "Stairwell B"),
            pt(1, rssi = -92), pt(2, rssi = -94),
        )
        val h = SessionStats.analyse(pts, thresholdDbm = -75).holes.first()
        assertNull("indoor session has no GPS", h.lat)
        assertEquals("plan.png", h.floorplanId)
        assertEquals("Stairwell B", h.waypoint)
    }

    @Test
    fun `holes are ordered worst first`() {
        val pts = listOf(
            pt(0, rssi = -80), pt(1, rssi = -81), pt(2, rssi = -82),
            pt(3, rssi = -60),
            pt(4, rssi = -100), pt(5, rssi = -101), pt(6, rssi = -102),
        )
        val holes = SessionStats.analyse(pts, thresholdDbm = -75).holes
        assertEquals(2, holes.size)
        assertTrue("worst hole first", holes[0].worstDbm < holes[1].worstDbm)
        assertEquals(-102, holes[0].worstDbm)
    }

    @Test
    fun `cellular sessions use the RSRP bucket scale, not the Wi-Fi one`() {
        // -95 dBm RSRP is GOOD cellular coverage. On the Wi-Fi scale it would land in BAD, which
        // would report a healthy DAS as a failure.
        val r = SessionStats.analyse(listOf(pt(0, rsrp = -95)))
        assertEquals(SessionStats.Kpi.CELL_RSRP, r.kpi)
        val nonZero = r.bucketCounts.filter { it.second > 0 }
        assertEquals(1, nonZero.size)
        assertTrue("expected the GOOD RSRP bucket, got ${nonZero[0].first}",
            nonZero[0].first.contains("−86") || nonZero[0].first.contains("-86"))
    }

    @Test
    fun `empty session does not divide by zero`() {
        val r = SessionStats.analyse(listOf(pt(0), pt(1)))
        assertEquals(0, r.measured)
        assertEquals(0.0, r.compliancePct, 0.001)
        assertTrue(r.holes.isEmpty())
    }

    // ---- Breakdown -------------------------------------------------------
    //
    // These exist because of a specific defect in a competitor's deliverable: a cell detected in
    // 9 of 495 samples was given a column indistinguishable from cells detected in nearly all of
    // them, and the headline overlap metric was written as a fraction under a "%" heading. Both
    // failure modes are pinned here.

    @Test
    fun `breakdown splits by band and shares sum to a hundred`() {
        val points = List(7) { pt(it.toLong(), rsrp = -90, cellBand = "n41") } +
            List(3) { pt((it + 7).toLong(), rsrp = -100, cellBand = "n25") }

        val b = SessionStats.breakdown(points, { it.cellBand })

        assertEquals(2, b.groups.size)
        // Largest group first, not alphabetical.
        assertEquals("n41", b.groups[0].label)
        assertEquals(7, b.groups[0].measured)
        assertEquals(70.0, b.groups[0].sharePct, 0.001)
        assertEquals(30.0, b.groups[1].sharePct, 0.001)
        assertEquals(100.0, b.groups.sumOf { it.sharePct }, 0.001)
        assertEquals(0, b.unlabelled)
    }

    @Test
    fun `unlabelled samples are counted, not folded into a band`() {
        val points = List(4) { pt(it.toLong(), rsrp = -90, cellBand = "n41") } +
            List(6) { pt((it + 4).toLong(), rsrp = -90, cellBand = null) }

        val b = SessionStats.breakdown(points, { it.cellBand })

        assertEquals(1, b.groups.size)
        assertEquals(4, b.groups[0].measured)
        assertEquals(6, b.unlabelled)
        // The share is of measured samples overall, so a band seen in 4 of 10 reads as 40%, not
        // 100% of the labelled subset. Otherwise a band present in a corner of the venue would
        // report as covering all of it.
        assertEquals(40.0, b.groups[0].sharePct, 0.001)
    }

    @Test
    fun `a band covering a sliver of the survey is marked thin`() {
        // The competitor's PCI 291 appeared in 9 of 495 samples and still got its own column.
        // Same ratio here. Note the threshold is *under* 2%, so a group at exactly 2.0% is not
        // thin — 9/495 is 1.82% and is.
        val points = List(486) { pt(it.toLong(), rsrp = -90, cellBand = "n41") } +
            List(9) { pt((it + 486).toLong(), rsrp = -70, cellBand = "n71") }

        val b = SessionStats.breakdown(points, { it.cellBand })
        val thin = b.groups.single { it.label == "n71" }

        assertEquals(100.0 * 9 / 495, thin.sharePct, 0.001)
        assertTrue("2% share must fall below the thin threshold", thin.thin)
        assertTrue("the dominant band must not be marked thin", !b.groups.single { it.label == "n41" }.thin)
        // The thin group has the *better* signal — so it would flatter the report if promoted,
        // which is precisely why the share has to be shown next to it.
        assertEquals(-70, thin.stats.median)
    }

    @Test
    fun `compliance is a percentage, not a fraction`() {
        // The competitor's sheet reports 285/495 as 0.5757..., labelled "%". A reader concludes
        // half a percent where the truth is 57.6. This pins ours to the 0-100 convention.
        val points = List(57) { pt(it.toLong(), rsrp = -90, cellBand = "b") } +
            List(43) { pt((it + 57).toLong(), rsrp = -120, cellBand = "b") }

        val g = SessionStats.breakdown(points, { it.cellBand }).groups.single()

        assertEquals(57.0, g.compliancePct, 0.001)
        assertTrue("a fraction would be <= 1.0 and read as half a percent", g.compliancePct > 1.0)
    }

    @Test
    fun `breakdown of a session with no measurements is empty, not zeroed`() {
        val b = SessionStats.breakdown(listOf(pt(0), pt(1)), { it.cellBand })

        assertTrue(b.groups.isEmpty())
        assertEquals(0, b.unlabelled)
    }

    @Test
    fun `band label carries the RAT for cellular and the plain band for Wi-Fi`() {
        val cell = pt(0, rsrp = -90, cellBand = "n41")
        assertEquals("n41", SessionStats.bandOf(cell, SessionStats.Kpi.CELL_RSRP))
        // A Wi-Fi session must not pick up the cellular band column.
        assertNull(SessionStats.bandOf(cell, SessionStats.Kpi.WIFI_RSSI))
    }
}
