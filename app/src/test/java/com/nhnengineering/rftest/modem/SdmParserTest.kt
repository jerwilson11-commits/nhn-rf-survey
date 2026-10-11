package com.nhnengineering.rftest.modem

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SDM frame layout and RRC extraction against the format documented by the SCAT reference
 * (github.com/fgsect/scat, parsers/samsung). Uses [SdmParser.buildFrame] to synthesize frames and
 * round-trips them, since there is no rootable Shannon device to capture real ones yet (RQ5).
 */
class SdmParserTest {

    private fun lteRrcOtaPayload(channel: Int, direction: Int, rrc: ByteArray): ByteArray =
        byteArrayOf(channel.toByte(), direction.toByte(), (rrc.size and 0xFF).toByte(), ((rrc.size ushr 8) and 0xFF).toByte()) + rrc

    @Test
    fun `build then parse round-trips an LTE RRC OTA frame`() {
        val rrc = byteArrayOf(0x40, 0x0B, 0x12, 0x34)
        val payload = lteRrcOtaPayload(channel = 3, direction = 0, rrc = rrc)
        val frame = SdmParser.buildFrame(
            SdmParser.IPC_DM_CMD, SdmParser.GROUP_LTE, SdmParser.LTE_RRC_OTA_PACKET, payload,
            timestamp = 1_000_000L, radioId = 1,
        )

        val result = SdmParser.parseFrames(frame)
        assertEquals(1, result.frames.size)
        assertEquals(frame.size, result.nextOffset)
        val f = result.frames[0]
        assertEquals(SdmParser.IPC_DM_CMD, f.direction)
        assertEquals(1, f.radioId)
        assertEquals(SdmParser.GROUP_LTE, f.group)
        assertEquals(SdmParser.LTE_RRC_OTA_PACKET, f.command)
        assertEquals(1_000_000L, f.timestamp)
        assertEquals(0x0252, f.cmdSig)
        assertTrue(f.isLteRrcOta)
        assertArrayEquals(payload, f.payload)
    }

    @Test
    fun `LTE RRC OTA inner parse yields channel direction and raw RRC`() {
        val rrc = byteArrayOf(0x68, 0x01, 0x02, 0x03)
        val ota = SdmParser.parseLteRrcOta(lteRrcOtaPayload(channel = 4, direction = 1, rrc = rrc))!!
        assertEquals(4, ota.channel)
        assertEquals(1, ota.direction)
        assertArrayEquals(rrc, ota.rrc)
    }

    @Test
    fun `LTE RRC OTA inner parse rejects a too-short payload`() {
        assertNull(SdmParser.parseLteRrcOta(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun `an NR RRC frame is classified as NR, not LTE`() {
        val frame = SdmParser.buildFrame(
            SdmParser.IPC_DM_CMD, SdmParser.GROUP_COMMON, SdmParser.COMMON_NR_RRC_SIGNALING_INFO,
            byteArrayOf(0x00, 0x11, 0x22),
        )
        val f = SdmParser.parseFrames(frame).frames.single()
        assertTrue(f.isNrRrc)
        assertFalse(f.isLteRrcOta)
        assertEquals(0x0108, f.cmdSig)
    }

    @Test
    fun `two concatenated frames both parse`() {
        val a = SdmParser.buildFrame(SdmParser.IPC_DM_CMD, SdmParser.GROUP_LTE, SdmParser.LTE_RRC_OTA_PACKET, byteArrayOf(2, 0, 0, 0))
        val b = SdmParser.buildFrame(SdmParser.IPC_DM_CMD, SdmParser.GROUP_COMMON, SdmParser.COMMON_NR_RRC_SIGNALING_INFO, byteArrayOf(9, 9))
        val result = SdmParser.parseFrames(a + b)
        assertEquals(2, result.frames.size)
        assertTrue(result.frames[0].isLteRrcOta)
        assertTrue(result.frames[1].isNrRrc)
        assertEquals(a.size + b.size, result.nextOffset)
    }

    @Test
    fun `a frame with a bad end byte is skipped and the next valid frame is recovered`() {
        val bad = SdmParser.buildFrame(SdmParser.IPC_DM_CMD, SdmParser.GROUP_LTE, SdmParser.LTE_RRC_OTA_PACKET, byteArrayOf(0x11, 0x11, 0x11, 0x11))
        bad[bad.size - 1] = 0x00 // corrupt the trailing 0x7E
        val good = SdmParser.buildFrame(SdmParser.IPC_DM_CMD, SdmParser.GROUP_COMMON, SdmParser.COMMON_NR_RRC_SIGNALING_INFO, byteArrayOf(0x22, 0x22))

        val frames = SdmParser.parseFrames(bad + good).frames
        assertEquals(1, frames.size)
        assertTrue(frames[0].isNrRrc)
    }

    @Test
    fun `a partial trailing frame is left for the next read`() {
        val a = SdmParser.buildFrame(SdmParser.IPC_DM_CMD, SdmParser.GROUP_LTE, SdmParser.LTE_RRC_OTA_PACKET, byteArrayOf(1, 2, 3, 4))
        val b = SdmParser.buildFrame(SdmParser.IPC_DM_CMD, SdmParser.GROUP_COMMON, SdmParser.COMMON_NR_RRC_SIGNALING_INFO, byteArrayOf(5, 6))
        val buf = a + b.copyOfRange(0, b.size / 2) // only half of b present

        val result = SdmParser.parseFrames(buf)
        assertEquals(1, result.frames.size)
        assertTrue(result.frames[0].isLteRrcOta)
        // nextOffset points at the start of the incomplete frame b, so the caller carries it forward.
        assertEquals(a.size, result.nextOffset)
    }

    @Test
    fun `control start frame carries the control group and command`() {
        val f = SdmParser.parseFrames(SdmParser.controlStartFrame()).frames.single()
        assertEquals(SdmParser.GROUP_CONTROL, f.group)
        assertEquals(SdmParser.CONTROL_START, f.command)
        assertEquals(4, f.payload.size) // the 4-byte start magic
    }
}
