package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.model.CoverageArea
import com.nhnengineering.rftest.model.CoverageVertex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round-trips coverage polygons per floorplan, preserving winding order. */
class CoverageAreaStoreTest {

    private fun tempStore(): CoverageAreaStore =
        CoverageAreaStore(File.createTempFile("coverage", ".jsonl").apply { deleteOnExit() })

    @Test
    fun `polygon round-trips with vertex order preserved`() {
        val store = tempStore()
        val poly = CoverageArea(
            listOf(
                CoverageVertex(0.1f, 0.2f),
                CoverageVertex(0.8f, 0.2f),
                CoverageVertex(0.8f, 0.9f),
                CoverageVertex(0.1f, 0.9f),
            ),
        )
        store.setArea("floor1.png", poly)

        val back = store.areaFor("floor1.png")
        assertEquals(4, back.vertices.size)
        assertEquals(0.8f, back.vertices[1].x, 0.0001f)
        assertEquals(0.9f, back.vertices[2].y, 0.0001f)
    }

    @Test
    fun `clearing one floorplan leaves others intact`() {
        val store = tempStore()
        val tri = CoverageArea(
            listOf(CoverageVertex(0f, 0f), CoverageVertex(1f, 0f), CoverageVertex(0.5f, 1f)),
        )
        store.setArea("a.png", tri)
        store.setArea("b.png", tri)

        store.setArea("a.png", CoverageArea.EMPTY)
        assertTrue(store.areaFor("a.png").vertices.isEmpty())
        assertEquals(3, store.areaFor("b.png").vertices.size)
    }
}
