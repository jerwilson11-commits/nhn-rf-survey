package com.nhnengineering.rftest.modem

/**
 * Decodes DIAG log code `0xB821` -- 5G NR RRC OTA -- as far as the fixed envelope, not the ASN.1
 * body it carries.
 *
 * ## Why this stops at the envelope
 *
 * The payload after the envelope is genuine ASN.1 UPER (a `BCCH-BCH`/`BCCH-DL-SCH`/`DL-DCCH`
 * message per 3GPP TS 38.331), which is what the 2026-09-26/27 investigation
 * (`docs/modem-diag-access.md`, "0xB821 (NR RRC OTA) packet layout") decoded externally with
 * pycrate rather than in this app -- writing an RRC-NR ASN.1 UPER decoder in Kotlin is a
 * substantial undertaking with no existing groundwork in this project, and is explicitly not
 * attempted here. What this parser gives instead: the envelope fields that ARE fixed-position and
 * already validated against a real capture (PCI, NR-ARFCN, PDU type), plus the raw UPER bytes,
 * exposed as hex for export and the same offline pycrate workflow that already worked once.
 *
 * ## Where the layout comes from
 *
 * Validated 2026-09-26, not guessed: after the standard 12-byte DIAG_LOG_F header, PCI is a `u16`
 * at payload offset 7, NR-ARFCN a `u32` at offset 9, PDU type a single byte at offset 16, and the
 * ASN.1 UPER body starts at offset 23. An earlier attempt starting at offset 24 was misaligned and
 * produced false "barred" results, withdrawn once cross-checked against a cell known to decode
 * correctly (n66, PCI 929) -- see the doc entry for the full validation. Offsets here are
 * `packet[HEADER_BYTES + N]` for payload offset `N`, matching [NrRrcServingCellParser]'s own
 * addressing convention.
 */
object RrcOtaParser {

    const val LOG_CODE = 0xB821

    private const val HEADER_BYTES = 12
    private const val PCI_AT = HEADER_BYTES + 7
    private const val ARFCN_AT = HEADER_BYTES + 9
    private const val PDU_TYPE_AT = HEADER_BYTES + 16
    private const val UPER_AT = HEADER_BYTES + 23

    enum class PduType(val code: Int, val label: String) {
        MIB(1, "MIB"),
        SIB1(2, "SIB1"),
        DL_DCCH(4, "DL-DCCH"),
        // Some modem log versions emit the NR RRCReconfiguration (the NSA SCG-add) under PDU type 9
        // rather than 4 -- observed on the OnePlus 9 / X60 for the n41 secondary-cell-group add. The
        // type byte's meaning is version-specific, so this is only a hint: a decoder confirms by
        // actually decoding the body (see NrRrcDecoder / tools/asn1), never by trusting the code.
        RRC_RECONFIG(9, "RRCReconfiguration"),
        ;

        companion object {
            fun of(code: Int): PduType? = entries.firstOrNull { it.code == code }
        }
    }

    data class Result(
        val looksValid: Boolean = false,
        val pci: Int? = null,
        val nrArfcn: Long? = null,
        val pduType: PduType? = null,
        /** The ASN.1 UPER body from payload offset 23, for export and offline decode. */
        val rawUperHex: String? = null,
        val notes: List<String> = emptyList(),
    )

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
    private fun hex(b: ByteArray, from: Int) =
        b.copyOfRange(from, b.size).joinToString("") { "%02x".format(it) }

    fun parse(packet: ByteArray): Result {
        if (packet.size < HEADER_BYTES) {
            return Result(notes = listOf("Packet is ${packet.size} bytes, too short for a log header."))
        }
        val code = u16(packet, 2)
        if (code != LOG_CODE) {
            return Result(notes = listOf("Log code 0x%04x is not NR RRC OTA.".format(code)))
        }
        val declared = u16(packet, 0)
        if (declared != packet.size) {
            return Result(notes = listOf("Packet declares $declared bytes but ${packet.size} arrived."))
        }
        if (packet.size <= PDU_TYPE_AT) {
            return Result(looksValid = true, notes = listOf("Packet too short for the fixed envelope."))
        }

        val pci = u16(packet, PCI_AT)
        val arfcn = u32(packet, ARFCN_AT)
        val pduTypeCode = u8(packet, PDU_TYPE_AT)
        val pduType = PduType.of(pduTypeCode)
        val uperHex = if (packet.size > UPER_AT) hex(packet, UPER_AT) else null

        return Result(
            looksValid = true,
            pci = pci,
            nrArfcn = arfcn,
            pduType = pduType,
            rawUperHex = uperHex,
            notes = if (pduType == null) {
                listOf("PDU type $pduTypeCode is not one this app recognises.")
            } else {
                emptyList()
            },
        )
    }

    /** Parses a `LOG <hex>` line from the helper, or null when it is not one. */
    fun parseLogLine(line: String): Result? {
        val t = line.trim()
        if (!t.startsWith("LOG ")) return null
        val hexStr = t.removePrefix("LOG ").trim()
        if (hexStr.isEmpty() || hexStr.length % 2 != 0) return null
        val bytes = ByteArray(hexStr.length / 2)
        for (i in bytes.indices) {
            val v = hexStr.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            bytes[i] = v.toByte()
        }
        return parse(bytes)
    }
}
