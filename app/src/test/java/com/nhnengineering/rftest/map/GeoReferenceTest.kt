package com.nhnengineering.rftest.map

import com.nhnengineering.rftest.model.Building
import com.nhnengineering.rftest.model.FloorGeoref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

/**
 * Pins the floorplan<->world similarity to a known ground-truth transform.
 *
 * The test builds tie points FROM a chosen scale/rotation/origin, solves from them, then checks the
 * solved transform reproduces the ground truth at *other* plan coordinates (not just the tie points)
 * and round-trips. An RF design engineer will trust the exported coordinates only as far as this math
 * is correct, so it is held to 1e-6 degrees.
 */
class GeoReferenceTest {

    private val W = 1200
    private val H = 800
    private val lat0 = 26.05
    private val lon0 = -80.14
    private val mPerPx = 0.05
    private val thetaDeg = 30.0
    private val mPerDeg = GeoReference.M_PER_DEG

    /** Ground truth: plan (u,v) -> (lat, lon) for the chosen transform, world origin at u=0,v=1. */
    private fun truth(u: Double, v: Double): Pair<Double, Double> {
        val px = u * W
        val py = (1.0 - v) * H
        val t = thetaDeg * PI / 180.0
        val e = mPerPx * (cos(t) * px - sin(t) * py)
        val n = mPerPx * (sin(t) * px + cos(t) * py)
        val lat = lat0 + n / mPerDeg
        val lon = lon0 + e / (mPerDeg * cos(lat0 * PI / 180.0))
        return lat to lon
    }

    private fun tie(u: Double, v: Double): TiePoint {
        val (lat, lon) = truth(u, v)
        return TiePoint(u, v, lat, lon)
    }

    @Test
    fun `solve recovers the transform at non-tie-point coordinates`() {
        val geo = GeoReference.solve(W, H, listOf(tie(0.1, 0.2), tie(0.9, 0.3), tie(0.5, 0.9)))
        assertNotNull(geo); geo!!

        for ((u, v) in listOf(0.4 to 0.6, 0.0 to 0.0, 1.0 to 1.0, 0.25 to 0.75)) {
            val (lat, lon) = geo.toLatLon(u, v)
            val (tLat, tLon) = truth(u, v)
            assertEquals("lat at ($u,$v)", tLat, lat, 1e-6)
            assertEquals("lon at ($u,$v)", tLon, lon, 1e-6)
        }

        assertEquals("metres per pixel", mPerPx, geo.metresPerPixel, 1e-6)
        // 1e-3° of rotation: the only slack the test needs, from the ground truth and the solver
        // referencing cos(lat) at slightly different origins (fixed vs tie-point average). Still well
        // under a tenth of a degree -- far tighter than any hand-placed tie point achieves.
        assertEquals("rotation", thetaDeg, geo.rotationDeg, 1e-3)
    }

    @Test
    fun `toPlan is the inverse of toLatLon`() {
        val geo = GeoReference.solve(W, H, listOf(tie(0.1, 0.2), tie(0.9, 0.85)))!!
        val (lat, lon) = geo.toLatLon(0.42, 0.61)
        val (u, v) = geo.toPlan(lat, lon)
        assertEquals(0.42, u, 1e-9)
        assertEquals(0.61, v, 1e-9)
    }

    @Test
    fun `two tie points are enough and fit exactly`() {
        val a = tie(0.2, 0.2)
        val b = tie(0.8, 0.7)
        val geo = GeoReference.solve(W, H, listOf(a, b))!!
        val (latA, lonA) = geo.toLatLon(a.uNorm, a.vNorm)
        assertEquals(a.lat, latA, 1e-9)
        assertEquals(a.lon, lonA, 1e-9)
    }

    @Test
    fun `degenerate inputs return null, never a bogus transform`() {
        assertNull("one point", GeoReference.solve(W, H, listOf(tie(0.1, 0.1))))
        assertNull("zero-size image", GeoReference.solve(0, H, listOf(tie(0.1, 0.1), tie(0.9, 0.9))))
        // Coincident tie points leave the scale undetermined.
        assertNull("coincident", GeoReference.solve(W, H, listOf(tie(0.5, 0.5), tie(0.5, 0.5))))
    }

    @Test
    fun `stacked floor inherits scale and rotation and aligns on the shared anchor`() {
        // Reference floor georeferenced by two tie points, with a stack anchor at the lift core.
        val ref = FloorGeoref(
            floorplanId = "f1.png", widthPx = W, heightPx = H, label = "1",
            tiePoints = listOf(tie(0.1, 0.2), tie(0.9, 0.3)),
            stackAnchorU = 0.5, stackAnchorV = 0.5,
        )
        // Upper floor: a DIFFERENT image size, no tie points, lift core at a different spot on its
        // own sheet. It must inherit scale+rotation and land its anchor on the same ground point.
        val up = FloorGeoref(
            floorplanId = "f2.png", widthPx = 1000, heightPx = 900, label = "2",
            stackAnchorU = 0.55, stackAnchorV = 0.48,
        )
        val geos = Building("b1", "Tower", "f1.png", listOf(ref, up)).resolve()

        val g1 = geos["f1.png"]!!
        val g2 = geos["f2.png"]!!
        // The lift core resolves to the same lat/lon on both floors.
        val core1 = g1.toLatLon(0.5, 0.5)
        val core2 = g2.toLatLon(0.55, 0.48)
        assertEquals(core1.first, core2.first, 1e-9)
        assertEquals(core1.second, core2.second, 1e-9)
        // Inherited scale and rotation match the reference floor.
        assertEquals(g1.metresPerPixel, g2.metresPerPixel, 1e-9)
        assertEquals(g1.rotationDeg, g2.rotationDeg, 1e-9)
    }

    @Test
    fun `a floor that can be neither solved nor inherited is omitted`() {
        val ref = FloorGeoref(
            "f1.png", W, H, "1",
            tiePoints = listOf(tie(0.1, 0.2), tie(0.9, 0.3)),
            stackAnchorU = 0.5, stackAnchorV = 0.5,
        )
        val orphan = FloorGeoref("f3.png", W, H, "3")  // no tie points, no anchor
        val geos = Building("b1", "Tower", "f1.png", listOf(ref, orphan)).resolve()
        assertTrue(geos.containsKey("f1.png"))
        assertTrue("orphan floor must not be guessed at", !geos.containsKey("f3.png"))
    }
}
