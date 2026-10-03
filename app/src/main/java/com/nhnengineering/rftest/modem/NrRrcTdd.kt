package com.nhnengineering.rftest.modem

import com.nhnengineering.rftest.cellular.BandMapping
import com.nhnengineering.rftest.profile.Sib1Parser
import org.json.JSONObject

/**
 * Bridges the native NR RRC decoder ([NrRrcDecoder], `libnrrrc.so`) to the existing, well-tested
 * [Sib1Parser] derivation logic, and surfaces the extra RF/optimization fields the native decoder
 * pulls that have no home on a [Sib1Parser.Result].
 *
 * The native side emits the TDD config plus cell-identity / frequency / selection fields as JSON of
 * raw ASN.1 enum/integer values. The TDD subset is rendered back into the canonical 3GPP token text
 * [Sib1Parser] already parses (so an auto-captured config and a hand-pasted decode travel the same
 * path and the parser's test suite covers both); the rest becomes human-readable [Decoded.cellInfo]
 * lines for the capture display, the export, and anyone diagnosing a cell.
 */
object NrRrcTdd {

    /** The TDD profile (for saving) plus the extra RF fields (for display/export). */
    data class Decoded(
        val profile: Sib1Parser.Result,
        val cellInfo: List<Pair<String, String>>,
    )

    // 3GPP TS 38.331 enum index -> spelling / value.
    private val SCS = mapOf(0 to "kHz15", 1 to "kHz30", 2 to "kHz60", 3 to "kHz120", 4 to "kHz240")
    private val SCS_KHZ = mapOf(0 to 15, 1 to 30, 2 to 60, 3 to 120, 4 to 240)
    private val PERIOD = mapOf(
        0 to "ms0p5", 1 to "ms0p625", 2 to "ms1", 3 to "ms1p25",
        4 to "ms2", 5 to "ms2p5", 6 to "ms5", 7 to "ms10",
    )
    private val PERIOD_V1530 = mapOf(0 to "ms3", 1 to "ms4", 2 to "ms6")
    private val SSB_PERIOD = mapOf(0 to "ms5", 1 to "ms10", 2 to "ms20", 3 to "ms40", 4 to "ms80", 5 to "ms160")
    private val T310_MS = mapOf(0 to 0, 1 to 50, 2 to 100, 3 to 200, 4 to 500, 5 to 1000, 6 to 2000, 7 to 4000, 8 to 6000)
    private val N310 = mapOf(0 to "n1", 1 to "n2", 2 to "n3", 3 to "n4", 4 to "n6", 5 to "n8", 6 to "n10", 7 to "n20")
    private val T311_MS = mapOf(0 to 1000, 1 to 3000, 2 to 5000, 3 to 10000, 4 to 15000, 5 to 20000, 6 to 30000)

    /**
     * Decode one captured NR RRC message body (as handed up at [RrcOtaParser]'s offset 23). Null
     * when the native lib is unavailable, the decode failed, or the cell carries no TDD config (FDD).
     *
     * [pduKind]: 0 = RRCReconfiguration (NSA), 1 = BCCH-DL-SCH-Message / SIB1 (SA).
     */
    fun decode(pduKind: Int, uper: ByteArray): Decoded? =
        NrRrcDecoder.decode(pduKind, uper)?.let { decodeJsonForTest(it) }

    /**
     * The best TDD config decodable from a signaling capture, most-recent event first, with the band
     * filled from the measured NR-ARFCN. Shared by the capture dialog and the export so both agree.
     */
    fun bestFromEvents(events: List<ModemNrStream.SignalingEvent>): Decoded? {
        for (e in events.asReversed()) {
            val rrc = e.rrc ?: continue
            val hex = rrc.rawUperHex ?: continue
            val pduKind = when (rrc.pduType) {
                RrcOtaParser.PduType.SIB1 -> 1
                RrcOtaParser.PduType.RRC_RECONFIG, RrcOtaParser.PduType.DL_DCCH -> 0
                else -> continue
            }
            val bytes = hexToBytes(hex) ?: continue
            val d = decode(pduKind, bytes) ?: continue
            val band = d.profile.band ?: rrc.nrArfcn?.let { BandMapping.nrBandLabel(it.toInt()) }
            return if (band != null) d.copy(profile = d.profile.copy(band = band)) else d
        }
        return null
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val s = hex.trim()
        if (s.isEmpty() || s.length % 2 != 0) return null
        return ByteArray(s.length / 2) {
            (s.substring(it * 2, it * 2 + 2).toIntOrNull(16) ?: return null).toByte()
        }
    }

    /** Visible for testing: build a [Decoded] from a native JSON string (no native call). */
    internal fun decodeJsonForTest(json: String): Decoded? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!o.optBoolean("tddPresent", false)) return null
        val text = renderCanonical(o) ?: return null
        val result = runCatching { Sib1Parser.parse(text) }.getOrNull() ?: return null
        return Decoded(result, cellInfoLines(o))
    }

    /** Visible for testing: the [Sib1Parser.Result] alone from a native JSON string. */
    fun fromJson(json: String): Sib1Parser.Result? = decodeJsonForTest(json)?.profile

    private fun renderCanonical(o: JSONObject): String? {
        val sb = StringBuilder()
        sb.appendLine("systemInformationBlockType1")
        sb.appendLine("servingCellConfigCommon")
        sb.appendLine("tdd-UL-DL-ConfigurationCommon")
        SCS[o.optInt("refSCS", -1)]?.let {
            sb.appendLine("referenceSubcarrierSpacing $it")
            sb.appendLine("subcarrierSpacing $it")
        }
        SSB_PERIOD[o.optInt("ssbPeriodicity", -1)]?.let { sb.appendLine("ssb-periodicityServingCell $it") }
        // Fields Sib1Parser already understands, so they flow into the saved profile.
        o.optInt("pci", -1).takeIf { it >= 0 }?.let { sb.appendLine("physCellId $it") }
        o.optInt("carrierBandwidthRb", -1).takeIf { it > 0 }?.let { sb.appendLine("carrierBandwidth $it") }
        o.optString("mcc", "").ifEmpty { null }?.let { sb.appendLine("mcc $it") }
        o.optString("mnc", "").ifEmpty { null }?.let { sb.appendLine("mnc $it") }
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

    /** The RF/optimization fields that don't fit a TddProfile, as display/export rows. */
    private fun cellInfoLines(o: JSONObject): List<Pair<String, String>> = buildList {
        o.optInt("ssPBCHBlockPower", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            ?.let { add("SS-PBCH block power" to "$it dBm") }
        val rb = o.optInt("carrierBandwidthRb", -1)
        if (rb > 0) add("Channel bandwidth" to bandwidthLabel(rb, SCS_KHZ[o.optInt("carrierSCS", -1)]))
        o.optInt("ssbArfcn", -1).takeIf { it > 0 }?.let { add("SSB ARFCN" to "$it") }
        o.optInt("pointAArfcn", -1).takeIf { it > 0 }?.let { add("Point A ARFCN" to "$it") }
        if (o.has("qRxLevMin")) add("q-RxLevMin" to "${o.optInt("qRxLevMin") * 2} dBm")
        o.optLong("tac", -1).takeIf { it >= 0 }?.let { add("TAC" to "$it") }
        o.optString("nci", "").ifEmpty { null }?.let { add("NCI" to "0x$it") }
        if (o.has("cellReservedForOperatorUse")) {
            add("Reserved for operator use" to if (o.optInt("cellReservedForOperatorUse") == 0) "yes" else "no")
        }
        if (o.optBoolean("imsEmergencySupport", false)) add("IMS emergency" to "supported")
        if (o.has("t310")) {
            val t310 = T310_MS[o.optInt("t310", -1)]?.let { "${it} ms" } ?: "?"
            val n310 = N310[o.optInt("n310", -1)] ?: "?"
            val t311 = T311_MS[o.optInt("t311", -1)]?.let { "${it} ms" } ?: "?"
            add("RLF timers" to "t310 $t310 / n310 $n310 / t311 $t311")
        }
    }

    /** RB count at a given SCS to the nominal channel bandwidth (rounded to the 5 MHz grid). */
    private fun bandwidthLabel(rb: Int, scsKhz: Int?): String {
        if (scsKhz == null) return "$rb RB"
        val mhz = Math.round(rb * 12.0 * scsKhz / 1000.0 / 5.0).toInt() * 5
        return "$rb RB (≈$mhz MHz)"
    }

    private fun ssbWidthBits(kind: String): Int = when (kind) {
        "short" -> 4
        "long" -> 64
        else -> 8
    }

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
