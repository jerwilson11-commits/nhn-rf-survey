package com.nhnengineering.rftest.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun `the same point is zero metres from itself`() {
        assertEquals(0.0, GeoMath.distanceMetres(40.7128, -74.0060, 40.7128, -74.0060), 0.001)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val d = GeoMath.distanceMetres(0.0, 0.0, 1.0, 0.0)
        assertTrue("expected ~111_320 m, got $d", d in 110_500.0..112_000.0)
    }

    @Test
    fun `a small latitude delta matches the well-established 111_320 m per degree`() {
        // Same constant this project already relies on elsewhere (Mercator.kt's
        // METRES_PER_DEG_LAT) -- a short northward hop should match it closely.
        val d = GeoMath.distanceMetres(40.0, -74.0, 40.001, -74.0)
        assertTrue("expected ~111.32 m, got $d", d in 111.0..111.7)
    }

    @Test
    fun `distance is symmetric`() {
        val a = GeoMath.distanceMetres(40.71, -74.00, 40.72, -74.01)
        val b = GeoMath.distanceMetres(40.72, -74.01, 40.71, -74.00)
        assertEquals(a, b, 0.0001)
    }
}
