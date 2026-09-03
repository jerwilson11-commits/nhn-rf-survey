package com.nhnengineering.rftest.profile

import com.nhnengineering.rftest.cellular.NrSsb
import com.nhnengineering.rftest.report.SessionStats

/**
 * Compares what a walk observed against what the profile says should be there.
 *
 * ## What this is for
 *
 * The profile library records vendor defaults — configuration a carrier adopts across a market. Its
 * value rests on an assumption: that this site is like the others. **The exceptions are what a
 * survey exists to find**, and they cluster in exactly the dense venues that get surveyed. A
 * stadium that varies SSB position between neighbouring sectors is the documented case.
 *
 * So the library needs something that notices when a site does not look like the default, and says
 * so while the engineer is still on site and can ask about it.
 *
 * ## What it can honestly compare
 *
 * Very little of a TDD configuration is observable, and this must not pretend otherwise. The slot
 * pattern, SSB periodicity and CSI-RS periodicity cannot be seen at all, so they cannot be checked
 * — a profile claiming DDDSU is neither confirmed nor contradicted by anything a handset measures.
 *
 * What *is* comparable:
 *
 * - **SSB arrangement.** Per-sector SSB planning is measurable, and a general profile that does not
 *   mention it is a profile that may not describe this building.
 * - **Duplex mode**, which follows from the band allocation and is therefore exact.
 * - **Subcarrier spacing**, weakly — both sides are conventions rather than measurements, so
 *   agreement proves nothing and disagreement is still worth a question.
 *
 * Every finding is phrased as something to check, never as a fault. The profile is a human record
 * and the measurement is a handset's partial view; a disagreement means one of them deserves a
 * second look, not that either is wrong.
 */
object ProfileCrossCheck {

    enum class Severity { INFO, CHECK }

    data class Finding(
        val headline: String,
        val detail: String,
        val severity: Severity,
    )

    /**
     * Compares a session's observed layout against the matched profile.
     *
     * Returns an empty list when there is nothing to say, which is the common case and should stay
     * quiet rather than reassuring — "no discrepancies found" over two comparable fields would
     * overstate how much was actually checked.
     */
    fun check(
        profile: TddProfile?,
        ssb: List<SessionStats.BandSsbLayout>,
        band: String?,
    ): List<Finding> {
        if (profile == null) return emptyList()
        val findings = mutableListOf<Finding>()

        val layout = band?.let { b ->
            ssb.firstOrNull { it.band.equals(b, ignoreCase = true) }
                ?: ssb.firstOrNull { b.split('/').any { part -> part.trim().equals(it.band, true) } }
        }

        // The finding worth having: a general profile applied to a site that is planned
        // differently. Only raised with enough cells to tell the arrangements apart, because a
        // walk that missed some sectors looks identical to a per-sector plan.
        if (layout != null &&
            layout.arrangement == SessionStats.SsbArrangement.PER_SECTOR &&
            layout.sufficientEvidence &&
            !profile.isSiteOverride
        ) {
            findings += Finding(
                "SSB position varies by sector here",
                "Each SSB position on ${layout.band} carries its own cells, which is a deliberate " +
                    "per-sector plan. The profile applied is a general one for " +
                    "${profile.operator}, so it may describe the vendor default rather than this " +
                    "building. Worth confirming the configuration for this site and recording it " +
                    "as a site override.",
                Severity.CHECK,
            )
        }

        // Several carriers where a profile describes one is not a contradiction, but it does mean
        // the profile answers for only part of what is on air.
        if (layout != null && layout.positions.size > 1 &&
            layout.arrangement == SessionStats.SsbArrangement.SHARED_PCI_PLAN
        ) {
            findings += Finding(
                "More than one carrier on ${layout.band}",
                "${layout.positions.size} SSB positions share a cell plan, so this is multiple " +
                    "carriers over the same sectors. The profile records one configuration; " +
                    "confirm whether it applies to every carrier or only one.",
                Severity.INFO,
            )
        }

        // Exact, because duplex follows from the allocation rather than from a deployment choice.
        val observedDuplex = band?.let { NrSsb.duplexFor(it.substringBefore('/')) }
        val profileDuplex = NrSsb.duplexFor(profile.band)
        if (observedDuplex != null && profileDuplex != null && observedDuplex != profileDuplex) {
            findings += Finding(
                "Duplex mode does not match",
                "This session was on $band, which is $observedDuplex, while the profile is for " +
                    "${profile.band}, which is $profileDuplex. The profile is probably matched to " +
                    "the wrong band.",
                Severity.CHECK,
            )
        }

        // Weak by construction, and labelled as such: both values are conventions.
        val inferredScs = band?.let {
            NrSsb.inferredScsKhz(it.substringBefore('/'), null)
        }
        if (profile.scsKhz != null && inferredScs != null && profile.scsKhz != inferredScs) {
            findings += Finding(
                "Subcarrier spacing differs from the usual value",
                "The profile records ${profile.scsKhz} kHz; ${inferredScs} kHz is the common " +
                    "choice for this band. Neither is measured — the app infers from the band and " +
                    "the profile is what someone was told — so this is a prompt to confirm, not a " +
                    "discrepancy.",
                Severity.INFO,
            )
        }

        return findings
    }
}
