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
    /**
     * Minimum Delivered Audio Quality (TSB-88 scale, 1.0–5.0) a reading must meet, or null to not
     * grade DAQ at all. Null by default because DAQ is optional field data and most dBm-only surveys
     * do not record it — enabling it should be a deliberate choice, not a hidden extra gate that
     * silently fails points that never carried a DAQ value. The common public-safety objective is
     * DAQ 3.0 (ERRCS acceptance) or DAQ 3.4 (TSB-88 wide-area P25).
     */
    val minDaq: Double? = null,
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
    /**
     * Outbound / downlink (talk-out): the level the portable *receives* from the DAS/BDA at this
     * point. This is the primary ERRCS reading and is always required — every point has it.
     */
    val signalDbm: Double,
    /**
     * Inbound / uplink (talk-in): the portable's transmit as received back at the donor site or
     * console, read off the operator's equipment. Optional — quick surveys often record outbound
     * only. ERRCS requires *two-way* coverage, so when this is present the point passes only if
     * BOTH directions clear the dBm floor. A null here means "not measured", never "zero".
     */
    val inboundDbm: Double? = null,
    /**
     * Delivered Audio Quality (TSB-88 scale, 1.0–5.0) read off the operator's service monitor.
     * Optional, and only graded when [PublicSafetyThresholds.minDaq] is set — recording it without
     * enabling the DAQ threshold keeps it as documentation without changing pass/fail.
     */
    val daq: Double? = null,
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

    /**
     * Whether this reading meets the thresholds for its own [areaClass]. See [passesAs] — this is
     * the common case where the point is graded as the class it was tagged with.
     */
    fun passes(thresholds: PublicSafetyThresholds): Boolean = passesAs(areaClass, thresholds)

    /**
     * Whether this reading meets the thresholds when graded as [gradedAs]. Outbound must clear the
     * dBm floor; inbound must too when it was measured; and DAQ must clear
     * [PublicSafetyThresholds.minDaq] when both that threshold and a DAQ reading are present. A
     * missing optional reading never fails the point on its own — only a measured value that falls
     * short does. The class is passed in (rather than always using [areaClass]) so a point sitting
     * in an AHJ-designated critical area is judged against the critical dBm floor even if it was
     * tagged general — see [effectiveAreaClass].
     */
    fun passesAs(gradedAs: ErrcsAreaClass, thresholds: PublicSafetyThresholds): Boolean {
        val floorDbm = thresholds.minDbmFor(gradedAs)
        if (signalDbm < floorDbm) return false
        if (inboundDbm != null && inboundDbm < floorDbm) return false
        val minDaq = thresholds.minDaq
        if (minDaq != null && daq != null && daq < minDaq) return false
        return true
    }
}

/**
 * A normalised rectangle (0..1 in both axes) marking an AHJ-designated **critical area** on a
 * floorplan.
 *
 * Stored as a *region*, not a grid cell index, deliberately: the AHJ designates critical areas by
 * location (a stairwell, a fire pump room), and that designation must not move or vanish when the
 * tester changes the sampling grid's rows/cols. A square of the display grid counts as critical when
 * its centre falls inside any of these regions.
 */
data class ErrcsCriticalArea(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    fun contains(xNorm: Float, yNorm: Float): Boolean =
        xNorm in minOf(x0, x1)..maxOf(x0, x1) && yNorm in minOf(y0, y1)..maxOf(y0, y1)
}

/** Whether a normalised point falls inside any designated critical area. */
fun isInCriticalArea(xNorm: Float, yNorm: Float, areas: List<ErrcsCriticalArea>): Boolean =
    areas.any { it.contains(xNorm, yNorm) }

/**
 * The class a reading is graded as: CRITICAL when it sits inside any AHJ-designated critical area,
 * otherwise its own tagged [ErrcsGridPoint.areaClass].
 *
 * Designated areas are **authoritative and never downgraded** — a reading in a critical area is
 * critical no matter what it was tagged, because the AHJ said that space is critical. The per-point
 * tag can still raise a reading to critical *outside* a designated area (the override), which is why
 * this is an OR of the two, not a replacement.
 */
fun ErrcsGridPoint.effectiveAreaClass(areas: List<ErrcsCriticalArea>): ErrcsAreaClass =
    if (areaClass == ErrcsAreaClass.CRITICAL || isInCriticalArea(xNorm, yNorm, areas)) {
        ErrcsAreaClass.CRITICAL
    } else {
        ErrcsAreaClass.GENERAL
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
    criticalAreas: List<ErrcsCriticalArea> = emptyList(),
): List<ErrcsAreaCompliance> = ErrcsAreaClass.entries.map { areaClass ->
    val inClass = points.filter { it.effectiveAreaClass(criticalAreas) == areaClass }
    ErrcsAreaCompliance(
        areaClass = areaClass,
        pointCount = inClass.size,
        passingCount = inClass.count { it.passesAs(areaClass, thresholds) },
        requiredPct = thresholds.requiredPctFor(areaClass),
    )
}

// ---------------------------------------------------------------------------
// Grid method (NFPA "divide the floor into ~20 equal squares")
// ---------------------------------------------------------------------------

/** The default grid: 5 columns × 4 rows = 20 squares, the figure NFPA grid testing is built around. */
const val ERRCS_DEFAULT_GRID_COLS = 5
const val ERRCS_DEFAULT_GRID_ROWS = 4

/**
 * The (row, col) grid cell a normalised point falls in, for a `rows × cols` overlay.
 *
 * Clamped so a point exactly on the far edge (norm == 1f) lands in the last cell rather than one
 * past it, and so a degenerate 0-dimension can never produce a negative index. Shared by the
 * on-screen overlay and the compliance rollup so both agree on which square a reading belongs to.
 */
fun errcsGridCell(xNorm: Float, yNorm: Float, rows: Int, cols: Int): Pair<Int, Int> {
    val rr = rows.coerceAtLeast(1)
    val cc = cols.coerceAtLeast(1)
    val row = (yNorm * rr).toInt().coerceIn(0, rr - 1)
    val col = (xNorm * cc).toInt().coerceIn(0, cc - 1)
    return row to col
}

/** Grid-method compliance for one [ErrcsAreaClass] — see [errcsGridCompliance]. */
data class ErrcsGridCellCompliance(
    val areaClass: ErrcsAreaClass,
    val testedCells: Int,
    val passingCells: Int,
    val requiredPct: Int,
) {
    /** Null, not 0.0, when no cell of this class was tested — the same reasoning as
     *  [ErrcsAreaCompliance.actualPct]: printing 0% would read as a measured failure. */
    val actualPct: Double? = if (testedCells == 0) null else 100.0 * passingCells / testedCells
    val meetsRequirement: Boolean get() = actualPct != null && actualPct >= requiredPct
}

/**
 * Grid-method compliance (the NFPA "20-square" approach): the floor is divided into a `rows × cols`
 * grid and each occupied square is graded, rather than each individual reading.
 *
 * A square is *tested* when it holds at least one reading of that area class, and *passes* only when
 * every reading in it passes [ErrcsGridPoint.passes] — worst-case within the square, because a
 * square with even one failing reading is not one a responder can rely on. Compliance per area class
 * is passing squares over tested squares, against the same required percentage as the point method.
 *
 * Deliberately separate from [errcsCompliance]: that grades every reading, this grades squares. An
 * AHJ that specifies the grid method wants the latter; both are reported so neither is assumed.
 */
fun errcsGridCompliance(
    points: List<ErrcsGridPoint>,
    rows: Int,
    cols: Int,
    thresholds: PublicSafetyThresholds,
    criticalAreas: List<ErrcsCriticalArea> = emptyList(),
): List<ErrcsGridCellCompliance> = ErrcsAreaClass.entries.map { areaClass ->
    val byCell = points.filter { it.effectiveAreaClass(criticalAreas) == areaClass }
        .groupBy { errcsGridCell(it.xNorm, it.yNorm, rows, cols) }
    ErrcsGridCellCompliance(
        areaClass = areaClass,
        testedCells = byCell.size,
        passingCells = byCell.count { (_, cellPoints) ->
            cellPoints.all { it.passesAs(areaClass, thresholds) }
        },
        requiredPct = thresholds.requiredPctFor(areaClass),
    )
}
