package com.nhnengineering.rftest.modem

import com.nhnengineering.rftest.profile.Sib1Parser
import org.json.JSONObject

/**
 * Bridges the native NR RRC decoder ([NrRrcDecoder], `libnrrrc.so`) to the existing, well-tested
 * [Sib1Parser] derivation logic.
 *
 * The native side emits the TDD configuration as JSON of raw ASN.1 enum indices. Rather than
 * duplicating the slot-string / combined-period / conflict logic, this renders that JSON back into
 * the canonical 3GPP token text [Sib1Parser] already parses (`referenceSubcarrierSpacing kHz30`,
 * `dl-UL-TransmissionPeriodicity-v1530 ms3`, `nrofDownlinkSlots 3`, a `pattern2` marker, …), then
 * calls [Sib1Parser.parse] unchanged. So an auto-captured config and a hand-pasted decode travel the
 * same path and are rendered identically, and [Sib1Parser]'s test suite keeps covering both.
 *
 * Note: band / PLMN are not in the native JSON (they live in frequencyInfoDL / cellAccessRelatedInfo,
 * not the TDD subtree). The capture layer supplies the band from the measured NR-ARFCN instead.
 */
object NrRrcTdd {

    // 3GPP TS 38.331 enum index -> spelling, matching what Sib1Parser scans for.
    private val SCS = mapOf(0 to "kHz15", 1 to "kHz30", 2 to "kHz60", 3 to "kHz120", 4 to "kHz240")
    private val PERIOD = mapOf(
        0 to "ms0p5", 1 to "ms0p625", 2 to "ms1", 3 to "ms1p25",
        4 to "ms2", 5 to "ms2p5", 6 to "ms5", 7 to "ms10",
    )
    private val PERIOD_V1530 = mapOf(0 to "ms3", 1 to "ms4", 2 to "ms6")
    private val SSB_PERIOD = mapOf(0 to "ms5", 1 to "ms10", 2 to "ms20", 3 to "ms40", 4 to "ms80", 5 to "ms160")

    /**
     * Decode one captured NR RRC message body (as handed up at [RrcOtaParser]'s offset 23) to a
     * [Sib1Parser.Result], or null when the native lib is unavailable, the decode failed, or the
     * cell carries no TDD config (an FDD cell).
     *
     * [pduKind]: 0 = RRCReconfiguration (NSA), 1 = BCCH-DL-SCH-Message / SIB1 (SA).
     */
    fun fromCapture(pduKind: Int, uper: ByteArray): Sib1Parser.Result? =
        NrRrcDecoder.decode(pduKind, uper)?.let { fromJson(it) }

    /** Visible for testing: map the native JSON directly. */
    fun fromJson(json: String): Sib1Parser.Result? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!o.optBoolean("tddPresent", false)) return null
        val text = renderCanonical(o) ?: return null
        return Sib1Parser.parse(text)
    }

    private fun renderCanonical(o: JSONObject): String? {
        val sb = StringBuilder()
        // Markers so Sib1Parser recognises it and flags it TDD.
        sb.appendLine("systemInformationBlockType1")
        sb.appendLine("servingCellConfigCommon")
        sb.appendLine("tdd-UL-DL-ConfigurationCommon")
        SCS[o.optInt("refSCS", -1)]?.let {
            sb.appendLine("referenceSubcarrierSpacing $it") // drives the TDD slot derivation
            sb.appendLine("subcarrierSpacing $it")          // populates Result.scsKhz for the profile
        }
        SSB_PERIOD[o.optInt("ssbPeriodicity", -1)]?.let { sb.appendLine("ssb-periodicityServingCell $it") }
        val ssbHex = o.optString("ssbHex", "").ifEmpty { null }
        val ssbKind = o.optString("ssbKind", "").ifEmpty { null }
        if (ssbHex != null && ssbKind != null) {
            hexToBits(ssbHex, ssbWidthBits(ssbKind))?.let {
                sb.appendLine("ssb-PositionsInBurst $ssbKind '$it'B")
            }
        }
        val p1 = o.optJSONObject("pattern1") ?: return null
        sb.appendLine("pattern1")
        appendPattern(sb, p1)
        o.optJSONObject("pattern2")?.let {
            sb.appendLine("pattern2")
            appendPattern(sb, it)
        }
        return sb.toString()
    }

    private fun appendPattern(sb: StringBuilder, p: JSONObject) {
        PERIOD[p.optInt("periodicity", -1)]?.let { sb.appendLine("dl-UL-TransmissionPeriodicity $it") }
        if (p.has("periodicityV1530")) {
            PERIOD_V1530[p.optInt("periodicityV1530", -1)]?.let {
                sb.appendLine("dl-UL-TransmissionPeriodicity-v1530 $it")
            }
        }
        sb.appendLine("nrofDownlinkSlots ${p.optInt("dlSlots")}")
        sb.appendLine("nrofDownlinkSymbols ${p.optInt("dlSymbols")}")
        sb.appendLine("nrofUplinkSlots ${p.optInt("ulSlots")}")
        sb.appendLine("nrofUplinkSymbols ${p.optInt("ulSymbols")}")
    }

    private fun ssbWidthBits(kind: String): Int = when (kind) {
        "short" -> 4
        "long" -> 64
        else -> 8 // "medium" and SIB "inOneGroup"
    }

    /** Hex bytes (MSB-first) to a [width]-bit binary string, e.g. "20",8 -> "00100000". */
    private fun hexToBits(hex: String, width: Int): String? {
        val clean = hex.trim().removePrefix("0x")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val bits = buildString {
            for (i in clean.indices step 2) {
                val b = clean.substring(i, i + 2).toIntOrNull(16) ?: return null
                append(b.toString(2).padStart(8, '0'))
            }
        }
        return if (bits.length >= width) bits.substring(0, width) else bits.padEnd(width, '0')
    }
}
