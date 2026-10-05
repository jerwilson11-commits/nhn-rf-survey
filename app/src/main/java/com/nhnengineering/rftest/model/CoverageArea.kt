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

// ---------------------------------------------------------------------------
// Real-world sizing (Phase B) — needs a georeference's pixel size + ground resolution
// ---------------------------------------------------------------------------

/** Metres per foot, for ft²/ft conversions. */
const val METRES_PER_FOOT = 0.3048

/** NFPA grid method's maximum grid dimension: 80 ft. A square larger than this needs the grid
 *  subdivided (or the floor split into sectors). */
const val ERRCS_MAX_GRID_DIM_FT = 80.0

fun squareMetresToFeet(m2: Double): Double = m2 / (METRES_PER_FOOT * METRES_PER_FOOT)

/**
 * Real floor area (m²) enclosed by a [CoverageArea] on a plan of the given pixel size and ground
 * resolution (`metresPerPixel`, from the floor's georeference).
 *
 * A similarity georeference scales area uniformly by `metresPerPixel²`, so the polygon's area in
 * pixels² (its normalised area × the plan's pixel area) times `metresPerPixel²` is the real area.
 * 0 when the polygon is undefined or the inputs are degenerate.
 */
fun coverageAreaSquareMetres(
    area: CoverageArea,
    widthPx: Int,
    heightPx: Int,
    metresPerPixel: Double,
): Double {
    if (!area.isDefined || widthPx <= 0 || heightPx <= 0 || metresPerPixel <= 0.0) return 0.0
    val pixelArea = area.normalizedArea().toDouble() * widthPx.toDouble() * heightPx.toDouble()
    return pixelArea * metresPerPixel * metresPerPixel
}

/** Real size (metres) of one grid square laid over a coverage area's bounding box at rows × cols. */
data class GridCellSize(val widthM: Double, val heightM: Double) {
    val maxDimM: Double get() = maxOf(widthM, heightM)
    val maxDimFt: Double get() = maxDimM / METRES_PER_FOOT
    /** True when the larger side exceeds the NFPA 80-ft maximum grid dimension. */
    fun exceedsNfpaMax(): Boolean = maxDimFt > ERRCS_MAX_GRID_DIM_FT
}

/**
 * The real-world size of one grid square for the current grid, so the operator can check the NFPA
 * 80-ft maximum grid dimension and the UI can suggest more rows/cols. Null without a usable
 * georeference. Uses the coverage area's bounding box (the grid spans that), falling back to the whole
 * plan when no area is drawn.
 */
fun errcsGridCellSize(
    area: CoverageArea,
    rows: Int,
    cols: Int,
    widthPx: Int,
    heightPx: Int,
    metresPerPixel: Double,
): GridCellSize? {
    if (widthPx <= 0 || heightPx <= 0 || metresPerPixel <= 0.0) return null
    val b = area.bounds()
    val wNorm = (b[2] - b[0]).toDouble()
    val hNorm = (b[3] - b[1]).toDouble()
    val cellW = wNorm / cols.coerceAtLeast(1) * widthPx * metresPerPixel
    val cellH = hNorm / rows.coerceAtLeast(1) * heightPx * metresPerPixel
    return GridCellSize(cellW, cellH)
}
