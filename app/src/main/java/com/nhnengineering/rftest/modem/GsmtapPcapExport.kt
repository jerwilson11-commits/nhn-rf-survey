package com.nhnengineering.rftest.modem

import java.io.ByteArrayOutputStream

/**
 * GSMTAP → PCAP export of a signaling capture (roadmap D1). Pro tier.
 *
 * Turns the NR RRC and NAS-5GS messages captured by [ModemNrStream] into a `.pcap` that opens in
 * Wireshark, so the signaling can be read with a real protocol analyser instead of a hex dump. This
 * is a serialisation of bytes that were **already captured and validated** ([RrcOtaParser],
 * [Nas5gsOtaParser]) — it does no offset parsing of its own, so it is robust across modem
 * generations in a way the MAC/PHY metric decoders are not.
 *
 * ## Why GSMTAPv3, and the one caveat
 *
 * GSMTAP is the pseudo-header Wireshark uses for cellular signaling. **Version 2 has no NR or 5GS
 * types**, so NR RRC and 5GS NAS can only be carried in **GSMTAPv3** (NR_RRC `0x0503`, NAS_5GS
 * `0x0504`). The byte layout here is matched exactly to SCAT (`fgsect/scat`), the established
 * Qualcomm-DIAG→Wireshark tool this project already references, including SCAT's quirk that the v3
 * header-length *word* is `ceil(bytes/4)` with no padding appended. Dissecting GSMTAPv3 currently
 * needs SCAT's Wireshark plugin (native support is in progress) — the same plugin a SCAT user
 * already has. The NR RRC and NAS-5GS **dissectors** themselves are stock Wireshark; only the
 * GSMTAP transport is the new part.
 *
 * Each message is framed exactly as SCAT frames it: Ethernet / IPv4 / UDP(13337→4729) / GSMTAPv3,
 * written into a standard microsecond PCAP (LINKTYPE_ETHERNET). Checksums are left uncomputed
 * (`0xffff`), which Wireshark accepts.
 */
object GsmtapPcapExport {

    // GSMTAPv3 payload types (SCAT gsmtapv3_types).
    private const val V3_TYPE_NR_RRC = 0x0503
    private const val V3_TYPE_NAS_5GS = 0x0504

    // GSMTAPv3 NR RRC channel sub-types (SCAT gsmtapv3_nr_rrc_types).
    private const val NR_BCCH_BCH = 0x0001
    private const val NR_BCCH_DL_SCH = 0x0002
    private const val NR_DL_DCCH = 0x0004

    // GSMTAPv3 metadata TLV tags (SCAT gsmtapv3_metadata_tags).
    private const val TLV_CHANNEL_NUMBER = 0x0002
    private const val TLV_END_OF_METADATA = 0xfffe

    private const val UDP_SRC = 13337
    private const val UDP_DST = 4729 // control plane
    private const val LINKTYPE_ETHERNET = 1

    /** One frame to serialise: the raw signaling bytes plus the GSMTAP type/sub-type they need. */
    private data class Frame(val gsmtapType: Int, val subType: Int, val arfcn: Long, val message: ByteArray, val atElapsedMs: Long)

    /**
     * Builds a complete `.pcap` byte stream from [events]. Timestamps are anchored at [baseWallMs]
     * and spaced by each event's capture time, so Wireshark shows the real order and gaps. Events
     * carrying neither a decodable RRC nor NAS body are skipped.
     */
    fun build(events: List<ModemNrStream.SignalingEvent>, baseWallMs: Long = System.currentTimeMillis()): ByteArray {
        val frames = events.mapNotNull { frameFor(it) }
        val t0 = frames.minOfOrNull { it.atElapsedMs } ?: 0L

        val out = ByteArrayOutputStream(1 shl 14)
        writePcapGlobalHeader(out)
        for (f in frames) {
            val wallMs = baseWallMs + (f.atElapsedMs - t0)
            val packet = ethIpUdp(gsmtapV3(f.gsmtapType, f.subType, f.arfcn, f.message))
            writeRecord(out, wallMs, packet)
        }
        return out.toByteArray()
    }

    /** How many signaling frames [events] will contribute — for a UI count before exporting. */
    fun frameCount(events: List<ModemNrStream.SignalingEvent>): Int = events.count { frameFor(it) != null }

    private fun frameFor(e: ModemNrStream.SignalingEvent): Frame? {
        e.rrc?.let { rrc ->
            val bytes = rrc.rawUperHex?.let { hexToBytes(it) } ?: return@let
            val sub = when (rrc.pduType) {
                RrcOtaParser.PduType.MIB -> NR_BCCH_BCH
                RrcOtaParser.PduType.SIB1 -> NR_BCCH_DL_SCH
                RrcOtaParser.PduType.DL_DCCH, RrcOtaParser.PduType.RRC_RECONFIG -> NR_DL_DCCH
                null -> NR_DL_DCCH // unknown PDU type: default to DL-DCCH rather than drop the frame
            }
            return Frame(V3_TYPE_NR_RRC, sub, rrc.nrArfcn ?: 0L, bytes, e.atElapsedMs)
        }
        e.nas?.let { nas ->
            val bytes = nas.rawNasHex?.let { hexToBytes(it) } ?: return@let
            return Frame(V3_TYPE_NAS_5GS, 0, 0L, bytes, e.atElapsedMs)
        }
        return null
    }

    // ---- GSMTAPv3 header (matched to SCAT create_gsmtap_header version 3) --------------------

    private fun gsmtapV3(type: Int, subType: Int, arfcn: Long, message: ByteArray): ByteArray {
        // Base (8) + CHANNEL_NUMBER TLV (8) + END_OF_METADATA (2) = 18 header bytes.
        val headerLen = 18
        val lenWords = (headerLen + 3) / 4 // ceil(headerLen/4); SCAT appends no padding.
        val b = ByteArrayOutputStream(headerLen + message.size)
        b.write(3); b.write(0)                 // version, reserved
        be16(b, lenWords)                       // header length, in 32-bit words
        be16(b, type)
        be16(b, subType)
        be16(b, TLV_CHANNEL_NUMBER); be16(b, 4); be32(b, arfcn)
        be16(b, TLV_END_OF_METADATA)
        b.write(message)
        return b.toByteArray()
    }

    // ---- Ethernet / IPv4 / UDP framing (matched to SCAT pcapwriter) -------------------------

    private var ipId = 0

    private fun ethIpUdp(gsmtap: ByteArray): ByteArray {
        val b = ByteArrayOutputStream(42 + gsmtap.size)
        // Ethernet: zero MACs, EtherType IPv4.
        repeat(12) { b.write(0) }
        be16(b, 0x0800)
        // IPv4.
        b.write(0x45); b.write(0x00)
        be16(b, gsmtap.size + 28)               // total length = IP(20)+UDP(8)+gsmtap
        be16(b, ipId and 0xFFFF); ipId++
        be16(b, 0x4000)                         // flags=DF, fragment offset 0
        b.write(0x40); b.write(0x11)            // TTL 64, protocol UDP
        be16(b, 0xFFFF)                         // header checksum: not computed
        be32(b, 0x7F000001)                     // src 127.0.0.1
        be32(b, 0x7F000001)                     // dst 127.0.0.1
        // UDP.
        be16(b, UDP_SRC); be16(b, UDP_DST)
        be16(b, gsmtap.size + 8)                // UDP length
        be16(b, 0xFFFF)                         // UDP checksum: not computed
        b.write(gsmtap)
        return b.toByteArray()
    }

    // ---- PCAP container (little-endian) -----------------------------------------------------

    private fun writePcapGlobalHeader(out: ByteArrayOutputStream) {
        le32(out, 0xA1B2C3D4.toInt())  // magic (microsecond)
        le16(out, 2); le16(out, 4)     // version 2.4
        le32(out, 0); le32(out, 0)     // thiszone, sigfigs
        le32(out, 0xFFFF)              // snaplen
        le32(out, LINKTYPE_ETHERNET)
    }

    private fun writeRecord(out: ByteArrayOutputStream, wallMs: Long, packet: ByteArray) {
        le32(out, (wallMs / 1000).toInt())
        le32(out, ((wallMs % 1000) * 1000).toInt())
        le32(out, packet.size)
        le32(out, packet.size)
        out.write(packet)
    }

    // ---- primitives -------------------------------------------------------------------------

    private fun be16(o: ByteArrayOutputStream, v: Int) { o.write((v ushr 8) and 0xFF); o.write(v and 0xFF) }
    private fun be32(o: ByteArrayOutputStream, v: Long) {
        o.write(((v ushr 24) and 0xFF).toInt()); o.write(((v ushr 16) and 0xFF).toInt())
        o.write(((v ushr 8) and 0xFF).toInt()); o.write((v and 0xFF).toInt())
    }
    private fun be32(o: ByteArrayOutputStream, v: Int) = be32(o, v.toLong() and 0xFFFFFFFFL)
    private fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v ushr 8) and 0xFF) }
    private fun le32(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v ushr 8) and 0xFF); o.write((v ushr 16) and 0xFF); o.write((v ushr 24) and 0xFF)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val h = hex.trim()
        if (h.isEmpty() || h.length % 2 != 0) return null
        val out = ByteArray(h.length / 2)
        for (i in out.indices) {
            out[i] = (h.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null).toByte()
        }
        return out
    }
}
