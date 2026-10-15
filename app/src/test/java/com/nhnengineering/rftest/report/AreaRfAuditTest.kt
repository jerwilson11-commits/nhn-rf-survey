package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaRfAuditTest {

    private val t0 = 1_760_000_000_000L

    private fun pt(seq: Int, rsrp: Int?, sinr: Int? = null, rssi: Int? = null, rat: String? = "NR SA") =
        TrackPoint(
            sequence = seq.toLong(),
            timestampUtcMillis = t0 + seq * 10_000L, // 10 s apart
            latitudeDeg = 26.0500, longitudeDeg = -80.1397,
            accuracyM = 4f, speedMps = 1.3f,
            rssiDbm = rssi, ssid = null, bssid = null, channel = null, band = null,
            coChannel = null, adjacentChannel = null,
            rsrpDbm = rsrp, sinrDb = sinr, rsrqDb = null,
            cellBand = if (rsrp != null) "n25" else null, rat = if (rsrp != null) rat else null,
            floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
            servingPci = if (rsrp != null) 100 else null,
        )

    private fun report(
        kpi: SessionStats.Kpi,
        median: Int,
        measured: Int,
        failing: Int,
        threshold: Int,
    ) = SessionStats.Report(
        kpi = kpi,
        thresholdDbm = threshold,
        stats = SessionStats.Stats(
            samples = measured, missing = 0, min = median - 20, p10 = median - 12,
            median = median, p90 = median + 10, max = median + 15, mean = median.toDouble(),
        ),
        measured = measured,
        failing = failing,
        compliancePct = if (measured == 0) 0.0 else 100.0 * (measured - failing) / measured,
        bucketCounts = emptyList(),
        holes = emptyList(),
        throughputRows = emptyList(),
    )

    @Test
    fun `strong clean cellular area grades high`() {
        val pts = (1..30).map { pt(it, rsrp = -85, sinr = 18) }
        val rep = report(SessionStats.Kpi.CELL_RSRP, median = -85, measured = 30, failing = 0, threshold = -105)
        val r = AreaRfAudit.analyse(pts, rep)

        assertTrue(r.hasData)
        assertEquals("Cellular (RSRP)", r.kpiLabel)
        assertTrue("expected A/B, got ${r.grade} ${r.overallScore}", r.grade in listOf("A", "B"))
        // Cellular session has all four components, weights renormalise to 100.
        assertEquals(4, r.components.size)
        assertEquals(100, r.components.sumOf { it.weightPct })
        assertTrue(r.components.any { it.name == "Quality (SINR)" })
    }

    @Test
    fun `weak lossy cellular area fails`() {
        // Poor RSRP, poor SINR, half the samples below threshold, and a service drop.
        val pts = buildList {
            repeat(15) { add(pt(it + 1, rsrp = -116, sinr = -6)) }
            add(pt(16, rsrp = null, rat = null))       // service lost
            repeat(14) { add(pt(it + 17, rsrp = -112, sinr = -4)) }
        }
        val rep = report(SessionStats.Kpi.CELL_RSRP, median = -114, measured = 29, failing = 20, threshold = -105)
        val r = AreaRfAudit.analyse(pts, rep)

        assertEquals("F", r.grade)
        assertTrue(r.overallScore < 50)
        assertTrue(r.verdict.contains("Weakest factor"))
    }

    @Test
    fun `wifi area has no SINR component and weights still sum to 100`() {
        val pts = (1..20).map { pt(it, rsrp = null, rssi = -62, rat = null) }
        val rep = report(SessionStats.Kpi.WIFI_RSSI, median = -62, measured = 20, failing = 2, threshold = -75)
        val r = AreaRfAudit.analyse(pts, rep)

        assertTrue(r.hasData)
        assertEquals("Wi-Fi (RSSI)", r.kpiLabel)
        assertFalse("Wi-Fi must not carry a SINR factor", r.components.any { it.name.contains("SINR") })
        assertEquals(100, r.components.sumOf { it.weightPct })
    }

    @Test
    fun `empty measurement set does not score`() {
        val rep = report(SessionStats.Kpi.CELL_RSRP, median = -100, measured = 0, failing = 0, threshold = -105)
        val r = AreaRfAudit.analyse(emptyList(), rep)
        assertFalse(r.hasData)
        assertEquals("F", r.grade)
    }

    @Test
    fun `grade bands are standard`() {
        assertEquals("A", AreaRfAudit.gradeFor(95))
        assertEquals("B", AreaRfAudit.gradeFor(80))
        assertEquals("C", AreaRfAudit.gradeFor(70))
        assertEquals("D", AreaRfAudit.gradeFor(60))
        assertEquals("F", AreaRfAudit.gradeFor(59))
    }
}
