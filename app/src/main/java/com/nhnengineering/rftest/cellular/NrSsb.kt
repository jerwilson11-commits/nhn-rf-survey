package com.nhnengineering.rftest.cellular

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Derivations a field engineer usually has to ask the carrier for.
 *
 * ## Why this exists
 *
 * A DAS, repeater or TDD sync unit cannot be commissioned without knowing the carrier's TDD
 * configuration — a repeater has to switch direction in step with the network or it transmits into
 * the uplink. Vendors therefore issue a parameter questionnaire (NR-ARFCN, SSB ARFCN, carrier
 * frequency, bandwidth, GSCN, subcarrier spacing, SSB periodicity, TDD pattern, CSI-RS periodicity)
 * and the integrator has to obtain the answers from the operator. That request is slow, often
 * refused, and frequently answered wrongly.
 *
 * **Some of those rows are arithmetic**, and a handset standing in the building already holds the
 * inputs. Filling them in from a walk turns a procurement conversation into a measurement.
 *
 * ## What this deliberately does not claim
 *
 * The rest of the questionnaire — SSB periodicity, SSB starting symbol, the TDD slot pattern,
 * CSI-RS periodicity, beam index — is **not** observable from Android's public telephony API. It
 * lives in the PHY layer and in SIB1, neither of which is exposed to an ordinary app. Guessing at
 * them from typical deployments would be worse than leaving them blank, because a plausible wrong
 * TDD pattern configured into a repeater causes interference rather than an obvious failure.
 *
 * So this reports three tiers, and the distinction is the point:
 *
 * - **Measured** — read from the air interface.
 * - **Derived** — pure arithmetic from a measured value, exact.
 * - **Inferred** — the near-universal convention for this band, and explicitly a guess.
 */
object NrSsb {

    /** How confident the app is entitled to be about a given field. */
    enum class Confidence { MEASURED, DERIVED, INFERRED, UNAVAILABLE }

    data class Parameter(
        val name: String,
        val value: String?,
        val confidence: Confidence,
        /** Why it is unavailable, or what the inference rests on. Never left implicit. */
        val note: String? = null,
    )

    /**
     * Global Synchronisation Channel Number for an SSB centre frequency, per TS 38.104 §5.4.3.1.
     *
     * GSCN is the number a sync or repeater vendor actually asks for, and it is a fixed function of
     * the SSB frequency — so an engineer looking it up in a table is doing arithmetic by hand.
     *
     * Returns null when the frequency does not sit on the synchronisation raster. That is not a
     * failure: the raster is sparse by design, and a frequency off it means the reported ARFCN is
     * not an SSB position, which is itself worth knowing rather than papering over.
     */
    fun gscnFor(ssbFreqMhz: Double): Int? = when {
        ssbFreqMhz <= 0 -> null

        // 0-3000 MHz: SS_REF = N * 1200 kHz + M * 50 kHz, N = 1..2499, M in {1,3,5}
        ssbFreqMhz < 3000.0 -> {
            var found: Int? = null
            for (m in intArrayOf(1, 3, 5)) {
                val n = (ssbFreqMhz - m * 0.05) / 1.2
                val nInt = n.roundToInt()
                if (nInt in 1..2499 && abs(nInt * 1.2 + m * 0.05 - ssbFreqMhz) < RASTER_TOLERANCE_MHZ) {
                    found = 3 * nInt + (m - 3) / 2
                    break
                }
            }
            found
        }

        // 3000-24250 MHz: SS_REF = 3000 MHz + N * 1.44 MHz, N = 0..14756
        ssbFreqMhz < 24250.0 -> {
            val n = ((ssbFreqMhz - 3000.0) / 1.44).roundToInt()
            if (n in 0..14756 && abs(3000.0 + n * 1.44 - ssbFreqMhz) < RASTER_TOLERANCE_MHZ) {
                7499 + n
            } else {
                null
            }
        }

        // 24250 MHz and above: SS_REF = 24250.08 MHz + N * 17.28 MHz, N = 0..4383
        else -> {
            val n = ((ssbFreqMhz - 24250.08) / 17.28).roundToInt()
            if (n in 0..4383 && abs(24250.08 + n * 17.28 - ssbFreqMhz) < RASTER_TOLERANCE_MHZ) {
                22256 + n
            } else {
                null
            }
        }
    }

    /** Inverse of [gscnFor], for checking a value an operator supplied. */
    fun ssbFreqMhzForGscn(gscn: Int): Double? = when (gscn) {
        in 2..7498 -> {
            val n = gscn / 3
            val rem = gscn - 3 * n
            val m = when (rem) {
                0 -> 3
                1 -> 5
                else -> 1
            }
            // rem == 2 corresponds to M = 1 on the next N.
            val nAdj = if (rem == 2) n + 1 else n
            val mAdj = if (rem == 2) 1 else m
            if (nAdj in 1..2499) nAdj * 1.2 + mAdj * 0.05 else null
        }
        in 7499..22255 -> 3000.0 + (gscn - 7499) * 1.44
        in 22256..26639 -> 24250.08 + (gscn - 22256) * 17.28
        else -> null
    }

    /** TDD or FDD, from the band. Exact — it is a property of the allocation, not the deployment. */
    fun duplexFor(band: String?): String? = when (band?.removePrefix("n")?.toIntOrNull()) {
        null -> null
        34, 38, 39, 40, 41, 46, 48, 50, 51, 53, 77, 78, 79, 90, 96, 101, 102, 104 -> "TDD"
        in 257..263 -> "TDD"
        else -> "FDD"
    }

    /**
     * The subcarrier spacing this band is deployed with in practice.
     *
     * **An inference, not a measurement.** SCS is carried in SIB1 and is not exposed to an app. The
     * conventions below are near-universal — 30 kHz for FR1 TDD, 15 kHz for FR1 FDD, 120 kHz for
     * FR2 — but a network is free to differ, and a repeater configured from a guess will fail in a
     * way that looks like a hardware fault. Labelled [Confidence.INFERRED] so it can never be
     * copied into a vendor form as though it had been read off the air.
     */
    fun inferredScsKhz(band: String?, freqMhz: Double?): Int? {
        val duplex = duplexFor(band) ?: return null
        return when {
            freqMhz != null && freqMhz >= 24250.0 -> 120
            duplex == "TDD" -> 30
            else -> 15
        }
    }

    /**
     * Builds the questionnaire rows, each carrying how much it can be trusted.
     *
     * The unavailable rows are returned rather than omitted, with the reason. An engineer holding a
     * vendor form needs to know which four lines a walk can fill and which eight still require the
     * operator — a short list with no explanation would leave them assuming the app had failed.
     */
    fun parameters(
        nrarfcn: Int?,
        band: String?,
        bandwidthKhz: Int?,
    ): List<Parameter> {
        val freq = nrarfcn?.let { BandMapping.nrArfcnToMhz(it) }
        val gscn = freq?.let { gscnFor(it) }
        val duplex = duplexFor(band)

        return listOf(
            Parameter("NR-ARFCN (SSB)", nrarfcn?.toString(), Confidence.MEASURED,
                "Reported by the modem as the serving cell's SSB position."),
            Parameter("Carrier frequency", freq?.let { "%.2f MHz".format(it) }, Confidence.DERIVED,
                "From the ARFCN via the 3GPP global raster."),
            Parameter("GSCN", gscn?.toString(),
                if (gscn != null) Confidence.DERIVED else Confidence.UNAVAILABLE,
                if (gscn != null) {
                    "From the SSB frequency via TS 38.104 §5.4.3.1."
                } else {
                    "The reported frequency is not on the synchronisation raster, so it is not an " +
                        "SSB position."
                }),
            Parameter("Band", band, Confidence.MEASURED, null),
            Parameter("Duplex", duplex, Confidence.DERIVED, "A property of the band allocation."),
            Parameter(
                "Bandwidth",
                bandwidthKhz?.let { "${it / 1000} MHz" },
                if (bandwidthKhz != null) Confidence.MEASURED else Confidence.UNAVAILABLE,
                if (bandwidthKhz == null) "Not reported by this modem for the serving cell." else null,
            ),
            Parameter(
                "Subcarrier spacing",
                inferredScsKhz(band, freq)?.let { "$it kHz" },
                Confidence.INFERRED,
                "Carried in SIB1, which an app cannot read. This is the usual value for the band, " +
                    "not a measurement — confirm before configuring equipment.",
            ),

            // The rows a handset genuinely cannot answer. Present, blank, and explained.
            Parameter("SSB periodicity", null, Confidence.UNAVAILABLE, PHY_NOTE),
            Parameter("SSB starting symbol", null, Confidence.UNAVAILABLE, PHY_NOTE),
            Parameter("SSB position in burst", null, Confidence.UNAVAILABLE, PHY_NOTE),
            Parameter("TDD periodicity", null, Confidence.UNAVAILABLE, TDD_NOTE),
            Parameter("Downlink slots", null, Confidence.UNAVAILABLE, TDD_NOTE),
            Parameter("Downlink symbols", null, Confidence.UNAVAILABLE, TDD_NOTE),
            Parameter("CSI-RS periodicity", null, Confidence.UNAVAILABLE, PHY_NOTE),
        )
    }

    private const val RASTER_TOLERANCE_MHZ = 0.001

    private const val PHY_NOTE =
        "Not exposed by Android's telephony API — it lives in SIB1 and the PHY layer. A scanning " +
            "receiver or a rooted protocol tool can read it; an ordinary app cannot."

    private const val TDD_NOTE =
        "Not exposed by Android's telephony API. Ask the operator, or read it with a scanner. " +
            "Do not infer it: a plausible but wrong slot pattern configured into a repeater causes " +
            "interference rather than an obvious failure."
}
