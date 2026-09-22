package com.nhnengineering.rftest.modem

/**
 * Decodes NR ML1 Measurement Database Update log packets (log code `0xB97F`).
 *
 * ## Why this exists
 *
 * This is the only surface on this handset that reports **5G NR neighbour cells**. Android's
 * `CellInfo` returns the serving cell alone. QMI NAS returns no cell list at all on NR SA — that
 * was checked directly, and `GET_CELL_LOCATION_INFO` comes back with four TLVs and no neighbours.
 * The modem has been measuring them the whole time and logs them here several times a second.
 *
 * Since the sites this app is built to survey are 5G SA, this is the measurement that decides
 * whether a report may say anything at all about sector overlap.
 *
 * ## Layout, taken from real captures
 *
 * Every packet observed was 184 bytes carrying two cells. The structure was derived by locating
 * known values — the serving PCI and ARFCN this handset was camped on — not by assuming a format:
 *
 * ```
 * offset  0   u16  total length, header included
 *         2   u16  log code, 0xB97F
 *         4   u64  timestamp
 *        32   u32  NR ARFCN            (0x00060bda = 396250, the camped channel)
 *        36   u16  cell count
 *        38   u16  serving PCI         (929, the camped cell)
 *        64   [ ]  cell records, 60 bytes each:
 *                    +0  u16  PCI
 *                    +2  u16  beam / SSB index (provisional, not yet confirmed)
 *                    +4  u32
 *                    +8  s32  RSRP in 128ths of a dB
 *                   +12  s32  RSRQ in 128ths of a dB
 * ```
 *
 * ## The integrity rule
 *
 * The count at offset 36 and the packet length are two independent statements about how many
 * cells there are. They are required to agree exactly: `length - 64 == count * 60`. A record
 * array read one field out yields a list of cells that were never measured and looks entirely
 * plausible, which is the failure this project has already shipped once. When the two disagree
 * the packet is discarded whole and said so, never part-read.
 */
object NrMl1Parser {

    /** NR ML1 Measurement Database Update. */
    const val LOG_CODE = 0xB97F

    private const val HEADER_BYTES = 12
    private const val RECORDS_AT = 64
    private const val RECORD_BYTES = 60

    private const val OFF_ARFCN = 32
    private const val OFF_COUNT = 36
    private const val OFF_SERVING_PCI = 38

    /**
     * The bottom of the SS-RSRP reporting range, in the modem's 128ths of a dB.
     *
     * 3GPP TS 38.133 reports SS-RSRP over -156 dBm to -31 dBm, so -156 is not a level, it is the
     * floor: "at or below anything this scale can express". The modem lists cells it has detected
     * but has no usable measurement for at exactly this value.
     *
     * Observed on 2026-09-22: a session recorded a neighbour at -156 dBm on every sample.
     * Published as a measurement that is nonsense -- below the thermal noise floor -- and worse
     * than nonsense in a report, because a cell that cannot be measured would be counted as a
     * neighbour and would move an overlap figure.
     *
     * The comparison is on the rounded dB rather than the raw value, because the raw value is
     * not a single constant. A capture of 46 multi-cell packets put every real reading between
     * -87 and -104 dB and the unmeasured one at raw -19964, which is -155.969 -- four counts off
     * an exact -156 x 128. An equality test on the raw floor missed it; rounding to the
     * reporting floor catches it and anything else that lands there.
     */
    private const val RSRP_FLOOR_DBM = -156

    /**
     * Signal values arrive in 128ths of a dB. The app's models carry whole dB, so [rsrpDbm]
     * rounds half away from zero; [rsrp128] keeps the original for anything that wants it.
     */
    data class Cell(
        val pci: Int,
        val beam: Int,
        val arfcn: Int,
        val rsrp128: Int,
        val rsrq128: Int,
        val serving: Boolean,
    ) {
        val rsrpDbm: Int get() = round128(rsrp128)
        val rsrqDb: Int get() = round128(rsrq128)
    }

    data class Result(
        val looksValid: Boolean = false,
        val arfcn: Int? = null,
        val servingPci: Int? = null,
        val serving: Cell? = null,
        val neighbours: List<Cell> = emptyList(),
        /**
         * Cells the modem listed but had no usable level for.
         *
         * Not an error and not a neighbour. Reported so that "one cell in the list" and "one
         * cell in the list plus two it could see but not measure" are distinguishable.
         */
        val unmeasuredCells: Int = 0,
        val notes: List<String> = emptyList(),
    ) {
        val allCells: List<Cell> get() = listOfNotNull(serving) + neighbours
    }

    /** 128ths of a dB to whole dB, rounded half away from zero so repeats cannot bias a mean. */
    private fun round128(v: Int): Int =
        if (v >= 0) (v + 64) / 128 else -((-v + 64) / 128)

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun s32(b: ByteArray, o: Int): Int =
        u8(b, o) or (u8(b, o + 1) shl 8) or (u8(b, o + 2) shl 16) or (u8(b, o + 3) shl 24)

    /** Parses one log packet exactly as the DCI stream delivered it, header included. */
    fun parse(packet: ByteArray): Result {
        val notes = mutableListOf<String>()
        if (packet.size < HEADER_BYTES) {
            return Result(notes = listOf("Packet is ${packet.size} bytes, too short for a log header."))
        }
        val code = u16(packet, 2)
        if (code != LOG_CODE) {
            return Result(notes = listOf("Log code 0x%04x is not the NR measurement log.".format(code)))
        }
        val declared = u16(packet, 0)
        if (declared != packet.size) {
            notes += "Packet declares $declared bytes but ${packet.size} arrived."
            return Result(notes = notes)
        }
        if (packet.size < RECORDS_AT) {
            return Result(notes = listOf("Packet is ${packet.size} bytes, too short to hold any cell."))
        }

        val arfcn = s32(packet, OFF_ARFCN)
        val count = u16(packet, OFF_COUNT)
        val servingPci = u16(packet, OFF_SERVING_PCI)

        // The two independent statements about how many cells are present.
        val bytesForCells = packet.size - RECORDS_AT
        if (count * RECORD_BYTES != bytesForCells) {
            notes += "The packet says $count cell(s), which needs ${count * RECORD_BYTES} bytes, " +
                "but $bytesForCells are present. Discarded rather than part-read."
            return Result(notes = notes)
        }

        var serving: Cell? = null
        var unmeasured = 0
        val neighbours = mutableListOf<Cell>()
        for (i in 0 until count) {
            val r = RECORDS_AT + i * RECORD_BYTES
            val cell = Cell(
                pci = u16(packet, r),
                beam = u16(packet, r + 2),
                arfcn = arfcn,
                rsrp128 = s32(packet, r + 8),
                rsrq128 = s32(packet, r + 12),
                serving = false,
            )
            // Detected but not measurable. Kept out of the cell lists entirely rather than
            // carried with a floor value, because everything downstream treats a cell in the
            // list as one that was measured.
            if (cell.rsrpDbm <= RSRP_FLOOR_DBM) {
                unmeasured++
                continue
            }
            if (cell.pci == servingPci && serving == null) serving = cell.copy(serving = true)
            else neighbours += cell
        }
        if (unmeasured > 0) {
            notes += "$unmeasured cell(s) were listed with no usable level and are not reported."
        }
        if (serving == null && count > 0) {
            notes += "The serving PCI $servingPci was not among the $count measured cell(s)."
        }

        return Result(
            looksValid = true,
            arfcn = arfcn,
            servingPci = servingPci,
            serving = serving,
            neighbours = neighbours,
            unmeasuredCells = unmeasured,
            notes = notes,
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
