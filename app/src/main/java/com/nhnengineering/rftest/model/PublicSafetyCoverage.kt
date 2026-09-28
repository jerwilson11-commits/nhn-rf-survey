package com.nhnengineering.rftest.model

/**
 * Public safety in-building radio coverage — ERRCS (Emergency Responder Radio Coverage System,
 * strict Land Mobile Radio) and ERCES (the broader NFPA 1225 framework some jurisdictions have
 * adopted, which permits cellular-based coverage such as FirstNet on 3GPP Band 14/n14 to count).
 *
 * **A phone cannot measure LMR at all.** Traditional public-safety radio (VHF 150-174 MHz, UHF
 * 450-512 MHz, or 700/800 MHz P25 digital/analog voice) uses a completely different RF front end
 * and protocol than a phone's 3GPP cellular or Wi-Fi radios -- the same category of hard floor as
 * this project's own "no SNR" and "no spectrum analysis" findings elsewhere, not a software gap.
 * See `docs/public-safety-coverage.md` for the research this is built on.
 *
 * Two tracks follow from that constraint, both represented here:
 *
 * - **Manual entry** ([ErrcsGridPoint], in `session/ErrcsGridStore.kt`): the operator reads a dBm
 *   value off their own equipment tuned to the AHJ's actual LMR frequency, and records it here.
 *   This app never measures or verifies that reading -- it ingests it, the same way it already
 *   ingests a pasted NSG SIB1 decode.
 * - **Automatic** (Track B, in `SessionStats`/`PdfReportGenerator`): filtered to Band 14/n14
 *   samples from the app's own existing cellular capture, for the FirstNet/ERCES case specifically.
 *
 * **[ErrcsAreaClass] and [PublicSafetyThresholds] are shared by both tracks.** Whether a given
 * AHJ accepts the Band 14 substitution at all is jurisdiction-specific and never assumed by this
 * app -- both tracks' reports say plainly which kind of test produced which numbers, and the two
 * are never merged into one compliance figure.
 */
enum class ErrcsAreaClass(val label: String) {
    GENERAL("General area"),
    CRITICAL("Critical area"),
}

/**
 * The pass/fail limits applied per [ErrcsAreaClass]. Deliberately configurable rather than
 * hardcoded: IFC 510 and NFPA 72/1225 do not agree on the exact general-area percentage (95% is
 * commonly cited for IFC 510; NFPA 72 sources are commonly cited at 90%), and any given AHJ may
 * amend either. Shipping one hardcoded number as "the" requirement would be a wrong figure landing
 * in a life-safety compliance document -- the same class of mistake this project has been careful
 * to avoid everywhere else (the RSRP sign, "overlap: 0.0%" never actually measured, etc.).
 *
 * The dBm floor defaults match the one figure every source agrees on: -95 dBm downlink.
 */
data class PublicSafetyThresholds(
    val generalMinDbm: Int = -95,
    val generalPct: Int = 95,
    val criticalMinDbm: Int = -95,
    val criticalPct: Int = 99,
) {
    fun minDbmFor(areaClass: ErrcsAreaClass): Int = when (areaClass) {
        ErrcsAreaClass.GENERAL -> generalMinDbm
        ErrcsAreaClass.CRITICAL -> criticalMinDbm
    }

    fun requiredPctFor(areaClass: ErrcsAreaClass): Int = when (areaClass) {
        ErrcsAreaClass.GENERAL -> generalPct
        ErrcsAreaClass.CRITICAL -> criticalPct
    }
}

/**
 * One manually entered grid-point reading (Track A). The operator's own equipment did the actual
 * measurement -- [systemLabel] is what they say they tested, typed by hand, and this app has no
 * way to verify it against the reading. A report built from this data says so explicitly.
 */
data class ErrcsGridPoint(
    val id: String,
    val floorplanId: String,
    val xNorm: Float,
    val yNorm: Float,
    val floor: String? = null,
    val areaClass: ErrcsAreaClass,
    val signalDbm: Double,
    /** e.g. "Fire Dept VHF", "PD 800 MHz P25" -- free text, operator-typed, unverified. */
    val systemLabel: String? = null,
    val note: String? = null,
    val recordedAtUtcMillis: Long,
) {
    init {
        require(xNorm in 0f..1f && yNorm in 0f..1f) {
            "floorplan coordinates must be normalised to 0..1, got ($xNorm, $yNorm)"
        }
    }

    fun passes(thresholds: PublicSafetyThresholds): Boolean = signalDbm >= thresholds.minDbmFor(areaClass)
}

/** Compliance for one [ErrcsAreaClass] within a set of [ErrcsGridPoint]s -- see
 *  [errcsCompliance]. */
data class ErrcsAreaCompliance(
    val areaClass: ErrcsAreaClass,
    val pointCount: Int,
    val passingCount: Int,
    val requiredPct: Int,
) {
    /** Null, not 0.0, when there is nothing to divide -- an area class with zero points tested
     *  has no compliance figure, and printing 0% would read as a measured failure. */
    val actualPct: Double? = if (pointCount == 0) null else 100.0 * passingCount / pointCount
    val meetsRequirement: Boolean get() = actualPct != null && actualPct >= requiredPct
}

/**
 * The discrete per-point compliance computation Track A needs. Deliberately not
 * `SessionStats.breakdown()`: that engine computes median/p10/worst-style statistics across many
 * continuous per-second samples, which don't apply to one manually entered reading per grid
 * point. Per area class the question is simply: what fraction of tested points met their
 * threshold.
 */
fun errcsCompliance(
    points: List<ErrcsGridPoint>,
    thresholds: PublicSafetyThresholds,
): List<ErrcsAreaCompliance> = ErrcsAreaClass.entries.map { areaClass ->
    val inClass = points.filter { it.areaClass == areaClass }
    ErrcsAreaCompliance(
        areaClass = areaClass,
        pointCount = inClass.size,
        passingCount = inClass.count { it.passes(thresholds) },
        requiredPct = thresholds.requiredPctFor(areaClass),
    )
}
