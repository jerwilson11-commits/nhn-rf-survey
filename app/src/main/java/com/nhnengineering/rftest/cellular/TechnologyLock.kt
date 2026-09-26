package com.nhnengineering.rftest.cellular

/**
 * Holding the radio on one access technology for the length of a measurement.
 *
 * ## What this is for
 *
 * A survey that lets the handset roam across technologies measures the handset's preferences as
 * much as it measures the site. Asking "what does LTE look like here" is only answerable if the
 * phone can be held on LTE while the question is asked.
 *
 * ## Why this is QMI's bit space, not Android's
 *
 * `TelephonyManager.setAllowedNetworkTypesForReason` is accepted by the framework and then
 * silently recomputed back to the handset default by a vendor layer (`OplusNetworkUtils`),
 * confirmed directly on this handset. The mechanism that actually works sits underneath that
 * layer: `QMI_NAS_SET_SYSTEM_SELECTION_PREFERENCE`'s Mode Preference TLV, driven by hand on
 * 2026-09-22 and confirmed to move the radio in under five seconds.
 *
 * That TLV's bit layout is **not** `TelephonyManager`'s `NETWORK_TYPE_BITMASK_*` numbering --
 * they are two unrelated encodings for the same idea. QMI's is 7 bits wide and was reconstructed
 * from what was actually observed: `0x005F` is the handset's baseline allowance, and it matches
 * the modem RAF the framework itself logged (`UMTS|EvDo|1xRTT|LTE|GSM|LTE_CA|NR`) once decomposed
 * bit by bit. Writing an Android bitmask value into this TLV would set the wrong technologies
 * entirely, so this class was rewritten in QMI's numbering rather than translated at a boundary --
 * a translation table covering bits nobody has individually verified is exactly the kind of guess
 * this project keeps finding and removing.
 *
 * ## What it can and cannot do
 *
 * This selects a *technology*, never a band: [Technology.NR_ONLY] forces NR rather than LTE; it
 * cannot force n41 rather than n25. Bands are a separate lever in the same QMI message -- see
 * [BandLock] and [BandLockController].
 *
 * The one indirect lever worth knowing: NSA is not a technology of its own, it is NR anchored on
 * LTE. Removing LTE from the mask therefore removes NSA as a possibility, so a handset that keeps
 * NR service under [Technology.NR_ONLY] is necessarily on standalone NR. See [forcesStandalone].
 * Forcing the opposite -- NSA specifically, never SA -- needs a second lever: the NR5G SA Band
 * Preference TLV, emptied so standalone NR has no band left to camp on. Verified on 2026-09-26
 * and offered as [Technology.NSA_ONLY]; [TechnologyLockController] writes it and restores it.
 *
 * ## Why it cannot add capability
 *
 * The modem's effective allowance is the bitwise AND across every reason it tracks internally.
 * Writing one reason can only ever narrow what the radio may use. Asking for a technology the
 * carrier config already excludes changes nothing, which is why
 * [TechnologyLockController.lock] reports what actually took effect rather than what was
 * requested.
 *
 * ## Why change duration is always "until power cycle"
 *
 * Every write this app makes uses that duration, never "permanent". That choice has a deliberate
 * safety property: a lock this app somehow fails to release clears itself the moment the phone is
 * rebooted -- or, confirmed on 2026-09-22, the moment Airplane Mode is toggled off and back on,
 * which needs no computer and takes seconds.
 *
 * Requires root: the write goes over a QRTR socket, which needs it regardless of the app's own
 * privilege level. [TechnologyLockController.unavailableReason] is how callers find out, and
 * refusal is the normal case on any handset that is not rooted for development.
 */
object TechnologyLock {

    // RAT bits within QMI's Mode Preference TLV (0x11), confirmed by direct observation: 0x005F
    // read back as this handset's baseline, and 0x0010 alone moved the radio to LTE in under
    // five seconds. QMI has one bit per RAT family -- unlike Android's bitmask, there is no
    // separate "carrier aggregation" bit alongside LTE's.
    private const val RAT_CDMA_1X = 0x0001
    private const val RAT_HRPD = 0x0002
    private const val RAT_GSM = 0x0004
    private const val RAT_UMTS = 0x0008
    const val RAT_LTE = 0x0010
    private const val RAT_TD_SCDMA = 0x0020
    const val RAT_NR = 0x0040

    /** Pre-LTE circuit and packet technologies, kept together as one fallback group. */
    private const val LEGACY_ALL = RAT_CDMA_1X or RAT_HRPD or RAT_GSM or RAT_UMTS or RAT_TD_SCDMA

    /**
     * A technology the radio can be held on.
     *
     * [warning] is non-null where the choice can plausibly cost the handset its service, and is
     * meant to be shown before the lock is applied rather than after it has taken effect.
     */
    enum class Technology(
        val label: String,
        val modePref: Int,
        val warning: String? = null,
    ) {
        /**
         * Named for what it does, not for the bit it sets: with no LTE bit present there is no
         * anchor for NSA, so any NR service this handset gets under this mask is necessarily
         * standalone. See [forcesStandalone].
         */
        NR_ONLY(
            "5G SA only",
            RAT_NR,
            "Removes the LTE anchor, so NSA is no longer possible. If this site has no " +
                "standalone NR, the handset will lose data service until the lock is released.",
        ),
        LTE_ONLY(
            "LTE only",
            RAT_LTE,
            "5G will not be used.",
        ),

        /**
         * Both bits stay in the mode preference -- LTE is the anchor, NR the data -- and
         * standalone is removed by emptying the NR5G SA band mask, which is what
         * [excludesStandalone] tells the controller to do. Verified 2026-09-26: the phone left SA
         * n25 for an LTE anchor, and under load the carriers were LTE primary + NR secondary.
         * Idle it looks like plain LTE, since the NR leg exists only while data flows.
         */
        NSA_ONLY(
            "5G NSA only",
            RAT_LTE or RAT_NR,
            "Removes standalone NR. 5G data is used only alongside an LTE anchor, and only while " +
                "traffic is flowing; idle, the phone looks like plain LTE.",
        ),
        ;

        /** True where holding this technology necessarily means standalone NR. */
        val forcesStandalone: Boolean get() = forcesStandalone(modePref)

        /** True where the mode preference alone is not enough and the SA band mask must be emptied. */
        val excludesStandalone: Boolean get() = this == NSA_ONLY
    }

    /**
     * Whether a mask can only be satisfied by standalone NR.
     *
     * NSA carries NR traffic on an LTE anchor, so it needs both bits. NR present with LTE absent
     * leaves SA as the only way to hold NR service -- the closest thing this mechanism offers to
     * an SA/NSA test on its own, without touching the band-preference TLVs at all.
     */
    fun forcesStandalone(modePref: Int): Boolean =
        modePref and RAT_NR != 0 && modePref and RAT_LTE == 0

    /**
     * What the modem's allowance actually permits, given every value observed for it.
     *
     * Kept for the same reason it existed for the framework mechanism: a single authoritative
     * reading is still just one number, and this is the arithmetic for combining more than one
     * where that is ever needed.
     */
    fun effective(masks: List<Int>): Int =
        if (masks.isEmpty()) 0 else masks.reduce { a, b -> a and b }

    /**
     * Human-readable technologies in a mask, widest first.
     *
     * Deliberately not a bit dump: this goes in front of an engineer reading a report, where
     * "5G NR, LTE" is checkable against what they saw and `0x0`&#8203;`05F` is not.
     */
    fun describeMask(modePref: Int): String {
        if (modePref == 0) return "none"
        val parts = buildList {
            if (modePref and RAT_NR != 0) add("5G NR")
            if (modePref and RAT_LTE != 0) add("LTE")
            if (modePref and LEGACY_ALL != 0) add("3G/2G")
        }
        return if (parts.isEmpty()) "other" else parts.joinToString(", ")
    }

    /**
     * Whether a requested technology survives contact with the other reasons in force.
     *
     * Returns the technologies that would actually be available. An empty result is the case
     * worth catching before applying anything: the lock would leave the radio with nothing.
     */
    fun previewLock(requested: Int, otherReasons: List<Int>): Int =
        effective(listOf(requested) + otherReasons)

    /**
     * Whether an allowance observed after a write actually reflects the lock that was asked for.
     *
     * ## Why a write is not evidence
     *
     * A `SUCCESS` result TLV means the modem accepted the value, not that the radio is holding
     * it. This project shipped that exact shape of defect once already for the framework
     * mechanism -- accepted, then silently reverted -- and separately in the rate-limit backoff,
     * where the displayed text was right and the machine-readable half was silently absent. QMI
     * has been reliable in every test run against it so far, but "reliable so far" is not
     * "verified", and the cost of checking is a few lines already written. So the lock is
     * confirmed by observation or it is not claimed.
     *
     * ## The test
     *
     * A lock holds when the radio is left with something, and with nothing outside what was
     * asked for. Narrower than requested is fine and expected -- the carrier's own configuration
     * is ANDed in. Anything *wider* means the request did not survive.
     */
    fun lockHeld(requested: Int, observed: Int): Boolean =
        observed != 0 && observed and requested.inv() == 0

    /**
     * The mode preference to write for [Technology.NSA_ONLY]: the baseline, unchanged.
     *
     * That is exactly what was verified (mode 0x5F with an emptied SA mask); narrowing it to
     * LTE+NR alone would be an untested side effect on 3G/2G. Null where the baseline lacks LTE or
     * NR, since NSA cannot exist there and writing it would only add bits the handset was not
     * allowed.
     */
    fun nsaOnlyWriteMode(baseline: Int): Int? {
        val need = Technology.NSA_ONLY.modePref
        return if (baseline and need == need) baseline else null
    }

    /**
     * Whether NSA-only is actually in force: LTE and NR both permitted, and no SA band left.
     * The mode alone cannot show it -- with the SA mask intact the same mode allows standalone.
     */
    fun nsaOnlyHeld(mode: Int, saBands: Set<Int>): Boolean {
        val need = Technology.NSA_ONLY.modePref
        return mode and need == need && saBands.isEmpty()
    }

    /**
     * The one-word difference between an operator's self-report and this app's own claim.
     *
     * The band-lock field beside this control is a declaration: "the operator says they did
     * this," unverifiable and recorded as such. A technology lock through [TechnologyLockController]
     * is not that -- it is applied and confirmed by observation before this label is ever
     * written. The report needs to tell the two apart without a schema change, so the recorded
     * string carries the distinction itself.
     */
    private const val VERIFIED_SUFFIX = " (locked and verified by this app)"

    /** The string [TechnologyLockController] records once [Technology] is confirmed held. */
    fun verifiedLabel(technology: Technology): String = technology.label + VERIFIED_SUFFIX

    /** Whether a recorded technology-lock declaration was this app's own verified claim. */
    fun isVerifiedLabel(declared: String): Boolean = declared.endsWith(VERIFIED_SUFFIX)
}
