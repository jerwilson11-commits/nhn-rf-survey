package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the GPS fix-quality grade to hand-chosen inputs.
 *
 * The grade decides what the GPS satellite page tells the tester about how much to trust the track,
 * so a drift in the cutoffs is a drift in the advice a field tester acts on. The thresholds under
 * test: accuracy <=10 m / <=20 m, avg C/N0 >=30 / >=22 dB-Hz, satellites used >=7 / >=5 are
 * GOOD / MARGINAL, worse is POOR; the worst metric wins.
 */
class GpsQualityTest {

    private fun gp(
        seq: Long,
        acc: Float?,
        cn0: Float? = null,
        used: Int? = null,
        inView: Int? = null,
    ) = TrackPoint(
        sequence = seq, timestampUtcMillis = 1_756_000_000_000 + seq * 1000,
        latitudeDeg = 26.0 + seq * 1e-5, longitudeDeg = -80.0 + seq * 1e-5,
        accuracyM = acc, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = -90, sinrDb = null, rsrqDb = null, cellBand = "n66", rat = "5G SA",
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        gnssSatellitesUsed = used,
        gnssSatellitesInView = inView,
        gnssAvgCn0DbHz = cn0,
    )

    @Test
    fun `clean open-sky walk grades GOOD with no advice`() {
        val gps = (1L..5L).map { gp(it, acc = 6f, cn0 = 38f, used = 10, inView = 12) }
        val q = SessionStats.gpsQuality(gps)
        assertEquals(SessionStats.GpsQualityGrade.GOOD, q.grade)
        assertTrue(q.reasons.isEmpty())
        assertTrue(q.advice.isEmpty())
    }

    @Test
    fun `bad-weather indoor walk grades POOR and explains each cause`() {
        // The 2026-10-01 house walk shape: coarse fix, weak signal, many satellites seen but few used.
        val gps = (1L..6L).map { gp(it, acc = 25f, cn0 = 19f, used = 4, inView = 14) }
        val q = SessionStats.gpsQuality(gps)
        assertEquals(SessionStats.GpsQualityGrade.POOR, q.grade)
        assertEquals(25.0, q.medianAccuracyM!!, 0.001)
        // All three degraded causes recognised.
        assertTrue(q.reasons.any { it.contains("satellites could be used") })
        assertTrue(q.reasons.any { it.contains("dB-Hz") })
        assertTrue(q.reasons.any { it.contains("reported accuracy") })
        assertTrue(q.advice.isNotEmpty())
    }

    @Test
    fun `plenty of satellites used but weak signal is MARGINAL without a blockage claim`() {
        // The real 2026-10-01 house walk: median accuracy 9 m, C/N0 28 dB-Hz, 21 of 44 used.
        // 21 used is healthy for a multi-constellation receiver -- only the weak signal degrades it,
        // so the grade is MARGINAL and the "satellites blocked" message must NOT appear.
        val gps = (1L..6L).map { gp(it, acc = 9f, cn0 = 28f, used = 21, inView = 44) }
        val q = SessionStats.gpsQuality(gps)
        assertEquals(SessionStats.GpsQualityGrade.MARGINAL, q.grade)
        assertTrue(q.reasons.none { it.contains("could be used") })
        assertTrue(q.reasons.any { it.contains("dB-Hz") })
    }

    @Test
    fun `worst metric drives the grade`() {
        // Accuracy and C/N0 are fine, but only 4 satellites were used -> POOR wins.
        val gps = (1L..5L).map { gp(it, acc = 6f, cn0 = 40f, used = 4, inView = 6) }
        assertEquals(SessionStats.GpsQualityGrade.POOR, SessionStats.gpsQuality(gps).grade)
    }

    @Test
    fun `middling numbers grade MARGINAL`() {
        val gps = (1L..5L).map { gp(it, acc = 14f, cn0 = 26f, used = 6, inView = 7) }
        assertEquals(SessionStats.GpsQualityGrade.MARGINAL, SessionStats.gpsQuality(gps).grade)
    }

    @Test
    fun `no quality telemetry is MARGINAL, not a false GOOD`() {
        val gps = (1L..4L).map { gp(it, acc = null) }
        val q = SessionStats.gpsQuality(gps)
        assertEquals(SessionStats.GpsQualityGrade.MARGINAL, q.grade)
    }

    @Test
    fun `cold-start first fix is flagged even on an otherwise-good walk`() {
        // First fix is a 40 m outlier against a 6 m median; the rest of the walk is clean GOOD.
        val gps = listOf(gp(1, acc = 40f, cn0 = 38f, used = 10, inView = 12)) +
            (2L..6L).map { gp(it, acc = 6f, cn0 = 38f, used = 10, inView = 12) }
        val q = SessionStats.gpsQuality(gps)
        assertEquals(SessionStats.GpsQualityGrade.GOOD, q.grade)
        assertTrue(q.reasons.any { it.contains("first fix", ignoreCase = true) })
        assertTrue(q.advice.any { it.contains("30–60 seconds") })
    }
}
