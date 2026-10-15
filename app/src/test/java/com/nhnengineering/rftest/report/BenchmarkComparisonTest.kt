package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.SessionSummary
import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BenchmarkComparisonTest {

    private val t0 = 1_760_000_000_000L

    private fun pt(seq: Int, rsrp: Int?, sinr: Int?, rat: String? = "5G SA", dl: Double? = null) = TrackPoint(
        sequence = seq.toLong(),
        timestampUtcMillis = t0 + seq * 10_000L,
        latitudeDeg = 26.0500, longitudeDeg = -80.1397,
        accuracyM = 4f, speedMps = 1.3f,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = rsrp, sinrDb = sinr, rsrqDb = null,
        cellBand = if (rsrp != null) "n25" else null, rat = if (rsrp != null) rat else null,
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        servingPci = if (rsrp != null) 100 else null,
        downloadMbps = dl,
    )

    private fun summary(name: String, pts: List<TrackPoint>) = SessionSummary(
        file = File("$name.csv"), displayName = name,
        startedAtUtcMillis = t0, durationMs = (pts.size - 1) * 10_000L,
        rowCount = pts.size, pointCount = pts.count { it.hasGpsPosition },
        rssiMin = null, rssiMax = null,
        minLat = 26.0500, maxLat = 26.0501, minLon = -80.1397, maxLon = -80.1396,
    )

    private fun side(name: String, pts: List<TrackPoint>) =
        BenchmarkComparison.Side(name, summary(name, pts), pts)

    @Test
    fun `clearly stronger session wins the benchmark`() {
        val strong = (1..25).map { pt(it, rsrp = -85, sinr = 18, dl = 150.0) }
        val weak = buildList {
            repeat(12) { add(pt(it + 1, rsrp = -115, sinr = -5, dl = 12.0)) }
            add(pt(13, rsrp = null, sinr = null, rat = null)) // service loss
            repeat(12) { add(pt(it + 14, rsrp = -112, sinr = -3, dl = 10.0)) }
        }
        val r = BenchmarkComparison.analyse(side("Carrier A", strong), side("Carrier B", weak))

        assertTrue(r.hasData)
        assertTrue(r.sameKpi)
        assertTrue("A should win clearly, got ${r.aWins}-${r.bWins}", r.aWins > r.bWins)
        assertTrue(r.verdict.contains("Carrier A"))
        // Coverage row: A higher is better → A wins.
        val cov = r.metrics.first { it.name.startsWith("Coverage") }
        assertEquals(BenchmarkComparison.Winner.A, cov.winner)
        // Delta is B − A; B has worse coverage, so delta is negative.
        assertTrue(cov.deltaText().startsWith("-"))
    }

    @Test
    fun `lower-is-better metrics invert the winner`() {
        // A has a service loss; B is clean. For "service losses / min" the lower (B) should win.
        val withLoss = buildList {
            repeat(10) { add(pt(it + 1, rsrp = -100, sinr = 5, dl = 40.0)) }
            add(pt(11, rsrp = null, sinr = null, rat = null))
            repeat(10) { add(pt(it + 12, rsrp = -100, sinr = 5, dl = 40.0)) }
        }
        val clean = (1..22).map { pt(it, rsrp = -100, sinr = 5, dl = 40.0) }
        val r = BenchmarkComparison.analyse(side("A", withLoss), side("B", clean))
        val losses = r.metrics.first { it.name.startsWith("Service losses") }
        assertEquals(BenchmarkComparison.Winner.B, losses.winner)
    }

    @Test
    fun `different KPIs drop the signal-level rows`() {
        val cell = (1..20).map { pt(it, rsrp = -95, sinr = 10) }
        val wifi = (1..20).map {
            pt(it, rsrp = null, sinr = null, rat = null).copy(rssiDbm = -60, ssid = "TestAP-5G", band = "5 GHz")
        }
        val r = BenchmarkComparison.analyse(side("Cellular", cell), side("Wi-Fi", wifi))
        assertFalse(r.sameKpi)
        assertFalse("signal-level rows must be dropped across KPIs",
            r.metrics.any { it.name == "Median signal" || it.name.startsWith("Cell edge") })
        // Coverage is still comparable.
        assertTrue(r.metrics.any { it.name.startsWith("Coverage") })
    }
}
