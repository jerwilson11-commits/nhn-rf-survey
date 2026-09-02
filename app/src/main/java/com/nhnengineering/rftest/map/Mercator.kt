package com.nhnengineering.rftest.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator, the projection the satellite tiles are drawn in.
 *
 * The plot elsewhere in this project uses equirectangular with longitude scaled by cos(latitude),
 * which is correct in isolation and adequate for a plot with no basemap. It is **not** adequate
 * under imagery: the two projections diverge with latitude, so a trail drawn one way over tiles
 * drawn the other slides progressively out of register. A trail that is *nearly* on the right
 * building is worse than no imagery at all, because it looks authoritative.
 *
 * The same arithmetic exists in the live view's JavaScript, because that page is served as a
 * static string to a browser and cannot call into Kotlin. `MercatorTest` pins both against
 * hand-computed reference values so the phone and the laptop cannot disagree about where a sample
 * was taken.
 */
object Mercator {

    /**
     * Latitude limit of the projection, atan(sinh(pi)) in degrees.
     *
     * Written to full precision rather than the usual rounded 85.05112878: the rounded value sits
     * a hair *beyond* the true limit, which puts the world's top edge at a slightly negative tile
     * coordinate. Harmless in pixels, but it means the projection's own defining property does not
     * hold exactly, and a constant that is almost right is a poor foundation for a test suite
     * whose job is to catch drift.
     *
     * Web Mercator cannot represent the poles: the formula diverges as latitude approaches ±90.
     * Clamping rather than returning an error because a GPS glitch reporting an impossible
     * latitude should distort one sample, not abort the map.
     */
    const val MAX_LATITUDE = 85.05112877980659

    /** Tile edge in pixels, fixed by the tile scheme. */
    const val TILE_SIZE = 256

    /** Fractional tile-x at [zoom]. Whole part is the tile index, fraction is the offset within it. */
    fun lonToTileX(lonDeg: Double, zoom: Int): Double =
        (lonDeg + 180.0) / 360.0 * 2.0.pow(zoom)

    /** Fractional tile-y at [zoom]. Increases southward, unlike latitude. */
    fun latToTileY(latDeg: Double, zoom: Int): Double {
        val lat = latDeg.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val r = lat * PI / 180.0
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 2.0.pow(zoom)
    }

    fun tileXToLon(tileX: Double, zoom: Int): Double =
        tileX / 2.0.pow(zoom) * 360.0 - 180.0

    fun tileYToLat(tileY: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * tileY / 2.0.pow(zoom)
        return 180.0 / PI * atan(sinh(n))
    }

    /**
     * Ground distance per pixel at [latDeg] and [zoom].
     *
     * Needed for the scale bar. Mercator stretches with latitude — a pixel is a smaller distance
     * near the poles than at the equator — so a scale bar computed at the equator and drawn in
     * Florida would be wrong by about 11%. That is exactly the kind of error nobody notices and
     * everybody relies on.
     */
    fun metresPerPixel(latDeg: Double, zoom: Int): Double {
        val lat = latDeg.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        return EARTH_CIRCUMFERENCE_M * cos(lat * PI / 180.0) / (TILE_SIZE * 2.0.pow(zoom))
    }

    /**
     * The deepest zoom whose tiles for [bounds] fit within [widthPx] x [heightPx].
     *
     * Bounded above by [maxZoom] because imagery providers stop at a finite level, and requesting
     * deeper returns nothing. Bounded below by 1 so a degenerate extent cannot loop forever.
     */
    fun fitZoom(
        bounds: Bounds,
        widthPx: Int,
        heightPx: Int,
        maxZoom: Int = 19,
        slack: Double = 1.0,
    ): Int {
        for (z in maxZoom downTo 1) {
            val w = abs(lonToTileX(bounds.maxLon, z) - lonToTileX(bounds.minLon, z)) * TILE_SIZE
            val h = abs(latToTileY(bounds.minLat, z) - latToTileY(bounds.maxLat, z)) * TILE_SIZE
            if (w <= widthPx * slack && h <= heightPx * slack) return z
        }
        return 1
    }

    /**
     * A geographic extent, expanded to a sensible minimum.
     *
     * [minSpanM] exists because a stationary handset produces a few metres of GPS scatter, and
     * fitting a map tightly to that cloud zooms to jitter and implies a precision the measurement
     * does not have.
     */
    data class Bounds(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
    ) {
        val midLat: Double get() = (minLat + maxLat) / 2

        fun expandedToAtLeast(minSpanM: Double): Bounds {
            val mLat = METRES_PER_DEG_LAT
            val mLon = METRES_PER_DEG_LAT * cos(midLat * PI / 180.0)
            var a = minLat; var b = maxLat; var c = minLon; var d = maxLon
            val spanLatM = (b - a) * mLat
            if (spanLatM < minSpanM) {
                val pad = (minSpanM - spanLatM) / 2 / mLat
                a -= pad; b += pad
            }
            val spanLonM = (d - c) * mLon
            if (spanLonM < minSpanM && mLon > 0) {
                val pad = (minSpanM - spanLonM) / 2 / mLon
                c -= pad; d += pad
            }
            return Bounds(a, b, c, d)
        }

        companion object {
            /** Null when there is nothing to bound — an empty session is not an error. */
            fun of(points: List<Pair<Double, Double>>): Bounds? {
                if (points.isEmpty()) return null
                return Bounds(
                    minLat = points.minOf { it.first },
                    maxLat = points.maxOf { it.first },
                    minLon = points.minOf { it.second },
                    maxLon = points.maxOf { it.second },
                )
            }
        }
    }

    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    private const val METRES_PER_DEG_LAT = 111_320.0
}
