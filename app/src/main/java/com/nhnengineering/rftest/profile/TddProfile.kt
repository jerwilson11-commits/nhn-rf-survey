package com.nhnengineering.rftest.profile

/**
 * A record of TDD and SSB configuration that the handset cannot measure.
 *
 * ## Why a library rather than a measurement
 *
 * SSB periodicity, the slot pattern and CSI-RS periodicity live in SIB1 and the PHY layer, and an
 * ordinary app cannot read them. But they are not arbitrary per site: they are **set by the RAN
 * vendor's defaults**, which a carrier adopts across a market and rarely varies. Determine the
 * configuration once for Ericsson, once for Nokia, once for Samsung on a given carrier and band,
 * and it covers most of that carrier's sites.
 *
 * So the answer that cannot be measured can be *remembered*. Learn it once — from the operator,
 * from a scanner, from a rooted protocol tool — and every subsequent site is a lookup.
 *
 * ## The exceptions, which is where the survey work is
 *
 * Dense venues are the exception. Stadium deployments are known to vary SSB position between
 * neighbouring sectors for capacity and to reduce interference, so a site override is a
 * first-class part of this rather than an afterthought: the deviations cluster in exactly the
 * buildings that get surveyed.
 *
 * ## The rule this must never break
 *
 * **A reader must always be able to tell which kind of value they are looking at.** Some of these
 * were typed after somebody said them; some were read off the air and pasted out of a SIB1 decode.
 * Both are carried in this one type, so [provenance] is what separates them, and [source] is not
 * nullable -- a configuration without a provenance is a rumour, and one claiming to be measured
 * without saying what measured it is worse than a rumour.
 */
/**
 * Where a profile's radio configuration came from.
 *
 * ## Why this exists
 *
 * This library was built on the premise that the TDD configuration cannot be measured by a
 * handset -- the slot pattern, SSB periodicity and subcarrier spacing live in SIB1, which no
 * ordinary application can reach across Android's RIL boundary. That is still true of this app and
 * always will be.
 *
 * It is not true of the engineer holding the phone. On a rooted handset a diagnostic tool reads
 * SIB1 and prints `tdd-UL-DL-ConfigurationCommon`, `ssb-PositionsInBurst` and
 * `subcarrierSpacing` in full, off the air, from the network's own broadcast. Demonstrated on the
 * OnePlus on 2026-09-19.
 *
 * So a profile may now hold either kind of value, and **the difference is the whole point**. A slot
 * pattern somebody was told is hearsay that happens to be usually right. The same pattern read out
 * of SIB1 is evidence. A commissioning document that cannot tell a reader which one it is holding
 * is worth less than one that can, and the default is deliberately the weaker claim.
 */
enum class Provenance(val label: String) {
    /** Supplied by a person -- operator RF team, design document, vendor default. */
    REPORTED("reported"),

    /** Read off the air from the network's own broadcast, typically SIB1 via a diagnostic tool. */
    MEASURED("measured from SIB1"),
}

/**
 * The one active SSB candidate's 0-based index, when [bitmap] is a short- or medium-length
 * `ssb-PositionsInBurst` bitmap ('0'/'1' characters) with exactly one bit set.
 *
 * A DAS commissioning tool that only offers a single "SSB position" field is asking for this --
 * which candidate is actually transmitted, not the raw bitmap. Null for anything that number does
 * not cleanly describe: no bitmap recorded, more than one candidate active (a genuine multi-beam
 * site, which has no single position to report), or a long-form/hex bitmap ([Sib1Parser] renders
 * one as `0x...`), since indexing into that would need to know which half of a 64-bit pattern it
 * came from.
 */
fun ssbPositionOf(bitmap: String?): Int? {
    if (bitmap.isNullOrEmpty() || bitmap.startsWith("0x", ignoreCase = true)) return null
    if (bitmap.any { it != '0' && it != '1' }) return null
    if (bitmap.count { it == '1' } != 1) return null
    return bitmap.indexOf('1')
}

data class TddProfile(
    val id: String,
    /** RAN vendor — the strongest predictor, since these are vendor defaults. */
    val vendor: String,
    /** Operator name as an engineer would say it. */
    val operator: String,
    /** Matched against the serving cell when present, which is more reliable than a name. */
    val mcc: String?,
    val mnc: String?,
    val band: String,
    /** Optional market or region, for the variation that does occur between them. */
    val market: String?,
    /** Non-null makes this a site override, which wins over any general profile. */
    val siteName: String?,

    val tddPattern: String?,
    /**
     * What the whole slot string actually repeats on -- pattern1 alone, or pattern1 + pattern2
     * when there are two. Not either pattern's own duration; see [pattern1PeriodicityMs] and
     * [pattern2PeriodicityMs] for those. Kept separate on purpose: a two-pattern site with this
     * field alone would have no way to say "3 ms then 2 ms" versus "5 ms twice".
     */
    val tddPeriodicityMs: String?,
    /** Pattern1's own duration. Equal to [tddPeriodicityMs] when there is no pattern2. */
    val pattern1PeriodicityMs: String? = null,
    /** Pattern2's own duration, or null when this site has only one pattern. */
    val pattern2PeriodicityMs: String? = null,
    val dlSlots: Int?,
    val dlSymbols: Int?,
    val ulSlots: Int?,
    val ulSymbols: Int?,
    /** Pattern2's downlink slots, or null when this site has only one pattern. */
    val p2DlSlots: Int? = null,
    /** Pattern2's special-slot downlink symbols. */
    val p2DlSymbols: Int? = null,
    val p2UlSlots: Int? = null,
    val p2UlSymbols: Int? = null,
    val ssbPeriodicityMs: Int?,
    val ssbPositionsInBurst: String?,
    val scsKhz: Int?,

    /** Where this came from. Required — a remembered value without a provenance is a rumour. */
    /**
     * Whether the configuration values above were measured or reported.
     *
     * Covers the radio configuration block only. Vendor, operator and market are metadata and are
     * never measured, so a single flag describes the profile honestly without pretending to
     * per-field provenance the workflow does not produce.
     */
    val provenance: Provenance = Provenance.REPORTED,
    val source: String,
    val recordedAtUtcMillis: Long,
    val note: String?,
) {
    val isSiteOverride: Boolean get() = !siteName.isNullOrBlank()

    /** True when this site broadcasts a second TDD pattern, not just one. */
    val hasPattern2: Boolean
        get() = pattern2PeriodicityMs != null || p2DlSlots != null || p2DlSymbols != null ||
            p2UlSlots != null || p2UlSymbols != null

    /** The one active SSB candidate's 0-based index, or null -- see [ssbPositionOf]. */
    val ssbPosition: Int? get() = ssbPositionOf(ssbPositionsInBurst)

    /** One-line identity for a list. */
    val title: String
        get() = buildString {
            append(operator).append("  ").append(band)
            if (!vendor.isBlank()) append("  ·  ").append(vendor)
            siteName?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
            market?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
        }

    /** True when nothing useful was actually filled in, so the UI can warn rather than save a shell. */
    val isEmpty: Boolean
        get() = listOf(
            tddPattern, tddPeriodicityMs, ssbPositionsInBurst,
        ).all { it.isNullOrBlank() } &&
            listOf(dlSlots, dlSymbols, ulSlots, ulSymbols, ssbPeriodicityMs, scsKhz).all { it == null }
}

/**
 * Chooses the profile that applies to what the handset is currently on.
 *
 * Specificity wins, in a fixed order, because a general vendor default must never override
 * something recorded about this actual building:
 *
 * 1. site override for this site and band
 * 2. operator, band and market
 * 3. operator and band
 *
 * Returns null rather than a near miss. A profile for the wrong band or the wrong operator is not
 * a partial answer, it is a wrong one, and a wrong slot pattern configured into a repeater causes
 * interference rather than an obvious failure.
 */
object ProfileMatcher {

    data class Query(
        val mcc: String?,
        val mnc: String?,
        val operator: String?,
        val band: String?,
        val market: String? = null,
        val siteName: String? = null,
    )

    fun match(profiles: List<TddProfile>, q: Query): TddProfile? {
        val band = q.band?.takeIf { it.isNotBlank() } ?: return null

        // Band is compared leniently at the edges only: the app labels an ambiguous channel
        // "n2/n25", and a profile recorded as "n25" should still match it. Anything less exact
        // than that is refused.
        fun bandMatches(p: TddProfile): Boolean {
            val a = p.band.trim()
            if (a.equals(band, ignoreCase = true)) return true
            return band.split('/').any { it.trim().equals(a, ignoreCase = true) }
        }

        fun operatorMatches(p: TddProfile): Boolean {
            if (!q.mcc.isNullOrBlank() && !p.mcc.isNullOrBlank()) {
                return p.mcc == q.mcc && p.mnc == q.mnc
            }
            val name = q.operator?.trim() ?: return false
            return p.operator.trim().equals(name, ignoreCase = true)
        }

        val candidates = profiles.filter { bandMatches(it) && operatorMatches(it) }
        if (candidates.isEmpty()) return null

        val site = q.siteName?.trim()?.takeIf { it.isNotBlank() }
        if (site != null) {
            candidates.firstOrNull {
                it.isSiteOverride && it.siteName!!.trim().equals(site, ignoreCase = true)
            }?.let { return it }
        }

        val market = q.market?.trim()?.takeIf { it.isNotBlank() }
        if (market != null) {
            candidates.firstOrNull {
                !it.isSiteOverride && it.market?.trim().equals(market, ignoreCase = true)
            }?.let { return it }
        }

        // A site override must not be returned for a different site, so general profiles only.
        return candidates.firstOrNull { !it.isSiteOverride && it.market.isNullOrBlank() }
            ?: candidates.firstOrNull { !it.isSiteOverride }
    }
}
