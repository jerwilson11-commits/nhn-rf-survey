package com.nhnengineering.rftest.cellular

/**
 * Splits an NR Cell Identity into a gNodeB ID and a cell ID.
 *
 * ## Why this is not as simple as the LTE equivalent
 *
 * LTE's ECI is 28 bits split at a fixed boundary — 20 bits of eNB, 8 of cell — so `ci shr 8` is
 * exact and needs no explanation. NR is not like that. TS 38.401 makes the NCI 36 bits and lets the
 * operator choose how many belong to the gNB, anywhere from 22 to 32. **The split cannot be derived
 * from the NCI alone.** Nothing broadcast to a handset says where the boundary is.
 *
 * The project's first answer to that was to store the NCI whole and refuse to split it, on the
 * grounds that any split would be a guess. That is defensible and turned out to be unhelpful: for
 * DAS and commissioning work the gNodeB ID is the number that names the site, and a survey that
 * cannot say which site it walked is missing the identifier the customer cares about most.
 * WalkTest splits at 24 bits and prints a gNodeB ID; we printed a 36-bit integer nobody can use.
 *
 * So this splits, at a stated and overridable assumption, and every surface that shows the result
 * is expected to say that it is an assumption. A guess that announces itself is useful; a guess
 * that doesn't is the problem.
 */
object NrCellId {

    /**
     * The assumed gNB ID length in bits.
     *
     * 24 is the common configuration and the one WalkTest assumes; a 24/12 split of the observed
     * T-Mobile NCI 6592188719 gives gNB 1609421 and cell 303, matching their report exactly. It is
     * still a convention rather than a measurement.
     */
    const val DEFAULT_GNB_ID_BITS = 24

    /** TS 38.401: the gNB ID occupies 22 to 32 of the NCI's 36 bits. */
    const val NCI_BITS = 36
    const val MIN_GNB_ID_BITS = 22
    const val MAX_GNB_ID_BITS = 32

    data class Split(
        val gnbId: Long,
        val cellId: Int,
        /** Carried so a caller can state the assumption rather than imply a measurement. */
        val gnbIdBits: Int,
    )

    /**
     * Returns null for a null NCI, and for one that cannot be a 36-bit identity — a negative value
     * or one too large. Those are not split into plausible-looking halves, because a wrong gNB ID
     * is worse than none: it reads as a real site and would be looked up as one.
     */
    fun split(nci: Long?, gnbIdBits: Int = DEFAULT_GNB_ID_BITS): Split? {
        if (nci == null || nci < 0) return null
        if (nci >= (1L shl NCI_BITS)) return null
        if (gnbIdBits !in MIN_GNB_ID_BITS..MAX_GNB_ID_BITS) return null

        val cellIdBits = NCI_BITS - gnbIdBits
        return Split(
            gnbId = nci ushr cellIdBits,
            cellId = (nci and ((1L shl cellIdBits) - 1)).toInt(),
            gnbIdBits = gnbIdBits,
        )
    }
}
