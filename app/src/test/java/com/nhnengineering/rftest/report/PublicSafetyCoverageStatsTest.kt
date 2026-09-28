package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.model.ErrcsAreaClass
import com.nhnengineering.rftest.model.PublicSafetyThresholds
import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins Track B (FirstNet Band 14/n14, auto-measured) compliance math to hand-computed values.
 *
 * The one dangerous bug this feature could ship with is silently counting a strong commercial-band
 * reading toward a public-safety compliance number -- [SessionStats.isFirstNetBand14]'s exclusion
 * logic is tested separately from the pass/fail math for exactly that reason.
 */
class PublicSafetyCoverageStatsTest {

    private fun pt(
        seq: Long,
        cellBand: String?,
        rsrp: Int?,
        areaClass: ErrcsAreaClass?,
    ) = TrackPoint(
        sequence = seq, timestampUtcMillis = 1_756_000_000_000 + seq * 1000,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = rsrp, sinrDb = null, rsrqDb = null, cellBand = cellBand, rat = null,
        floorplanId = "plan.png", floorplanX = 0.5f, floorplanY = 0.5f, waypoint = null,
        errcsAreaClass = areaClass,
    )

    @Test
    fun `LTE band 14 is recognised as B14`() {
        assertTrue(SessionStats.isFirstNetBand14(pt(1, "B14", -90, ErrcsAreaClass.GENERAL)))
    }

    @Test
    fun `NR n14 is recognised whether alone or joined with other NR bands`() {
        assertTrue(SessionStats.isFirstNetBand14(pt(1, "n14", -90, ErrcsAreaClass.GENERAL)))
        assertTrue(SessionStats.isFirstNetBand14(pt(1, "n41/n14", -90, ErrcsAreaClass.GENERAL)))
    }

    @Test
    fun `a similar-looking band is not mistaken for Band 14`() {
        // Regression guard: prefix/substring matching would wrongly catch these.
        assertFalse(SessionStats.isFirstNetBand14(pt(1, "B140", -90, ErrcsAreaClass.GENERAL)))
        assertFalse(SessionStats.isFirstNetBand14(pt(1, "n141", -90, ErrcsAreaClass.GENERAL)))
        assertFalse(SessionStats.isFirstNetBand14(pt(1, "B66", -90, ErrcsAreaClass.GENERAL)))
        assertFalse(SessionStats.isFirstNetBand14(pt(1, null, -90, ErrcsAreaClass.GENERAL)))
    }

    @Test
    fun `a non-Band-14 sample is excluded from compliance regardless of signal strength`() {
        // The one bug that would actually be dangerous here: a very strong commercial-band
        // reading (B66, -60 dBm) must never count toward a FirstNet compliance figure.
        val points = listOf(
            pt(1, "B66", -60, ErrcsAreaClass.GENERAL),
            pt(2, "B14", -100, ErrcsAreaClass.GENERAL),
        )

        val result = SessionStats.publicSafetyCoverage(points, PublicSafetyThresholds())
        val general = result.single { it.areaClass == ErrcsAreaClass.GENERAL }

        // Only the Band 14 sample counts, and it fails its -95 dBm floor.
        assertEquals(1, general.sampleCount)
        assertEquals(0, general.passingCount)
        assertEquals(0.0, general.actualPct!!, 0.001)
    }

    @Test
    fun `an unclassified Band 14 sample is excluded from both area classes`() {
        val points = listOf(pt(1, "B14", -80, areaClass = null))

        val result = SessionStats.publicSafetyCoverage(points, PublicSafetyThresholds())

        assertTrue(result.all { it.sampleCount == 0 })
        assertTrue(result.all { it.actualPct == null })
    }

    @Test
    fun `a Band 14 sample with no RSRP reading is excluded from the denominator, not counted as failing`() {
        val points = listOf(
            pt(1, "B14", null, ErrcsAreaClass.CRITICAL),
            pt(2, "B14", -80, ErrcsAreaClass.CRITICAL),
        )

        val result = SessionStats.publicSafetyCoverage(points, PublicSafetyThresholds())
        val critical = result.single { it.areaClass == ErrcsAreaClass.CRITICAL }

        assertEquals(1, critical.sampleCount)
        assertEquals(1, critical.missing)
        assertEquals(1, critical.passingCount)
        assertEquals(100.0, critical.actualPct!!, 0.001)
    }

    @Test
    fun `compliance is judged per area class with the configured thresholds`() {
        val points = listOf(
            pt(1, "B14", -95, ErrcsAreaClass.GENERAL),
            pt(2, "B14", -96, ErrcsAreaClass.GENERAL),
            pt(3, "n14", -95, ErrcsAreaClass.CRITICAL),
        )
        val thresholds = PublicSafetyThresholds(
            generalMinDbm = -95, generalPct = 95,
            criticalMinDbm = -95, criticalPct = 99,
        )

        val result = SessionStats.publicSafetyCoverage(points, thresholds)
        val general = result.single { it.areaClass == ErrcsAreaClass.GENERAL }
        val critical = result.single { it.areaClass == ErrcsAreaClass.CRITICAL }

        assertEquals(2, general.sampleCount)
        assertEquals(1, general.passingCount)
        assertEquals(50.0, general.actualPct!!, 0.001)
        assertFalse(general.meetsRequirement)

        assertEquals(1, critical.sampleCount)
        assertEquals(1, critical.passingCount)
        assertEquals(100.0, critical.actualPct!!, 0.001)
        assertTrue(critical.meetsRequirement)
    }

    @Test
    fun `no Band 14 samples at all yields null percentages for both classes`() {
        val points = listOf(pt(1, "B66", -70, ErrcsAreaClass.GENERAL))

        val result = SessionStats.publicSafetyCoverage(points, PublicSafetyThresholds())

        assertTrue(result.all { it.actualPct == null })
        assertNull(result.first().actualPct)
    }
}
