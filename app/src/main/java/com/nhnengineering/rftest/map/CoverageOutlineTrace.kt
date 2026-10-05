package com.nhnengineering.rftest.map

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin detection of a traced coverage outline from a floorplan raster — no Android types, so it
 * unit-tests on the JVM against a synthetic pixel array.
 *
 * iBwave exports draw the coverage boundary as a **bold, closed loop in a colour the design engineer
 * chose** (varies per plan), distinct from the thin grey CAD linework and the cyan/black cable runs. So
 * the operator taps that line; we sample its colour and the loop it belongs to, and return the enclosed
 * polygon. A detached neighbouring structure is a separate connected component and is left alone.
 *
 * Pipeline: sample colour at the tap → colour mask → morphological close (seal hairline gaps) →
 * connected component at the tap → fill the loop's interior → trace the outer contour (Moore-neighbor)
 * → simplify (Douglas–Peucker). Best-effort: the result is always handed to the editor for the operator
 * to confirm or adjust, never committed blind.
 */
object CoverageOutlineTrace {

    /**
     * @return the detected outline as normalised (0..1) vertices, or null when nothing usable is found.
     * @param pixels ARGB pixels, row-major, length >= w*h.
     * @param tapX,tapY the tapped pixel (on or very near the bold line).
     */
    fun detect(
        pixels: IntArray,
        w: Int,
        h: Int,
        tapX: Int,
        tapY: Int,
        hueTolDeg: Double = 28.0,
        satTol: Double = 0.30,
        valTol: Double = 0.40,
        epsilonFrac: Double = 0.006,
        minSat: Double = 0.12,
    ): List<Pair<Float, Float>>? {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return null
        val tx = tapX.coerceIn(0, w - 1)
        val ty = tapY.coerceIn(0, h - 1)
        val (th, ts, tv) = rgbToHsv(pixels[ty * w + tx])

        // Coverage outlines are a saturated colour the engineer chose; the background (white), the CAD
        // walls (grey) and cable runs (near-grey/black) are not. A near-grey tap means the operator
        // missed the line, so there is nothing to trace rather than "the whole page".
        if (ts < minSat) return null

        // Colour mask: pixels close to the sampled line colour.
        val mask = BooleanArray(w * h)
        for (i in 0 until w * h) {
            val (hh, ss, vv) = rgbToHsv(pixels[i])
            mask[i] = hueDiff(hh, th) <= hueTolDeg && abs(ss - ts) <= satTol && abs(vv - tv) <= valTol
        }

        // Seal hairline gaps (anti-aliasing / JPEG) so the loop stays closed for the fill.
        val closed = dilate(mask, w, h)

        // The connected loop the tap belongs to (snap to the nearest masked pixel if the tap missed).
        val seed = if (closed[ty * w + tx]) ty * w + tx else nearestTrue(closed, w, h, tx, ty, 8) ?: return null
        val comp = componentAt(closed, w, h, seed)

        // Fill the loop's interior, then trace the outer boundary of the filled footprint.
        val footprint = fillHoles(comp, w, h)
        val contour = traceOuterContour(footprint, w, h) ?: return null
        val simplified = douglasPeucker(contour, epsilonFrac * max(w, h))
        if (simplified.size < 3) return null
        // Safety: if the fill engulfed almost the whole page, the tap did not land on a bounded loop.
        val footprintFrac = footprint.count { it }.toDouble() / (w * h)
        if (footprintFrac > 0.97) return null
        return simplified.map { (px, py) -> (px.toFloat() / w) to (py.toFloat() / h) }
    }

    // ---- colour ------------------------------------------------------------

    private fun rgbToHsv(argb: Int): Triple<Double, Double, Double> {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val v = mx
        val s = if (mx == 0.0) 0.0 else d / mx
        var hue = when {
            d == 0.0 -> 0.0
            mx == r -> 60.0 * (((g - b) / d) % 6.0)
            mx == g -> 60.0 * ((b - r) / d + 2.0)
            else -> 60.0 * ((r - g) / d + 4.0)
        }
        if (hue < 0) hue += 360.0
        return Triple(hue, s, v)
    }

    private fun hueDiff(a: Double, b: Double): Double {
        val d = abs(a - b)
        return min(d, 360.0 - d)
    }

    // ---- morphology / components ------------------------------------------

    /** 3×3 dilation: a pixel is set if any 8-neighbour (or itself) is set. */
    private fun dilate(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (mask[y * w + x]) { out[y * w + x] = true; continue }
            var any = false
            var dy = -1
            while (dy <= 1 && !any) {
                var dx = -1
                while (dx <= 1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h && mask[ny * w + nx]) { any = true; break }
                    dx++
                }
                dy++
            }
            out[y * w + x] = any
        }
        return out
    }

    private fun nearestTrue(mask: BooleanArray, w: Int, h: Int, x: Int, y: Int, radius: Int): Int? {
        for (r in 1..radius) {
            for (dy in -r..r) for (dx in -r..r) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h && mask[ny * w + nx]) return ny * w + nx
            }
        }
        return null
    }

    /** 8-connected component containing [seed]. */
    private fun componentAt(mask: BooleanArray, w: Int, h: Int, seed: Int): BooleanArray {
        val comp = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        stack.addLast(seed)
        comp[seed] = true
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % w
            val y = i / w
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) {
                    val ni = ny * w + nx
                    if (mask[ni] && !comp[ni]) { comp[ni] = true; stack.addLast(ni) }
                }
            }
        }
        return comp
    }

    /** footprint = component ∪ its interior holes. Exterior is flood-filled from the border through
     *  non-component pixels (4-connected); any non-component pixel the exterior cannot reach is a hole. */
    private fun fillHoles(comp: BooleanArray, w: Int, h: Int): BooleanArray {
        val exterior = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        fun pushIfOpen(i: Int) {
            if (!comp[i] && !exterior[i]) { exterior[i] = true; stack.addLast(i) }
        }
        for (x in 0 until w) { pushIfOpen(x); pushIfOpen((h - 1) * w + x) }
        for (y in 0 until h) { pushIfOpen(y * w); pushIfOpen(y * w + (w - 1)) }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % w
            val y = i / w
            if (x > 0) pushIfOpen(i - 1)
            if (x < w - 1) pushIfOpen(i + 1)
            if (y > 0) pushIfOpen(i - w)
            if (y < h - 1) pushIfOpen(i + w)
        }
        val footprint = BooleanArray(w * h)
        for (i in 0 until w * h) footprint[i] = comp[i] || !exterior[i]
        return footprint
    }

    // ---- contour tracing (Moore-neighbor with Jacob's stopping criterion) --

    private val MOORE = arrayOf(
        -1 to 0, -1 to -1, 0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1,
    )

    private fun traceOuterContour(fp: BooleanArray, w: Int, h: Int): List<Pair<Int, Int>>? {
        // Start: first footprint pixel in row-major order (top-most, then left-most).
        var start = -1
        for (i in 0 until w * h) if (fp[i]) { start = i; break }
        if (start < 0) return null
        val sx = start % w
        val sy = start / w

        fun solid(x: Int, y: Int) = x in 0 until w && y in 0 until h && fp[y * w + x]

        val contour = ArrayList<Pair<Int, Int>>()
        var cx = sx
        var cy = sy
        // Enter from the left (the pixel before the start in scan order is background/out of bounds).
        var backtrackDir = 0 // index into MOORE pointing from current to the previous (background) cell
        var firstMove = -1
        var steps = 0
        val maxSteps = w.toLong() * h.toLong() * 4 // safety bound
        while (steps < maxSteps) {
            contour.add(cx to cy)
            // Search clockwise starting just past the backtrack direction for the next solid neighbour.
            var found = -1
            for (k in 1..8) {
                val dir = (backtrackDir + k) % 8
                val nx = cx + MOORE[dir].first
                val ny = cy + MOORE[dir].second
                if (solid(nx, ny)) { found = dir; break }
            }
            if (found < 0) break // isolated pixel
            val nx = cx + MOORE[found].first
            val ny = cy + MOORE[found].second
            // New backtrack points from the next cell back toward the current cell.
            backtrackDir = (found + 4) % 8
            // Jacob's stopping criterion: back at start, entering by the same direction as the first move.
            if (cx == sx && cy == sy && firstMove >= 0 && found == firstMove && contour.size > 1) {
                contour.removeAt(contour.size - 1)
                break
            }
            if (firstMove < 0) firstMove = found
            cx = nx
            cy = ny
            steps++
            if (cx == sx && cy == sy && contour.isNotEmpty()) {
                // Reached start again; one more check handled above, otherwise stop.
            }
        }
        return if (contour.size >= 3) contour else null
    }

    // ---- Douglas–Peucker ---------------------------------------------------

    private fun douglasPeucker(pts: List<Pair<Int, Int>>, epsilon: Double): List<Pair<Int, Int>> {
        if (pts.size < 3) return pts
        // Closed ring: split at the two farthest-apart points to avoid collapsing the loop.
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to pts.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = perpDistance(pts[i], pts[a], pts[b])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > epsilon) {
                keep[idx] = true
                stack.addLast(a to idx)
                stack.addLast(idx to b)
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun perpDistance(p: Pair<Int, Int>, a: Pair<Int, Int>, b: Pair<Int, Int>): Double {
        val x = p.first.toDouble()
        val y = p.second.toDouble()
        val ax = a.first.toDouble()
        val ay = a.second.toDouble()
        val bx = b.first.toDouble()
        val by = b.second.toDouble()
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return kotlin.math.hypot(x - ax, y - ay)
        val t = ((x - ax) * dx + (y - ay) * dy) / len2
        val projX = ax + t * dx
        val projY = ay + t * dy
        return kotlin.math.hypot(x - projX, y - projY)
    }
}
