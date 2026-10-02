package com.nhnengineering.rftest.map

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * A tie point: a normalised floorplan coordinate paired with its real-world latitude/longitude.
 *
 * The floorplan half is normalised (0..1 on each axis, [com.nhnengineering.rftest.model.IndoorPosition]'s
 * convention) so a tie point survives the image being shown or re-exported at any resolution.
 */
data class TiePoint(
    val uNorm: Double,
    val vNorm: Double,
    val lat: Double,
    val lon: Double,
)

/**
 * A solved floorplan <-> world georeference for one floorplan of known pixel size.
 *
 * The transform is a **similarity** -- uniform scale, rotation, translation, no shear -- between
 * floorplan pixel space and a local east/north metres projection about a reference origin. A to-scale
 * architectural drawing has no shear, so a similarity (not a full affine) is the correct model: two
 * tie points determine it exactly, and more refine it by least squares without letting measurement
 * noise skew the plan into a parallelogram.
 *
 * Floorplan Y runs downward (row 0 at the top) while north runs upward. The flip is folded into the
 * plan coordinate (`v -> 1 - v`) so the remaining transform is a proper rotation, never a reflection.
 * That keeps two tie points sufficient and the handedness unambiguous.
 *
 * Internally the map is the complex-number similarity `metres = a*p + b`, with `p` the Y-up pixel
 * coordinate and `a` carrying both scale and rotation. The local metres projection is equirectangular
 * about ([lat0], [lon0]) -- exact enough over a single building, and the same cos(lat) scaling the
 * rest of this codebase uses.
 */
class GeoReference internal constructor(
    val widthPx: Int,
    val heightPx: Int,
    private val lat0: Double,
    private val lon0: Double,
    private val ax: Double,
    private val ay: Double,
    private val bx: Double,
    private val by: Double,
    val tiePoints: List<TiePoint>,
) {

    private fun planar(u: Double, v: Double): Pair<Double, Double> =
        u * widthPx to (1.0 - v) * heightPx

    private fun metresToLatLon(e: Double, n: Double): Pair<Double, Double> {
        val lat = lat0 + n / M_PER_DEG
        val lon = lon0 + e / (M_PER_DEG * cos(lat0 * PI / 180.0))
        return lat to lon
    }

    /** Floorplan ([u],[v]) in 0..1 -> (lat, lon). */
    fun toLatLon(u: Double, v: Double): Pair<Double, Double> {
        val (px, py) = planar(u, v)
        // metres = a*p + b, complex multiply (a = ax + i·ay, p = px + i·py).
        val e = ax * px - ay * py + bx
        val n = ay * px + ax * py + by
        return metresToLatLon(e, n)
    }

    /**
     * (lat, lon) -> floorplan (u, v). Not clamped to 0..1: a fix outside the sheet returns a
     * coordinate outside 0..1, and the caller decides whether that is "off this floorplan".
     */
    fun toPlan(lat: Double, lon: Double): Pair<Double, Double> {
        val e = (lon - lon0) * M_PER_DEG * cos(lat0 * PI / 180.0)
        val n = (lat - lat0) * M_PER_DEG
        // p = (metres - b) / a, complex divide.
        val wx = e - bx
        val wy = n - by
        val denom = ax * ax + ay * ay
        val px = (wx * ax + wy * ay) / denom
        val py = (wy * ax - wx * ay) / denom
        return px / widthPx to 1.0 - py / heightPx
    }

    /** Ground resolution the solve implies -- metres per image pixel. For a scale bar and for QA
     *  (a plausible number is a quick sanity check that the tie points were placed correctly). */
    val metresPerPixel: Double get() = hypot(ax, ay)

    /** Plan rotation relative to true north, degrees. Mostly a QA readout. */
    val rotationDeg: Double get() = Math.toDegrees(atan2(ay, ax))

    /** The (a, b) similarity components, so a stacked floor can inherit this floor's scale+rotation. */
    internal fun components(): DoubleArray = doubleArrayOf(ax, ay, bx, by, lat0, lon0)

    companion object {
        /** Metres per degree of latitude; longitude is scaled by cos(lat0) at the origin. */
        const val M_PER_DEG = 111_320.0

        /**
         * Solves the georeference from [tps] (>= 2) for a floorplan of [widthPx] x [heightPx].
         *
         * Exact for two tie points; least squares for more. Returns null when it cannot be solved --
         * too few points, a degenerate (zero-area) image, or coincident tie points that leave the
         * scale undetermined.
         */
        fun solve(widthPx: Int, heightPx: Int, tps: List<TiePoint>): GeoReference? {
            if (tps.size < 2 || widthPx <= 0 || heightPx <= 0) return null
            val lat0 = tps.map { it.lat }.average()
            val lon0 = tps.map { it.lon }.average()
            val cosLat = cos(lat0 * PI / 180.0)

            // Y-up pixel coordinate and local metres for each tie point.
            val p = tps.map { it.uNorm * widthPx to (1.0 - it.vNorm) * heightPx }
            val w = tps.map {
                (it.lon - lon0) * M_PER_DEG * cosLat to (it.lat - lat0) * M_PER_DEG
            }
            val pbx = p.map { it.first }.average()
            val pby = p.map { it.second }.average()
            val wbx = w.map { it.first }.average()
            val wby = w.map { it.second }.average()

            // Complex least squares for a in  a·(p - p̄) ≈ (w - w̄):
            //   a = Σ conj(p')·w' / Σ |p'|²
            var numRe = 0.0
            var numIm = 0.0
            var den = 0.0
            for (i in tps.indices) {
                val px = p[i].first - pbx
                val py = p[i].second - pby
                val wx = w[i].first - wbx
                val wy = w[i].second - wby
                numRe += px * wx + py * wy        // Re(conj(p')·w')
                numIm += px * wy - py * wx        // Im(conj(p')·w')
                den += px * px + py * py
            }
            if (den == 0.0) return null           // coincident tie points: scale undetermined
            val ax = numRe / den
            val ay = numIm / den
            if (ax == 0.0 && ay == 0.0) return null
            // b = w̄ - a·p̄
            val bx = wbx - (ax * pbx - ay * pby)
            val by = wby - (ay * pbx + ax * pby)
            return GeoReference(widthPx, heightPx, lat0, lon0, ax, ay, bx, by, tps)
        }

        /**
         * Builds a floor's georeference by **inheriting** another floor's scale and rotation ([a])
         * and placing it with a single shared vertical reference point -- the iBwave-style stack.
         *
         * [refWorldLat]/[refWorldLon] is where the shared feature (lift core, stair) actually is on
         * the ground, taken from the reference floor; ([anchorU], [anchorV]) is where that same
         * feature sits on *this* floor's sheet. The scale and rotation come from the reference floor,
         * so only the translation is solved here -- one point per upper floor aligns the whole stack.
         */
        internal fun inherited(
            widthPx: Int,
            heightPx: Int,
            ref: GeoReference,
            anchorU: Double,
            anchorV: Double,
            refWorldLat: Double,
            refWorldLon: Double,
        ): GeoReference? {
            if (widthPx <= 0 || heightPx <= 0) return null
            val c = ref.components()
            val ax = c[0]; val ay = c[1]; val lat0 = c[4]; val lon0 = c[5]
            val cosLat = cos(lat0 * PI / 180.0)
            // Target metres for the shared feature, in the reference floor's projection.
            val we = (refWorldLon - lon0) * M_PER_DEG * cosLat
            val wn = (refWorldLat - lat0) * M_PER_DEG
            // This floor's anchor in Y-up pixels, then b = metres - a·p.
            val px = anchorU * widthPx
            val py = (1.0 - anchorV) * heightPx
            val bx = we - (ax * px - ay * py)
            val by = wn - (ay * px + ax * py)
            return GeoReference(widthPx, heightPx, lat0, lon0, ax, ay, bx, by, emptyList())
        }
    }
}
