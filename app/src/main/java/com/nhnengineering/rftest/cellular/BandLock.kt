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
 *    written in the same request. Restricted to n41, the phone left n25 SA for LTE (no n41
 *    reachable at the test desk) and returned to NR on restore.
 *
 * Not offered, because it was tried and the modem refused it (error 48, InvalidArgument):
 * LTE bands above 64 (B66, B71) via the extended mask, and an LTE selection with no band in
 * 1-64. This handset's own mask also does not list B66/B71 even though the radio has camped on B66
 * during a walk, so absence from the list is not proof a band is unusable.
 *
 * Not possible over this interface at all: a specific EARFCN. QMI NAS carries band masks, not
 * channel lists.
 *
 * NSA NR bands are left exactly as the modem reported them. Restricting only the SA mask does not
 * constrain an NSA connection, and the label says so by naming the SA scope.
 */
object BandLock {

    /**
     * Why a request cannot be made, or null where it can. Checked before anything is written so a
     * bad request never reaches the modem.
     */
    fun validate(
        lte: Set<Int>,
        nrSa: Set<Int>,
        supportedLte: Set<Int>,
        supportedNrSa: Set<Int>,
    ): String? {
        if (lte.isEmpty() && nrSa.isEmpty()) return "Pick at least one band."
        (lte - supportedLte).let {
            if (it.isNotEmpty()) return "LTE ${it.sorted().joinToString { b -> "B$b" }} is not in this modem's band list."
        }
        (nrSa - supportedNrSa).let {
            if (it.isNotEmpty()) return "NR ${it.sorted().joinToString { b -> "n$b" }} is not in this modem's band list."
        }
        return null
    }

    /** LTE bands this mechanism can select: those in the base mask (1-64). */
    fun selectableLte(supported: Set<Int>): Set<Int> = supported.filter { it in 1..64 }.toSet()

    private const val VERIFIED_SUFFIX = " (locked and verified by this app)"

    /**
     * The string recorded per sample: bands as the serving-cell labels write them (`B4`, `n41`) so
     * the report can compare directly, then the marker that this app -- not the operator --
     * applied and read it back. NR bands here are the standalone scope; see the class doc.
     */
    fun verifiedLabel(lte: Set<Int>, nrSa: Set<Int>): String =
        (lte.sorted().map { "B$it" } + nrSa.sorted().map { "n$it" }).joinToString(", ") + VERIFIED_SUFFIX

    fun isVerifiedLabel(declared: String): Boolean = declared.endsWith(VERIFIED_SUFFIX)

    /** The band tokens in a verified label, e.g. `["B4", "n41"]`. Empty for anything else. */
    fun tokens(declared: String): List<String> =
        if (!isVerifiedLabel(declared)) {
            emptyList()
        } else {
            declared.removeSuffix(VERIFIED_SUFFIX).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
}
