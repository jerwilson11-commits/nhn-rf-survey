package com.nhnengineering.rftest.model

import kotlin.math.abs

/**
 * A traced floor-outline polygon in normalised (0..1) floorplan-image coordinates.
 *
 * Shared by the public-safety grid method (lay the 20 grids inside the floor, not over the whole PDF
 * page) and by cellular coverage reporting ("x dBm met in x% of the defined coverage area"). Captured
 * corner-by-corner with the crosshair, the same control the georeference screen uses, because wall
 * corners need to be placed precisely.
 *
 * Normalised to the image so it is independent of display zoom and resolution; once the floorplan is
 * georeferenced the real floor area follows from [normalizedArea] times the real area of the plan.
 */
data class CoverageVertex(val x: Float, val y: Float)

data class CoverageArea(val vertices: List<CoverageVertex>) {

    /** A polygon needs at least three corners to enclose area. Fewer is "not drawn yet". */
    val isDefined: Boolean get() = vertices.size >= 3

    /**
     * Ray-casting point-in-polygon test. An **undefined** polygon (fewer than three vertices) contains
     * everything, so callers can treat "no area drawn yet" as "the whole plan" without special-casing.
     */
    fun contains(x: Float, y: Float): Boolean {
        if (!isDefined) return true
        var inside = false
        var j = vertices.size - 1
        for (i in vertices.indices) {
            val vi = vertices[i]
            val vj = vertices[j]
            if ((vi.y > y) != (vj.y > y) &&
                x < (vj.x - vi.x) * (y - vi.y) / (vj.y - vi.y) + vi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Normalised area via the shoelace formula (fraction of the plan image, 0..1). 0 when undefined. */
    fun normalizedArea(): Float {
        if (!isDefined) return 0f
        var sum = 0f
        var j = vertices.size - 1
        for (i in vertices.indices) {
            sum += (vertices[j].x + vertices[i].x) * (vertices[j].y - vertices[i].y)
            j = i
        }
        return abs(sum) / 2f
    }

    /** Bounding box as [minX, minY, maxX, maxY]; the full plan (0,0,1,1) when undefined. */
    fun bounds(): FloatArray {
        if (!isDefined) return floatArrayOf(0f, 0f, 1f, 1f)
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        vertices.forEach {
            minX = minOf(minX, it.x)
            minY = minOf(minY, it.y)
            maxX = maxOf(maxX, it.x)
            maxY = maxOf(maxY, it.y)
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    companion object {
        val EMPTY = CoverageArea(emptyList())
    }
}
