package com.nhnengineering.rftest.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the projection against hand-computed values.
 *
 * A projection error does not announce itself. Tiles load, the trail draws, everything looks
 * finished — and the samples sit tens of metres from where they were taken, on the wrong side of a
 * wall. That is worse than no basemap, because it looks authoritative. Hence reference values
 * computed from the Web Mercator definition rather than from this implementation.
 */
class MercatorTest {

    @Test
    fun `the origin is the top-left of the world at every zoom`() {
        // Longitude -180, latitude +85.0511 is tile (0,0) by definition of the scheme.
        for (z in 0..19) {
            // Tolerance scales with zoom because a tile coordinate is in tiles, and there are
            // 2^z of them across the world. Held to well under a tenth of a pixel throughout.
            val tolerance = Math.pow(2.0, z.toDouble()) * 1e-9
            assertEquals("x at z=$z", 0.0, Mercator.lonToTileX(-180.0, z), tolerance)
            assertEquals("y at z=$z", 0.0, Mercator.latToTileY(Mercator.MAX_LATITUDE, z), tolerance)
        }
    }

    @Test
    fun `the equator and prime meridian sit at the centre of the world`() {
        // At zoom z the world is 2^z tiles across, so (0,0) degrees is at 2^(z-1).
        assertEquals(1.0, Mercator.lonToTileX(0.0, 1), 1e-9)
        assertEquals(1.0, Mercator.latToTileY(0.0, 1), 1e-9)
        assertEquals(8.0, Mercator.lonToTileX(0.0, 4), 1e-9)
        assertEquals(8.0, Mercator.latToTileY(0.0, 4), 1e-9)
    }

    @Test
    fun `the Pentagon lands in the tile that actually contains it`() {
        // Verified against live Esri imagery on 2026-09-02: tile 16/18740/25076 renders the
        // Pentagon. If the projection drifts, this test fails before anyone sees a wrong map.
        val z = 16
        val x = Mercator.lonToTileX(-77.0563, z).toInt()
        val y = Mercator.latToTileY(38.8719, z).toInt()

        assertEquals(18740, x)
        assertEquals(25076, y)
    }

    @Test
    fun `latitude and tile-y round-trip`() {
        for (lat in listOf(-84.0, -45.0, -0.5, 0.0, 26.05, 38.87, 51.5, 84.0)) {
            val y = Mercator.latToTileY(lat, 18)
            assertEquals("round trip at $lat", lat, Mercator.tileYToLat(y, 18), 1e-7)
        }
    }

    @Test
    fun `longitude and tile-x round-trip`() {
        for (lon in listOf(-179.9, -80.14, 0.0, 12.5, 179.9)) {
            val x = Mercator.lonToTileX(lon, 18)
            assertEquals("round trip at $lon", lon, Mercator.tileXToLon(x, 18), 1e-9)
        }
    }

    @Test
    fun `tile-y increases southward, opposite to latitude`() {
        // The sign flip is the single easiest thing to get backwards, and it produces a map that
        // is upside down in one axis only -- which reads as a plotting bug rather than an error.
        val north = Mercator.latToTileY(40.0, 12)
        val south = Mercator.latToTileY(30.0, 12)

        assertTrue("further south must have a larger tile-y", south > north)
    }

    @Test
    fun `impossible latitudes are clamped rather than producing infinity`() {
        // A GPS glitch reporting 90 degrees should distort one sample, not blow up the map: the
        // Mercator formula diverges at the poles.
        val atPole = Mercator.latToTileY(90.0, 10)
        val atLimit = Mercator.latToTileY(Mercator.MAX_LATITUDE, 10)

        assertTrue("must be finite", atPole.isFinite())
        assertEquals(atLimit, atPole, 1e-9)
    }

    @Test
    fun `metres per pixel matches the known value at the equator`() {
        // Zoom 0 puts the whole world in one 256 px tile, so a pixel is circumference/256 at the
        // equator -- about 156.5 km. This is the standard reference figure for the scheme.
        assertEquals(156_543.03, Mercator.metresPerPixel(0.0, 0), 0.5)
        // Each zoom level halves it.
        assertEquals(
            Mercator.metresPerPixel(0.0, 0) / 2,
            Mercator.metresPerPixel(0.0, 1),
            1e-6,
        )
    }

    @Test
    fun `metres per pixel shrinks with latitude`() {
        // Mercator stretches toward the poles, so a pixel covers less ground there. A scale bar
        // computed at the equator and drawn in Florida would be wrong by roughly a tenth -- the
        // kind of error nobody notices and everybody relies on.
        val equator = Mercator.metresPerPixel(0.0, 18)
        val florida = Mercator.metresPerPixel(26.05, 18)

        assertTrue("Florida pixel must cover less ground", florida < equator)
        assertEquals(equator * Math.cos(Math.toRadians(26.05)), florida, 1e-9)
    }

    @Test
    fun `fitZoom picks a level whose tiles fit the canvas`() {
        val bounds = Mercator.Bounds(26.0500, 26.0510, -80.1400, -80.1390)
        val z = Mercator.fitZoom(bounds, widthPx = 1000, heightPx = 720)

        val w = (Mercator.lonToTileX(bounds.maxLon, z) - Mercator.lonToTileX(bounds.minLon, z)) *
            Mercator.TILE_SIZE
        val h = (Mercator.latToTileY(bounds.minLat, z) - Mercator.latToTileY(bounds.maxLat, z)) *
            Mercator.TILE_SIZE

        assertTrue("width $w must fit 1000", w <= 1000)
        assertTrue("height $h must fit 720", h <= 720)
        assertTrue("zoom must be usable", z in 1..19)
    }

    @Test
    fun `a stationary session is expanded rather than zoomed into its own jitter`() {
        // Two metres of GPS scatter is what standing still looks like. Fitting the map to that
        // would zoom to a jitter cloud and imply a precision the measurement does not have.
        val tight = Mercator.Bounds(26.05000, 26.05002, -80.13900, -80.13898)
        val expanded = tight.expandedToAtLeast(25.0)

        val spanLatM = (expanded.maxLat - expanded.minLat) * 111_320.0
        assertTrue("expected at least 25 m, got $spanLatM", spanLatM >= 24.9)
        // Still centred on the same place.
        assertEquals(tight.midLat, expanded.midLat, 1e-9)
    }

    @Test
    fun `an already-wide extent is left alone`() {
        val wide = Mercator.Bounds(26.0500, 26.0600, -80.1400, -80.1300)
        val result = wide.expandedToAtLeast(25.0)

        assertEquals(wide.minLat, result.minLat, 1e-12)
        assertEquals(wide.maxLat, result.maxLat, 1e-12)
    }

    @Test
    fun `bounds of nothing is null, not a point at the origin`() {
        // Null Island is off the coast of Ghana. A session with no fixes must not be drawn there.
        assertNull(Mercator.Bounds.of(emptyList()))
    }
}
