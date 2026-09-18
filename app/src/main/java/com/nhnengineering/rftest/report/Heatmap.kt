package com.nhnengineering.rftest.report

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Interpolates measured samples into a grid for rendering over a floorplan.
 *
 * ## The decision that matters
 *
 * **Nothing is painted where nothing was measured.** Every cell beyond [radius] of a real sample
 * comes back null and is left transparent.
 *
 * This is the whole argument for the feature. A heatmap is the most persuasive artefact a survey
 * produces — a client looks at the picture and not at the sample count — and it is therefore the
 * easiest place to mislead. Filling a room the operator never entered, in a confident green,
 * asserts coverage nobody measured. The walls that were not walked are exactly the ones a survey
 * exists to find.
 *
 * So the uncovered area stays blank, and the report says how much of the plan that was. A blank
 * region is a truthful statement about where the survey went; a green one is a fabrication.
 *
 * ## Method
 *
 * Inverse distance weighting, which is the standard choice and, more importantly, one that can be
 * explained to a client in a sentence: each point is the average of the samples near it, weighted
 * by one over the distance squared, and samples further than the cutoff are not consulted at all.
 * Ekahau's interpolation is proprietary and cannot be described to the person paying for the
 * survey. This can.
 *
 * ## Units
 *
 * [radius] is a fraction of the plan's width, not a distance in metres, because the plan is an
 * image the operator supplied rather than a georeferenced raster — the same reason the survey plot
 * carries no scale bar. Claiming a radius in metres would invent a scale the instrument does not
 * know.
 *
 * Pure Kotlin so the geometry is unit-testable on the JVM.
 */
object Heatmap {

    /** A measurement placed on the plan. [x] and [y] are normalised to 0..1 across the image. */
    data class Sample(val x: Float, val y: Float, val value: Float)

    data class Grid(
        val width: Int,
        val height: Int,
        /** Row-major, `width * height` entries. Null where no sample was within the radius. */
        val values: List<Float?>,
    ) {
        val covered: Int get() = values.count { it != null }

        /** Share of the plan the survey actually reached, which belongs beside every heatmap. */
        val coveredFraction: Float
            get() = if (values.isEmpty()) 0f else covered.toFloat() / values.size
    }

    /**
     * @param aspect the plan's height divided by its width. Distances are measured in the image's
     *   own proportions; without this a circle of influence on a wide plan would be drawn as an
     *   ellipse, and the radius would mean something different along each axis.
     * @param radius influence cutoff, as a fraction of plan width.
     * @param power the IDW exponent. 2 is conventional: higher makes each sample more local.
     */
    fun interpolate(
        samples: List<Sample>,
        width: Int,
        height: Int,
        radius: Float = 0.12f,
        aspect: Float = 1f,
        power: Float = 2f,
    ): Grid {
        require(width > 0 && height > 0) { "grid must have positive dimensions" }
        if (samples.isEmpty()) {
            return Grid(width, height, List(width * height) { null })
        }

        val r2 = radius * radius
        val values = ArrayList<Float?>(width * height)

        for (row in 0 until height) {
            // Cell centres rather than corners, so the grid is not biased half a cell up and left.
            val py = (row + 0.5f) / height
            for (col in 0 until width) {
                val px = (col + 0.5f) / width

                var weighted = 0.0
                var weights = 0.0
                var exact: Float? = null

                for (s in samples) {
                    val dx = px - s.x
                    val dy = (py - s.y) * aspect
                    val d2 = dx * dx + dy * dy
                    if (d2 > r2) continue

                    // A cell sitting on top of a sample takes that sample's value outright. Without
                    // this the weight is one over zero, and the cell that should be the most
                    // certain on the whole map becomes the only one that is NaN.
                    if (d2 <= 1e-12f) {
                        exact = s.value
                        break
                    }
                    val w = 1.0 / sqrt(d2.toDouble()).pow(power.toDouble())
                    weighted += w * s.value
                    weights += w
                }

                values += when {
                    exact != null -> exact
                    weights > 0.0 -> (weighted / weights).toFloat()
                    else -> null
                }
            }
        }
        return Grid(width, height, values)
    }
}
