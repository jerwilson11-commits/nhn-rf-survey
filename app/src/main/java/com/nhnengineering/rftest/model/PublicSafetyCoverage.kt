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

/**
 * One square of the grid laid over a [CoverageArea].
 *
 * Square-centric rather than reading-centric: the square has a fixed location, an [areaClass] decided
 * by that location (critical when its centre falls in a designated critical area), and whether it is
 * [testable] (centre inside the coverage polygon — squares over dead space / outside the footprint are
 * not). This is the NFPA grid unit: the floor is divided into squares and each one is graded, so a
 * square with no reading is an *untested square*, not an absent data point.
 */
data class ErrcsGridSquare(
    val row: Int,
    val col: Int,
    /** Normalised centre on the plan image. */
    val centerX: Float,
    val centerY: Float,
    val areaClass: ErrcsAreaClass,
    val testable: Boolean,
    val readings: List<ErrcsGridPoint>,
) {
    val tested: Boolean get() = readings.isNotEmpty()

    /** A square passes only when it was tested and every reading in it clears the floor for the
     *  square's class — worst-case, because one weak reading makes the square unreliable. */
    fun passes(thresholds: PublicSafetyThresholds): Boolean =
        tested && readings.all { it.passesAs(areaClass, thresholds) }
}

/**
 * Lay a `rows × cols` grid over the [coverage] area (its bounding box) and bucket [points] into
 * squares. Every square in the grid is returned (so the UI can draw the whole grid); use [testable]
 * to tell floor squares from dead-space ones. With the default empty coverage the grid covers the
 * whole plan and every square is testable — the original behaviour.
 */
fun errcsGridSquares(
    points: List<ErrcsGridPoint>,
    rows: Int,
    cols: Int,
    criticalAreas: List<ErrcsCriticalArea> = emptyList(),
    coverage: CoverageArea = CoverageArea.EMPTY,
): List<ErrcsGridSquare> {
    val rr = rows.coerceAtLeast(1)
    val cc = cols.coerceAtLeast(1)
    val b = coverage.bounds()
    val w = (b[2] - b[0]).coerceAtLeast(1e-6f)
    val h = (b[3] - b[1]).coerceAtLeast(1e-6f)

    val byCell = HashMap<Pair<Int, Int>, MutableList<ErrcsGridPoint>>()
    for (p in points) {
        if (!coverage.contains(p.xNorm, p.yNorm)) continue
        val col = (((p.xNorm - b[0]) / w) * cc).toInt().coerceIn(0, cc - 1)
        val row = (((p.yNorm - b[1]) / h) * rr).toInt().coerceIn(0, rr - 1)
        byCell.getOrPut(row to col) { mutableListOf() }.add(p)
    }

    val out = ArrayList<ErrcsGridSquare>(rr * cc)
    for (row in 0 until rr) for (col in 0 until cc) {
        val cx = b[0] + (col + 0.5f) / cc * w
        val cy = b[1] + (row + 0.5f) / rr * h
        val cls = if (isInCriticalArea(cx, cy, criticalAreas)) {
            ErrcsAreaClass.CRITICAL
        } else {
            ErrcsAreaClass.GENERAL
        }
        out += ErrcsGridSquare(
            row = row, col = col, centerX = cx, centerY = cy,
            areaClass = cls, testable = coverage.contains(cx, cy),
            readings = byCell[row to col] ?: emptyList(),
        )
    }
    return out
}

/**
 * Choose rows × cols so that at least [minInside] grid squares fall **inside** the coverage area.
 *
 * The NFPA ~20-grid minimum applies to the floor, not the bounding rectangle, so a bounding-box grid
 * over an irregular footprint under-samples (e.g. a 4×5 grid yielding only 13 inside). This grows the
 * grid until enough cells land inside the polygon, keeping cells roughly square via [imageAspect] (the
 * plan image's width/height). Both dimensions are clamped to 1..[maxDim]; if even maxDim×maxDim cannot
 * reach the target (a sliver polygon), the densest grid tried is returned. With no area defined it
 * returns the default grid, leaving the whole-page behaviour unchanged.
 */
fun fitErrcsGrid(
    coverage: CoverageArea,
    imageAspect: Float,
    minInside: Int = 20,
    maxDim: Int = 20,
): Pair<Int, Int> {
    if (!coverage.isDefined) return ERRCS_DEFAULT_GRID_ROWS to ERRCS_DEFAULT_GRID_COLS
    val b = coverage.bounds()
    val wn = (b[2] - b[0]).coerceAtLeast(1e-4f)
    val hn = (b[3] - b[1]).coerceAtLeast(1e-4f)
    // cols:rows ratio that makes cells ~square on the image.
    val bboxAspect = (wn / hn).toDouble() * imageAspect.coerceAtLeast(1e-4f)
    var best = 1 to 1
    var total = minInside
    repeat(200) {
        val cols = Math.round(Math.sqrt(total * bboxAspect)).toInt().coerceIn(1, maxDim)
        val rows = Math.round(Math.sqrt(total / bboxAspect)).toInt().coerceIn(1, maxDim)
        val inside = errcsGridSquares(emptyList(), rows, cols, emptyList(), coverage).count { it.testable }
        best = rows to cols
        if (inside >= minInside) return best
        if (rows >= maxDim && cols >= maxDim) return best
        total += maxOf(1, total / 8)
    }
    return best
}

/** Grid-method compliance for one [ErrcsAreaClass] — see [errcsGridCompliance]. */
data class ErrcsGridCellCompliance(
    val areaClass: ErrcsAreaClass,
    /** Testable squares of this class inside the coverage area (the denominator for the grid test). */
    val totalCells: Int,
    /** Of those, how many hold at least one reading. */
    val testedCells: Int,
    /** Of those, how many pass — graded over [totalCells], so an untested square is not a pass. */
    val passingCells: Int,
    val requiredPct: Int,
) {
    /** Null, not 0.0, when no square of this class exists in the area — printing 0% would read as a
     *  measured failure. Graded over all testable squares (an untested square counts against). */
    val actualPct: Double? = if (totalCells == 0) null else 100.0 * passingCells / totalCells

    /** How much of the required grid has actually been walked; drives the completeness indicator. */
    val testedPct: Double? = if (totalCells == 0) null else 100.0 * testedCells / totalCells
    val complete: Boolean get() = totalCells > 0 && testedCells == totalCells
    val meetsRequirement: Boolean get() = actualPct != null && actualPct >= requiredPct
}

/**
 * Grid-method compliance (the NFPA "20-square" approach): the floor — the [coverage] polygon, not the
 * whole PDF page — is divided into a `rows × cols` grid and each testable square is graded.
 *
 * A square *passes* only when it was tested and every reading in it passes (worst-case). Compliance
 * per area class is **passing squares over all testable squares** (`actualPct`) — so a square that was
 * never walked counts against, which is what makes a 20-grid result valid rather than "looks
 * compliant" — while `testedPct`/`complete` report how much of the grid has actually been covered.
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
    coverage: CoverageArea = CoverageArea.EMPTY,
): List<ErrcsGridCellCompliance> {
    val squares = errcsGridSquares(points, rows, cols, criticalAreas, coverage).filter { it.testable }
    return ErrcsAreaClass.entries.map { ac ->
        val inClass = squares.filter { it.areaClass == ac }
        ErrcsGridCellCompliance(
            areaClass = ac,
            totalCells = inClass.size,
            testedCells = inClass.count { it.tested },
            passingCells = inClass.count { it.passes(thresholds) },
            requiredPct = thresholds.requiredPctFor(ac),
        )
    }
}
