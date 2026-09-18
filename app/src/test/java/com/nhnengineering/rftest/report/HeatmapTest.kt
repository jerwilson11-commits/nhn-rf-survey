package com.nhnengineering.rftest.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the heatmap.
 *
 * Most of these assert that the map stays *blank*. A heatmap is the most persuasive thing a survey
 * produces and therefore the easiest place to mislead: a client reads the picture, not the sample
 * count. Painting a room nobody walked into asserts coverage nobody measured, and the rooms that
 * were not walked are the ones a survey exists to find.
 */
class HeatmapTest {

    private fun Heatmap.Grid.at(col: Int, row: Int) = values[row * width + col]

    @Test
    fun `a cell sitting on a sample takes that sample's value`() {
        // Without the exact-hit case the weight is one over zero, and the cell that should be the
        // most certain on the map becomes the only NaN on it.
        val g = Heatmap.interpolate(
            listOf(Heatmap.Sample(0.25f, 0.25f, -60f)),
            width = 4, height = 4, radius = 0.5f,
        )

        assertEquals(-60f, g.at(1, 1)!!, 0.001f)
        assertTrue("no cell may be NaN", g.values.filterNotNull().none { it.isNaN() })
    }

    @Test
    fun `halfway between two equal samples is that value`() {
        val g = Heatmap.interpolate(
            listOf(Heatmap.Sample(0.1f, 0.5f, -70f), Heatmap.Sample(0.9f, 0.5f, -70f)),
            width = 9, height = 1, radius = 1.0f,
        )

        assertEquals(-70f, g.at(4, 0)!!, 0.01f)
    }

    @Test
    fun `the nearer sample dominates`() {
        val g = Heatmap.interpolate(
            listOf(Heatmap.Sample(0.0f, 0.5f, -50f), Heatmap.Sample(1.0f, 0.5f, -90f)),
            width = 10, height = 1, radius = 2.0f,
        )

        val left = g.at(0, 0)!!
        val right = g.at(9, 0)!!
        assertTrue("left should be near -50, was $left", left < -50f + 12f)
        assertTrue("right should be near -90, was $right", right > -90f - 12f)
        assertTrue("left must be stronger than right", left > right)
    }

    @Test
    fun `beyond the radius nothing is painted`() {
        // The central assertion of the whole feature.
        val g = Heatmap.interpolate(
            listOf(Heatmap.Sample(0.05f, 0.05f, -60f)),
            width = 10, height = 10, radius = 0.1f,
        )

        assertNotNull("near the sample must be covered", g.at(0, 0))
        assertNull("the far corner was never measured", g.at(9, 9))
        assertTrue("most of the plan is unmeasured here", g.coveredFraction < 0.2f)
    }

    @Test
    fun `no samples paints nothing at all`() {
        val g = Heatmap.interpolate(emptyList(), width = 5, height = 5)

        assertEquals(25, g.values.size)
        assertTrue(g.values.all { it == null })
        assertEquals(0f, g.coveredFraction, 0.0001f)
    }

    @Test
    fun `interpolation never invents a value outside the measured range`() {
        // IDW is an average, so it cannot exceed its inputs. Worth pinning: a heatmap showing a
        // stronger reading than anything recorded would be indefensible in front of a client.
        val samples = listOf(
            Heatmap.Sample(0.2f, 0.2f, -55f),
            Heatmap.Sample(0.8f, 0.3f, -72f),
            Heatmap.Sample(0.5f, 0.9f, -88f),
        )
        val g = Heatmap.interpolate(samples, width = 20, height = 20, radius = 1.5f)

        val measured = g.values.filterNotNull()
        assertTrue(measured.isNotEmpty())
        assertTrue("nothing may exceed the strongest sample", measured.max() <= -55f + 0.001f)
        assertTrue("nothing may fall below the weakest sample", measured.min() >= -88f - 0.001f)
    }

    @Test
    fun `aspect keeps the influence circular on a non-square plan`() {
        // On a plan twice as wide as it is tall, a 0.1 step in y is half the distance of a 0.1 step
        // in x. Ignoring that would stretch every sample's influence into an ellipse and make the
        // radius mean two different things depending on direction.
        val sample = listOf(Heatmap.Sample(0.5f, 0.5f, -60f))
        val square = Heatmap.interpolate(sample, 21, 21, radius = 0.2f, aspect = 1f)
        val wide = Heatmap.interpolate(sample, 21, 21, radius = 0.2f, aspect = 0.5f)

        // The wide plan's rows are physically closer together, so more of them fall inside the
        // radius and more of the grid is covered.
        assertTrue(
            "a wide plan should cover more rows: ${wide.coveredFraction} vs ${square.coveredFraction}",
            wide.coveredFraction > square.coveredFraction,
        )
    }

    @Test
    fun `covered fraction reports what was actually reached`() {
        val g = Heatmap.interpolate(
            listOf(Heatmap.Sample(0.5f, 0.5f, -60f)),
            width = 10, height = 10, radius = 5f,
        )

        assertEquals(1.0f, g.coveredFraction, 0.0001f)
        assertEquals(100, g.covered)
    }
}
