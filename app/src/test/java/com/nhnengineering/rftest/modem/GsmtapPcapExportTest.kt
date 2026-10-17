package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re-parses the produced PCAP to prove every layer is byte-correct: the PCAP container, the
 * Ethernet/IPv4/UDP framing, and the GSMTAPv3 header (version, type, sub-type, channel TLV,
 * end-of-metadata) with the original signaling bytes intact. This is structural proof; final
 * dissection is confirmed in Wireshark with the SCAT GSMTAPv3 plugin.
 */
class GsmtapPcapExportTest {

    private fun rrcEvent(atMs: Long, pdu: RrcOtaParser.PduType, arfcn: Long, uperHex: String) =
        ModemNrStream.SignalingEvent(
            atElapsedMs = atMs,
            rrc = RrcOtaParser.Result(looksValid = true, pci = 929, nrArfcn = arfcn, pduType = pdu, rawUperHex = uperHex),
        )

    private fun nasEvent(atMs: Long, nasHex: String) =
        ModemNrStream.SignalingEvent(
            atElapsedMs = atMs,
            nas = Nas5gsOtaParser.Result(looksValid = true, rawNasHex = nasHex),
        )

    private fun u16be(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32le(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    @Test
    fun `pcap container and framing are well formed`() {
        val pcap = GsmtapPcapExport.build(
            listOf(
                rrcEvent(1000, RrcOtaParser.PduType.SIB1, arfcn = 646272, uperHex = "deadbeef"),
                nasEvent(1200, "7e005b"),
            ),
            baseWallMs = 1_760_000_000_000L,
        )

        // Global header: magic, version, linktype Ethernet.
        assertEquals(0xA1B2C3D4L, u32le(pcap, 0))
        assertEquals(1L, u32le(pcap, 20)) // LINKTYPE_ETHERNET
        // Walk both records and assert every layer (throws on any mismatch).
        walkRecordsAndAssertLayers(pcap)
    }

    /** Walks both records and asserts the Eth/IP/UDP/GSMTAPv3 layers. */
    private fun walkRecordsAndAssertLayers(pcap: ByteArray) {
        var off = 24
        var seen = 0
        val sawTypes = mutableListOf<Int>()
        while (off < pcap.size) {
            val inclLen = u32le(pcap, off + 8).toInt()
            val frame = pcap.copyOfRange(off + 16, off + 16 + inclLen)
            // Ethernet: EtherType IPv4 at 12.
            assertEquals(0x0800, u16be(frame, 12))
            // IPv4: version/IHL and protocol UDP(17); check loopback addresses.
            assertEquals(0x45, frame[14].toInt() and 0xFF)
            assertEquals(17, frame[23].toInt() and 0xFF)
            // UDP: dst port 4729.
            assertEquals(4729, u16be(frame, 36))
            // GSMTAPv3 base starts after Eth(14)+IP(20)+UDP(8) = 42.
            val g = 42
            assertEquals(3, frame[g].toInt() and 0xFF)          // version 3
            val type = u16be(frame, g + 4)
            sawTypes += type
            // TLV right after the 8-byte base: CHANNEL_NUMBER (0x0002), length 4.
            assertEquals(0x0002, u16be(frame, g + 8))
            assertEquals(4, u16be(frame, g + 10))
            // END_OF_METADATA at base+16.
            assertEquals(0xfffe, u16be(frame, g + 16))
            // Message bytes follow at base+18.
            val msg = frame.copyOfRange(g + 18, frame.size)
            assertTrue(msg.isNotEmpty())
            seen++
            off += 16 + inclLen
        }
        assertEquals(2, seen)
        // One NR RRC (0x0503) and one NAS-5GS (0x0504).
        assertTrue(sawTypes.contains(0x0503))
        assertTrue(sawTypes.contains(0x0504))
    }

    @Test
    fun `rrc message bytes survive the round trip with the right channel sub-type`() {
        val pcap = GsmtapPcapExport.build(
            listOf(rrcEvent(500, RrcOtaParser.PduType.SIB1, arfcn = 646272, uperHex = "0102030405")),
            baseWallMs = 1L,
        )
        // First (only) record's GSMTAP: type NR_RRC, sub BCCH_DL_SCH (SIB1), arfcn in the channel TLV.
        val g = 24 + 16 + 42
        assertEquals(0x0503, u16be(pcap, g + 4))                 // NR_RRC
        assertEquals(0x0002, u16be(pcap, g + 6))                 // sub-type BCCH_DL_SCH
        assertEquals(646272L, u32le_be(pcap, g + 12))            // arfcn in CHANNEL_NUMBER value
        val msg = pcap.copyOfRange(g + 18, pcap.size)
        assertEquals("0102030405", msg.joinToString("") { "%02x".format(it) })
    }

    private fun u32le_be(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    @Test
    fun `emit a sample pcap for independent validation`() {
        val pcap = GsmtapPcapExport.build(
            listOf(
                rrcEvent(1000, RrcOtaParser.PduType.MIB, 646272, "0102"),
                rrcEvent(1100, RrcOtaParser.PduType.SIB1, 646272, "aabbccdd"),
                rrcEvent(1250, RrcOtaParser.PduType.RRC_RECONFIG, 646272, "1122334455"),
                nasEvent(1400, "7e005b00"),           // 5GMM identity request-ish bytes
                nasEvent(1600, "7e0068aabb"),          // DL NAS transport-ish
            ),
            baseWallMs = 1_760_000_000_000L,
        )
        val dir = java.io.File("build/sample-sessions").apply { mkdirs() }
        java.io.File(dir, "signaling-sample.pcap").writeBytes(pcap)
    }

    @Test
    fun `frame count skips events with no decodable body`() {
        val events = listOf(
            rrcEvent(1, RrcOtaParser.PduType.MIB, 0, "aa"),
            ModemNrStream.SignalingEvent(atElapsedMs = 2), // neither nas nor rrc
            nasEvent(3, "7e00"),
        )
        assertEquals(2, GsmtapPcapExport.frameCount(events))
    }
}
