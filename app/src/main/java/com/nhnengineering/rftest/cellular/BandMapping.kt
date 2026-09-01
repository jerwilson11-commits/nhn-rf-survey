package com.nhnengineering.rftest.cellular

/**
 * EARFCN / NR-ARFCN to band and centre frequency.
 *
 * Needed because `CellIdentityLte.getBands()` and `CellIdentityNr.getBands()` arrived at API 30 and
 * are still frequently empty in practice — the framework reports what the modem hands it, and
 * plenty of modems hand it nothing. The channel number, by contrast, is almost always present.
 * Deriving the band ourselves turns "band: unknown" into usable data on exactly the devices where
 * the vendor path fails.
 *
 * Pure Kotlin with no Android dependencies, so it is unit-testable on the JVM. That matters here
 * more than usual: this is the one part of the cellular collector that can be verified before a SIM
 * exists, and everything downstream — the CSV, the map colouring, any band-specific analysis —
 * inherits whatever this gets wrong.
 *
 * References: 3GPP TS 36.101 table 5.7.3-1 (E-UTRA), TS 38.104 table 5.4.2.1-1 (NR).
 */
object BandMapping {

    // -----------------------------------------------------------------------
    // LTE
    // -----------------------------------------------------------------------

    /**
     * E-UTRA operating bands, keyed by downlink EARFCN range.
     *
     * `dlLowMhz` and `nOffsDl` come straight from TS 36.101; the downlink centre frequency is
     * `dlLowMhz + 0.1 * (earfcn - nOffsDl)`.
     *
     * Restricted to bands actually deployed in North America. A global table would be longer and
     * would introduce ambiguity this does not need — and a band this handset cannot receive is not
     * a useful answer.
     */
    data class LteBand(
        val band: Int,
        val label: String,
        val earfcnRange: IntRange,
        val dlLowMhz: Double,
        val nOffsDl: Int,
    )

    val LTE_BANDS = listOf(
        LteBand(2, "1900 PCS", 600..1199, 1930.0, 600),
        LteBand(4, "1700/2100 AWS", 1950..2399, 2110.0, 1950),
        LteBand(5, "850 CLR", 2400..2649, 869.0, 2400),
        LteBand(7, "2600", 2750..3449, 2620.0, 2750),
        LteBand(12, "700 lower", 5010..5179, 729.0, 5010),
        LteBand(13, "700 upper C", 5180..5279, 746.0, 5180),
        LteBand(14, "700 FirstNet", 5280..5379, 758.0, 5280),
        LteBand(17, "700 b/c", 5730..5849, 734.0, 5730),
        LteBand(25, "1900 PCS-G", 8040..8689, 1930.0, 8040),
        LteBand(26, "850 ESMR", 8690..9039, 859.0, 8690),
        LteBand(29, "700 SDL", 9660..9769, 717.0, 9660),
        LteBand(30, "2300 WCS", 9770..9869, 2350.0, 9770),
        LteBand(41, "2500 BRS", 39650..41589, 2496.0, 39650),
        LteBand(46, "5200 LAA", 46790..54539, 5150.0, 46790),
        LteBand(48, "3500 CBRS", 55240..56739, 3550.0, 55240),
        LteBand(66, "1700/2100 AWS-3", 66436..67335, 2110.0, 66436),
        LteBand(71, "600", 68586..68935, 617.0, 68586),
    )

    fun lteBandFor(earfcn: Int): LteBand? =
        LTE_BANDS.firstOrNull { earfcn in it.earfcnRange }

    /** Downlink centre frequency in MHz, or null if the EARFCN is outside the known bands. */
    fun lteDownlinkMhz(earfcn: Int): Double? {
        val b = lteBandFor(earfcn) ?: return null
        return b.dlLowMhz + 0.1 * (earfcn - b.nOffsDl)
    }

    // -----------------------------------------------------------------------
    // NR
    // -----------------------------------------------------------------------

    /**
     * NR-ARFCN to frequency, per TS 38.104 section 5.4.2.1.
     *
     * Unlike E-UTRA, NR uses one global frequency raster shared by all bands, so the channel number
     * converts to a frequency directly and the band follows from the frequency. That is the more
     * robust direction: a band table keyed on ARFCN ranges would be ambiguous, because NR bands
     * genuinely overlap in ARFCN space (n25 sits inside n2's range; n66 contains n4).
     *
     *   F = F_REF-Offs + ΔF_Global × (N_REF − N_REF-Offs)
     */
    fun nrArfcnToMhz(nrarfcn: Int): Double? = when (nrarfcn) {
        in 0..599_999 -> 0.0 + 0.005 * nrarfcn                      // ΔF 5 kHz
        in 600_000..2_016_666 -> 3000.0 + 0.015 * (nrarfcn - 600_000)   // ΔF 15 kHz
        in 2_016_667..3_279_165 -> 24_250.08 + 0.060 * (nrarfcn - 2_016_667) // ΔF 60 kHz
        else -> null
    }

    data class NrBand(val band: String, val label: String, val dlMhz: ClosedFloatingPointRange<Double>, val tdd: Boolean)

    /**
     * NR bands deployed in North America, by downlink frequency range.
     *
     * Ordered so that narrower, more specific bands are tested first. n25 is a superset of n2 and
     * n66 a superset of n4, so testing the wider band first would mislabel every channel in the
     * overlap — the classic failure mode of a naive frequency lookup.
     */
    val NR_BANDS = listOf(
        NrBand("n71", "600", 617.0..652.0, tdd = false),
        NrBand("n29", "700 SDL", 717.0..728.0, tdd = false),
        NrBand("n12", "700 lower", 729.0..746.0, tdd = false),
        NrBand("n13", "700 upper C", 746.0..756.0, tdd = false),
        NrBand("n14", "700 FirstNet", 758.0..768.0, tdd = false),
        NrBand("n5", "850 CLR", 869.0..894.0, tdd = false),
        NrBand("n26", "850 ESMR", 859.0..894.0, tdd = false),
        NrBand("n2", "1900 PCS", 1930.0..1990.0, tdd = false),
        NrBand("n25", "1900 PCS-G", 1930.0..1995.0, tdd = false),
        NrBand("n4", "1700/2100 AWS", 2110.0..2155.0, tdd = false),
        NrBand("n66", "1700/2100 AWS-3", 2110.0..2200.0, tdd = false),
        NrBand("n30", "2300 WCS", 2350.0..2360.0, tdd = false),
        NrBand("n41", "2500 BRS", 2496.0..2690.0, tdd = true),
        NrBand("n48", "3500 CBRS", 3550.0..3700.0, tdd = true),
        NrBand("n77", "3700 C-band", 3300.0..4200.0, tdd = true),
        NrBand("n78", "3500", 3300.0..3800.0, tdd = true),
        NrBand("n260", "39 GHz mmWave", 37_000.0..40_000.0, tdd = true),
        NrBand("n261", "28 GHz mmWave", 27_500.0..28_350.0, tdd = true),
    )

    /**
     * Candidate NR bands for a channel number.
     *
     * Returns a list rather than a single answer because overlapping allocations make some
     * frequencies genuinely ambiguous from the channel alone — 1950 MHz is valid in both n2 and
     * n25, and no amount of arithmetic resolves that. Reporting both is honest; picking one and
     * presenting it as fact is not.
     *
     * `CellIdentityNr.getBands()` is authoritative when the modem supplies it; this is the fallback
     * for when it does not.
     */
    fun nrBandsFor(nrarfcn: Int): List<NrBand> {
        val mhz = nrArfcnToMhz(nrarfcn) ?: return emptyList()
        return NR_BANDS.filter { mhz in it.dlMhz }
    }

    /** Single best-guess label, marked ambiguous where it is. */
    fun nrBandLabel(nrarfcn: Int): String? {
        val candidates = nrBandsFor(nrarfcn)
        return when (candidates.size) {
            0 -> null
            1 -> candidates.first().band
            // Narrowest range first: the more specific allocation is the likelier deployment.
            else -> candidates.minByOrNull { it.dlMhz.endInclusive - it.dlMhz.start }!!.band +
                " (or " + candidates.filter { it != candidates.minByOrNull { c -> c.dlMhz.endInclusive - c.dlMhz.start } }
                    .joinToString("/") { it.band } + ")"
        }
    }
}
