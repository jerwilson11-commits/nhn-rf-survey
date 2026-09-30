package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Nas5gsOtaParserTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun u8(v: Int) = byteArrayOf(v.toByte())

    /** The 12-byte DIAG_LOG_F header: total length, log code, an 8-byte timestamp unused here. */
    private fun header(totalLen: Int, logCode: Int): ByteArray =
        le16(totalLen) + le16(logCode) + ByteArray(8)

    /** `u32` version + 3 version-component bytes, per the validated 2026-09-27 layout. */
    private fun versionField(): ByteArray = ByteArray(4) + ByteArray(3)

    private fun packet(logCode: Int, nasBytes: ByteArray): ByteArray {
        val body = versionField() + nasBytes
        val total = 12 + body.size
        return header(total, logCode) + body
    }

    /** A plain (unprotected) NAS-5GS message: EPD, security header type 0, message type, IEs. */
    private fun plainNas(messageType: Int, extraIes: ByteArray = ByteArray(0)): ByteArray =
        u8(0x7E) + u8(0x00) + u8(messageType) + extraIes

    /** A security-protected message: EPD, non-zero security header type, MAC(4), seq(1), body. */
    private fun protectedNas(securityHeaderType: Int): ByteArray =
        u8(0x7E) + u8(securityHeaderType) + ByteArray(4) + u8(0) + ByteArray(8)

    @Test
    fun `recognises both incoming and outgoing log codes`() {
        val inc = Nas5gsOtaParser.parse(packet(0xB80A, plainNas(0x42)))
        val out = Nas5gsOtaParser.parse(packet(0xB80B, plainNas(0x45)))
        assertEquals(Nas5gsOtaParser.Direction.INCOMING, inc.direction)
        assertEquals(Nas5gsOtaParser.Direction.OUTGOING, out.direction)
    }

    @Test
    fun `rejects a log code that is not NAS-5GS OTA`() {
        val r = Nas5gsOtaParser.parse(packet(0xB823, plainNas(0x42)))
        assertNull(r.direction)
        assertTrue(r.notes.first().contains("not NAS-5GS OTA"))
    }

    @Test
    fun `decodes each standard 5GMM message type from the table`() {
        val cases = mapOf(
            0x41 to "Registration request", 0x42 to "Registration accept",
            0x45 to "Deregistration request (UE orig.)", 0x46 to "Deregistration accept (UE orig.)",
            0x4E to "Service accept", 0x67 to "UL NAS transport",
        )
        for ((code, name) in cases) {
            val r = Nas5gsOtaParser.parse(packet(0xB80A, plainNas(code)))
            assertEquals("mismatch for 0x%02x".format(code), name, r.messageTypeName)
            assertEquals(false, r.securityProtected)
        }
    }

    @Test
    fun `a security-protected message is reported honestly, not guessed at`() {
        val r = Nas5gsOtaParser.parse(packet(0xB80A, protectedNas(securityHeaderType = 2)))
        assertEquals(true, r.securityProtected)
        assertNull(r.messageType)
        assertNull(r.messageTypeName)
        assertTrue(r.rawNasHex != null)
    }

    @Test
    fun `an unrecognised plain message type is flagged, not silently named`() {
        val r = Nas5gsOtaParser.parse(packet(0xB80A, plainNas(0x01)))
        assertEquals(false, r.securityProtected)
        assertNull(r.messageTypeName)
        assertTrue(r.notes.any { it.contains("not in the known 5GMM table") })
    }

    @Test
    fun `raw hex is present whenever NAS bytes are reachable`() {
        val r = Nas5gsOtaParser.parse(packet(0xB80B, plainNas(0x4E)))
        assertEquals("7e004e", r.rawNasHex)
    }

    @Test
    fun `parseLogLine decodes a LOG-prefixed hex line the same way`() {
        val bytes = packet(0xB80A, plainNas(0x4E))
        val line = "LOG " + bytes.joinToString("") { "%02x".format(it) }
        val r = Nas5gsOtaParser.parseLogLine(line)
        assertEquals("Service accept", r?.messageTypeName)
    }

    @Test
    fun `parseLogLine returns null for a non-LOG line`() {
        assertNull(Nas5gsOtaParser.parseLogLine("READY 2 code(s)"))
    }
}
