package com.nhnengineering.rftest.map

import com.nhnengineering.rftest.model.CoverageArea
import com.nhnengineering.rftest.model.CoverageVertex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validates the pure outline tracer against a synthetic bold-rectangle "coverage loop". */
class CoverageOutlineTraceTest {

    private val WHITE = 0xFFFFFFFF.toInt()
    private val PURPLE = 0xFF803090.toInt()

    /** A 120x100 white canvas with a 3px purple rectangle outline from (20,20) to (90,70). */
    private fun canvas(): IntArray {
        val w = 120
        val h = 100
        val px = IntArray(w * h) { WHITE }
        fun set(x: Int, y: Int) { if (x in 0 until w && y in 0 until h) px[y * w + x] = PURPLE }
        for (x in 20..90) for (t in 0..2) { set(x, 20 + t); set(x, 70 - t) }
        for (y in 20..70) for (t in 0..2) { set(20 + t, y); set(90 - t, y) }
        return px
    }

    @Test
    fun `traces a bold rectangle outline tapped on its edge`() {
        val verts = CoverageOutlineTrace.detect(canvas(), w = 120, h = 100, tapX = 21, tapY = 45)
        assertNotNull("expected an outline", verts)
        val poly = CoverageArea(verts!!.map { CoverageVertex(it.first, it.second) })
        assertTrue("need a closed polygon, got ${verts.size}", verts.size >= 4)

        // Bounds should hug the drawn rectangle (20..90 of 120, 20..70 of 100), within a small margin.
        val xs = verts.map { it.first }
        val ys = verts.map { it.second }
        assertEquals(20f / 120f, xs.min(), 0.04f)
        assertEquals(90f / 120f, xs.max(), 0.04f)
        assertEquals(20f / 100f, ys.min(), 0.04f)
        assertEquals(70f / 100f, ys.max(), 0.04f)

        // The interior is enclosed; a point well outside is not.
        assertTrue(poly.contains(55f / 120f, 45f / 100f))
        assertTrue(!poly.contains(5f / 120f, 5f / 100f))

        // Area ~ (70*50)/(120*100) = 0.29 of the plan (a little larger from line width/dilation).
        assertEquals(0.29f, poly.normalizedArea(), 0.06f)
    }

    @Test
    fun `returns null when the tap is nowhere near the colour`() {
        // Tap deep in white space, far from the purple line.
        assertNull(CoverageOutlineTrace.detect(canvas(), 120, 100, tapX = 55, tapY = 45))
    }
}
