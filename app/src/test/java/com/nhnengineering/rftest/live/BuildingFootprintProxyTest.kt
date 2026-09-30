package com.nhnengineering.rftest.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingFootprintProxyTest {

    // ---- quantize: stable cache keys for a coarse grid ---------------------------------

    @Test
    fun `two points in the same cell quantize to the same bbox`() {
        val a = quantize(40.7128, -74.0060)
        val b = quantize(40.7130, -74.0055)
        assertEquals(a, b)
    }

    @Test
    fun `points a cell apart quantize to different bboxes`() {
        val a = quantize(40.7128, -74.0060, cellSizeDeg = 0.005)
        val b = quantize(40.7128 + 0.01, -74.0060, cellSizeDeg = 0.005)
        assertTrue(a != b)
    }

    @Test
    fun `the quantized cell actually contains the original point`() {
        val cell = quantize(40.7128, -74.0060, cellSizeDeg = 0.005)
        assertTrue(cell.south <= 40.7128 && 40.7128 < cell.north)
        assertTrue(cell.west <= -74.0060 && -74.0060 < cell.east)
    }

    // ---- buildOverpassQuery ------------------------------------------------------------

    @Test
    fun `the query carries the bbox in south,west,north,east order`() {
        val cell = BuildingFootprintProxy.BboxCell(south = 1.0, west = 2.0, north = 3.0, east = 4.0)
        val q = buildOverpassQuery(cell)
        assertTrue(q.contains("way[\"building\"](1.000000,2.000000,3.000000,4.000000)"))
        assertTrue(q.contains("out geom"))
    }

    // ---- parseBuildings ------------------------------------------------------------------

    private val sampleResponse = """
        {
          "elements": [
            {
              "type": "way",
              "id": 1,
              "geometry": [
                {"lat": 40.1, "lon": -74.1},
                {"lat": 40.2, "lon": -74.1},
                {"lat": 40.2, "lon": -74.2},
                {"lat": 40.1, "lon": -74.2},
                {"lat": 40.1, "lon": -74.1}
              ]
            },
            {
              "type": "node",
              "id": 2,
              "lat": 40.5,
              "lon": -74.5
            },
            {
              "type": "way",
              "id": 3,
              "geometry": [
                {"lat": 41.0, "lon": -75.0},
                {"lat": 41.0, "lon": -75.1}
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `each way with a geometry array becomes one polygon`() {
        val polygons = parseBuildings(sampleResponse)
        assertEquals(1, polygons.size)
        assertEquals(5, polygons[0].size)
        assertEquals(40.1 to -74.1, polygons[0].first())
    }

    @Test
    fun `a node element is not mistaken for a building`() {
        val polygons = parseBuildings(sampleResponse)
        assertTrue(polygons.none { it.size == 1 })
    }

    @Test
    fun `a way with fewer than 3 points is skipped rather than producing a degenerate shape`() {
        // way id 3 above has only 2 points -- confirmed excluded, not just that id 1 is present.
        val polygons = parseBuildings(sampleResponse)
        assertTrue(polygons.none { it.size < 3 })
    }

    @Test
    fun `no elements array yields an empty list, not an error`() {
        assertEquals(emptyList<Polygon>(), parseBuildings("{}"))
    }
}
