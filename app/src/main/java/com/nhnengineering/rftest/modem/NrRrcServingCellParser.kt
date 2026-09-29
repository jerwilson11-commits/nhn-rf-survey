package com.nhnengineering.rftest.modem

/**
 * Decodes DIAG log code `0xB823`, `LOG_5GNR_RRC_SERVING_CELL_INFO` -- the modem's own RRC-layer
 * view of the serving cell (PCI, both ARFCNs, bandwidth, TAC, band and, from a later payload
 * version on, the full NR CGI), a second and independent source for exactly the fields
 * [com.nhnengineering.rftest.cellular.CellularCollector] already reports from the public
 * `CellIdentityNr` surface. Useful the same way this project already cross-checks against Field
 * Test Mode (`*3001#12345#*`): a second reading of the same fact, not a new one.
 *
 * ## Where the field layout comes from
 *
 * Read from SCAT's own open-source parser (fgsect/scat,
 * `parsers/qualcomm/diagnrlogparser.py`, `parse_nr_rrc_scell_info`) -- the same source this
 * project already used for the NAS-5GS OTA payload offsets -- not reverse-engineered from a
 * capture the way [NrMl1Parser] was. SCAT's own offsets are relative to its `pkt_body`, which
 * starts *after* SCAT's own outer envelope; reconciled here against the 12-byte header
 * [NrMl1Parser] already confirmed empirically for this same DCI stream (2-byte total length,
 * 2-byte log code, 8-byte timestamp) -- a standard DIAG_LOG_F shape shared across log codes, not
 * something specific to 0xB97F. So SCAT's `pkt_body[N]` is this parser's `packet[12 + N]`.
 *
 * ## Not yet seen on this handset -- documented as a finding, not left implicit
 *
 * Tested live 2026-09-29 on the OnePlus 9: the DCI subscription to `0xB823` is accepted without
 * error (`READY 1 code(s)`), exactly like `0xB97F`'s always has been, but zero packets arrived
 * across three separate conditions -- idle (15s), immediately after a forced re-attach (Airplane
 * Mode off/on), and during sustained data traffic (45s) -- while `0xB97F` kept streaming normally
 * the entire time on the same subscription, in the same process, confirming this is not a
 * plumbing or masking problem. The likeliest explanation is that this exact log code is not
 * implemented on this baseband (`MPSS.HI.4.3.c4-00234`, SM8350/lahaina) -- this project's own
 * docs already record one prior wrong guess for this same log item (0xB825 was tried first, and
 * is actually RRC Configuration Info), so a second miss for this exact firmware is plausible
 * rather than surprising.
 *
 * The parser and the [ModemNrStream] plumbing are still worth keeping: correctly built, unit
 * tested, and inert rather than broken when nothing decodes (`CellularSample.modemRrcServingCell`
 * simply stays null, the same as any other "root read that hasn't answered yet" field already
 * handled throughout this app) -- and there is no reason to expect *every* rooted Qualcomm phone
 * this app is sold to shares this exact baseband's gap.
 */
object NrRrcServingCellParser {

    /** LOG_5GNR_RRC_SERVING_CELL_INFO. */
    const val LOG_CODE = 0xB823

    private const val HEADER_BYTES = 12
    private const val VER_AT = HEADER_BYTES // pkt_body[0:4] in SCAT's own addressing

    data class Result(
        val looksValid: Boolean = false,
        val payloadVersion: String? = null,
        val pci: Int? = null,
        val nrCgi: Long? = null,
        val dlNrArfcn: Long? = null,
        val ulNrArfcn: Long? = null,
        /** Raw modem units -- not yet confirmed against a public bandwidth-index table. */
        val dlBandwidthRaw: Int? = null,
        val ulBandwidthRaw: Int? = null,
        val cellId: Long? = null,
        val mcc: Int? = null,
        val mncDigit: Int? = null,
        val mnc: Int? = null,
        val allowedAccess: Int? = null,
        val tac: Long? = null,
        val band: Int? = null,
        val notes: List<String> = emptyList(),
    )

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
    private fun u64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or u8(b, o + i).toLong()
        return v
    }

    /** Parses one log packet exactly as the DCI stream delivered it, header included. */
    fun parse(packet: ByteArray): Result {
        if (packet.size < HEADER_BYTES) {
            return Result(notes = listOf("Packet is ${packet.size} bytes, too short for a log header."))
        }
        val code = u16(packet, 2)
        if (code != LOG_CODE) {
            return Result(notes = listOf("Log code 0x%04x is not the NR RRC serving cell log.".format(code)))
        }
        val declared = u16(packet, 0)
        if (declared != packet.size) {
            return Result(notes = listOf("Packet declares $declared bytes but ${packet.size} arrived."))
        }
        if (packet.size < VER_AT + 4) {
            return Result(notes = listOf("Packet is ${packet.size} bytes, too short to hold a version header."))
        }

        val verMajor = u16(packet, VER_AT)
        val verMinor = u16(packet, VER_AT + 2)
        val version = "$verMajor.$verMinor"

        // SCAT's three known shapes, offsets translated into this packet's own addressing (see
        // the class doc for why pkt_body[N] here is packet[12 + N]).
        return when {
            verMajor == 0 -> parseBase(packet, base = HEADER_BYTES + 4, version)
            verMajor == 3 && verMinor == 0 -> parseExtended(packet, base = HEADER_BYTES + 4, version)
            verMajor == 3 && verMinor in 2..3 -> parseExtended(packet, base = HEADER_BYTES + 7, version)
            else -> Result(
                payloadVersion = version,
                notes = listOf("Unrecognised payload version $version -- not decoded."),
            )
        }
    }

    /** No NR CGI: pci, dl/ul ARFCN, dl/ul bandwidth, cell ID, MCC, MNC digit, MNC, allowed
     *  access, TAC, band -- 34 bytes, little-endian. */
    private fun parseBase(packet: ByteArray, base: Int, version: String): Result {
        val end = base + 34
        if (packet.size < end) {
            return Result(payloadVersion = version, notes = listOf("Packet too short for the v$version body."))
        }
        return Result(
            looksValid = true,
            payloadVersion = version,
            pci = u16(packet, base),
            dlNrArfcn = u32(packet, base + 2),
            ulNrArfcn = u32(packet, base + 6),
            dlBandwidthRaw = u16(packet, base + 10),
            ulBandwidthRaw = u16(packet, base + 12),
            cellId = u64(packet, base + 14),
            mcc = u16(packet, base + 22),
            mncDigit = u8(packet, base + 24),
            mnc = u16(packet, base + 25),
            allowedAccess = u8(packet, base + 27),
            tac = u32(packet, base + 28),
            band = u16(packet, base + 32),
        )
    }

    /** Same as [parseBase] with an 8-byte NR CGI inserted right after PCI -- 42 bytes. */
    private fun parseExtended(packet: ByteArray, base: Int, version: String): Result {
        val end = base + 42
        if (packet.size < end) {
            return Result(payloadVersion = version, notes = listOf("Packet too short for the v$version body."))
        }
        return Result(
            looksValid = true,
            payloadVersion = version,
            pci = u16(packet, base),
            nrCgi = u64(packet, base + 2),
            dlNrArfcn = u32(packet, base + 10),
            ulNrArfcn = u32(packet, base + 14),
            dlBandwidthRaw = u16(packet, base + 18),
            ulBandwidthRaw = u16(packet, base + 20),
            cellId = u64(packet, base + 22),
            mcc = u16(packet, base + 30),
            mncDigit = u8(packet, base + 32),
            mnc = u16(packet, base + 33),
            allowedAccess = u8(packet, base + 35),
            tac = u32(packet, base + 36),
            band = u16(packet, base + 40),
        )
    }

    /** Parses a `LOG <hex>` line from the helper, or null when it is not one. */
    fun parseLogLine(line: String): Result? {
        val t = line.trim()
        if (!t.startsWith("LOG ")) return null
        val hex = t.removePrefix("LOG ").trim()
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val v = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            bytes[i] = v.toByte()
        }
        return parse(bytes)
    }
}
