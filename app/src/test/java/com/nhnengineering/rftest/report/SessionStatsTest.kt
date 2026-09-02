package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.ObservedCell
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
        cells: List<ObservedCell> = emptyList(),
        sinr: Int? = null,
    ) =
        TrackPoint(
            sequence = seq, timestampUtcMillis = 1_756_000_000_000 + seq * 1000,
            latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
            rssiDbm = rssi, ssid = null, bssid = null, channel = null, band = null,
            coChannel = null, adjacentChannel = null,
            rsrpDbm = rsrp, sinrDb = sinr, rsrqDb = null, cellBand = cellBand, rat = null,
            floorplanId = "plan.png", floorplanX = 0.5f, floorplanY = 0.5f, waypoint = waypoint,
            cells = cells,
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

    // ---- Dominance -------------------------------------------------------

    private fun cell(
        pci: Int?,
        rsrp: Int?,
        serving: Boolean = false,
        ageMs: Long = 0,
        channel: Int? = 1,
    ) = ObservedCell(
        pci = pci, channel = channel, rsrpDbm = rsrp, band = null,
        serving = serving, ageMs = ageMs,
    )

    @Test
    fun `dominant sectors are the cells within the window of the strongest`() {
        // Strongest -80. Window 6 dB, so -80, -84 and -86 are dominant; -90 is not.
        val p = pt(
            0, rsrp = -80,
            cells = listOf(
                cell(1, -80, serving = true), cell(2, -84), cell(3, -86), cell(4, -90),
            ),
        )

        val d = SessionStats.dominance(listOf(p))

        assertEquals(1, d.samples)
        assertEquals(listOf(3 to 1), d.countHistogram)
        assertEquals(3.0, d.meanCount, 0.001)
    }

    @Test
    fun `the window boundary is inclusive`() {
        // Exactly 6 dB down counts; 7 dB does not.
        val p = pt(0, rsrp = -80, cells = listOf(cell(1, -80, serving = true), cell(2, -86), cell(3, -87)))
        assertEquals(listOf(2 to 1), SessionStats.dominance(listOf(p)).countHistogram)
    }

    @Test
    fun `overlap is the share of samples with two or more dominant sectors`() {
        // The competitor's exact shape: 285 of 495 samples with 2+ dominant sectors. They report
        // it as 0.5757..., labelled "%". The correct answer is 57.58.
        val overlapping = List(285) {
            pt(it.toLong(), rsrp = -80, cells = listOf(cell(1, -80, serving = true), cell(2, -82)))
        }
        val clean = List(210) {
            pt((it + 285).toLong(), rsrp = -80, cells = listOf(cell(1, -80, serving = true), cell(2, -95)))
        }

        val d = SessionStats.dominance(overlapping + clean)

        assertEquals(495, d.samples)
        assertEquals(57.58, d.overlapPct, 0.01)
        // The assertion that matters: a fraction here would read as half a percent.
        assertTrue("overlap must be on 0-100, not 0-1", d.overlapPct > 1.0)
    }

    @Test
    fun `a cell with no level is excluded, never treated as zero dBm`() {
        // 0 dBm would be the strongest reading in the file and would win every ranking. The
        // levelled cells must decide the outcome alone.
        val p = pt(0, rsrp = -80, cells = listOf(cell(1, -80, serving = true), cell(2, null), cell(3, -95)))

        val d = SessionStats.dominance(listOf(p))

        assertEquals(listOf(1 to 1), d.countHistogram)
        assertEquals(1, d.servers.single { it.pci == 1 }.bestServerIn)
        assertTrue("the unlevelled cell must not appear", d.servers.none { it.pci == 2 })
    }

    @Test
    fun `stale neighbours do not count as simultaneous by default`() {
        // Retained for the live display, but it was not in this sample's report, so counting it
        // would invent a simultaneity that was never observed.
        val p = pt(0, rsrp = -80, cells = listOf(cell(1, -80, serving = true), cell(2, -81, ageMs = 4_000)))

        assertEquals(listOf(1 to 1), SessionStats.dominance(listOf(p)).countHistogram)
        // ...but the caller can widen the window deliberately.
        assertEquals(listOf(2 to 1), SessionStats.dominance(listOf(p), maxAgeMs = 10_000).countHistogram)
    }

    @Test
    fun `samples whose cells all lack levels are counted as excluded`() {
        val p = pt(0, cells = listOf(cell(1, null), cell(2, null)))

        val d = SessionStats.dominance(listOf(p))

        assertEquals(0, d.samples)
        assertEquals(1, d.excluded)
    }

    @Test
    fun `detection rate distinguishes a real server from a statistical accident`() {
        // PCI 291 in 9 of 495 samples -- the competitor gave it a column with nothing to say so.
        val common = List(486) {
            pt(it.toLong(), rsrp = -80, cells = listOf(cell(101, -80, serving = true)))
        }
        val rare = List(9) {
            pt((it + 486).toLong(), rsrp = -80, cells = listOf(cell(101, -80, serving = true), cell(291, -110)))
        }

        val d = SessionStats.dominance(common + rare)

        assertEquals(100.0, d.servers.single { it.pci == 101 }.detectionPct, 0.001)
        assertEquals(100.0 * 9 / 495, d.servers.single { it.pci == 291 }.detectionPct, 0.001)
        // It is never the best server, so it must not head the table.
        assertEquals(0, d.servers.single { it.pci == 291 }.bestServerIn)
        assertEquals(101, d.servers.first().pci)
    }

    @Test
    fun `a session with no cellular cells yields an empty dominance result`() {
        val d = SessionStats.dominance(listOf(pt(0, rssi = -60), pt(1, rssi = -65)))

        assertEquals(0, d.samples)
        assertEquals(0, d.excluded)
        assertTrue(d.servers.isEmpty())
    }

    @Test
    fun `a physically impossible level is rejected, not ranked`() {
        // 0 dBm is one milliwatt at the antenna. Earlier builds wrote it as the sentinel for "no
        // level"; if it reached the ranking it would beat every real cell in the survey.
        val p = pt(0, rsrp = -95, cells = listOf(cell(1, -95, serving = true), cell(2, 0), cell(3, -99)))

        val d = SessionStats.dominance(listOf(p))

        assertEquals(1, d.servers.single { it.pci == 1 }.bestServerIn)
        assertTrue("0 dBm must never be treated as a measurement", d.servers.none { it.pci == 2 })
        assertEquals(listOf(2 to 1), d.countHistogram)
    }

    @Test
    fun `levels at the edge of the reportable range are kept`() {
        // -140 is the bottom of LTE's reportable RSRP range and -44 the top. Both are real
        // measurements and the guard must not discard them.
        val p = pt(0, rsrp = -44, cells = listOf(cell(1, -44, serving = true), cell(2, -140)))

        val d = SessionStats.dominance(listOf(p))

        assertEquals(2, d.servers.size)
        assertEquals(1, d.samples)
    }

    // Both of the following were found by rendering a report and reading it, not by reasoning
    // about the code. The first generated PDF reported PCI 216 as "detected in 36 samples" out of
    // 28 analysed -- a number that cannot be true, sitting in a client deliverable.

    @Test
    fun `a cell seen twice in one sample is detected in one sample`() {
        // Real data from session_20260901_152732: eight of its rows report the same PCI on two
        // channels at once. Counting occurrences rather than samples produced 37 detections
        // across 29 samples.
        val p = pt(
            0, rsrp = -95,
            cells = listOf(
                cell(206, -95, serving = true, channel = 501390),
                cell(216, -108, channel = 501390),
                cell(216, -110, channel = 521310),
            ),
        )

        val d = SessionStats.dominance(listOf(p))

        // Two distinct cells, because PCI 216 on two channels is two cells -- but each is
        // detected in exactly one sample, never two.
        assertTrue(d.servers.all { it.detectedIn <= d.samples })
        assertEquals(1, d.servers.first { it.pci == 216 && it.channel == 501390 }.detectedIn)
        assertEquals(1, d.servers.first { it.pci == 216 && it.channel == 521310 }.detectedIn)
    }

    @Test
    fun `the same PCI on two channels is two cells, not one`() {
        // A PCI is unique only within a carrier -- 504 values for LTE, 1008 for NR. Merging by
        // PCI alone would attribute one cell's coverage to another.
        val p = pt(
            0, rsrp = -90,
            cells = listOf(cell(216, -90, serving = true, channel = 100), cell(216, -120, channel = 200)),
        )

        val d = SessionStats.dominance(listOf(p))

        assertEquals(2, d.servers.size)
        assertEquals(setOf(100, 200), d.servers.map { it.channel }.toSet())
        // Only the strong one is the best server; the weak one must not inherit its share.
        assertEquals(1, d.servers.first { it.channel == 100 }.bestServerIn)
        assertEquals(0, d.servers.first { it.channel == 200 }.bestServerIn)
    }

    @Test
    fun `detection rate can never exceed one hundred percent`() {
        // The property the broken version violated, stated directly.
        val points = List(20) {
            pt(
                it.toLong(), rsrp = -95,
                cells = listOf(
                    cell(1, -95, serving = true, channel = 10),
                    cell(2, -100, channel = 10),
                    cell(2, -101, channel = 10),
                ),
            )
        }

        val d = SessionStats.dominance(points)

        assertTrue(d.servers.all { it.detectionPct <= 100.0 })
        assertEquals(20, d.servers.first { it.pci == 2 }.detectedIn)
        // The stronger of the two duplicate observations is the one kept.
        assertEquals(-100, d.servers.first { it.pci == 2 }.stats.median)
    }

    // ---- Update cadence --------------------------------------------------
    //
    // From the 2026-09-02 walk: SS-SINR held one value for 99 consecutive samples while RSRP
    // changed 88 times. Both were written on every sample, so nothing in the file distinguishes a
    // field the modem refreshed from one it did not -- you have to count the changes.

    @Test
    fun `cadence counts transitions, not samples`() {
        val values = listOf(10, 10, 10, 12, 12, 9)
        val points = values.mapIndexed { i, v -> pt(i.toLong(), rsrp = -90, sinr = v) }

        val c = SessionStats.cadence(points) { it.sinrDb }

        assertEquals(6, c.samples)
        assertEquals(2, c.changes)
        assertEquals(3, c.longestRunSamples)
    }

    @Test
    fun `a field that never changes is one run spanning the session`() {
        val points = List(50) { pt(it.toLong(), rsrp = -90, sinr = 20) }

        val c = SessionStats.cadence(points) { it.sinrDb }

        assertEquals(0, c.changes)
        assertEquals(50, c.longestRunSamples)
    }

    @Test
    fun `the longest run is found when it ends the session`() {
        // The tail is the case an off-by-one loses: the final run never hits a transition, so a
        // loop that only measures on change would miss it entirely.
        val points = (listOf(1, 2, 3) + List(20) { 7 })
            .mapIndexed { i, v -> pt(i.toLong(), rsrp = -90, sinr = v) }

        val c = SessionStats.cadence(points) { it.sinrDb }

        assertEquals(3, c.changes)
        assertEquals(20, c.longestRunSamples)
    }

    @Test
    fun `the longest run is reported in seconds from the timestamps`() {
        // pt() spaces samples one second apart, so a 20-sample run spans 19 s between its first
        // and last timestamp.
        val points = List(20) { pt(it.toLong(), rsrp = -90, sinr = 15) }

        val c = SessionStats.cadence(points) { it.sinrDb }

        assertEquals(19.0, c.longestRunSeconds!!, 0.001)
    }

    @Test
    fun `absent samples are skipped rather than counted as a change`() {
        // A field that is missing, then present with the same value, has not changed. Counting
        // the gap as a transition would make a slow field look responsive -- the opposite of
        // what this measurement exists to detect.
        val points = listOf(
            pt(0, rsrp = -90, sinr = 20),
            pt(1, rsrp = -90, sinr = null),
            pt(2, rsrp = -90, sinr = 20),
        )

        val c = SessionStats.cadence(points) { it.sinrDb }

        assertEquals(2, c.samples)
        assertEquals(0, c.changes)
    }

    @Test
    fun `a field absent throughout yields an empty cadence, not a division by zero`() {
        val c = SessionStats.cadence(List(10) { pt(it.toLong(), rsrp = -90) }) { it.sinrDb }

        assertEquals(0, c.samples)
        assertEquals(0, c.changes)
        assertNull(c.longestRunSeconds)
    }

    @Test
    fun `the walk's disparity is what the report threshold is meant to catch`() {
        // RSRP moving on most samples while SINR sits still is the shape the methodology note
        // fires on: SINR changes must be at most half RSRP's for the caveat to apply.
        val points = List(100) {
            pt(it.toLong(), rsrp = -90 - (it % 7), sinr = if (it < 99) 20 else 9)
        }

        val rsrp = SessionStats.cadence(points) { it.rsrpDbm }
        val sinr = SessionStats.cadence(points) { it.sinrDb }

        assertEquals(1, sinr.changes)
        assertEquals(99, sinr.longestRunSamples)
        assertTrue("RSRP must change far more often", sinr.changes * 2 <= rsrp.changes)
    }
}
