package com.nhnengineering.rftest.profile

/**
 * Reads a pasted SIB1 decode and pulls out the fields a [TddProfile] carries.
 *
 * ## Why this exists
 *
 * This application cannot reach SIB1 -- it lives below Android's RIL boundary and always will.
 * But an engineer standing on site with a rooted handset and a diagnostic tool has the decoded
 * tree on screen, and retyping nine fields off it is both slow and a place to make a transcription
 * error nobody can catch later. Pasting the decode in is faster and, more importantly, checkable:
 * what came out of the parse can be shown back against what went in.
 *
 * So this closes the loop the [Provenance.MEASURED] path opened. We do not decode SIB1. We accept
 * the decode.
 *
 * ## Why it scans for tokens rather than parsing a tree
 *
 * There is no one format to parse. Diagnostic tools render ASN.1 differently, the same tool renders
 * differently between versions, and what a person actually copies off a screen is often a fragment
 * rather than a whole message. Requiring a well-formed tree would mean the feature works against
 * one tool's current build and silently fails against everything else.
 *
 * What is stable is 3GPP TS 38.331 itself: the field identifiers (freqBandIndicatorNR,
 * nrofDownlinkSlots) and the enumerated spellings (kHz30, ms2p5) come from the spec, so every tool
 * prints the same names whatever it does with the whitespace around them. This scans for those
 * names and accepts a colon, an equals sign or nothing between name and value.
 *
 * ## The rule about repeated fields
 *
 * Several of these identifiers legitimately appear more than once in one message: the
 * scs-SpecificCarrierList holds an entry per numerology, and freqBandIndicatorNR appears under both
 * the uplink and downlink config. **Taking the first occurrence is the wrong answer**, and it is
 * the kind of wrong answer that reads perfectly until the one deployment where the values differ.
 *
 * So every field is collected in full. When the occurrences agree the value is used; when they
 * disagree nothing is filled and the disagreement is reported in [Result.conflicts] for a human to
 * settle. A conflict is information, not a failure.
 */
object Sib1Parser {

    /** A field this parser knows how to look for, named as the profile screen names it. */
    enum class Field(val label: String) {
        BAND("Band"),
        SCS("Subcarrier spacing"),
        TDD_PERIODICITY("TDD periodicity"),
        DL_SLOTS("DL slots"),
        DL_SYMBOLS("DL symbols"),
        UL_SLOTS("UL slots"),
        UL_SYMBOLS("UL symbols"),
        SSB_PERIODICITY("SSB periodicity"),
        SSB_POSITIONS("SSB positions in burst"),
        PLMN("PLMN"),
    }

    data class Result(
        /** False when the text carries none of SIB1's characteristic identifiers. */
        val looksLikeSib1: Boolean = false,
        /** True when tdd-UL-DL-ConfigurationCommon is present, which only a TDD carrier has. */
        val isTdd: Boolean = false,

        val band: String? = null,
        val scsKhz: Int? = null,
        val carrierBandwidthRb: Int? = null,
        val tddPeriodicityMs: String? = null,
        val dlSlots: Int? = null,
        val dlSymbols: Int? = null,
        val ulSlots: Int? = null,
        val ulSymbols: Int? = null,

        /** True when the configuration carries a second pattern. */
        val hasPattern2: Boolean = false,
        val pattern2PeriodicityMs: String? = null,
        val p2DlSlots: Int? = null,
        val p2DlSymbols: Int? = null,
        val p2UlSlots: Int? = null,
        val p2UlSymbols: Int? = null,
        /**
         * The period the slot string actually repeats on.
         *
         * pattern1 + pattern2 where there are two, pattern1 alone where there is one. Kept
         * separate from [tddPeriodicityMs] because storing a two-pattern slot string next to
         * pattern1's periodicity describes part of a cycle and reads as the whole of it.
         */
        val effectivePeriodicityMs: String? = null,

        /** Rendered from the slot counts when they can be reconciled with the period. */
        val derivedPattern: String? = null,
        val ssbPeriodicityMs: Int? = null,
        val ssbPositionsInBurst: String? = null,
        val mcc: String? = null,
        val mnc: String? = null,
        val pci: Int? = null,

        val found: List<Field> = emptyList(),
        val missing: List<Field> = emptyList(),
        /** Things a person needs to know about this parse, in the order they matter. */
        val notes: List<String> = emptyList(),
        /** Identifiers that appeared more than once with different values. Nothing was filled. */
        val conflicts: List<String> = emptyList(),
    ) {
        val anythingFound: Boolean get() = found.isNotEmpty()
    }

    private val SIB1_MARKERS = listOf(
        "systemInformationBlockType1", "servingCellConfigCommon", "cellSelectionInfo",
        "plmn-IdentityInfoList", "plmn-IdentityList", "freqBandIndicatorNR",
        "tdd-UL-DL-ConfigurationCommon", "ssb-PositionsInBurst", "cellAccessRelatedInfo",
    )

    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result()

        val looksRight = SIB1_MARKERS.any { text.contains(it, ignoreCase = true) }
        if (!looksRight) {
            return Result(
                looksLikeSib1 = false,
                notes = listOf(
                    "This does not look like a SIB1 decode -- none of the identifiers SIB1 always " +
                        "carries are in it. Paste the decoded systemInformationBlockType1 tree, " +
                        "not a summary line or a screenshot caption.",
                ),
            )
        }

        val found = mutableListOf<Field>()
        val notes = mutableListOf<String>()
        val conflicts = mutableListOf<String>()

        fun <T> sole(name: String, values: List<T>): T? = when {
            values.isEmpty() -> null
            values.distinct().size == 1 -> values.first()
            else -> {
                conflicts += "$name appears ${values.size} times with different values " +
                    "(${values.distinct().joinToString(", ")}). Nothing was filled in for it."
                null
            }
        }

        // --- band ---------------------------------------------------------------------------
        val bandNum = sole("freqBandIndicatorNR", allInts(text, "freqBandIndicatorNR"))
        val band = bandNum?.let { "n$it" }
        if (band != null) found += Field.BAND

        // --- subcarrier spacing -------------------------------------------------------------
        // A word boundary will not match inside referenceSubcarrierSpacing, so these stay distinct.
        val scsKhz = sole(
            "subcarrierSpacing",
            allTokens(text, "subcarrierSpacing").mapNotNull(::scsToKhz),
        )
        if (scsKhz != null) found += Field.SCS
        val refScsKhz = sole(
            "referenceSubcarrierSpacing",
            allTokens(text, "referenceSubcarrierSpacing").mapNotNull(::scsToKhz),
        )

        val carrierBw = sole("carrierBandwidth", allInts(text, "carrierBandwidth"))

        // --- TDD ----------------------------------------------------------------------------
        val isTdd = text.contains("tdd-UL-DL-ConfigurationCommon", ignoreCase = true)
        // A two-pattern configuration repeats every pattern1 + pattern2, and the same identifiers
        // appear once per pattern. Scanning the whole message for them would find
        // nrofDownlinkSlots twice with different values, call it a conflict and fill in nothing --
        // on precisely the deployments where the slot pattern is least guessable. So each pattern
        // is read from its own slice of the text.
        val (scope1, scope2) = patternScopes(text)

        fun readPattern(scope: String, suffix: String) = RawPattern(
            periodMs = sole(
                "dl-UL-TransmissionPeriodicity$suffix",
                allTokens(scope, "dl-UL-TransmissionPeriodicity").mapNotNull(::msToNumber),
            ),
            dlSlots = sole("nrofDownlinkSlots$suffix", allInts(scope, "nrofDownlinkSlots")),
            dlSymbols = sole("nrofDownlinkSymbols$suffix", allInts(scope, "nrofDownlinkSymbols")),
            ulSlots = sole("nrofUplinkSlots$suffix", allInts(scope, "nrofUplinkSlots")),
            ulSymbols = sole("nrofUplinkSymbols$suffix", allInts(scope, "nrofUplinkSymbols")),
        )

        val p1 = readPattern(scope1, "")
        val p2 = scope2?.let { readPattern(it, " (pattern2)") }

        val periodMs = p1.periodMs
        val dlSlots = p1.dlSlots
        val dlSymbols = p1.dlSymbols
        val ulSlots = p1.ulSlots
        val ulSymbols = p1.ulSymbols
        if (periodMs != null) found += Field.TDD_PERIODICITY
        if (dlSlots != null) found += Field.DL_SLOTS
        if (dlSymbols != null) found += Field.DL_SYMBOLS
        if (ulSlots != null) found += Field.UL_SLOTS
        if (ulSymbols != null) found += Field.UL_SYMBOLS

        val effScs = refScsKhz ?: scsKhz
        val slots1 = derivePattern(p1, effScs, "pattern1", notes)
        val slots2 = p2?.let { derivePattern(it, effScs, "pattern2", notes) }

        val combinedPeriod = if (p2 == null) {
            periodMs
        } else {
            val a = periodMs
            val b = p2.periodMs
            if (a != null && b != null) a + b else null
        }

        if (p2 != null && combinedPeriod != null) {
            // TS 38.213 s11.1: the two periodicities together have to divide 20 ms. A combination
            // that does not is not a deployment this tool has never seen -- it is a misread paste.
            val repeats = 20.0 / combinedPeriod
            if (Math.abs(repeats - Math.round(repeats)) > 1e-6) {
                notes += "pattern1 and pattern2 come to ${trimNumber(combinedPeriod)} ms together, " +
                    "which does not divide 20 ms. No real configuration does that, so check the " +
                    "paste rather than trusting either pattern."
            }
        }

        val pattern: String?
        if (slots1 != null && slots2 != null) {
            pattern = slots1 + slots2
            val a = periodMs
            val b = p2?.periodMs
            if (a != null && b != null) {
                notes += "Two patterns: ${trimNumber(a)} ms then ${trimNumber(b)} ms, repeating " +
                    "every ${trimNumber(a + b)} ms. The slot string covers the whole cycle."
            }
        } else if (p2 != null) {
            pattern = null
            notes += "pattern2 is present but could not be derived, so no slot string was built. " +
                "Recording pattern1 on its own would describe one part of the cycle in a field " +
                "that reads as the whole of it."
        } else {
            pattern = slots1
        }

        // --- SSB ----------------------------------------------------------------------------
        val ssbPeriod = sole(
            "ssb-periodicityServingCell",
            allTokens(text, "ssb-periodicityServingCell").mapNotNull(::msToNumber),
        )?.let { if (it % 1.0 == 0.0) it.toInt() else null }
        if (ssbPeriod != null) found += Field.SSB_PERIODICITY

        val ssbPositions = ssbPositionsOf(text)
        if (ssbPositions != null) found += Field.SSB_POSITIONS

        // --- identity -------------------------------------------------------------------------
        val mcc = digitsGroup(text, "mcc")
        val mnc = digitsGroup(text, "mnc")
        if (mcc != null || mnc != null) found += Field.PLMN
        val pci = sole("physCellId", allInts(text, "physCellId"))

        val missing = Field.entries.filter { it !in found }

        if (!isTdd) {
            notes += "No tdd-UL-DL-ConfigurationCommon in this paste. On an FDD carrier -- n2, n5, " +
                "n25, n66 -- that is correct and there is no slot pattern to record. On a TDD " +
                "carrier it means the paste is incomplete."
        }

        return Result(
            looksLikeSib1 = true,
            isTdd = isTdd,
            band = band,
            scsKhz = scsKhz,
            carrierBandwidthRb = carrierBw,
            tddPeriodicityMs = periodMs?.let(::trimNumber),
            dlSlots = dlSlots, dlSymbols = dlSymbols,
            ulSlots = ulSlots, ulSymbols = ulSymbols,
            hasPattern2 = p2 != null,
            pattern2PeriodicityMs = p2?.periodMs?.let(::trimNumber),
            p2DlSlots = p2?.dlSlots, p2DlSymbols = p2?.dlSymbols,
            p2UlSlots = p2?.ulSlots, p2UlSymbols = p2?.ulSymbols,
            effectivePeriodicityMs = combinedPeriod?.let(::trimNumber),
            derivedPattern = pattern,
            ssbPeriodicityMs = ssbPeriod,
            ssbPositionsInBurst = ssbPositions,
            mcc = mcc, mnc = mnc, pci = pci,
            found = found, missing = missing,
            notes = notes, conflicts = conflicts,
        )
    }

    /** One TDD-UL-DL-Pattern as scanned out of the text, before it is reconciled. */
    private data class RawPattern(
        val periodMs: Double?,
        val dlSlots: Int?, val dlSymbols: Int?,
        val ulSlots: Int?, val ulSymbols: Int?,
    )

    /**
     * Splits the configuration into one slice of text per TDD pattern.
     *
     * `TDD-UL-DL-ConfigCommon` holds `pattern1` and an optional `pattern2`, each a
     * `TDD-UL-DL-Pattern` with the same five field names. The names carry no index, so the only
     * thing separating one pattern's `nrofDownlinkSlots` from the other's is where it sits
     * relative to the `pattern2` marker.
     *
     * Returns the whole text as a single scope whenever there is no second pattern to separate,
     * which keeps single-pattern parsing exactly as it was -- including for a pasted fragment
     * that never mentions `pattern1` at all.
     */
    private fun patternScopes(text: String): Pair<String, String?> {
        val second = Regex("""\bpattern2\b""", RegexOption.IGNORE_CASE).find(text)
            ?: return text to null
        val first = Regex("""\bpattern1\b""", RegexOption.IGNORE_CASE).find(text)
        val start1 = first?.range?.first ?: 0
        val start2 = second.range.first
        // pattern2 before pattern1 is not a shape 38.331 produces; rather than guess at what the
        // paste means, fall back to reading it as one pattern.
        if (start2 <= start1) return text to null
        return text.substring(start1, start2) to text.substring(start2)
    }

    /**
     * Renders the classic slot string for one pattern.
     *
     * NR lays a period out as full DL slots, then one special slot carrying DL and UL symbols,
     * then full UL slots, with anything unassigned left flexible in between. So the string is
     * derivable -- but only if the counts actually fit the period, and if they do not that is
     * worth saying rather than rendering a pattern that looks authoritative and is wrong.
     */
    private fun derivePattern(
        p: RawPattern,
        scsKhz: Int?,
        label: String,
        notes: MutableList<String>,
    ): String? {
        val periodMs = p.periodMs ?: return null
        val dlSlots = p.dlSlots ?: return null
        val ulSlots = p.ulSlots ?: return null
        if (scsKhz == null) return null

        val slotsExact = periodMs * (scsKhz / 15.0)
        val slotsPerPeriod = Math.round(slotsExact).toInt()
        if (Math.abs(slotsExact - slotsPerPeriod) > 1e-6 || slotsPerPeriod <= 0) {
            notes += "$label: a ${trimNumber(periodMs)} ms period at $scsKhz kHz does not come " +
                "to a whole number of slots, so no pattern was derived."
            return null
        }

        val hasSpecial = (p.dlSymbols ?: 0) > 0 || (p.ulSymbols ?: 0) > 0
        val used = dlSlots + ulSlots + if (hasSpecial) 1 else 0
        if (used > slotsPerPeriod) {
            notes += "$label: the slot counts add up to $used slots but a " +
                "${trimNumber(periodMs)} ms period at $scsKhz kHz holds $slotsPerPeriod. No " +
                "pattern was derived -- check the paste rather than trusting these numbers."
            return null
        }

        val flexible = slotsPerPeriod - used
        if (flexible > 0) {
            notes += "$label: $flexible slot${if (flexible == 1) "" else "s"} in the period " +
                "${if (flexible == 1) "is" else "are"} flexible, shown as F."
        }
        return "D".repeat(dlSlots) +
            (if (hasSpecial) "S" else "") +
            "F".repeat(flexible) +
            "U".repeat(ulSlots)
    }

    // --- primitives ---------------------------------------------------------------------------

    /** Every integer that follows [name], in order. Accepts `name 3`, `name: 3`, `name = 3`. */
    private fun allInts(text: String, name: String): List<Int> =
        Regex("""\b${Regex.escape(name)}\b\s*[:=]?\s*(-?\d+)""", RegexOption.IGNORE_CASE)
            .findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()

    /** Every bare token that follows [name] -- an enumerated value such as kHz30 or ms2p5. */
    private fun allTokens(text: String, name: String): List<String> =
        Regex(
            """\b${Regex.escape(name)}\b\s*[:=]?\s*([A-Za-z][A-Za-z0-9_.]*)""",
            RegexOption.IGNORE_CASE,
        ).findAll(text).map { it.groupValues[1] }.toList()

    /** kHz30, or a bare 30, to kHz. */
    private fun scsToKhz(token: String): Int? {
        val t = token.trim()
        Regex("""^kHz(\d+)$""", RegexOption.IGNORE_CASE).find(t)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return t.toIntOrNull()?.takeIf { it in setOf(15, 30, 60, 120, 240) }
    }

    /** ms2p5 to 2.5, ms10 to 10.0 -- 38.331 writes the decimal point as `p`. */
    private fun msToNumber(token: String): Double? {
        val m = Regex("""^ms(\d+)(?:p(\d+))?$""", RegexOption.IGNORE_CASE).find(token.trim())
            ?: return null
        val whole = m.groupValues[1]
        val frac = m.groupValues[2]
        return if (frac.isEmpty()) whole.toDoubleOrNull() else "$whole.$frac".toDoubleOrNull()
    }

    private fun trimNumber(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

    /**
     * The bitmap under ssb-PositionsInBurst, whichever CHOICE variant carries it.
     *
     * Looked for in a window after the identifier rather than anywhere in the message, because a
     * bit string elsewhere in SIB1 -- a tracking area code, a cell identity -- would otherwise be
     * picked up as an SSB bitmap, which is a silent wrong answer rather than a missing one.
     */
    private fun ssbPositionsOf(text: String): String? {
        val at = text.indexOf("ssb-PositionsInBurst", ignoreCase = true)
        if (at < 0) return null
        val window = text.substring(at, minOf(text.length, at + 260))
        val bits = Regex("""'([01][01\s]*)'\s*B""").find(window)
            ?.groupValues?.get(1)?.replace(Regex("""\s"""), "")
        if (bits != null) return bits
        val hex = Regex("""'([0-9A-Fa-f\s]+)'\s*H""").find(window)
            ?.groupValues?.get(1)?.replace(Regex("""\s"""), "")
        return hex?.let { "0x$it" }
    }

    /** `mcc { 3, 1, 0 }` to "310"; also accepts `mcc 310`. */
    private fun digitsGroup(text: String, name: String): String? {
        Regex("""\b${Regex.escape(name)}\b\s*[:=]?\s*\{([^}]*)}""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val digits = Regex("""\d""").findAll(m.groupValues[1]).joinToString("") { it.value }
                if (digits.isNotEmpty()) return digits
            }
        return Regex("""\b${Regex.escape(name)}\b\s*[:=]?\s*(\d{2,3})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
    }
}
