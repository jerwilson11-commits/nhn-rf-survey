package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.model.ErrcsCriticalArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round-trips per-floorplan critical-area designations and the per-floorplan isolation of clears. */
class ErrcsCriticalAreaStoreTest {

    private fun tempStore(): ErrcsCriticalAreaStore =
        ErrcsCriticalAreaStore(File.createTempFile("errcs_crit", ".jsonl").apply { deleteOnExit() })

    @Test
    fun `areas round-trip per floorplan`() {
        val store = tempStore()
        val a = listOf(
            ErrcsCriticalArea(0f, 0f, 0.25f, 0.25f),
            ErrcsCriticalArea(0.5f, 0.5f, 0.75f, 1f),
        )
        store.setAreas("floor1.png", a)
        store.setAreas("floor2.png", listOf(ErrcsCriticalArea(0f, 0f, 1f, 1f)))

        val f1 = store.areasFor("floor1.png")
        assertEquals(2, f1.size)
        assertEquals(0.5f, f1[1].x0, 0.0001f)
        assertEquals(0.75f, f1[1].x1, 0.0001f)
        assertEquals(1f, f1[1].y1, 0.0001f)
        assertEquals(1, store.areasFor("floor2.png").size)
    }

    @Test
    fun `clearing one floorplan leaves others intact`() {
        val store = tempStore()
        store.setAreas("a.png", listOf(ErrcsCriticalArea(0f, 0f, 0.5f, 0.5f)))
        store.setAreas("b.png", listOf(ErrcsCriticalArea(0f, 0f, 0.5f, 0.5f)))

        store.setAreas("a.png", emptyList())
        assertTrue(store.areasFor("a.png").isEmpty())
        assertEquals(1, store.areasFor("b.png").size)
    }
}
