package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decode of the modem's own cell list against real captures.
 *
 * Every byte string below came off the handset on 2026-09-22 over QRTR, with the radio held on
 * LTE. They are kept verbatim because the thing that validated this decode in the first place was
 * that the structures consume exactly their declared length — a property that only means anything
 * against bytes a modem actually produced.
 *
 * The neighbours in these captures are the measurement whose absence let a report state a
 * confident "0.0% sector overlap" for a site that had never been assessed for overlap at all.
 */
class QmiCellParserTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /** Wraps TLVs in a GET_CELL_LOCATION_INFO response header, as the socket delivers it. */
    private fun response(vararg tlvs: Pair<Int, String>): ByteArray {
        val body = mutableListOf<Byte>()
        for ((id, h) in tlvs) {
            val v = hex(h)
            body += id.toByte()
            body += (v.size and 0xFF).toByte()
            body += ((v.size shr 8) and 0xFF).toByte()
            body += v.toList()
        }
        val head = listOf(
            0x02.toByte(), 0x01, 0x00, 0x43, 0x00,
            (body.size and 0xFF).toByte(), ((body.size shr 8) and 0xFF).toByte(),
        )
        return (head + body).toByteArray()
    }

    private val resultOk = 0x02 to "00 00 00 00"

    /** Serving frequency: EARFCN 1300, PCI 114, one cell (itself). */
    private val intra =
        0x13 to "00 13 00 62 f9 81 03 18 0e 04 14 05 72 00 00 00 00 00 01 72 00 9f ff 7e fc 76 fd 00 00"

    /** Two frequencies: EARFCN 1000 with PCI 288 and 368, EARFCN 2300 with none. */
    private val inter2 =
        0x14 to ("00 02 e8 03 00 00 00 02 20 01 74 ff 63 fc 4e fd 00 00 " +
            "70 01 4c ff 03 fc 1f fd 00 00 fc 08 00 00 00 00")

    /** One frequency, same two neighbours, a few seconds later. */
    private val inter3 =
        0x14 to ("00 01 e8 03 00 00 00 02 20 01 91 ff 65 fc 2e fd 00 00 " +
            "70 01 3d ff 0b fc 4c fd 00 00")

    // ---- the capture that matters ------------------------------------------

    @Test
    fun `the LTE capture yields the serving cell and both neighbours`() {
        val r = QmiCellParser.parse(response(resultOk, intra, inter2))

        assertTrue(r.looksValid)
        assertEquals(true, r.success)
        assertEquals("No note should be needed for a clean capture: ${r.notes}", 0, r.notes.size)

        assertEquals(114, r.serving?.pci)
        assertEquals(1300, r.serving?.earfcn)

        assertEquals(listOf(288, 368), r.neighbours.map { it.pci })
        assertEquals(listOf(1000, 1000), r.neighbours.map { it.earfcn })
    }

    @Test
    fun `neighbour signal values decode to the measured levels`() {
        val r = QmiCellParser.parse(response(resultOk, intra, inter2))
        val n288 = r.neighbours.first { it.pci == 288 }
        val n368 = r.neighbours.first { it.pci == 368 }

        assertEquals(-925, n288.rsrpTenths)
        assertEquals(-140, n288.rsrqTenths)
        assertEquals(-1021, n368.rsrpTenths)
        assertEquals(-180, n368.rsrqTenths)
    }

    @Test
    fun `tenths are rounded for the app's whole-dB models`() {
        val r = QmiCellParser.parse(response(resultOk, intra, inter2))
        val n288 = r.neighbours.first { it.pci == 288 }

        // -92.5 rounds away from zero rather than toward it, so repeated rounding cannot bias a
        // survey's mean upward.
        assertEquals(-93, n288.rsrpDbm)
        assertEquals(-14, n288.rsrqDb)
    }

    @Test
    fun `the operator and tracking area come off the serving frequency`() {
        val r = QmiCellParser.parse(response(resultOk, intra, inter2))

        assertEquals("310-260", r.plmn)
        assertEquals(33273, r.trackingAreaCode)
        assertEquals(false, r.ueInIdle)
    }

    @Test
    fun `the serving cell is not also listed as a neighbour`() {
        // It appears both in serving_cell_id and in its own frequency's cell array. Counting it
        // twice would inflate every neighbour count by one, on every sample.
        val r = QmiCellParser.parse(response(resultOk, intra, inter2))

        assertEquals(114, r.serving?.pci)
        assertFalse(r.neighbours.any { it.pci == 114 })
        assertEquals(3, r.allCells.size)
    }

    @Test
    fun `the second capture reads the same two neighbours`() {
        val r = QmiCellParser.parse(response(resultOk, intra, inter3))

        assertEquals(listOf(288, 368), r.neighbours.map { it.pci })
        assertEquals(-923, r.neighbours.first { it.pci == 288 }.rsrpTenths)
    }

    // ---- refusing to invent ------------------------------------------------

    @Test
    fun `an NR SA response yields no cells rather than fabricated ones`() {
        // The same message on NR SA carries only serving-cell TLVs this parser does not read.
        // The right answer is an empty list, not a guess, and not an error either.
        val nr = QmiCellParser.parse(
            response(
                resultOk,
                0x2e to "da 0b 06 00",
                0x2f to "13 00 62 81 f9 00 17 c0 ec 88 01 00 00 00 a1 03 92 ff a4 fc 69 00",
                0x32 to "33 31 30 32 36 30",
            ),
        )

        assertTrue(nr.looksValid)
        assertEquals(true, nr.success)
        assertNull(nr.serving)
        assertEquals(0, nr.neighbours.size)

        // The load-bearing part. Zero neighbours here means "not decoded", not "none present",
        // and anything that promotes it to a measured absence would state a finding about a site
        // nobody measured.
        assertFalse(nr.lteInfoPresent)
    }

    @Test
    fun `an LTE capture is marked as decoded`() {
        val r = QmiCellParser.parse(response(resultOk, intra, inter2))
        assertTrue(r.lteInfoPresent)
    }

    @Test
    fun `an LTE serving TLV alone still counts as decoded`() {
        // A site with genuinely no neighbours: the serving TLV is present, the inter-frequency
        // one is not. That is a real measured absence and must be distinguishable from NR.
        val r = QmiCellParser.parse(response(resultOk, intra))

        assertTrue(r.lteInfoPresent)
        assertEquals(0, r.neighbours.size)
    }

    @Test
    fun `a cell count that does not fit discards the whole TLV`() {
        // One byte out and a counted array yields a plausible list of cells that were never
        // measured. The count here says three cells where the bytes hold two.
        val tampered = inter2.second.replaceFirst("00 02 e8 03 00 00 00 02", "00 02 e8 03 00 00 00 03")
        val r = QmiCellParser.parse(response(resultOk, intra, 0x14 to tampered))

        assertEquals("No neighbour may survive a TLV that does not add up.", 0, r.neighbours.size)
        assertTrue(r.notes.any { it.contains("do not fit") })
    }

    @Test
    fun `trailing bytes after the last frequency discard the TLV`() {
        val r = QmiCellParser.parse(response(resultOk, 0x14 to (inter3.second + " ff ff")))

        assertEquals(0, r.neighbours.size)
        assertTrue(r.notes.any { it.contains("trailing") })
    }

    @Test
    fun `a serving TLV whose arithmetic does not close is discarded whole`() {
        val tampered = intra.second.replaceFirst("00 00 01 72 00", "00 00 02 72 00")
        val r = QmiCellParser.parse(response(resultOk, 0x13 to tampered))

        assertNull(r.serving)
        assertTrue(r.notes.any { it.contains("Discarded rather than part-read") })
    }

    @Test
    fun `a truncated TLV is reported rather than read past`() {
        val raw = response(resultOk, intra, inter2)
        val cut = raw.copyOfRange(0, raw.size - 6)
        val r = QmiCellParser.parse(cut)

        assertTrue(r.notes.any { it.contains("only") })
    }

    @Test
    fun `a response to a different message is refused`() {
        val wrong = response(resultOk, intra).copyOf()
        wrong[3] = 0x24  // GET_SERVING_SYSTEM
        val r = QmiCellParser.parse(wrong)

        assertFalse(r.looksValid)
        assertEquals(0, r.neighbours.size)
    }

    @Test
    fun `something too short to be a QMI header is refused`() {
        val r = QmiCellParser.parse(byteArrayOf(0x02, 0x01, 0x00))

        assertFalse(r.looksValid)
        assertTrue(r.notes.any { it.contains("shorter than a QMI header") })
    }

    @Test
    fun `a failed QMI result is reported rather than read as empty coverage`() {
        // result=1 means the modem refused the request. Zero neighbours here is "not answered",
        // which is a different thing from "none present" and must not read as the latter.
        val r = QmiCellParser.parse(response(0x02 to "01 00 0f 00"))

        assertEquals(false, r.success)
        assertEquals(0, r.neighbours.size)
    }

    // ---- the helper boundary -----------------------------------------------

    @Test
    fun `the helper's OK line is parsed`() {
        val raw = response(resultOk, intra, inter3)
        val line = "OK " + raw.joinToString("") { "%02x".format(it) }

        val r = QmiCellParser.parseHelperLine(line)

        assertNotNull(r)
        assertEquals(listOf(288, 368), r!!.neighbours.map { it.pci })
    }

    @Test
    fun `an error line from the helper is not mistaken for an empty reading`() {
        assertNull(QmiCellParser.parseHelperLine("ERR socket(AF_QIPCRTR): Permission denied"))
        assertNull(QmiCellParser.parseHelperLine(""))
        assertNull(QmiCellParser.parseHelperLine("OK zzzz"))
    }
}
