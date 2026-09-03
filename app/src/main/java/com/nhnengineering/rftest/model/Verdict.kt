package com.nhnengineering.rftest.model

/**
 * A plain-language reading of what the radio conditions actually mean.
 *
 * ## Why an app this pedantic about measurement ships a verdict
 *
 * Everyone who opens this app wants the same first answer — *is this all right?* — and only one
 * group can get it from a dBm. An executive, a salesperson, a facilities manager and a homeowner
 * all read "−104 dBm" as no information at all. Competitors show them the number and stop.
 *
 * The verdict is not a dumbing-down, because of one thing it does that a number cannot:
 * **it separates a coverage problem from an interference problem.** That is the actual remediation
 * decision on an in-building system — add a node, or fix overlap — and it is invisible in RSRP
 * alone. A strong signal with poor SINR and a weak signal with clean SINR read identically on a
 * signal-strength bar and require opposite fixes.
 *
 * So the same line that tells a homeowner "expect dropped calls" tells an engineer "this is
 * overlap, not coverage". That is worth more than either audience alone.
 *
 * ## What it deliberately does not do
 *
 * It does not predict throughput, promise call quality, or state a percentage. Those depend on
 * load, on the network's configuration and on the far end, none of which a handset can see. The
 * wording stays at the level the measurement can support — this project's whole credibility rests
 * on not overclaiming, and a cheerful green verdict over a marginal reading would spend that.
 */
data class Verdict(
    val headline: String,
    val detail: String,
    /** Drives the colour, reusing the existing coverage scale rather than inventing a second one. */
    val severity: Severity,
    /**
     * True when signal level is adequate but quality is not — interference or pilot pollution
     * rather than lack of coverage. The single most useful distinction this makes.
     */
    val interferenceLimited: Boolean = false,
) {
    enum class Severity { GOOD, FAIR, POOR, UNKNOWN }

    companion object {

        // Level thresholds mirror RsrpBucket so the verdict and the map never disagree.
        private const val RSRP_STRONG = -85
        private const val RSRP_OK = -95
        private const val RSRP_FAIR = -105

        // SINR: below 0 dB the wanted signal is weaker than the interference plus noise, which is
        // the point at which "more signal" stops being the answer.
        private const val SINR_GOOD = 13
        private const val SINR_FAIR = 0

        /**
         * Cellular verdict from level and quality together.
         *
         * SINR is optional because it is not always reported, and its absence must not be treated
         * as zero — a missing quality reading means the verdict speaks only to coverage, and says
         * so, rather than silently inventing the more confident answer.
         */
        fun cellular(rsrpDbm: Int?, sinrDb: Int?): Verdict {
            if (rsrpDbm == null) {
                return Verdict(
                    "No reading",
                    "No serving cell is being reported. Either there is no service here, or the " +
                        "handset has not finished registering.",
                    Severity.UNKNOWN,
                )
            }

            val levelOk = rsrpDbm >= RSRP_OK
            val levelUsable = rsrpDbm >= RSRP_FAIR

            if (sinrDb == null) {
                return when {
                    rsrpDbm >= RSRP_STRONG -> Verdict(
                        "Strong signal",
                        "Signal level is strong. Quality was not reported, so this speaks to " +
                            "coverage only.",
                        Severity.GOOD,
                    )
                    levelOk -> Verdict(
                        "Good signal",
                        "Signal level is adequate. Quality was not reported, so this speaks to " +
                            "coverage only.",
                        Severity.GOOD,
                    )
                    levelUsable -> Verdict(
                        "Marginal signal",
                        "Usable but with little margin. Expect trouble in lifts, stairwells and " +
                            "at the edges of the building.",
                        Severity.FAIR,
                    )
                    else -> Verdict(
                        "Weak signal",
                        "Below the level most systems are designed to deliver. Expect dropped " +
                            "calls and slow data.",
                        Severity.POOR,
                    )
                }
            }

            val qualityGood = sinrDb >= SINR_GOOD
            val qualityUsable = sinrDb >= SINR_FAIR

            return when {
                levelOk && qualityGood -> Verdict(
                    "Good coverage",
                    "Strong signal and clean quality. Reliable voice and data here.",
                    Severity.GOOD,
                )

                // The distinction that earns this feature its place.
                levelOk && !qualityUsable -> Verdict(
                    "Strong but noisy",
                    "Plenty of signal, poor quality — interference or too many overlapping cells. " +
                        "Adding coverage here will not help; this needs the overlap fixed.",
                    Severity.POOR,
                    interferenceLimited = true,
                )
                levelOk -> Verdict(
                    "Adequate, some interference",
                    "Signal is fine but quality is reduced, which usually means overlapping cells " +
                        "rather than a shortage of coverage.",
                    Severity.FAIR,
                    interferenceLimited = true,
                )

                levelUsable && qualityGood -> Verdict(
                    "Weak but clean",
                    "Quality is good, level is low — the edge of coverage rather than " +
                        "interference. More signal here would help.",
                    Severity.FAIR,
                )
                levelUsable -> Verdict(
                    "Marginal",
                    "Low signal and reduced quality. Expect unreliable calls and slow data, " +
                        "especially indoors.",
                    Severity.FAIR,
                )

                qualityGood -> Verdict(
                    "Very weak, clean",
                    "Barely enough signal, but no interference. This is a coverage gap.",
                    Severity.POOR,
                )
                else -> Verdict(
                    "Poor",
                    "Too little signal and poor quality. Expect dropped calls and data failures.",
                    Severity.POOR,
                )
            }
        }

        /**
         * Wi-Fi verdict from level and co-channel congestion.
         *
         * The Wi-Fi analogue of interference-limited: a strong AP on a channel shared with several
         * others performs badly for reasons more signal cannot fix. Channel congestion is the
         * commonest real-world Wi-Fi complaint and the one users least expect.
         */
        fun wifi(rssiDbm: Int?, coChannelCount: Int?): Verdict {
            if (rssiDbm == null) {
                return Verdict(
                    "Not connected",
                    "No Wi-Fi connection to measure.",
                    Severity.UNKNOWN,
                )
            }

            val crowded = (coChannelCount ?: 0) >= 3
            val levelOk = rssiDbm >= -67
            val levelUsable = rssiDbm >= -75

            return when {
                levelOk && crowded -> Verdict(
                    "Strong but crowded",
                    "Good signal, but ${coChannelCount} other networks share this channel. " +
                        "Congestion, not coverage — a different channel would help more than a " +
                        "closer access point.",
                    Severity.FAIR,
                    interferenceLimited = true,
                )
                levelOk -> Verdict(
                    "Good Wi-Fi",
                    "Strong signal on an uncongested channel. Suitable for voice and video.",
                    Severity.GOOD,
                )
                levelUsable && crowded -> Verdict(
                    "Marginal and crowded",
                    "Moderate signal sharing a channel with ${coChannelCount} other networks. " +
                        "Expect slow and inconsistent performance.",
                    Severity.POOR,
                    interferenceLimited = true,
                )
                levelUsable -> Verdict(
                    "Usable Wi-Fi",
                    "Enough for browsing, marginal for voice or video calls.",
                    Severity.FAIR,
                )
                else -> Verdict(
                    "Weak Wi-Fi",
                    "Below the level needed for reliable use. Expect drops and slow speeds.",
                    Severity.POOR,
                )
            }
        }
    }
}
