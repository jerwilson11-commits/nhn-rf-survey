package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decode of 5G NR neighbour measurements against real captures.
 *
 * Both packets below came off the handset on 2026-09-22 over DCI, camped on 5G SA n25 with the
 * phone stationary. They are the first neighbour measurements this project has ever obtained on
 * NR: Android reports none, and QMI NAS returns no cell list at all on NR SA.
 *
 * The layout was derived by locating values already known from other sources — ARFCN 396250 and
 * serving PCI 929, both confirmed by `dumpsys telephony.registry` — rather than by assuming a
 * structure. PCI 422 was one of the four serving cells recorded on the 2026-09-20 drive, so the
 * neighbour is a real cell in this area and not an artefact of reading at the wrong offset.
 */
class NrMl1ParserTest {

    private fun hex(s: String): ByteArray {
        val c = s.replace(" ", "")
        return ByteArray(c.length / 2) { c.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Serving PCI 929 beam 908, neighbour PCI 422. */
    private val capture1 = "b8007fb9f18f932226941201080002000080611c01140000d7060000bffcffff" +
        "da0b06000200a103000000000000000000000000ffffffffffff0000ffffffff" +
        "a1038c03010000000aceffff06f9ffff00000000000000000000000005f52d51" +
        "5f870a04b1ccffff09ceffff0aceffffedf8ffff0000000000000000" +
        "a6018c03010000008fccffffc2f6ffff000000000000000000000000558c4251" +
        "17880a0435c7ffff93ccffff8fccffff71f7ffff0000000000000000"

    /** The next packet: same cells, serving beam has moved to 972. */
    private val capture2 = "b8007fb98198912426941201080002000080601e01140000d4060000bffcffff" +
        "da0b06000200a103000000000000000000000000ffffffffffff0000ffffffff" +
        "a103cc03010000000aceffff06f9ffff00000000000000000000000005f52d51" +
        "5f870a04b1ccffff09ceffff0aceffffedf8ffff0000000000000000" +
        "a6018c03010000008fccffffc2f6ffff000000000000000000000000558c4251" +
        "17880a0435c7ffff93ccffff8fccffff71f7ffff0000000000000000"

    // ---- the measurement that did not exist before --------------------------

    @Test
    fun `an NR capture yields the serving cell and its neighbour`() {
        val r = NrMl1Parser.parse(hex(capture1))

        assertTrue(r.looksValid)
        assertEquals("A clean capture needs no notes: ${r.notes}", 0, r.notes.size)
        assertEquals(396250, r.arfcn)
        assertEquals(929, r.servingPci)
        assertEquals(929, r.serving?.pci)
        assertEquals(listOf(422), r.neighbours.map { it.pci })
    }

    @Test
    fun `signal values decode to the measured levels`() {
        val r = NrMl1Parser.parse(hex(capture1))

        assertEquals(-12790, r.serving?.rsrp128)
        assertEquals(-1786, r.serving?.rsrq128)

        val n = r.neighbours.single()
        assertEquals(-13169, n.rsrp128)
        assertEquals(-2366, n.rsrq128)
    }

    @Test
    fun `128ths are rounded for the app's whole-dB models`() {
        val r = NrMl1Parser.parse(hex(capture1))

        // -99.92 and -13.95
        assertEquals(-100, r.serving?.rsrpDbm)
        assertEquals(-14, r.serving?.rsrqDb)
        // -102.88 and -18.48
        assertEquals(-103, r.neighbours.single().rsrpDbm)
        assertEquals(-18, r.neighbours.single().rsrqDb)
    }

    @Test
    fun `every cell carries the channel, because a PCI alone is not a cell`() {
        // A PCI is unique only within a carrier. The app already learned this the hard way on
        // LTE, where one PCI appeared on two channels in the same session.
        val r = NrMl1Parser.parse(hex(capture1))

        assertTrue(r.allCells.isNotEmpty())
        assertTrue(r.allCells.all { it.arfcn == 396250 })
    }

    @Test
    fun `the serving cell is not also counted as a neighbour`() {
        val r = NrMl1Parser.parse(hex(capture1))

        assertEquals(2, r.allCells.size)
        assertFalse(r.neighbours.any { it.pci == 929 })
        assertTrue(r.serving?.serving == true)
    }

    @Test
    fun `the beam index moves between packets while the cells do not`() {
        // Provisional reading of the field at record offset 2. It varies per packet for the
        // serving cell and holds steady for the neighbour, which is how an SSB beam index would
        // behave — recorded as an observation, not relied upon by anything.
        val a = NrMl1Parser.parse(hex(capture1))
        val b = NrMl1Parser.parse(hex(capture2))

        assertEquals(a.neighbours.single().pci, b.neighbours.single().pci)
        assertEquals(908, a.serving?.beam)
        assertEquals(972, b.serving?.beam)
    }

    // ---- refusing to invent -------------------------------------------------

    @Test
    fun `a cell count that does not match the packet length is discarded whole`() {
        // The count and the length are two independent statements about how many cells there
        // are. When they disagree one of them is wrong, and a part-read record array produces a
        // plausible list of cells that were never measured.
        val b = hex(capture1)
        b[36] = 3  // says three cells; the bytes hold two
        val r = NrMl1Parser.parse(b)

        assertEquals(0, r.neighbours.size)
        assertNull(r.serving)
        assertTrue(r.notes.any { it.contains("Discarded rather than part-read") })
    }

    @Test
    fun `a packet whose declared length disagrees with its size is refused`() {
        val b = hex(capture1)
        b[0] = 0x50  // claims 80 bytes
        val r = NrMl1Parser.parse(b)

        assertFalse(r.looksValid)
        assertTrue(r.notes.any { it.contains("declares") })
    }

    @Test
    fun `a different log code is refused rather than read as NR`() {
        val b = hex(capture1)
        b[2] = 0x23
        b[3] = 0xB8.toByte()  // 0xB823, NR RRC Serving Cell Info
        val r = NrMl1Parser.parse(b)

        assertFalse(r.looksValid)
        assertEquals(0, r.neighbours.size)
    }

    @Test
    fun `something too short to be a log packet is refused`() {
        val r = NrMl1Parser.parse(byteArrayOf(0x0c, 0x00, 0x7f))

        assertFalse(r.looksValid)
        assertTrue(r.notes.any { it.contains("too short") })
    }

    @Test
    fun `a packet with no cells is valid and reports none`() {
        // 64 bytes: the fixed part and an empty record array. An honest zero.
        val b = ByteArray(64)
        b[0] = 64; b[1] = 0
        b[2] = 0x7f; b[3] = 0xb9.toByte()
        b[36] = 0; b[37] = 0

        val r = NrMl1Parser.parse(b)

        assertTrue(r.looksValid)
        assertEquals(0, r.allCells.size)
    }

    // ---- the helper boundary ------------------------------------------------

    @Test
    fun `a LOG line from the helper is parsed`() {
        val r = NrMl1Parser.parseLogLine("LOG $capture1")

        assertNotNull(r)
        assertEquals(listOf(422), r!!.neighbours.map { it.pci })
    }

    @Test
    fun `helper lines that are not log packets are not mistaken for empty readings`() {
        assertNull(NrMl1Parser.parseLogLine("READY 1 code(s)"))
        assertNull(NrMl1Parser.parseLogLine("ERR dlopen libdiag: not found"))
        assertNull(NrMl1Parser.parseLogLine("DONE 39"))
        assertNull(NrMl1Parser.parseLogLine("LOG zz"))
    }
}
