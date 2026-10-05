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
        inbound: Double? = null,
        daq: Double? = null,
        xNorm: Float = 0.5f,
        yNorm: Float = 0.5f,
    ) = ErrcsGridPoint(
        id = id, floorplanId = "plan.png", xNorm = xNorm, yNorm = yNorm,
        areaClass = areaClass, signalDbm = dbm, inboundDbm = inbound, daq = daq,
        recordedAtUtcMillis = 0L,
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

    // ---- inbound / two-way grading (#3) -----------------------------------

    @Test
    fun `a missing inbound reading never fails the point on its own`() {
        // Outbound passes, inbound not measured -> passes. A null is "not measured", not a failure.
        assertTrue(point(ErrcsAreaClass.GENERAL, -90.0, inbound = null).passes(PublicSafetyThresholds()))
    }

    @Test
    fun `a failing inbound fails the point even when outbound passes`() {
        val t = PublicSafetyThresholds()
        assertTrue(point(ErrcsAreaClass.GENERAL, -90.0, inbound = -94.0).passes(t))  // both pass
        assertFalse(point(ErrcsAreaClass.GENERAL, -90.0, inbound = -96.0).passes(t)) // UL below -95
    }

    // ---- DAQ grading (#4) -------------------------------------------------

    @Test
    fun `DAQ is graded only when a threshold is set and a value is recorded`() {
        val noDaqThreshold = PublicSafetyThresholds()
        // DAQ recorded but not graded (no minDaq) -> dBm decides, passes.
        assertTrue(point(ErrcsAreaClass.GENERAL, -90.0, daq = 1.0).passes(noDaqThreshold))

        val daq3 = PublicSafetyThresholds(minDaq = 3.0)
        // minDaq set, but this point has no DAQ -> not failed on DAQ.
        assertTrue(point(ErrcsAreaClass.GENERAL, -90.0, daq = null).passes(daq3))
        // minDaq set and DAQ below it -> fails, even though dBm is fine.
        assertFalse(point(ErrcsAreaClass.GENERAL, -90.0, daq = 2.9).passes(daq3))
        assertTrue(point(ErrcsAreaClass.GENERAL, -90.0, daq = 3.0).passes(daq3))
    }

    // ---- grid method (#2) -------------------------------------------------

    @Test
    fun `grid cell buckets normalised points and clamps the far edge`() {
        // 2x2 grid: (row,col). x,y in [0,1).
        assertEquals(0 to 0, errcsGridCell(0.1f, 0.1f, rows = 2, cols = 2))
        assertEquals(1 to 1, errcsGridCell(0.9f, 0.9f, rows = 2, cols = 2))
        // Exactly on the far edge lands in the last cell, not one past it.
        assertEquals(1 to 1, errcsGridCell(1.0f, 1.0f, rows = 2, cols = 2))
    }

    @Test
    fun `a square fails if any reading in it fails, and compliance is per square`() {
        // 2-column grid. Left column (x<0.5) has two readings, one failing -> square fails.
        // Right column (x>=0.5) has one passing reading -> square passes. 1 of 2 squares = 50%.
        val points = listOf(
            point(ErrcsAreaClass.GENERAL, -90.0, "l1", xNorm = 0.1f),
            point(ErrcsAreaClass.GENERAL, -110.0, "l2", xNorm = 0.2f), // fails
            point(ErrcsAreaClass.GENERAL, -80.0, "r1", xNorm = 0.9f),
        )
        val general = errcsGridCompliance(points, rows = 1, cols = 2, PublicSafetyThresholds())
            .single { it.areaClass == ErrcsAreaClass.GENERAL }

        assertEquals(2, general.testedCells)
        assertEquals(1, general.passingCells)
        assertEquals(50.0, general.actualPct!!, 0.001)
        assertFalse(general.meetsRequirement)
    }

    @Test
    fun `an untested area class has a null square percentage, not zero`() {
        val general = errcsGridCompliance(
            listOf(point(ErrcsAreaClass.GENERAL, -80.0)),
            rows = 4, cols = 5, PublicSafetyThresholds(),
        ).single { it.areaClass == ErrcsAreaClass.CRITICAL }

        assertEquals(0, general.testedCells)
        assertNull(general.actualPct)
    }

    // ---- designated critical areas (#5) -----------------------------------

    private val leftHalfCritical = listOf(ErrcsCriticalArea(0f, 0f, 0.5f, 1f))

    @Test
    fun `a designated area upgrades a general-tagged reading, and the tag can upgrade outside one`() {
        // General-tagged reading inside the critical region -> graded critical.
        assertEquals(
            ErrcsAreaClass.CRITICAL,
            point(ErrcsAreaClass.GENERAL, -90.0, xNorm = 0.2f).effectiveAreaClass(leftHalfCritical),
        )
        // General-tagged reading outside it -> stays general.
        assertEquals(
            ErrcsAreaClass.GENERAL,
            point(ErrcsAreaClass.GENERAL, -90.0, xNorm = 0.8f).effectiveAreaClass(leftHalfCritical),
        )
        // Critical tag still raises a reading to critical outside any designated area (the override).
        assertEquals(
            ErrcsAreaClass.CRITICAL,
            point(ErrcsAreaClass.CRITICAL, -90.0, xNorm = 0.8f).effectiveAreaClass(emptyList()),
        )
    }

    @Test
    fun `compliance counts a reading in a designated area under critical, not general`() {
        // One general-TAGGED reading sitting in the critical region. With the designation, it should
        // land in the critical bucket (graded at 99%), and the general bucket should be empty.
        val points = listOf(point(ErrcsAreaClass.GENERAL, -90.0, xNorm = 0.2f))
        val result = errcsCompliance(points, PublicSafetyThresholds(), leftHalfCritical)

        val general = result.single { it.areaClass == ErrcsAreaClass.GENERAL }
        val critical = result.single { it.areaClass == ErrcsAreaClass.CRITICAL }
        assertEquals(0, general.pointCount)
        assertEquals(1, critical.pointCount)
        assertEquals(1, critical.passingCount)
    }

    // ---- coverage area polygon + grid-within (Phase A) ---------------------

    @Test
    fun `coverage polygon contains points and computes normalised area`() {
        val quarter = CoverageArea(
            listOf(
                CoverageVertex(0f, 0f), CoverageVertex(0.5f, 0f),
                CoverageVertex(0.5f, 0.5f), CoverageVertex(0f, 0.5f),
            ),
        )
        assertTrue(quarter.contains(0.25f, 0.25f))
        assertFalse(quarter.contains(0.75f, 0.75f))
        assertEquals(0.25f, quarter.normalizedArea(), 0.001f) // 0.5 x 0.5 of the plan
        // An undefined polygon contains everything, so "no area drawn" == "whole plan".
        assertTrue(CoverageArea.EMPTY.contains(0.9f, 0.9f))
    }

    @Test
    fun `grid is laid within the coverage area and an untested square counts against`() {
        // Coverage = left half of the plan; grid 1x2 over its bounding box -> two testable squares.
        val coverage = CoverageArea(
            listOf(
                CoverageVertex(0f, 0f), CoverageVertex(0.5f, 0f),
                CoverageVertex(0.5f, 1f), CoverageVertex(0f, 1f),
            ),
        )
        // One passing reading in the first square; the second square is left untested. A reading far
        // to the right (outside the coverage area) must be ignored entirely.
        val points = listOf(
            point(ErrcsAreaClass.GENERAL, -80.0, "in", xNorm = 0.1f, yNorm = 0.5f),
            point(ErrcsAreaClass.GENERAL, -80.0, "out", xNorm = 0.9f, yNorm = 0.5f),
        )
        val gen = errcsGridCompliance(
            points, rows = 1, cols = 2, PublicSafetyThresholds(), emptyList(), coverage,
        ).single { it.areaClass == ErrcsAreaClass.GENERAL }

        assertEquals(2, gen.totalCells)       // two squares inside the coverage area
        assertEquals(1, gen.testedCells)      // only one was walked
        assertEquals(1, gen.passingCells)     // graded over totalCells, so the untested one is not a pass
        assertEquals(50.0, gen.actualPct!!, 0.001)
        assertFalse(gen.complete)
    }
}
