package com.nhnengineering.rftest.session

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Round-trips the per-floorplan grid-dimension config and checks the default fallback. */
class ErrcsGridConfigStoreTest {

    private fun tempStore(): ErrcsGridConfigStore =
        ErrcsGridConfigStore(File.createTempFile("errcs_cfg", ".jsonl").apply { deleteOnExit() })

    @Test
    fun `an unknown floorplan falls back to the default grid`() {
        assertEquals(ErrcsGridConfigStore.DEFAULT, tempStore().configFor("never-set.png"))
    }

    @Test
    fun `set then configFor round-trips rows and cols`() {
        val store = tempStore()
        store.set("floor3.png", rows = 6, cols = 7)
        val cfg = store.configFor("floor3.png")
        assertEquals(6, cfg.rows)
        assertEquals(7, cfg.cols)
    }

    @Test
    fun `dimensions are clamped to a sane range`() {
        val store = tempStore()
        store.set("f.png", rows = 0, cols = 99)
        val cfg = store.configFor("f.png")
        assertEquals(1, cfg.rows)   // 0 -> 1
        assertEquals(20, cfg.cols)  // 99 -> 20
    }
}
