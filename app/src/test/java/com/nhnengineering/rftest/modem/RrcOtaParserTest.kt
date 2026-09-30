package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RrcOtaParserTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }
    private fun u8(v: Int) = byteArrayOf(v.toByte())

    private fun header(totalLen: Int, logCode: Int = RrcOtaParser.LOG_CODE): ByteArray =
        le16(totalLen) + le16(logCode) + ByteArray(8)

    /**
     * Builds a payload matching the validated envelope: PCI @7, NR-ARFCN @9, PDU type @16, UPER
     * body from @23 -- offsets relative to payload start (i.e. packet[12 + N]).
     */
    private fun envelope(pci: Int, arfcn: Long, pduType: Int, uper: ByteArray): ByteArray {
        val body = ByteArray(7) +           // payload 0-6: unused fields, not modelled
            le16(pci) +                     // payload 7-8
            le32(arfcn) +                    // payload 9-12
            ByteArray(3) +                    // payload 13-15: unused
            u8(pduType) +                     // payload 16
            ByteArray(6) +                     // payload 17-22: unused
            uper                               // payload 23+
        return body
    }

    private fun packet(pci: Int, arfcn: Long, pduType: Int, uper: ByteArray = ByteArray(4)): ByteArray {
        val body = envelope(pci, arfcn, pduType, uper)
        val total = 12 + body.size
        return header(total) + body
    }

    @Test
    fun `extracts PCI, NR-ARFCN and PDU type at the validated offsets`() {
        val r = RrcOtaParser.parse(packet(pci = 206, arfcn = 501390, pduType = 2, uper = byteArrayOf(0x01, 0x02)))
        assertEquals(206, r.pci)
        assertEquals(501390L, r.nrArfcn)
        assertEquals(RrcOtaParser.PduType.SIB1, r.pduType)
        assertEquals("0102", r.rawUperHex)
    }

    @Test
    fun `recognises MIB and DL-DCCH pdu types too`() {
        assertEquals(RrcOtaParser.PduType.MIB, RrcOtaParser.parse(packet(929, 651670, 1)).pduType)
        assertEquals(RrcOtaParser.PduType.DL_DCCH, RrcOtaParser.parse(packet(929, 651670, 4)).pduType)
    }

    @Test
    fun `an unrecognised pdu type degrades to null with a note, not a guess`() {
        val r = RrcOtaParser.parse(packet(929, 651670, 99))
        assertNull(r.pduType)
        assertTrue(r.notes.any { it.contains("not one this app recognises") })
    }

    @Test
    fun `rejects a log code that is not NR RRC OTA`() {
        val bytes = header(12 + envelope(206, 501390, 2, ByteArray(0)).size, logCode = 0xB823) +
            envelope(206, 501390, 2, ByteArray(0))
        val r = RrcOtaParser.parse(bytes)
        assertNull(r.pci)
        assertTrue(r.notes.first().contains("not NR RRC OTA"))
    }

    @Test
    fun `parseLogLine decodes a LOG-prefixed hex line the same way`() {
        val bytes = packet(206, 501390, 2, byteArrayOf(0x0A.toByte()))
        val line = "LOG " + bytes.joinToString("") { "%02x".format(it) }
        val r = RrcOtaParser.parseLogLine(line)
        assertEquals(206, r?.pci)
        assertEquals("0a", r?.rawUperHex)
    }
}
