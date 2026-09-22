package com.nhnengineering.rftest.modem

/**
 * Decodes a `QMI_NAS_GET_CELL_LOCATION_INFO` response into serving and neighbour cells.
 *
 * ## Why this exists
 *
 * Android does not pass NR neighbours to an application on this handset, and on LTE it passes
 * fewer than the modem has. The modem itself has them. Reading them needs a QRTR socket, which
 * needs root and a native helper — but only for *transport*. Everything from the response bytes
 * onwards is ordinary parsing, so it lives here in Kotlin where it can be tested against real
 * captured responses instead of against a handset that happens to be in the right state.
 *
 * ## The integrity rule
 *
 * Every structure here is a fixed header followed by a counted array. That makes it trivially
 * possible to emit confident nonsense from a wrong offset: a count byte read one place out yields
 * a plausible list of cells that were never measured, and nothing downstream could tell.
 *
 * So each TLV is required to consume **exactly** its declared length. That is the property that
 * validated this decode in the first place — the real captures consumed 29 of 29 and 34 of 34
 * bytes — and it is cheap to keep checking forever. A TLV whose arithmetic does not close is
 * discarded whole and reported in [Result.notes]; it never contributes half a cell list.
 *
 * ## Units
 *
 * The modem reports RSRP, RSRQ and RSSI as tenths of a dB, so −925 means −92.5 dBm. The app's
 * models carry whole dB, so values are rounded half away from zero on the way out. The tenths are
 * kept on [Cell] for anything that wants the original precision.
 */
object QmiCellParser {

    /** Message id this parser understands. */
    const val MSG_GET_CELL_LOCATION_INFO = 0x0043

    private const val TLV_RESULT = 0x02
    private const val TLV_LTE_INTRA = 0x13
    private const val TLV_LTE_INTER = 0x14

    /** One cell as the modem reported it, signal values in tenths of a dB. */
    data class Cell(
        val pci: Int,
        val earfcn: Int,
        val rsrpTenths: Int,
        val rsrqTenths: Int,
        val rssiTenths: Int,
        val serving: Boolean,
    ) {
        val rsrpDbm: Int get() = roundTenths(rsrpTenths)
        val rsrqDb: Int get() = roundTenths(rsrqTenths)
        val rssiDbm: Int get() = roundTenths(rssiTenths)
    }

    data class Result(
        /** False when the payload was not a well-formed response to this message. */
        val looksValid: Boolean = false,
        /** The QMI result TLV. Null when absent, which is itself worth knowing. */
        val success: Boolean? = null,
        val serving: Cell? = null,
        val neighbours: List<Cell> = emptyList(),
        val plmn: String? = null,
        val trackingAreaCode: Int? = null,
        /**
         * Whether the modem said the UE was idle.
         *
         * Not cosmetic: a connected UE reports the cells it is actually measuring, an idle one
         * reports what it is monitoring for reselection, and the two lists differ. A survey that
         * does not record which it was cannot explain why two passes over the same ground saw
         * different neighbour counts.
         */
        val ueInIdle: Boolean? = null,
        /**
         * Whether the response carried LTE cell information at all.
         *
         * False on 5G NR SA, where the modem answers successfully with NR serving-cell TLVs this
         * parser does not yet decode. That distinction is load-bearing: an empty neighbour list
         * with this false means "not decoded", and reporting it as "none present" would state a
         * measured absence of neighbours for a site nobody measured -- the same defect, in a new
         * place, as the 0.0% overlap this project already shipped once.
         */
        val lteInfoPresent: Boolean = false,
        val notes: List<String> = emptyList(),
    ) {
        /** Every cell reported, serving first. */
        val allCells: List<Cell> get() = listOfNotNull(serving) + neighbours
    }

    private fun roundTenths(tenths: Int): Int =
        if (tenths >= 0) (tenths + 5) / 10 else -((-tenths + 5) / 10)

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun s16(b: ByteArray, o: Int): Int {
        val v = u16(b, o)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong()) or (u8(b, o + 1).toLong() shl 8) or
            (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)

    /**
     * PLMN from the three nibble-swapped BCD bytes 38.413 uses.
     *
     * `13 00 62` is 310-260. Returns null rather than a guess when a nibble is the 0xF filler in
     * a position that would make the answer ambiguous.
     */
    private fun plmnOf(b: ByteArray, o: Int): String? {
        val mcc1 = u8(b, o) and 0x0F
        val mcc2 = u8(b, o) shr 4
        val mcc3 = u8(b, o + 1) and 0x0F
        val mnc3 = u8(b, o + 1) shr 4
        val mnc1 = u8(b, o + 2) and 0x0F
        val mnc2 = u8(b, o + 2) shr 4
        if (mcc1 > 9 || mcc2 > 9 || mcc3 > 9) return null
        val mcc = "$mcc1$mcc2$mcc3"
        val mnc = if (mnc3 == 0xF) "$mnc1$mnc2" else "$mnc1$mnc2$mnc3"
        if (mnc.any { !it.isDigit() }) return null
        return "$mcc-$mnc"
    }

    /**
     * Parses a whole QMI response, header included.
     *
     * [payload] is exactly what came off the socket: the 7-byte QMI header followed by TLVs.
     */
    fun parse(payload: ByteArray): Result {
        val notes = mutableListOf<String>()
        if (payload.size < 7) {
            return Result(notes = listOf("Response is ${payload.size} bytes, shorter than a QMI header."))
        }
        val type = u8(payload, 0)
        val msgId = u16(payload, 3)
        val declared = u16(payload, 5)
        if (msgId != MSG_GET_CELL_LOCATION_INFO) {
            return Result(notes = listOf("Response is for message 0x%04x, not cell location info.".format(msgId)))
        }
        if (type != 0x02) {
            notes += "QMI message type 0x%02x is not a response.".format(type)
        }
        if (declared != payload.size - 7) {
            notes += "Header declares $declared TLV bytes but ${payload.size - 7} are present."
        }

        var success: Boolean? = null
        var serving: Cell? = null
        val neighbours = mutableListOf<Cell>()
        var plmn: String? = null
        var tac: Int? = null
        var idle: Boolean? = null
        var sawLte = false

        var off = 7
        while (off + 3 <= payload.size) {
            val id = u8(payload, off)
            val len = u16(payload, off + 1)
            off += 3
            if (off + len > payload.size) {
                notes += "TLV 0x%02x claims $len bytes but only ${payload.size - off} remain.".format(id)
                break
            }
            val body = payload.copyOfRange(off, off + len)
            off += len

            when (id) {
                TLV_RESULT -> if (len >= 4) success = u16(body, 0) == 0
                TLV_LTE_INTRA -> {
                    sawLte = true
                    val r = parseIntraFrequency(body, notes)
                    if (r != null) {
                        serving = r.serving
                        neighbours += r.others
                        plmn = r.plmn
                        tac = r.tac
                        idle = r.idle
                    }
                }
                TLV_LTE_INTER -> {
                    sawLte = true
                    val r = parseInterFrequency(body, notes)
                    if (r != null) {
                        neighbours += r.cells
                        if (idle == null) idle = r.idle
                    }
                }
            }
        }

        return Result(
            looksValid = true,
            success = success,
            serving = serving,
            neighbours = neighbours,
            plmn = plmn,
            trackingAreaCode = tac,
            ueInIdle = idle,
            lteInfoPresent = sawLte,
            notes = notes,
        )
    }

    private class Intra(
        val serving: Cell?,
        val others: List<Cell>,
        val plmn: String?,
        val tac: Int?,
        val idle: Boolean,
    )

    private class Inter(val cells: List<Cell>, val idle: Boolean)

    /** 10 bytes: pci, rsrq, rsrp, rssi, cell-selection rx level. */
    private const val CELL_BYTES = 10

    private fun cellAt(b: ByteArray, o: Int, earfcn: Int, serving: Boolean) = Cell(
        pci = u16(b, o),
        earfcn = earfcn,
        rsrqTenths = s16(b, o + 2),
        rsrpTenths = s16(b, o + 4),
        rssiTenths = s16(b, o + 6),
        serving = serving,
    )

    /**
     * TLV 0x13, the serving frequency: 19 fixed bytes then a counted cell array.
     *
     * The serving cell appears in that array as well as in the header's `serving_cell_id`, so it
     * is matched by PCI and marked rather than listed twice.
     */
    private fun parseIntraFrequency(b: ByteArray, notes: MutableList<String>): Intra? {
        val fixed = 19
        if (b.size < fixed) {
            notes += "LTE serving-frequency TLV is ${b.size} bytes, too short to read."
            return null
        }
        val idle = u8(b, 0) != 0
        val plmn = plmnOf(b, 1)
        val tac = u16(b, 4)
        val earfcn = u16(b, 10)
        val servingPci = u16(b, 12)
        val count = u8(b, 18)

        val need = fixed + count * CELL_BYTES
        if (need != b.size) {
            notes += "LTE serving-frequency TLV declares $count cell(s), which needs $need bytes, " +
                "but the TLV is ${b.size}. Discarded rather than part-read."
            return null
        }

        var serving: Cell? = null
        val others = mutableListOf<Cell>()
        for (i in 0 until count) {
            val c = cellAt(b, fixed + i * CELL_BYTES, earfcn, serving = false)
            if (c.pci == servingPci && serving == null) serving = c.copy(serving = true)
            else others += c
        }
        if (serving == null && count > 0) {
            notes += "The serving PCI $servingPci was not in its own frequency's cell list."
        }
        return Intra(serving, others, plmn, tac, idle)
    }

    /**
     * TLV 0x14, other frequencies: a counted array of frequencies, each with its own counted
     * cell array. These are the cells Android never surfaces.
     */
    private fun parseInterFrequency(b: ByteArray, notes: MutableList<String>): Inter? {
        if (b.size < 2) {
            notes += "LTE other-frequency TLV is ${b.size} bytes, too short to read."
            return null
        }
        val idle = u8(b, 0) != 0
        val freqCount = u8(b, 1)
        var o = 2
        val cells = mutableListOf<Cell>()
        for (f in 0 until freqCount) {
            if (o + 6 > b.size) {
                notes += "LTE other-frequency TLV ends inside frequency ${f + 1} of $freqCount. " +
                    "Discarded rather than part-read."
                return null
            }
            val earfcn = u16(b, o)
            val count = u8(b, o + 5)
            o += 6
            if (o + count * CELL_BYTES > b.size) {
                notes += "Frequency $earfcn declares $count cell(s) that do not fit the TLV. " +
                    "Discarded rather than part-read."
                return null
            }
            for (i in 0 until count) {
                cells += cellAt(b, o + i * CELL_BYTES, earfcn, serving = false)
            }
            o += count * CELL_BYTES
        }
        if (o != b.size) {
            notes += "LTE other-frequency TLV has ${b.size - o} trailing byte(s) after " +
                "$freqCount frequency block(s). Discarded rather than part-read."
            return null
        }
        return Inter(cells, idle)
    }

    /** Parses the helper's `OK <hex>` line, or null when it did not produce one. */
    fun parseHelperLine(line: String): Result? {
        val t = line.trim()
        if (!t.startsWith("OK ")) return null
        val hex = t.removePrefix("OK ").trim()
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val v = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            bytes[i] = v.toByte()
        }
        return parse(bytes)
    }
}
