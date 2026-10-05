package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.model.CoverageArea
import com.nhnengineering.rftest.model.CoverageRegion
import com.nhnengineering.rftest.model.CoverageVertex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round-trips multiple coverage regions per floorplan, including the old single-polygon format. */
class CoverageAreaStoreTest {

    private fun tempFile(): File = File.createTempFile("coverage", ".jsonl").apply { deleteOnExit() }

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = CoverageArea(
        listOf(
            CoverageVertex(x0, y0), CoverageVertex(x1, y0),
            CoverageVertex(x1, y1), CoverageVertex(x0, y1),
        ),
    )

    @Test
    fun `two regions on one floor round-trip with their own grids and order`() {
        val store = CoverageAreaStore(tempFile())
        store.setRegions(
            "floor2.png",
            listOf(
                CoverageRegion(rect(0.05f, 0.05f, 0.6f, 0.9f), rows = 4, cols = 5),
                CoverageRegion(rect(0.7f, 0.05f, 0.95f, 0.3f), rows = 3, cols = 3),
            ),
        )
        val back = store.regionsFor("floor2.png")
        assertEquals(2, back.size)
        assertEquals(4, back[0].rows)
        assertEquals(5, back[0].cols)
        assertEquals(3, back[1].cols)
        assertEquals(4, back[1].polygon.vertices.size)
        assertEquals(0.7f, back[1].polygon.vertices[0].x, 0.0001f)
    }

    @Test
    fun `clearing one floorplan leaves others intact`() {
        val store = CoverageAreaStore(tempFile())
        store.setRegions("a.png", listOf(CoverageRegion(rect(0f, 0f, 0.5f, 0.5f))))
        store.setRegions("b.png", listOf(CoverageRegion(rect(0f, 0f, 0.5f, 0.5f))))
        store.setRegions("a.png", emptyList())
        assertTrue(store.regionsFor("a.png").isEmpty())
        assertEquals(1, store.regionsFor("b.png").size)
    }

    @Test
    fun `old single-polygon format loads as one region`() {
        val f = tempFile()
        // Exactly the pre-multi-region shape: one vertex per line, no region/points keys.
        f.writeText(
            buildString {
                append("{\"floorplanId\":\"old.png\",\"i\":0,\"x\":0.1,\"y\":0.1}\n")
                append("{\"floorplanId\":\"old.png\",\"i\":1,\"x\":0.9,\"y\":0.1}\n")
                append("{\"floorplanId\":\"old.png\",\"i\":2,\"x\":0.5,\"y\":0.9}\n")
            },
        )
        val back = CoverageAreaStore(f).regionsFor("old.png")
        assertEquals(1, back.size)
        assertEquals(3, back[0].polygon.vertices.size)
    }
}
