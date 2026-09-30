package com.nhnengineering.rftest.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the fallback heading calculation used when a GPS fix has no bearing of its own. */
class MapScreenTest {

    private val delta = 0.5f

    @Test
    fun `due north is zero degrees`() {
        assertEquals(0f, headingBetween(0.0, 0.0, 1.0, 0.0), delta)
    }

    @Test
    fun `due east is ninety degrees`() {
        assertEquals(90f, headingBetween(0.0, 0.0, 0.0, 1.0), delta)
    }

    @Test
    fun `due south is one-eighty degrees`() {
        assertEquals(180f, headingBetween(1.0, 0.0, 0.0, 0.0), delta)
    }

    @Test
    fun `due west is two-seventy degrees`() {
        assertEquals(270f, headingBetween(0.0, 1.0, 0.0, 0.0), delta)
    }

    @Test
    fun `the same point has a defined, not NaN, heading`() {
        val h = headingBetween(40.0, -74.0, 40.0, -74.0)
        assertEquals(0f, h, delta)
    }

    @Test
    fun `result is always in the 0 to 360 range`() {
        val h = headingBetween(40.7128, -74.0060, 40.7000, -74.0200)
        assert(h in 0f..360f) { "heading $h out of range" }
    }
}
