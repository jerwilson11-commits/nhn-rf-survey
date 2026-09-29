package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decode against synthetic packets built from the field layout SCAT's own open-source
 * parser documents -- see [NrRrcServingCellParser]'s class doc for the source and for why this is
 * synthetic rather than a real capture, the way [NrMl1ParserTest] is: this payload has not yet
 * been seen from this handset's own modem firmware.
 */
class NrRrcServingCellParserTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }
    private fun le64(v: Long) = ByteArray(8) { ((v shr (8 * it)) and 0xFF).toByte() }
    private fun u8(v: Int) = byteArrayOf(v.toByte())

    /** The 12-byte DIAG_LOG_F header [NrMl1Parser] already confirmed empirically: total length,
     *  log code, an 8-byte timestamp this parser does not use. */
    private fun header(totalLen: Int, logCode: Int = 0xB823): ByteArray =
        le16(totalLen) + le16(logCode) + ByteArray(8)

    /** Version 0x0000 body: no NR CGI, 34 bytes. */
    private fun baseBody(
        pci: Int = 206,
        dlArfcn: Long = 501390,
        ulArfcn: Long = 501390,
        dlBw: Int = 51,
        ulBw: Int = 51,
        cellId: Long = 6592188719L,
        mcc: Int = 310,
        mncDigit: Int = 2,
        mnc: Int = 260,
        allowedAccess: Int = 1,
        tac: Long = 8517888,
        band: Int = 41,
    ): ByteArray =
        le16(pci) + le32(dlArfcn) + le32(ulArfcn) + le16(dlBw) + le16(ulBw) + le64(cellId) +
            le16(mcc) + u8(mncDigit) + le16(mnc) + u8(allowedAccess) + le32(tac) + le16(band)

    /** Version 0x0003 body: NR CGI inserted right after PCI, 42 bytes. */
    private fun extendedBody(nrCgi: Long = 123456789L): ByteArray {
        val base = baseBody()
        return base.copyOfRange(0, 2) + le64(nrCgi) + base.copyOfRange(2, base.size)
    }

    private fun packet(version: Pair<Int, Int>, body: ByteArray, offsetPad: Int = 0): ByteArray {
        // The version header itself is always at a fixed position right after the 12-byte packet
        // header; the 3-byte shift SCAT documents applies to where the *body* starts after that,
        // not to the version header, so the padding goes between them, not before the version.
        val ver = le16(version.first) + le16(version.second)
        val padded = ver + ByteArray(offsetPad) + body
        val total = 12 + padded.size
        return header(total) + padded
    }

    // ---- version 0x0000: base body -------------------------------------------------------

    @Test
    fun `version 0 decodes the base body with no NR CGI`() {
        val r = NrRrcServingCellParser.parse(packet(0 to 4, baseBody()))

        assertTrue(r.looksValid)
        assertEquals("0.4", r.payloadVersion)
        assertEquals(206, r.pci)
        assertEquals(null, r.nrCgi)
        assertEquals(501390L, r.dlNrArfcn)
        assertEquals(310, r.mcc)
        assertEquals(260, r.mnc)
        assertEquals(41, r.band)
        assertEquals(8517888L, r.tac)
    }

    // ---- version 0x0003, rel_min 0: extended body, no offset shift -----------------------

    @Test
    fun `version 3 point 0 decodes the extended body with an NR CGI`() {
        val r = NrRrcServingCellParser.parse(packet(3 to 0, extendedBody(nrCgi = 999)))

        assertTrue(r.looksValid)
        assertEquals("3.0", r.payloadVersion)
        assertEquals(206, r.pci)
        assertEquals(999L, r.nrCgi)
        assertEquals(41, r.band)
    }

    // ---- version 0x0003, rel_min 2/3: same extended body, 3-byte offset shift -------------

    @Test
    fun `version 3 point 2 applies the 3-byte offset shift`() {
        val r = NrRrcServingCellParser.parse(packet(3 to 2, extendedBody(nrCgi = 555), offsetPad = 3))

        assertTrue(r.looksValid)
        assertEquals("3.2", r.payloadVersion)
        assertEquals(555L, r.nrCgi)
        assertEquals(206, r.pci)
    }

    // ---- error paths -------------------------------------------------------------

    @Test
    fun `the wrong log code is refused and named`() {
        val pkt = packet(0 to 4, baseBody())
        val wrongCode = le16(pkt.size) + le16(0xB97F) + pkt.copyOfRange(4, pkt.size)
        val r = NrRrcServingCellParser.parse(wrongCode)

        assertFalse(r.looksValid)
        assertTrue(r.notes.single().contains("b97f"))
    }

    @Test
    fun `a declared length mismatch is refused`() {
        val pkt = packet(0 to 4, baseBody())
        val corrupted = pkt.copyOfRange(0, pkt.size - 5)
        val r = NrRrcServingCellParser.parse(corrupted)

        assertFalse(r.looksValid)
    }

    @Test
    fun `an unrecognised payload version is named rather than guessed at`() {
        val r = NrRrcServingCellParser.parse(packet(7 to 1, baseBody()))

        assertFalse(r.looksValid)
        assertEquals("7.1", r.payloadVersion)
        assertTrue(r.notes.single().contains("7.1"))
    }

    @Test
    fun `too short a packet is refused rather than partially read`() {
        val r = NrRrcServingCellParser.parse(byteArrayOf(1, 2, 3))
        assertFalse(r.looksValid)
    }

    // ---- LOG line framing -------------------------------------------------------------

    @Test
    fun `parseLogLine strips the LOG prefix and decodes hex`() {
        val hex = packet(0 to 4, baseBody()).joinToString("") { "%02x".format(it) }
        val r = NrRrcServingCellParser.parseLogLine("LOG $hex")!!

        assertTrue(r.looksValid)
        assertEquals(206, r.pci)
    }

    @Test
    fun `a line that is not a LOG line is not parsed at all`() {
        assertEquals(null, NrRrcServingCellParser.parseLogLine("READY 2 code(s)"))
    }
}
