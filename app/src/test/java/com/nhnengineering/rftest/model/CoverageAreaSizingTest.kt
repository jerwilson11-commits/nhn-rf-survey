package com.nhnengineering.rftest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase B: real-world floor area and grid-dimension maths from a georeference's pixel size + mpp. */
class CoverageAreaSizingTest {

    @Test
    fun `floor area scales with pixel area and mpp squared`() {
        // Left-half polygon (normalised area 0.5) of a 100x100 px plan at 0.5 m/px.
        // pixels² = 0.5 * 100 * 100 = 5000; x mpp² (0.25) = 1250 m².
        val leftHalf = CoverageArea(
            listOf(
                CoverageVertex(0f, 0f), CoverageVertex(0.5f, 0f),
                CoverageVertex(0.5f, 1f), CoverageVertex(0f, 1f),
            ),
        )
        val m2 = coverageAreaSquareMetres(leftHalf, widthPx = 100, heightPx = 100, metresPerPixel = 0.5)
        assertEquals(1250.0, m2, 0.001)
        assertEquals(1250.0 / (0.3048 * 0.3048), squareMetresToFeet(m2), 0.01)
    }

    @Test
    fun `an undefined area has zero floor area`() {
        assertEquals(0.0, coverageAreaSquareMetres(CoverageArea.EMPTY, 100, 100, 0.5), 0.0)
    }

    @Test
    fun `grid cell size flags the NFPA 80 foot maximum`() {
        // Whole-plan 2000x2000 px at 0.05 m/px = 100 m x 100 m floor. 5 cols x 4 rows:
        // cell = 20 m wide x 25 m tall; 25 m = 82.0 ft > 80 ft -> exceeds.
        val size = errcsGridCellSize(
            CoverageArea.EMPTY, rows = 4, cols = 5,
            widthPx = 2000, heightPx = 2000, metresPerPixel = 0.05,
        )!!
        assertEquals(20.0, size.widthM, 0.001)
        assertEquals(25.0, size.heightM, 0.001)
        assertEquals(25.0 / 0.3048, size.maxDimFt, 0.01)
        assertTrue(size.exceedsNfpaMax())
    }

    @Test
    fun `a finer grid stays under the 80 foot maximum`() {
        // Same 100 m floor at 8 cols x 8 rows -> 12.5 m cells (~41 ft) -> within spec.
        val size = errcsGridCellSize(
            CoverageArea.EMPTY, rows = 8, cols = 8,
            widthPx = 2000, heightPx = 2000, metresPerPixel = 0.05,
        )!!
        assertFalse(size.exceedsNfpaMax())
    }

    @Test
    fun `no georeference yields no cell size`() {
        assertNull(errcsGridCellSize(CoverageArea.EMPTY, 4, 5, 0, 0, 0.0))
    }
}
