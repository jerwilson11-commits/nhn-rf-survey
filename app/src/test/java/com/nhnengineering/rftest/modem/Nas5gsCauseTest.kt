package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2: 5GMM cause extraction from reject / status NAS-5GS messages.
 *
 * Packet layout mirrors the validated DIAG envelope: 12-byte log header, u32 version + 3 version
 * bytes (NAS starts at byte 19), then the plain NAS message (EPD 0x7e, SHT 0x00, message type, then
 * the mandatory 5GMM cause for reject/status messages).
 */
class Nas5gsCauseTest {

    /** Builds a 0xB80A (incoming) NAS-5GS log packet carrying [nas] as the plain NAS message. */
    private fun pkt(nas: ByteArray, logCode: Int = Nas5gsOtaParser.LOG_CODE_INCOMING): ByteArray {
        val total = 19 + nas.size
        val b = ByteArray(total)
        b[0] = (total and 0xFF).toByte(); b[1] = ((total shr 8) and 0xFF).toByte()
        b[2] = (logCode and 0xFF).toByte(); b[3] = ((logCode shr 8) and 0xFF).toByte()
        // bytes 4..18 (timestamp + version) left zero
        nas.copyInto(b, 19)
        return b
    }

    private fun nas(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `registration reject carries a decoded 5GMM cause`() {
        // EPD 0x7e, SHT 0x00 (plain), msg 0x44 (Registration reject), cause 0x0b (PLMN not allowed).
        val r = Nas5gsOtaParser.parse(pkt(nas(0x7e, 0x00, 0x44, 0x0b)))
        assertTrue(r.looksValid)
        assertEquals("Registration reject", r.messageTypeName)
        assertEquals(0x0b, r.cause5gmm)
        assertEquals("PLMN not allowed", r.cause5gmmName)
    }

    @Test
    fun `service reject and 5GMM status also carry the cause`() {
        val svc = Nas5gsOtaParser.parse(pkt(nas(0x7e, 0x00, 0x4d, 0x16))) // Service reject, Congestion
        assertEquals("Service reject", svc.messageTypeName)
        assertEquals("Congestion", svc.cause5gmmName)

        val status = Nas5gsOtaParser.parse(pkt(nas(0x7e, 0x00, 0x64, 0x5f))) // 5GMM status, semantically incorrect
        assertEquals("5GMM status", status.messageTypeName)
        assertEquals("Semantically incorrect message", status.cause5gmmName)
    }

    @Test
    fun `an unknown cause value is surfaced numerically, not dropped`() {
        val r = Nas5gsOtaParser.parse(pkt(nas(0x7e, 0x00, 0x44, 0x7e))) // 0x7e not in the table
        assertEquals(0x7e, r.cause5gmm)
        assertNull(r.cause5gmmName)
        assertTrue(r.notes.any { it.contains("cause") })
    }

    @Test
    fun `non-reject messages carry no cause`() {
        val accept = Nas5gsOtaParser.parse(pkt(nas(0x7e, 0x00, 0x42, 0x01))) // Registration accept
        assertEquals("Registration accept", accept.messageTypeName)
        assertNull(accept.cause5gmm)
    }

    @Test
    fun `reject truncated before the cause byte does not fabricate one`() {
        val r = Nas5gsOtaParser.parse(pkt(nas(0x7e, 0x00, 0x44))) // no cause byte present
        assertEquals("Registration reject", r.messageTypeName)
        assertNull(r.cause5gmm)
    }
}
