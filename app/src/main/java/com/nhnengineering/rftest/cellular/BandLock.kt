package com.nhnengineering.rftest.cellular

/**
 * Pure rules for a band lock: what may be asked for, and how it is written down in a session.
 * The modem I/O is in [BandLockController].
 *
 * ## What is and is not selectable
 *
 * Proven on the OnePlus 9 (2026-09-26) by watching the serving cell move and then restoring the
 * baseline exactly:
 *
 *  - **LTE bands 1-64**, by restricting the base LTE mask. Band 4 moved the serving cell from
 *    EARFCN 1000 (B2) to EARFCN 2350 (B4) and it stayed there.
 *  - **Standalone NR bands**, by restricting the SA mask -- but only when the mode preference is
 *    written in the same request. The modem accepts the restriction (reads back "allows n41 (SA)")
 *    but that is not the same as the radio camping there -- see the 2026-09-29 correction below.
 *  - **Non-standalone NR bands**, by restricting the NSA mask the same way. First proven under load
 *    via raw packet capture (2026-09-26, not through this mechanism): restricting the NSA mask
 *    (TLV 0x30) to n41 while an LTE anchor carried data left n41 as the only NR leg added, in 49 of
 *    49 observed packets, and excluding n41 removed the leg entirely. Then proven through this
 *    app's own write path (2026-09-29): NSA-only technology lock plus an NSA band lock to n41
 *    camped the serving cell on n41 NSA, ARFCN 501390 -- the same channel the raw-capture
 *    experiment found.
 *
 * ## Correction, 2026-09-29: the site does broadcast SA n41, and this handset still cannot hold it
 *
 * The original note above ("no n41 reachable as a standalone cell at the test desk") was wrong. An
 * iPhone 16 in Qualcomm field-test mode (`*3001#12345#*`) at the same desk read **SA n41, 100 MHz
 * bandwidth, PCI 206** -- a real, wide standalone layer. Retried on the OnePlus 9 the same day with
 * "5G SA only" technology-locked and the SA mask restricted to n41: the modem again confirmed the
 * restriction ("Modem allows n41 (SA)"), but the radio did not camp there. `getAllCellInfo` and this
 * app's own Cellular card both went to RAT `unknown`, and `dumpsys telephony.registry` showed
 * `getRilDataRadioTechnology=18 (IWLAN)` -- the phone had lost cellular PS data entirely and failed
 * over to Wi-Fi. So the SA write mechanism is correct (the modem accepts and echoes back the
 * restriction) but this handset could not complete the RRC-level connection to the site's actual SA
 * n41 layer, while forced to exclude every fallback. This reads as a genuine OnePlus 9 / Snapdragon
 * 888 (X60 modem) limitation reaching that specific layer, not a site-coverage fact and not a bug in
 * this class or in [QmiSelectionPreference][com.nhnengineering.rftest.modem.QmiSelectionPreference] --
 * consistent with the throughput gap also seen this session (34.5 Mbps NSA-locked on this handset vs.
 * 500+ Mbps SA on the iPhone 16 at the same desk): NSA here rides a 10 MHz LTE anchor with an NR
 * secondary leg that Android's `PhysicalChannelConfig` shows attaching only intermittently, nowhere
 * near saturating a 100 MHz channel, while true SA n41 -- the config that would actually deliver that
 * bandwidth -- is the one mode this handset cannot hold at this desk.
 *
 * SA and NR are independent restrictions on the same modem preference group and can be set to
 * different bands (or one left alone) at the same time; which one the radio actually uses depends
 * on the technology lock held alongside, if any. See [BandLockController] for why they share one
 * QMI write. Restricting SA-only to a band the radio cannot hold is exactly the scenario the
 * "service until released" warning on the band-lock UI exists for.
 *
 * Not offered, because it was tried and the modem refused it (error 48, InvalidArgument):
 * LTE bands above 64 (B66, B71) via the extended mask, and an LTE selection with no band in
 * 1-64. This handset's own mask also does not list B66/B71 even though the radio has camped on B66
 * during a walk, so absence from the list is not proof a band is unusable.
 *
 * Not possible over this interface at all: a specific EARFCN. QMI NAS carries band masks, not
 * channel lists.
 */
object BandLock {

    /**
     * Why a request cannot be made, or null where it can. Checked before anything is written so a
     * bad request never reaches the modem. [nrSa] and [nrNsa] are validated against their own
     * supported sets independently -- a band can be reachable on one NR scope and not the other.
     */
    fun validate(
        lte: Set<Int>,
        nrSa: Set<Int>,
        supportedLte: Set<Int>,
        supportedNrSa: Set<Int>,
        nrNsa: Set<Int> = emptySet(),
        supportedNrNsa: Set<Int> = emptySet(),
    ): String? {
        if (lte.isEmpty() && nrSa.isEmpty() && nrNsa.isEmpty()) return "Pick at least one band."
        (lte - supportedLte).let {
            if (it.isNotEmpty()) return "LTE ${it.sorted().joinToString { b -> "B$b" }} is not in this modem's band list."
        }
        (nrSa - supportedNrSa).let {
            if (it.isNotEmpty()) return "NR ${it.sorted().joinToString { b -> "n$b" }} is not in this modem's band list."
        }
        (nrNsa - supportedNrNsa).let {
            if (it.isNotEmpty()) return "NR ${it.sorted().joinToString { b -> "n$b" }} (NSA) is not in this modem's band list."
        }
        return null
    }

    /** LTE bands this mechanism can select: those in the base mask (1-64). */
    fun selectableLte(supported: Set<Int>): Set<Int> = supported.filter { it in 1..64 }.toSet()

    private const val VERIFIED_SUFFIX = " (locked and verified by this app)"

    /**
     * The string recorded per sample: bands as the serving-cell labels write them (`B4`, `n41`) so
     * the report can compare directly, then the marker that this app -- not the operator --
     * applied and read it back.
     *
     * [nrSa] and [nrNsa] are folded into the same bare `n<band>` tokens without a scope suffix,
     * deliberately: [BandLockCheck][com.nhnengineering.rftest.report.BandLockCheck] matches these
     * tokens against the plain band string a serving cell reports, which never says which NR scope
     * produced it, so a scope-qualified token here would never match anything and every NSA-locked
     * walk would report its own locked band as "never seen". A band locked on both scopes at once
     * collapses to one token, which is correct: the question that check answers is only whether the
     * band appeared, not which scope carried it.
     */
    fun verifiedLabel(lte: Set<Int>, nrSa: Set<Int>, nrNsa: Set<Int> = emptySet()): String =
        (lte.sorted().map { "B$it" } + (nrSa + nrNsa).sorted().map { "n$it" })
            .distinct().joinToString(", ") + VERIFIED_SUFFIX

    fun isVerifiedLabel(declared: String): Boolean = declared.endsWith(VERIFIED_SUFFIX)

    /** The band tokens in a verified label, e.g. `["B4", "n41"]`. Empty for anything else. */
    fun tokens(declared: String): List<String> =
        if (!isVerifiedLabel(declared)) {
            emptyList()
        } else {
            declared.removeSuffix(VERIFIED_SUFFIX).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
}
