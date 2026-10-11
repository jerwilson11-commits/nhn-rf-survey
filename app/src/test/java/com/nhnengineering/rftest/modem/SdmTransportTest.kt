package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Exercises [SdmTransport.processStream] — the byte-stream → frame → RRC-dispatch logic that needs no
 * device or root. The live `/dev/umts_dm0` open + SDM enable handshake is gated behind hardware
 * validation (RQ5) and is not covered here.
 */
class SdmTransportTest {

    /** Returns at most [max] bytes per read, to force partial-frame carry-over across reads. */
    private class DribbleStream(data: ByteArray, private val max: Int) : InputStream() {
        private val inner = ByteArrayInputStream(data)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, max))
    }

    private fun nrFrame(vararg bytes: Int) = SdmParser.buildFrame(
        SdmParser.IPC_DM_CMD, SdmParser.GROUP_COMMON, SdmParser.COMMON_NR_RRC_SIGNALING_INFO,
        ByteArray(bytes.size) { bytes[it].toByte() },
    )

    private fun lteFrame(channel: Int, direction: Int, rrc: ByteArray) = SdmParser.buildFrame(
        SdmParser.IPC_DM_CMD, SdmParser.GROUP_LTE, SdmParser.LTE_RRC_OTA_PACKET,
        byteArrayOf(channel.toByte(), direction.toByte(), (rrc.size and 0xFF).toByte(), 0) + rrc,
    )

    @Test
    fun `processStream dispatches NR and LTE RRC frames`() {
        val stream = ByteArrayInputStream(nrFrame(1, 2, 3) + lteFrame(3, 0, byteArrayOf(0x40, 0x0B)))
        val nr = ArrayList<SdmParser.SdmFrame>()
        val lte = ArrayList<SdmParser.LteRrcOta>()

        SdmTransport().processStream(stream, onNrRrc = { nr += it }, onLteRrc = { lte += it })

        assertEquals(1, nr.size)
        assertEquals(1, lte.size)
        assertEquals(3, lte[0].channel)
        assertArrayEquals2(byteArrayOf(0x40, 0x0B), lte[0].rrc)
    }

    @Test
    fun `processStream reassembles frames split across reads`() {
        // One byte per read forces every frame boundary to land mid-accumulation.
        val data = nrFrame(9, 9) + lteFrame(4, 1, byteArrayOf(0x68)) + nrFrame(7)
        val nr = ArrayList<SdmParser.SdmFrame>()
        val lte = ArrayList<SdmParser.LteRrcOta>()

        SdmTransport().processStream(DribbleStream(data, max = 1), onNrRrc = { nr += it }, onLteRrc = { lte += it })

        assertEquals(2, nr.size)
        assertEquals(1, lte.size)
    }

    @Test
    fun `summary counts frames by type and cmd_sig`() {
        val data = nrFrame(1, 2) + lteFrame(3, 0, byteArrayOf(0x40)) + nrFrame(9)
        val t = SdmTransport()
        t.processStream(ByteArrayInputStream(data), onNrRrc = {}, onLteRrc = {})
        val s = t.summary()
        assertEquals(3, s.framesTotal)
        assertEquals(2, s.nrRrcFrames)
        assertEquals(1, s.lteRrcFrames)
        assertEquals(data.size.toLong(), s.bytesRead)
        assertEquals(2L, s.cmdSigCounts[0x0108])   // NR RRC
        assertEquals(1L, s.cmdSigCounts[0x0252])   // LTE RRC OTA
    }

    @Test
    fun `start returns false (with a node reason) when the diag node can't be opened`() {
        // On a JVM/CI host there is no `su`, so the device open fails -- the honest beta outcome.
        val t = SdmTransport()
        val ok = t.start(onNrRrc = {}, onLteRrc = {})
        assertEquals(false, ok)
        assertTrue(t.unavailableReason!!.contains("umts_dm0"))
    }

    private fun assertArrayEquals2(expected: ByteArray, actual: ByteArray) {
        assertTrue(expected.contentEquals(actual))
    }
}
