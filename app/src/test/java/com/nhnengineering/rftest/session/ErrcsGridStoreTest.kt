package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.model.ErrcsAreaClass
import com.nhnengineering.rftest.model.ErrcsGridPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Round-trips the Track A grid-point store, including the optional inbound/DAQ fields and the
 *  backward-compatible parse of records written before those fields existed. */
class ErrcsGridStoreTest {

    private fun tempStore(): Pair<ErrcsGridStore, File> {
        val f = File.createTempFile("errcs_grid", ".jsonl").apply { deleteOnExit() }
        return ErrcsGridStore(f) to f
    }

    @Test
    fun `inbound and DAQ survive a save-load round trip`() {
        val (store, _) = tempStore()
        val p = ErrcsGridPoint(
            id = "p1", floorplanId = "plan.png", xNorm = 0.25f, yNorm = 0.75f,
            areaClass = ErrcsAreaClass.CRITICAL, signalDbm = -88.0,
            inboundDbm = -92.5, daq = 3.4, systemLabel = "PD 800 P25", recordedAtUtcMillis = 123L,
        )
        store.save(listOf(p))

        val back = store.load().points.single()
        assertEquals(-88.0, back.signalDbm, 0.0001)
        assertEquals(-92.5, back.inboundDbm!!, 0.0001)
        assertEquals(3.4, back.daq!!, 0.0001)
        assertEquals("PD 800 P25", back.systemLabel)
    }

    @Test
    fun `a record written before inbound and DAQ existed still parses, with nulls`() {
        val (store, f) = tempStore()
        // Exactly the old serialised shape -- no inboundDbm / daq keys.
        f.writeText(
            "{\"id\":\"old\",\"floorplanId\":\"plan.png\",\"xNorm\":0.5,\"yNorm\":0.5," +
                "\"floor\":null,\"areaClass\":\"GENERAL\",\"signalDbm\":-90.0," +
                "\"systemLabel\":null,\"note\":null,\"recordedAtUtcMillis\":0}\n",
        )
        val back = store.load().points.single()
        assertEquals(-90.0, back.signalDbm, 0.0001)
        assertNull(back.inboundDbm)
        assertNull(back.daq)
    }
}
