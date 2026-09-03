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
 * **Nothing here was measured.** Every value is something a person typed after being told it. It
 * is carried in its own type, rendered separately, and always labelled with where it came from and
 * when — so a remembered value can never be mistaken on a page for a reading off the air. That is
 * why [source] is not nullable.
 */
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
    val tddPeriodicityMs: String?,
    val dlSlots: Int?,
    val dlSymbols: Int?,
    val ulSlots: Int?,
    val ulSymbols: Int?,
    val ssbPeriodicityMs: Int?,
    val ssbPositionsInBurst: String?,
    val scsKhz: Int?,

    /** Where this came from. Required — a remembered value without a provenance is a rumour. */
    val source: String,
    val recordedAtUtcMillis: Long,
    val note: String?,
) {
    val isSiteOverride: Boolean get() = !siteName.isNullOrBlank()

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
