package com.nhnengineering.rftest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins Track A (manual grid-point entry) compliance math to hand-computed values. */
class PublicSafetyCoverageTest {

    private fun point(
        areaClass: ErrcsAreaClass,
        dbm: Double,
        id: String = "p",
    ) = ErrcsGridPoint(
        id = id, floorplanId = "plan.png", xNorm = 0.5f, yNorm = 0.5f,
        areaClass = areaClass, signalDbm = dbm, recordedAtUtcMillis = 0L,
    )

    @Test
    fun `a point at exactly the threshold passes`() {
        val thresholds = PublicSafetyThresholds()
        assertTrue(point(ErrcsAreaClass.GENERAL, -95.0).passes(thresholds))
        assertFalse(point(ErrcsAreaClass.GENERAL, -95.1).passes(thresholds))
    }

    @Test
    fun `compliance percentage is computed per area class independently`() {
        // General: 3 of 4 pass (75%). Critical: 1 of 2 pass (50%).
        val points = listOf(
            point(ErrcsAreaClass.GENERAL, -90.0, "g1"),
            point(ErrcsAreaClass.GENERAL, -92.0, "g2"),
            point(ErrcsAreaClass.GENERAL, -95.0, "g3"),
            point(ErrcsAreaClass.GENERAL, -110.0, "g4"),
            point(ErrcsAreaClass.CRITICAL, -80.0, "c1"),
            point(ErrcsAreaClass.CRITICAL, -100.0, "c2"),
        )

        val result = errcsCompliance(points, PublicSafetyThresholds())
        val general = result.single { it.areaClass == ErrcsAreaClass.GENERAL }
        val critical = result.single { it.areaClass == ErrcsAreaClass.CRITICAL }

        assertEquals(4, general.pointCount)
        assertEquals(3, general.passingCount)
        assertEquals(75.0, general.actualPct!!, 0.001)
        // 75% actual vs. the default 95% requirement -- fails, correctly.
        assertFalse(general.meetsRequirement)

        assertEquals(2, critical.pointCount)
        assertEquals(1, critical.passingCount)
        assertEquals(50.0, critical.actualPct!!, 0.001)
        assertFalse(critical.meetsRequirement)
    }

    @Test
    fun `an area class with zero points has a null percentage, not zero`() {
        val result = errcsCompliance(
            listOf(point(ErrcsAreaClass.GENERAL, -80.0)),
            PublicSafetyThresholds(),
        )
        val critical = result.single { it.areaClass == ErrcsAreaClass.CRITICAL }

        assertEquals(0, critical.pointCount)
        assertNull(critical.actualPct)
        assertFalse(critical.meetsRequirement)
    }
}
