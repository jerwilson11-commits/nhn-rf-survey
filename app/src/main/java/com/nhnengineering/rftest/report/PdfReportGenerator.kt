package com.nhnengineering.rftest.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import com.nhnengineering.rftest.cellular.BandLock
import com.nhnengineering.rftest.cellular.TechnologyLock
import com.nhnengineering.rftest.live.TileProxy
import com.nhnengineering.rftest.map.GpsOutlierFilter
import com.nhnengineering.rftest.map.Mercator
import com.nhnengineering.rftest.session.FloorplanStore
import com.nhnengineering.rftest.session.SessionSummary
import com.nhnengineering.rftest.session.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Client-facing acceptance report.
 *
 * Uses the platform `PdfDocument` rather than a PDF library — the report is text, a table and one
 * plot, and pulling in a rendering dependency for that would be disproportionate.
 *
 * The last page is a methodology section stating what the instrument does **not** know: which
 * figures are approximate, how stale the neighbour data is, and whether the cellular collector has
 * been validated. A report that hides its limitations is worth less than one that states them,
 * because the first thing a competent reviewer does is look for them.
 */
object PdfReportGenerator {

    private const val PAGE_W = 595   // A4 at 72 dpi
    private const val PAGE_H = 842
    private const val MARGIN = 46f
    private const val LINE = 15f

    private val DATE = SimpleDateFormat("d MMMM yyyy 'at' HH:mm", Locale.getDefault())

    private class Ctx(val doc: PdfDocument) {
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        var pageNo = 0

        val title = paint(19f, bold = true)
        val h2 = paint(13f, bold = true)
        val body = paint(10f)
        val small = paint(8.5f, color = Color.rgb(90, 90, 90))
        val mono = paint(9f, mono = true)
        val monoBold = paint(9f, mono = true, bold = true)

        fun paint(size: Float, bold: Boolean = false, mono: Boolean = false, color: Int = Color.BLACK) =
            Paint().apply {
                isAntiAlias = true
                textSize = size
                this.color = color
                typeface = Typeface.create(
                    if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF,
                    if (bold) Typeface.BOLD else Typeface.NORMAL,
                )
            }

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            page = p
            canvas = p.canvas
            y = MARGIN
        }

        /** Starts a new page when the next block would not fit, so nothing is silently clipped. */
        fun ensure(space: Float) {
            if (y + space > PAGE_H - MARGIN - 20f) newPage()
        }

        fun text(s: String, p: Paint, indent: Float = 0f) {
            ensure(LINE)
            canvas?.drawText(s, MARGIN + indent, y, p)
            y += LINE
        }

        fun gap(h: Float = LINE / 2) { y += h }

        fun rule() {
            ensure(8f)
            val c = canvas ?: return
            val paint = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 0.7f }
            c.drawLine(MARGIN, y - 4f, PAGE_W - MARGIN, y - 4f, paint)
            y += 6f
        }

        /** Two-column key/value, value right-aligned to the margin. */
        fun kv(k: String, v: String) {
            ensure(LINE)
            val c = canvas ?: return
            c.drawText(k, MARGIN, y, body)
            val w = mono.measureText(v)
            c.drawText(v, PAGE_W - MARGIN - w, y, mono)
            y += LINE
        }

        /**
         * A paragraph, wrapped to the page.
         *
         * Wraps on measured width rather than a character count, because a character count is a
         * guess about a proportional font, and that guess is what pushed explanatory text off the
         * right-hand edge of the first report this generator produced. `measureText` knows.
         */
        fun para(sentence: String, p: Paint = small, indent: Float = 0f) {
            val width = PAGE_W - 2 * MARGIN - indent
            val line = StringBuilder()
            for (word in sentence.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) > width && line.isNotEmpty()) {
                    text(line.toString(), p, indent)
                    line.setLength(0)
                    line.append(word)
                } else {
                    line.setLength(0)
                    line.append(candidate)
                }
            }
            if (line.isNotEmpty()) text(line.toString(), p, indent)
        }

        /**
         * Bordered two-column title block.
         *
         * A survey report is a document that gets emailed onward, printed, and quoted back months
         * later. It has to say on its own face what site it is, when it was walked, and with what
         * — the competitor deck that prompted this work shipped with `Site Name- Carrier/Report
         * Type` and `DATE` still in the template.
         */
        fun titleBlock(rows: List<Pair<String, String>>) {
            val canvas = canvas ?: return
            val h = rows.size * LINE + 12f
            ensure(h + 8f)
            val top = y
            val box = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.8f
                color = Color.rgb(120, 120, 120)
                isAntiAlias = true
            }
            canvas.drawRect(MARGIN, top, PAGE_W - MARGIN, top + h, box)
            y = top + 10f + LINE * 0.72f
            for ((k, v) in rows) {
                canvas.drawText(k, MARGIN + 10f, y, small)
                canvas.drawText(v, MARGIN + 118f, y, body)
                y += LINE
            }
            y = top + h + LINE * 0.6f
        }

        fun finish() { page?.let { doc.finishPage(it) }; page = null; canvas = null }
    }

    suspend fun generate(
        context: Context,
        summary: SessionSummary,
        points: List<TrackPoint>,
        report: SessionStats.Report,
        out: File,
        profiles: List<com.nhnengineering.rftest.profile.TddProfile> = emptyList(),
        /** Track A (manual LMR entry) grid points, unfiltered -- matched to this session below
         *  by [SessionSummary.floorplanIds], the same way [profiles] is matched internally rather
         *  than pre-filtered by the caller. */
        errcsGridPoints: List<com.nhnengineering.rftest.model.ErrcsGridPoint> = emptyList(),
        publicSafetyThresholds: com.nhnengineering.rftest.model.PublicSafetyThresholds =
            com.nhnengineering.rftest.model.PublicSafetyThresholds(),
        /** Per-floorplan grid dimensions (floorplanId → rows to cols) for the NFPA grid-method
         *  compliance table. A floorplan absent here uses the default 4×5 grid. */
        errcsGridConfigs: Map<String, Pair<Int, Int>> = emptyMap(),
        /** Per-floorplan AHJ-designated critical areas; readings inside them are graded critical. */
        errcsCriticalAreas: Map<String, List<com.nhnengineering.rftest.model.ErrcsCriticalArea>> =
            emptyMap(),
        /** Per-floorplan traced coverage-area polygons — the grid is laid inside them and cellular
         *  coverage is reported against them. */
        errcsCoverageAreas: Map<String, com.nhnengineering.rftest.model.CoverageArea> = emptyMap(),
        /** Per-floorplan solved georeferences, for floor/building square footage and the 80-ft
         *  grid-dimension check. A floor absent here is simply not georeferenced. */
        errcsGeoRefs: Map<String, com.nhnengineering.rftest.map.GeoReference> = emptyMap(),
    ): File = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val c = Ctx(doc)
        c.newPage()

        // ---- Title block --------------------------------------------------
        c.text("RF Coverage Survey Report", c.title)
        c.gap(6f)
        c.titleBlock(
            listOf(
                "Site / session" to summary.displayName,
                "Survey date" to (summary.startedAtUtcMillis
                    ?.let { DATE.format(Date(it)) } ?: "not recorded"),
                "Duration" to formatDuration(summary.durationMs),
                "Measurement" to report.kpi.label,
                "Instrument" to
                    "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
                "Prepared by" to "NHN Engineering & Consultants",
                "Report generated" to DATE.format(Date(System.currentTimeMillis())),
            ),
        )
        c.rule()

        // ---- Summary ------------------------------------------------------
        c.text("Session summary", c.h2)
        c.kv("Samples recorded", summary.rowCount.toString())
        c.kv("Duration", formatDuration(summary.durationMs))
        c.kv("GPS-located samples", summary.pointCount.toString())
        if (summary.indoorPointCount > 0) {
            c.kv("Floorplan-located samples", summary.indoorPointCount.toString())
            if (summary.waypoints.isNotEmpty()) {
                c.kv("Waypoints", summary.waypoints.joinToString(", ").take(60))
            }
        }
        c.gap(); c.rule()

        // ---- KPI ----------------------------------------------------------
        c.text("${report.kpi.label} — measured", c.h2)
        val s = report.stats
        c.kv("Samples with a measurement", "${s.samples} of ${s.samples + s.missing}")
        c.kv("Best", s.max?.let { "$it dBm" } ?: "—")
        c.kv("90th percentile", s.p90?.let { "$it dBm" } ?: "—")
        c.kv("Median", s.median?.let { "$it dBm" } ?: "—")
        c.kv("10th percentile", s.p10?.let { "$it dBm" } ?: "—")
        c.kv("Worst", s.min?.let { "$it dBm" } ?: "—")
        c.kv("Mean", s.mean?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—")
        c.gap()
        c.para(
            "Percentiles are reported alongside the mean because a mean conceals the tail, and " +
                "coverage is judged on the worst areas rather than the average one.",
        )
        c.gap(); c.rule()

        // ---- Compliance ---------------------------------------------------
        c.text("Threshold compliance", c.h2)
        c.kv("Pass/fail threshold", "${report.thresholdDbm} dBm")
        c.kv("Samples meeting threshold", "${report.measured - report.failing} of ${report.measured}")
        c.kv("Compliance", String.format(Locale.US, "%.1f %%", report.compliancePct))
        c.kv("Coverage holes", report.holes.size.toString())
        c.gap()
        for ((label, count) in report.bucketCounts) {
            val pct = if (report.measured > 0) 100.0 * count / report.measured else 0.0
            c.kv(label, String.format(Locale.US, "%d  (%.1f %%)", count, pct))
        }
        c.gap()

        // The site identity, which a PCI alone does not give: a PCI is unique only within a
        // carrier and is reused across a network, so it cannot be looked up. WalkTest prints a
        // gNodeB ID and we printed a 36-bit NCI nobody can use. The split is stated as an
        // assumption, because it is one -- TS 38.401 lets the operator choose the boundary and
        // nothing broadcast to a handset says where it is.
        val nciCounts = points.mapNotNull { it.servingNci }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        if (nciCounts.isNotEmpty()) {
            c.text("Serving site", c.h2)
            c.text(
                String.format(Locale.US, "%-14s %-12s %-8s %9s", "NCI", "gNodeB", "Cell", "Samples"),
                c.monoBold,
            )
            for ((nci, count) in nciCounts.take(10)) {
                val split = com.nhnengineering.rftest.cellular.NrCellId.split(nci)
                c.text(
                    String.format(
                        Locale.US, "%-14d %-12s %-8s %9d",
                        nci,
                        split?.gnbId?.toString() ?: "--",
                        split?.cellId?.toString() ?: "--",
                        count,
                    ),
                    c.mono,
                )
            }
            c.para(
                "gNodeB and cell are derived from the NCI by splitting it at " +
                    "${com.nhnengineering.rftest.cellular.NrCellId.DEFAULT_GNB_ID_BITS} bits, " +
                    "which is the common configuration but is set by the operator and is not " +
                    "broadcast. If the operator uses a different length the two columns change " +
                    "and the NCI beside them does not -- that column is measured, these are not.",
            )
            c.gap()
        }

        c.gap(); c.rule()

        // ---- Distributions ------------------------------------------------
        //
        // Percentiles say where the middle of a survey sits; a distribution says how much of the
        // venue is in trouble, which is the number an acceptance argument actually turns on. Added
        // after reading a competitor's report that devotes a page to this per KPI -- the tables
        // were the most useful thing in thirty pages.
        c.ensure(200f)
        c.text("Distributions", c.h2)
        c.para(
            "How the survey's samples divide across value ranges, for each KPI that was measured. " +
                "Percentages are of samples that carried a reading; samples with no reading are " +
                "counted on their own line rather than folded into the worst range, because a " +
                "value the handset could not measure and a genuinely bad value are different " +
                "events and only one of them is the network's fault.",
        )

        val distributions = listOfNotNull(
            SessionStats.distribution(
                "SS-RSRP (dBm)", points, listOf(-85, -90, -95, -100, -105, -110, -115),
            ) { it.rsrpDbm }.takeIf { it.measured > 0 },
            SessionStats.distribution(
                "SS-SINR (dB)", points, listOf(20, 13, 10, 7, 5, 2, 0),
            ) { it.sinrDb }.takeIf { it.measured > 0 },
            SessionStats.distribution(
                "SS-RSRQ (dB)", points, listOf(-10, -12, -14, -16, -18),
            ) { it.rsrqDb }.takeIf { it.measured > 0 },
            SessionStats.distribution(
                "Wi-Fi RSSI (dBm)", points, listOf(-50, -60, -67, -75, -85),
            ) { it.rssiDbm }.takeIf { it.measured > 0 },
        )

        for (d in distributions) {
            c.ensure(LINE * (d.bins.size + 5))
            c.gap()
            c.text(d.metric, c.monoBold)
            c.text(
                String.format(Locale.US, "%-20s %9s %9s", "Range", "Samples", "Share"),
                c.monoBold,
            )
            for (b in d.bins) {
                c.text(
                    String.format(Locale.US, "%-20s %9d %8.1f%%", b.label, b.samples, b.pct),
                    c.mono,
                )
            }
            if (d.noReading > 0) {
                c.text(
                    String.format(
                        Locale.US, "%-20s %9d %8s", "no reading", d.noReading, "--",
                    ),
                    c.mono,
                )
            }
        }
        if (distributions.isEmpty()) {
            c.para("No KPI in this session carried enough readings to distribute.")
        }
        c.gap(); c.rule()

        // ---- Per-band -----------------------------------------------------
        val bands = SessionStats.breakdown(points, { SessionStats.bandOf(it, report.kpi) }, report.kpi, report.thresholdDbm)
        if (bands.groups.size > 1 || bands.unlabelled > 0) {
            c.ensure(140f)
            c.text("By band", c.h2)
            c.para(
                "The same statistics as above, computed separately for each band the survey saw. " +
                    "A single site-wide figure averages a band that covers the venue with one " +
                    "that appears in a corridor, and hides the difference.",
            )
            c.gap()
            groupTable(c, bands, report.thresholdDbm)
            c.gap(); c.rule()
        }

        // ---- Throughput per band and technology ---------------------------
        val throughput = SessionStats.throughputBreakdown(points) {
            SessionStats.bandOf(it, SessionStats.Kpi.CELL_RSRP)
        }
        if (throughput.anyMeasured) {
            c.ensure(170f)
            c.text("Throughput by band and technology", c.h2)
            c.para(
                "What each carrier actually delivered, split by the band it ran on and the " +
                    "technology running on that band. A band carrying both LTE and 5G is two " +
                    "different services sharing one label, and a single figure for the pair " +
                    "describes neither of them.",
            )
            c.para(
                "This application does not set a band or technology lock and does not claim to. " +
                    "Where a lock was in force it was applied externally and declared to the app, " +
                    "and the section below checks that declaration against what the walk actually " +
                    "saw. Where no lock was in force these rows describe whatever the network " +
                    "chose to serve, which is a different measurement and a weaker one for " +
                    "comparing sectors.",
            )
            c.gap()
            throughputTable(c, throughput)
            c.gap(); c.rule()
        }

        // ---- Per-floor ----------------------------------------------------
        val floors = SessionStats.breakdown(points, { it.floor }, report.kpi, report.thresholdDbm)
        if (floors.groups.isNotEmpty()) {
            c.ensure(140f)
            c.text("By floor", c.h2)
            c.para(
                "Floors as the operator recorded them while walking. This is the axis a " +
                    "multi-storey result is argued along: a site-wide compliance figure can pass " +
                    "comfortably while one floor fails outright, and only this table shows it.",
            )
            c.gap()
            groupTable(c, floors, report.thresholdDbm)
            c.gap(); c.rule()
        }

        // ---- Per-area -----------------------------------------------------
        val areas = SessionStats.breakdown(points, { it.waypoint }, report.kpi, report.thresholdDbm)
        if (areas.groups.isNotEmpty()) {
            c.ensure(140f)
            c.text("By area", c.h2)
            c.para(
                "Grouped by the waypoints marked during the walk. Only samples recorded while a " +
                    "waypoint was set appear here; the remainder are counted as unlabelled rather " +
                    "than assigned to the nearest one.",
            )
            c.gap()
            groupTable(c, areas, report.thresholdDbm)
            c.gap(); c.rule()
        }

        // ---- Building entry / wall loss -------------------------------------
        //
        // Reuses the same waypoint marks as "By area" above, but scored per doorway crossing
        // rather than as a site-wide average -- see SessionStats.buildingCrossings.
        val crossings = SessionStats.buildingCrossings(points, report.kpi)
        if (crossings.isNotEmpty()) {
            c.ensure(160f)
            c.text("Building entry / wall loss", c.h2)
            c.para(
                "Estimated from the Indoor/Outdoor marks set live during the walk: the serving-cell " +
                    "RSRP immediately before and after each doorway crossing. Only crossings where " +
                    "the serving cell did not change are scored -- a crossing where the cell also " +
                    "changed is listed but not attributed to the structure, since the delta would " +
                    "then reflect a different cell's own baseline as much as the wall.",
            )
            c.gap()
            c.text(
                String.format(
                    Locale.US, "%-6s %-9s %-6s %-8s %-9s %-8s %-8s %s",
                    "#", "Time", "Dir", "PCI", "Band", "Out dBm", "In dBm", "Loss",
                ),
                c.monoBold,
            )
            val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            crossings.forEachIndexed { i, cr ->
                val t = timeFmt.format(java.util.Date(cr.outdoorTimestampUtcMillis))
                val dir = if (cr.direction == SessionStats.CrossingDirection.ENTERING) "IN" else "OUT"
                val lossText = cr.lossDb?.let { "${it} dB" } ?: "cell changed"
                c.text(
                    String.format(
                        Locale.US, "%-6d %-9s %-6s %-8s %-9s %-8d %-8d %s",
                        i + 1, t, dir, cr.pci?.toString() ?: "—", (cr.band ?: "—").take(9),
                        cr.outdoorDbm, cr.indoorDbm, lossText,
                    ),
                    c.mono,
                )
            }
            c.gap()
            val measurable = crossings.filter { it.sameCell }
            when {
                measurable.size >= SessionStats.MIN_CROSSINGS_FOR_MEDIAN -> {
                    val median = SessionStats.medianLossDb(crossings)
                    c.text(
                        "Median estimated building loss: $median dB across ${measurable.size} " +
                            "measurable crossing(s) (of ${crossings.size} total).",
                        c.paint(10f, bold = true),
                    )
                }
                measurable.size == 1 -> {
                    c.text(
                        "Single measurable crossing: ${measurable.first().lossDb} dB. Not enough " +
                            "crossings for a median -- treat as one data point, not a building average.",
                        c.paint(10f, bold = true),
                    )
                }
                else -> {
                    c.text(
                        "No crossing kept the same serving cell on both sides, so no loss figure " +
                            "could be attributed to the structure alone.",
                        c.body,
                    )
                }
            }
            c.gap(); c.rule()
        }

        // ---- Cell lock compliance -------------------------------------------
        //
        // Proves whether a watched external cell lock actually held across the walk. The app did
        // not set the lock; this verifies the outcome from serving-cell telemetry -- see
        // SessionStats.lockWatch.
        val lockWatch = SessionStats.lockWatch(points)
        if (lockWatch != null) {
            c.ensure(150f)
            c.text("Cell lock compliance", c.h2)
            c.para(
                "The handset was locked to PCI ${lockWatch.targetPci} on ARFCN " +
                    "${lockWatch.targetArfcn} (set in an external tool, not by this app). Each sample's " +
                    "serving cell was checked against that target; a sample counts as on-target only " +
                    "when both its PCI and ARFCN match. Samples with no serving cell to judge are " +
                    "excluded rather than counted as failures.",
            )
            c.gap()
            c.text(
                "Lock held on ${lockWatch.onTarget} of ${lockWatch.evaluated} evaluable samples " +
                    "(${String.format(Locale.US, "%.1f", lockWatch.compliancePct)}%).",
                c.paint(10f, bold = true),
            )
            if (lockWatch.offTargetRuns.isEmpty()) {
                c.gap(4f)
                c.text(
                    if (lockWatch.compliancePct >= 100.0) {
                        "No off-target stretch: the phone stayed on the locked cell throughout."
                    } else {
                        "Off-target samples were scattered, with no sustained run of " +
                            "${SessionStats.MIN_OFF_TARGET_RUN}+ -- momentary reselection rather than the lock failing."
                    },
                    c.body,
                )
            } else {
                c.gap()
                c.text(
                    "Sustained off-target stretches (${SessionStats.MIN_OFF_TARGET_RUN}+ samples), worst first:",
                    c.body,
                )
                c.gap(4f)
                c.text(
                    String.format(Locale.US, "%-6s %-9s %-10s %-8s %s", "#", "Samples", "Saw PCI", "Secs", "Location"),
                    c.monoBold,
                )
                lockWatch.offTargetRuns.take(20).forEachIndexed { i, r ->
                    val where = if (r.lat != null && r.lon != null) {
                        String.format(Locale.US, "%.5f,%.5f", r.lat, r.lon)
                    } else "not located"
                    c.text(
                        String.format(
                            Locale.US, "%-6d %-9d %-10s %-8s %s",
                            i + 1, r.samples, r.sawPci?.toString() ?: "—",
                            r.durationS?.let { String.format(Locale.US, "%.0f", it) } ?: "—",
                            where.take(32),
                        ),
                        c.mono,
                    )
                }
            }
            c.gap(); c.rule()
        }

        // ---- Cellular coverage within the defined coverage area --------------
        //
        // When the operator has traced a coverage-area polygon on a floorplan, report the ordinary
        // cellular/Wi-Fi coverage restricted to samples that fall inside that outline -- "x dBm met
        // in x% of the defined coverage area" -- so dead space and samples outside the footprint do
        // not dilute the figure. Rendered only when an area is defined and has samples inside it.
        run {
            val defined = errcsCoverageAreas.filterValues { it.isDefined }
            if (defined.isNotEmpty()) {
                val kpiIsCell = report.kpi == SessionStats.Kpi.CELL_RSRP
                val vals = points
                    .filter { p ->
                        p.hasIndoorPosition &&
                            defined[p.floorplanId]?.contains(p.floorplanX!!, p.floorplanY!!) == true
                    }
                    .mapNotNull { if (kpiIsCell) it.rsrpDbm else it.rssiDbm }
                if (vals.isNotEmpty()) {
                    val meeting = vals.count { it >= report.thresholdDbm }
                    val pct = 100.0 * meeting / vals.size
                    c.ensure(70f)
                    c.text("Coverage area (defined floor outline)", c.h2)
                    c.para(
                        String.format(
                            Locale.US,
                            "Within the traced coverage area, %d dBm (%s) was met in %.1f%% of " +
                                "samples (%d of %d). Samples outside the outline — and the PDF page " +
                                "margins — are excluded.",
                            report.thresholdDbm,
                            if (kpiIsCell) "serving RSRP" else "Wi-Fi RSSI",
                            pct, meeting, vals.size,
                        ),
                    )
                    // Floor/building square footage from the georeference, when available.
                    val floorFt2 = defined.mapNotNull { (id, area) ->
                        errcsGeoRefs[id]?.let { gr ->
                            com.nhnengineering.rftest.model.squareMetresToFeet(
                                com.nhnengineering.rftest.model.coverageAreaSquareMetres(
                                    area, gr.widthPx, gr.heightPx, gr.metresPerPixel,
                                ),
                            )
                        }
                    }.filter { it > 0.0 }
                    if (floorFt2.isNotEmpty()) {
                        c.para(
                            if (floorFt2.size == 1) {
                                String.format(Locale.US, "Floor area (georeferenced): %,.0f ft².", floorFt2[0])
                            } else {
                                String.format(
                                    Locale.US,
                                    "Floor areas (georeferenced): %s = %,.0f ft² across %d floors.",
                                    floorFt2.joinToString(" + ") { String.format(Locale.US, "%,.0f", it) },
                                    floorFt2.sum(), floorFt2.size,
                                )
                            },
                        )
                    }
                    c.gap(); c.rule()
                }
            }
        }

        // ---- Public Safety Coverage -----------------------------------------
        //
        // Track A (manual LMR entry) and Track B (FirstNet Band 14/n14, auto-measured) never
        // merge into one compliance figure -- see model/PublicSafetyCoverage.kt for why. A
        // session with neither kind of data present renders no section at all, rather than a
        // table of zeros that would read as "tested and found compliant."
        run {
            val trackA = errcsGridPoints.filter { it.floorplanId in summary.floorplanIds }
            // Point compliance aggregated per floorplan so each floor's own critical-area designation
            // applies (a point's coordinates only mean anything within its own plan).
            val trackAResults = if (trackA.isNotEmpty()) {
                val agg = LinkedHashMap<com.nhnengineering.rftest.model.ErrcsAreaClass, IntArray>()
                trackA.groupBy { it.floorplanId }.forEach { (planId, planPoints) ->
                    com.nhnengineering.rftest.model.errcsCompliance(
                        planPoints, publicSafetyThresholds, errcsCriticalAreas[planId] ?: emptyList(),
                    ).forEach { r ->
                        val acc = agg.getOrPut(r.areaClass) { IntArray(2) }
                        acc[0] += r.pointCount
                        acc[1] += r.passingCount
                    }
                }
                com.nhnengineering.rftest.model.ErrcsAreaClass.entries.map { ac ->
                    val acc = agg[ac] ?: IntArray(2)
                    com.nhnengineering.rftest.model.ErrcsAreaCompliance(
                        areaClass = ac,
                        pointCount = acc[0],
                        passingCount = acc[1],
                        requiredPct = publicSafetyThresholds.requiredPctFor(ac),
                    )
                }
            } else {
                emptyList()
            }
            val trackBResults = SessionStats.publicSafetyCoverage(points, publicSafetyThresholds)
            val trackBHasData = trackBResults.any { it.sampleCount > 0 || it.missing > 0 }
            val band14SamplesUnclassified = !trackBHasData &&
                points.any { SessionStats.isFirstNetBand14(it) }

            if (trackA.isNotEmpty() || trackBHasData || band14SamplesUnclassified) {
                c.ensure(160f)
                c.text("Public safety coverage", c.h2)
                c.para(
                    "Traditional ERRCS (Land Mobile Radio: VHF, UHF, or 700/800 MHz P25) cannot be " +
                        "measured by a phone -- it uses a different RF front end and protocol than " +
                        "this device's cellular or Wi-Fi radios entirely. The two tracks below are " +
                        "the two ways this app can contribute evidence to a public-safety coverage " +
                        "question, and they are never combined into one compliance figure.",
                )
                c.gap()

                if (trackA.isNotEmpty()) {
                    c.text("Track A — manually entered LMR readings", c.body)
                    c.para(
                        "Each reading was taken on the operator's own tuned signal meter and " +
                            "typed in by hand; this app did not measure or verify it.",
                        indent = 10f,
                    )
                    c.gap(4f)
                    c.text(
                        String.format(
                            Locale.US, "%-16s %8s %8s %7s %9s",
                            "Area class", "Points", "Passing", "Pass %", "Required",
                        ),
                        c.monoBold,
                    )
                    for (r in trackAResults) {
                        c.text(
                            String.format(
                                Locale.US, "%-16s %8d %8d %6s %8s%%",
                                r.areaClass.label, r.pointCount, r.passingCount,
                                r.actualPct?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                                r.requiredPct.toString(),
                            ),
                            c.mono,
                        )
                    }
                    val failingA = trackAResults.filter { it.pointCount > 0 && !it.meetsRequirement }
                    if (failingA.isNotEmpty()) {
                        c.para(
                            "Below requirement: " + failingA.joinToString(", ") { it.areaClass.label },
                        )
                    }

                    // Grid-method (NFPA "20-square") compliance, aggregated across this session's
                    // floorplans -- each floor graded on its own saved grid, the squares summed. A
                    // square passes only if every reading in it passes, so one weak reading fails the
                    // square. This is the figure an AHJ specifying the grid method asks for.
                    // Each IntArray is [totalTestableSquares, testedSquares, passingSquares]; graded
                    // over the total so an untested square counts against (a valid 20-grid result),
                    // and the floor is the coverage polygon when one is defined, not the PDF page.
                    val gridAgg = LinkedHashMap<com.nhnengineering.rftest.model.ErrcsAreaClass, IntArray>()
                    trackA.groupBy { it.floorplanId }.forEach { (planId, planPoints) ->
                        val cfg = errcsGridConfigs[planId]
                        val rows = cfg?.first ?: com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_ROWS
                        val cols = cfg?.second ?: com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_COLS
                        com.nhnengineering.rftest.model.errcsGridCompliance(
                            planPoints, rows, cols, publicSafetyThresholds,
                            errcsCriticalAreas[planId] ?: emptyList(),
                            errcsCoverageAreas[planId] ?: com.nhnengineering.rftest.model.CoverageArea.EMPTY,
                        ).forEach { gc ->
                            val acc = gridAgg.getOrPut(gc.areaClass) { IntArray(3) }
                            acc[0] += gc.totalCells
                            acc[1] += gc.testedCells
                            acc[2] += gc.passingCells
                        }
                    }
                    if (gridAgg.values.any { it[0] > 0 }) {
                        c.gap(4f)
                        c.text("Grid method — by square", c.body)
                        c.text(
                            String.format(
                                Locale.US, "%-16s %8s %8s %7s %9s",
                                "Area class", "Tested", "Of total", "Pass %", "Required",
                            ),
                            c.monoBold,
                        )
                        for (ac in com.nhnengineering.rftest.model.ErrcsAreaClass.entries) {
                            val acc = gridAgg[ac] ?: continue
                            val total = acc[0]
                            if (total == 0) continue
                            val pct = 100.0 * acc[2] / total
                            c.text(
                                String.format(
                                    Locale.US, "%-16s %8d %8d %6s %8s%%",
                                    ac.label, acc[1], total, String.format(Locale.US, "%.1f", pct),
                                    publicSafetyThresholds.requiredPctFor(ac).toString(),
                                ),
                                c.mono,
                            )
                        }
                        val incomplete = gridAgg.values.any { it[1] < it[0] }
                        if (incomplete) {
                            c.para(
                                "Grid incomplete: not every square inside the coverage area has a " +
                                    "reading yet. Untested squares are graded as not-passing, so Pass % " +
                                    "is a floor until the grid is fully walked.",
                                indent = 10f,
                            )
                        }
                        // NFPA 80-ft maximum grid dimension, checked per floor where georeferenced.
                        val oversize = trackA.groupBy { it.floorplanId }.mapNotNull { (planId, _) ->
                            val gr = errcsGeoRefs[planId] ?: return@mapNotNull null
                            val cfg = errcsGridConfigs[planId]
                            val rows = cfg?.first ?: com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_ROWS
                            val cols = cfg?.second ?: com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_COLS
                            val cov = errcsCoverageAreas[planId]
                                ?: com.nhnengineering.rftest.model.CoverageArea.EMPTY
                            com.nhnengineering.rftest.model.errcsGridCellSize(
                                cov, rows, cols, gr.widthPx, gr.heightPx, gr.metresPerPixel,
                            )?.takeIf { it.exceedsNfpaMax() }?.maxDimFt
                        }
                        if (oversize.isNotEmpty()) {
                            c.para(
                                String.format(
                                    Locale.US,
                                    "Grid dimension exceeds the NFPA 80-ft maximum on one or more " +
                                        "floors (largest %.0f ft). Subdivide the grid (more rows/cols) " +
                                        "or split the floor into sectors.",
                                    oversize.max(),
                                ),
                                indent = 10f,
                            )
                        }
                    }

                    // Two-way and DAQ grading, called out only when the data actually uses them.
                    val anyInbound = trackA.any { it.inboundDbm != null }
                    val daqGraded = publicSafetyThresholds.minDaq != null && trackA.any { it.daq != null }
                    if (anyInbound || daqGraded) {
                        c.para(
                            buildString {
                                append("Grading: outbound (downlink) is always required")
                                if (anyInbound) append("; inbound (uplink), where entered, must also pass")
                                if (daqGraded) {
                                    append(
                                        "; DAQ must meet %.1f where recorded"
                                            .format(publicSafetyThresholds.minDaq),
                                    )
                                }
                                append(".")
                            },
                            indent = 10f,
                        )
                    }
                    c.gap(6f)
                }

                if (trackBHasData) {
                    c.text("Track B — FirstNet Band 14/n14 (measured automatically)", c.body)
                    c.para(
                        "Filtered to samples this session recorded while the serving cell was " +
                            "Band 14 (LTE) or n14 (NR) -- FirstNet, under NFPA 1225's broader ERCES " +
                            "framework. Whether a given building's AHJ accepts this in place of " +
                            "strict LMR ERRCS testing is jurisdiction-specific and is not assumed " +
                            "here.",
                        indent = 10f,
                    )
                    c.gap(4f)
                    c.text(
                        String.format(
                            Locale.US, "%-16s %8s %8s %7s %9s",
                            "Area class", "Samples", "Passing", "Pass %", "Required",
                        ),
                        c.monoBold,
                    )
                    for (r in trackBResults) {
                        c.text(
                            String.format(
                                Locale.US, "%-16s %8d %8d %6s %8s%%",
                                r.areaClass.label, r.sampleCount, r.passingCount,
                                r.actualPct?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                                r.requiredPct.toString(),
                            ),
                            c.mono,
                        )
                    }
                    val failingB = trackBResults.filter { it.sampleCount > 0 && !it.meetsRequirement }
                    if (failingB.isNotEmpty()) {
                        c.para(
                            "Below requirement: " + failingB.joinToString(", ") { it.areaClass.label },
                        )
                    }
                    if (trackBResults.any { it.missing > 0 }) {
                        c.para(
                            "Some classified Band 14/n14 samples carried no RSRP reading and are " +
                                "excluded from the counts above, not counted as failing.",
                        )
                    }
                } else if (band14SamplesUnclassified) {
                    c.text("Track B — FirstNet Band 14/n14", c.body)
                    c.para(
                        "This session recorded samples on Band 14/n14 (FirstNet), but none were " +
                            "classified into a general or critical area, so no compliance figure " +
                            "is shown.",
                        indent = 10f,
                    )
                }
                c.gap(); c.rule()
            }
        }

        // ---- Dominance / best server --------------------------------------
        val dom = SessionStats.dominance(points)
        if (dom.samples > 0 && dom.servers.isNotEmpty()) {
            c.ensure(130f)
            c.text("Sector dominance and overlap", c.h2)
            c.para(
                "A sample's dominant sectors are the cells within ${dom.windowDb} dB of its " +
                    "strongest. Two or more means the handset has no clear server and will hand " +
                    "back and forth between them, which is the usual driver of remediation work " +
                    "on an in-building system.",
            )
            c.gap()
            c.kv("Samples analysed", dom.samples.toString())
            if (dom.overlapAssessable) {
                c.kv("Mean dominant sectors", String.format(Locale.US, "%.2f", dom.meanCount))
                c.kv(
                    "Overlap (2 or more within ${dom.windowDb} dB)",
                    String.format(Locale.US, "%.1f %% of samples", dom.overlapPct),
                )
            } else {
                c.kv("Overlap (2 or more within ${dom.windowDb} dB)", "not measured — see below")
            }
            if (dom.excluded > 0) {
                c.kv("Samples excluded", "${dom.excluded}  (cells seen, none with a level)")
            }
            c.gap()
            if (dom.overlapAssessable) {
                c.text(
                    String.format(Locale.US, "%-22s %9s %9s", "Dominant sectors", "Samples", "Share"),
                    c.monoBold,
                )
                for ((n, count) in dom.countHistogram) {
                    c.text(
                        String.format(
                            Locale.US, "%-22d %9d %8.1f%%",
                            n, count, 100.0 * count / dom.samples,
                        ),
                        c.mono,
                    )
                }
            } else {
                // Printing 0.0% here would be a measurement this survey did not make. Overlap
                // drives remediation decisions, so a confident zero can retire a justified
                // recommendation -- the one direction this error must never take.
                c.para(
                    "No sample in this survey carried more than one cell, so there was nothing " +
                        "to compare and overlap was not measured. Read this as not measured, not " +
                        "as no overlap.",
                )
                c.para(
                    "This is a limitation of the measuring handset rather than a property of the " +
                        "site. Some handsets do not pass 5G NR neighbour cells to an application " +
                        "at all, and this survey was recorded on one of them. The modem still " +
                        "measures them — such a handset hands over between cells normally, and a " +
                        "diagnostic tool reading the modem directly lists the neighbours " +
                        "throughout — but nothing reaches the application. Other handsets report " +
                        "them without difficulty, so this is a property of the instrument used " +
                        "and is recorded in the methodology section.",
                )
            }

            c.gap()
            c.ensure(120f)
            c.text("By cell", c.h2)
            c.para(
                "Detection rate is shown against every cell. A cell is identified by PCI and " +
                    "channel together, because a PCI is unique only within a carrier -- the same " +
                    "PCI on two channels is two different cells. Without a detection rate a cell " +
                    "seen in a handful of samples presents identically to one seen throughout.",
            )
            c.gap()
            c.text(
                String.format(
                    Locale.US, "%-6s %-6s %-8s %9s %8s %9s %7s %7s",
                    "PCI", "Band", "Channel", "Detected", "Detect", "Best srv", "Median", "Best",
                ),
                c.monoBold,
            )
            c.text(
                String.format(
                    Locale.US, "%-6s %-6s %-8s %9s %8s %9s %7s %7s",
                    "", "", "", "samples", "rate", "share", "dBm", "dBm",
                ),
                c.monoBold,
            )
            for (r in dom.servers.take(20)) {
                c.ensure(LINE * 2)
                c.text(
                    String.format(
                        Locale.US, "%-6d %-6s %-8s %9d %7.1f%% %8.1f%% %7s %7s",
                        r.pci,
                        r.band?.take(6) ?: "—",
                        r.channel?.toString() ?: "—",
                        r.detectedIn, r.detectionPct, r.bestServerPct,
                        r.stats.median?.toString() ?: "—",
                        r.stats.max?.toString() ?: "—",
                    ),
                    c.mono,
                )
            }
            if (dom.servers.size > 20) {
                c.gap(4f)
                c.text("${dom.servers.size - 20} further cells omitted; all are in the CSV.", c.small)
            }
            c.gap(4f)
            c.para(
                "Lower bound. A scanning receiver decodes every cell on air at once; this handset " +
                    "reports its serving cell plus whatever partial neighbour list the modem chose " +
                    "to surface, and only cells present in a sample's own measurement report are " +
                    "counted. Cells the modem did not report are invisible here, so overlap can " +
                    "be understated but not overstated.",
            )
            c.gap(); c.rule()
        }

        // ---- SSB layout ---------------------------------------------------
        val ssb = SessionStats.ssbLayout(points)
        if (ssb.isNotEmpty()) {
            c.ensure(150f)
            c.text("Carrier and SSB layout", c.h2)
            c.para(
                "The SSB positions seen on each band, with the GSCN a synchronisation or repeater " +
                    "vendor asks for. Several positions on one band mean either multiple carriers " +
                    "over the same sectors, or sectors deliberately given different SSB positions " +
                    "-- which look identical in a log and mean opposite things. They are told " +
                    "apart here by whether the positions share physical cell identities.",
            )
            c.gap()
            for (b in ssb) {
                c.ensure(LINE * 4)
                c.text(
                    "${b.band}  —  ${b.arrangement.label}" +
                        if (!b.sufficientEvidence) "  (too few cells seen to be sure)" else "",
                    c.monoBold,
                )
                c.text(
                    String.format(
                        Locale.US, "  %-9s %11s %7s  %s",
                        "ARFCN", "MHz", "GSCN", "PCIs",
                    ),
                    c.mono,
                )
                for (pos in b.positions) {
                    c.ensure(LINE * 2)
                    c.text(
                        String.format(
                            Locale.US, "  %-9d %11s %7s  %s",
                            pos.channel,
                            pos.freqMhz?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                            pos.gscn?.toString() ?: "—",
                            pos.pcis.joinToString(",").take(46),
                        ),
                        c.mono,
                    )
                }
                c.para("  " + b.arrangement.meaning)
                c.gap(4f)
            }
            c.rule()
        }

        // ---- Configuration profile ----------------------------------------
        //
        // Rendered adjacent to the measured SSB layout deliberately, because they answer the two
        // halves of the same vendor questionnaire. Kept visually and verbally separate because one
        // half was measured on this walk and the other was typed by a person after being told it,
        // and a commissioning report that blurs the two is worse than one that omits the second.
        run {
            val bands = points.mapNotNull { it.cellBand }.groupingBy { it }.eachCount()
            val commonBand = bands.maxByOrNull { it.value }?.key
            val matched = com.nhnengineering.rftest.profile.ProfileMatcher.match(
                profiles,
                com.nhnengineering.rftest.profile.ProfileMatcher.Query(
                    mcc = points.firstNotNullOfOrNull { it.mcc },
                    mnc = points.firstNotNullOfOrNull { it.mnc },
                    operator = points.firstNotNullOfOrNull { it.networkOperator },
                    band = commonBand,
                    siteName = summary.displayName,
                ),
            )
            if (matched != null) {
                c.ensure(170f)
                val measured = matched.provenance ==
                    com.nhnengineering.rftest.profile.Provenance.MEASURED
                c.text(
                    if (measured) {
                        "Configuration — measured from SIB1"
                    } else {
                        "Configuration — from profile, not measured"
                    },
                    c.h2,
                )
                c.para(
                    if (measured) {
                        // A different claim entirely, so it gets different words rather than the
                        // same paragraph with a flag flipped.
                        "These values were read off the air from the network's own broadcast. " +
                            "SIB1 carries the slot pattern, SSB positions and subcarrier spacing, " +
                            "and an engineer decoded them on site with a diagnostic tool -- this " +
                            "application cannot reach SIB1 itself and did not produce them. They " +
                            "are evidence rather than report, and the source below records what " +
                            "read them."
                    } else {
                        "These values were not read off the air during this survey. They are " +
                            "reproduced from the recorded configuration profile so that a " +
                            "commissioning form can be completed alongside the measurements, and " +
                            "they carry their source so a reader can judge them. SSB periodicity, " +
                            "the slot pattern and CSI-RS periodicity live in SIB1 and the physical " +
                            "layer, which this application cannot reach -- though a diagnostic " +
                            "tool on a rooted handset can, and a profile recorded that way is " +
                            "marked as measured."
                    },
                )
                c.gap()
                c.kv("Profile", matched.title)
                c.kv("Source", matched.source.ifBlank { "unrecorded" })
                if (matched.recordedAtUtcMillis > 0) {
                    c.kv("Recorded", DATE.format(Date(matched.recordedAtUtcMillis)))
                }
                if (matched.isSiteOverride) {
                    c.kv("Scope", "Site-specific override for ${matched.siteName}")
                }
                c.gap()
                val p1Suffix = if (matched.hasPattern2) " (pattern 1)" else ""
                val rows = buildList {
                    matched.tddPattern?.takeIf { it.isNotBlank() }?.let { add("TDD pattern" to it) }
                    if (matched.hasPattern2) {
                        matched.pattern1PeriodicityMs?.let { add("Pattern 1 duration" to "$it ms") }
                        matched.pattern2PeriodicityMs?.let { add("Pattern 2 duration" to "$it ms") }
                        matched.tddPeriodicityMs?.let { add("Repeats every" to "$it ms") }
                    } else {
                        matched.tddPeriodicityMs?.takeIf { it.isNotBlank() }
                            ?.let { add("TDD periodicity" to "$it ms") }
                    }
                    matched.dlSlots?.let { add("Downlink slots$p1Suffix" to it.toString()) }
                    matched.dlSymbols?.let { add("Special slot downlink symbols$p1Suffix" to it.toString()) }
                    matched.ulSlots?.let { add("Uplink slots$p1Suffix" to it.toString()) }
                    matched.ulSymbols?.let { add("Special slot uplink symbols$p1Suffix" to it.toString()) }
                    if (matched.hasPattern2) {
                        matched.p2DlSlots?.let { add("Downlink slots (pattern 2)" to it.toString()) }
                        matched.p2DlSymbols?.let { add("Special slot downlink symbols (pattern 2)" to it.toString()) }
                        matched.p2UlSlots?.let { add("Uplink slots (pattern 2)" to it.toString()) }
                        matched.p2UlSymbols?.let { add("Special slot uplink symbols (pattern 2)" to it.toString()) }
                    }
                    matched.ssbPeriodicityMs?.let { add("SSB periodicity" to "$it ms") }
                    matched.ssbPosition?.let { add("SSB position" to it.toString()) }
                    matched.ssbPositionsInBurst?.takeIf { it.isNotBlank() }
                        ?.let { add("SSB position in burst" to it) }
                    matched.scsKhz?.let { add("Subcarrier spacing" to "$it kHz") }
                }
                for ((k, v) in rows) c.kv(k, v)
                matched.note?.takeIf { it.isNotBlank() }?.let { c.gap(4f); c.para("Note: $it") }

                // The cross-check sits under the profile it is checking, so a reader sees the
                // recorded values and the reasons to doubt them together rather than pages apart.
                val checks = com.nhnengineering.rftest.profile.ProfileCrossCheck.check(
                    matched, ssb, commonBand,
                )
                if (checks.isNotEmpty()) {
                    c.gap()
                    c.text("Worth confirming", c.monoBold)
                    for (f in checks) {
                        c.ensure(LINE * 3)
                        c.text(
                            (if (f.severity ==
                                    com.nhnengineering.rftest.profile.ProfileCrossCheck.Severity.CHECK
                                ) "⚠  " else "·  ") + f.headline,
                            c.body,
                        )
                        c.para(f.detail, c.small, indent = 12f)
                        c.gap(3f)
                    }
                    c.para(
                        "Only the SSB arrangement, the duplex mode and the subcarrier spacing can " +
                            "be compared at all. The slot pattern, SSB periodicity and CSI-RS " +
                            "periodicity are not observable by a handset, so nothing above either " +
                            "confirms or contradicts them.",
                    )
                }
                c.gap(); c.rule()
            } else if (profiles.isNotEmpty() && commonBand != null) {
                // Silence would read as "no such configuration exists". Saying it is absent keeps
                // the gap visible on the form the engineer is filling in.
                c.ensure(60f)
                c.text("Configuration — from profile, not measured", c.h2)
                c.para(
                    "No configuration profile is recorded for this operator on $commonBand. The " +
                        "TDD and SSB parameters a vendor questionnaire asks for cannot be measured " +
                        "by a handset and are not yet in the library.",
                )
                c.gap(); c.rule()
            }
        }

        // ---- Stronger neighbours ------------------------------------------
        val ho = SessionStats.strongerNeighbours(points)
        if (ho.neighbours.isNotEmpty()) {
            c.ensure(150f)
            c.text("Cells stronger than the serving cell", c.h2)
            c.para(
                "Where a neighbour was at least ${ho.marginDb} dB above the cell actually serving " +
                    "the handset. A phone shows its serving cell and its bars; it does not say a " +
                    "better cell was available and the network stayed put. Brief overshoots are " +
                    "ordinary fading -- runs of several seconds are not.",
            )
            c.gap()
            c.kv("Samples analysed", ho.analysed.toString())
            c.kv(
                "With a stronger neighbour",
                String.format(Locale.US, "%d  (%.1f %%)", ho.samplesWithStronger, ho.sharePct),
            )
            c.gap()
            c.text(
                String.format(
                    Locale.US, "%-6s %-6s %-8s %8s %7s %7s %-22s",
                    "PCI", "Band", "Channel", "Samples", "Max dB", "Run", "Reading",
                ),
                c.monoBold,
            )
            for (n in ho.neighbours.take(12)) {
                c.ensure(LINE * 2)
                val reading = when {
                    n.samePciAsServing -> "same site, aggregation"
                    n.likelyHandoverIssue -> "same band, sustained"
                    n.interBand && n.sustained -> "other band, likely policy"
                    else -> "brief, fading"
                }
                c.text(
                    String.format(
                        Locale.US, "%-6d %-6s %-8s %8d %7d %7d %-22s",
                        n.pci, n.band?.take(6) ?: "—", n.channel?.toString() ?: "—",
                        n.samples, n.maxMarginDb, n.longestRunSamples, reading,
                    ),
                    c.mono,
                )
            }
            c.gap(4f)
            if (ho.hasLikelyHandoverIssue) {
                c.para(
                    "One or more cells on the same band as the server stayed stronger for several " +
                        "seconds. That has a short list of causes and each is actionable: a " +
                        "missing neighbour relation, handover hysteresis or time-to-trigger set " +
                        "too high, or on an in-building system a sector assignment that does not " +
                        "match the floor plan.",
                )
            }
            c.para(
                "Cells on a different band are reported but not flagged: carriers deliberately " +
                    "hold devices on mid band for capacity even where a low-band cell is stronger, " +
                    "so more signal there is not necessarily a better connection. A neighbour " +
                    "sharing the serving cell's PCI on another channel is almost always the same " +
                    "site's second carrier seen through aggregation, not a handover candidate.",
            )
            c.gap(); c.rule()
        }

        // ---- Coverage holes -----------------------------------------------
        c.ensure(120f)
        // ---- Roaming ------------------------------------------------------
        //
        // Coverage answers "is there signal here". Roaming answers "does the connection survive
        // walking", which is the complaint an operator actually receives -- a call that drops in a
        // corridor on a network whose coverage map looks perfect.
        val roaming = WifiRoaming.analyse(points)
        if (roaming.samplesWithServing > 0 && roaming.distinctAps > 1) {
            c.ensure(160f)
            c.text("Roaming", c.h2)
            c.kv("Access points used", roaming.distinctAps.toString())
            c.kv("Roams", roaming.roams.size.toString())
            if (roaming.pingPongs > 0) {
                c.kv("Returned to the previous AP", roaming.pingPongs.toString())
            }
            c.gap()

            if (roaming.roams.isNotEmpty()) {
                c.text(
                    String.format(Locale.US, "%-20s %-20s %9s %9s", "From", "To", "Before", "After"),
                    c.monoBold,
                )
                for (r in roaming.roams.take(15)) {
                    c.ensure(LINE)
                    c.text(
                        String.format(
                            Locale.US, "%-20s %-20s %9s %9s",
                            r.fromBssid.takeLast(11), r.toBssid.takeLast(11),
                            r.rssiBeforeDbm?.toString() ?: "--",
                            r.rssiAfterDbm?.toString() ?: "--",
                        ),
                        c.mono,
                    )
                }
                c.gap()
                c.para(
                    "The level in the \"Before\" column is what the client tolerated before moving. " +
                        "Consistently low values mean the client roams late, which is felt as a " +
                        "stall rather than a disconnection. Repeated returns to a previous access " +
                        "point mean it roams too eagerly, which costs airtime on every bounce.",
                )
            }

            if (roaming.sticky.isNotEmpty()) {
                c.gap()
                c.text("Stayed on a weaker access point", c.monoBold)
                c.text(
                    String.format(Locale.US, "%-20s %8s %9s  %s", "Stayed on", "Samples", "Best by", "Better AP"),
                    c.monoBold,
                )
                for (st in roaming.sticky.take(10)) {
                    c.ensure(LINE)
                    c.text(
                        String.format(
                            Locale.US, "%-20s %8d %8d dB  %s",
                            st.bssid.takeLast(11), st.samples, st.maxDeltaDb,
                            st.bestAlternativeBssid.takeLast(11),
                        ),
                        c.mono,
                    )
                }
                c.para(
                    "A better access point on the same network was available and fresh for these " +
                        "stretches and the client stayed where it was. This is invisible to a " +
                        "coverage map -- the signal was adequate, from the wrong access point. " +
                        "The roam decision belongs to the client, so this describes the measuring " +
                        "handset's behaviour; another vendor's client may differ on the same " +
                        "network. It is still worth investigating, because a building where a " +
                        "common client goes sticky usually has access points too close together " +
                        "or a minimum basic rate low enough to let a distant one stay usable.",
                )
            } else if (roaming.roams.isNotEmpty()) {
                c.para(
                    "No stretch was found where a materially stronger access point on the same " +
                        "network was available and ignored.",
                )
            }
            c.gap(); c.rule()
        }

        c.text("Coverage holes", c.h2)
        if (report.holes.isEmpty()) {
            c.text("No contiguous run of samples fell below the threshold.", c.body)
        } else {
            c.para(
                "Contiguous runs below threshold, worst first. A compliance percentage alone " +
                    "cannot distinguish failures spread thinly across a site from failures " +
                    "concentrated in one place; only the latter tells an engineer where to return.",
            )
            c.gap()
            c.text(
                String.format(Locale.US, "%-6s %-9s %-9s %-8s %s", "#", "Samples", "Worst", "Secs", "Location"),
                c.monoBold,
            )
            report.holes.take(25).forEachIndexed { i, h ->
                val where = when {
                    h.waypoint != null -> h.waypoint
                    h.floorplanId != null ->
                        String.format(Locale.US, "plan %.2f,%.2f", h.floorplanX, h.floorplanY)
                    h.lat != null -> String.format(Locale.US, "%.5f,%.5f", h.lat, h.lon)
                    else -> "not located"
                }
                c.text(
                    String.format(
                        Locale.US, "%-6d %-9d %-9s %-8s %s",
                        i + 1, h.samples, "${h.worstDbm} dBm",
                        h.durationS?.let { String.format(Locale.US, "%.0f", it) } ?: "—",
                        where.take(34),
                    ),
                    c.mono,
                )
            }
            if (report.holes.size > 25) {
                c.gap()
                c.text("${report.holes.size - 25} further holes omitted; all are in the CSV.", c.small)
            }
        }

        // ---- Plot ---------------------------------------------------------
        drawPlot(context, c, summary, points, report.kpi)

        // ---- Methodology --------------------------------------------------
        c.newPage()
        c.text("Methodology and limitations", c.h2)
        c.gap()
        val nbr = SessionStats.neighbourVisibility(points)
        val nbrNote: Pair<String, String>? = when (nbr.visibility) {
            SessionStats.NeighbourVisibility.NEVER_REPORTED -> Pair(
                "This handset reported no neighbour cells",
                "Across ${nbr.cellularSamples} cellular samples, not one carried a second cell. " +
                    "That is a property of the measuring handset rather than of this site: some " +
                    "handsets never pass 5G NR neighbour cells to an application, and on those " +
                    "the modem still measures them and the handset still hands over normally. " +
                    "Sector overlap and cell dominance therefore could not be assessed here, and " +
                    "any figure of zero for them in this report means not measured. Assessing " +
                    "them on this site needs a handset that reports neighbours, or a diagnostic " +
                    "tool reading the modem directly.",
            )
            SessionStats.NeighbourVisibility.MEASURED_NONE -> Pair(
                "No neighbour cells are present here",
                "Neighbours were read from the modem directly on " +
                    "${nbr.modemReadSamples} of ${nbr.cellularSamples} cellular samples, and " +
                    "none reported a second cell. Unlike an empty list from the handset's " +
                    "platform interfaces, that is a measurement: this location is served by one " +
                    "cell with no others above the detection floor. Sector overlap of zero " +
                    "below is therefore a finding rather than an absence of data.",
            )
            SessionStats.NeighbourVisibility.TOO_FEW_SAMPLES -> Pair(
                "Too few samples to judge neighbour visibility",
                "No neighbour cell appeared in ${nbr.cellularSamples} cellular samples, which is " +
                    "too short a survey to tell an empty neighbourhood from a handset that does " +
                    "not report one. Neither conclusion is drawn.",
            )
            SessionStats.NeighbourVisibility.REPORTED -> Pair(
                "Neighbour cells were reported",
                "${nbr.distinctNeighbours} distinct neighbour cells appeared across " +
                    "${nbr.samplesWithNeighbour} of ${nbr.cellularSamples} cellular samples, so " +
                    "overlap and dominance below are measurements rather than absences.",
            )
            SessionStats.NeighbourVisibility.NO_CELLULAR -> null
        }
        (methodologyNotes(summary, points, report) + listOfNotNull(nbrNote)).forEach {
            c.text("•  ${it.first}", c.body)
            c.para(it.second, c.small, indent = 12f)
            c.gap(4f)
        }

        c.finish()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        out
    }

    /**
     * Renders one breakdown as a fixed-width table.
     *
     * The `Share` column is the point of the table. Without it a band measured in 2% of samples
     * presents identically to one measured in 90%, which is exactly the defect found in the
     * competitor package this report is built to beat.
     */
    private fun groupTable(c: Ctx, b: SessionStats.Breakdown, thresholdDbm: Int) {
        c.text(
            String.format(
                Locale.US, "%-22s %7s %7s %7s %7s %7s %8s",
                "", "Samples", "Share", "Median", "p10", "Worst", "Pass",
            ),
            c.monoBold,
        )
        for (g in b.groups) {
            c.ensure(LINE * 2)
            c.text(
                String.format(
                    Locale.US, "%-22s %7d %6.1f%% %7s %7s %7s %7.1f%%",
                    g.label.take(22),
                    g.measured,
                    g.sharePct,
                    g.stats.median?.toString() ?: "—",
                    g.stats.p10?.toString() ?: "—",
                    g.stats.min?.toString() ?: "—",
                    g.compliancePct,
                ),
                c.mono,
            )
        }
        c.gap(4f)
        c.para(
            "Pass = share of that group's samples at or above $thresholdDbm dBm. Values in dBm.",
        )
        val thin = b.groups.filter { it.thin }
        if (thin.isNotEmpty()) {
            c.para(
                "Under ${SessionStats.THIN_GROUP_PCT.toInt()}% of the survey, so the statistics " +
                    "are indicative only: " + thin.joinToString(", ") { it.label },
            )
        }
        if (b.unlabelled > 0) {
            c.para(
                "${b.unlabelled} measured samples carried no label and are excluded from this " +
                    "table. They remain in the site-wide figures above.",
            )
        }
    }

    /**
     * Throughput per group.
     *
     * Median rather than mean, with the range beside it. A walk yields a handful of speed tests
     * per band, not hundreds, so one run that started while the radio was still ramping moves a
     * mean and does not move a median -- and the range is printed so that the reader can see the
     * spread the median is hiding rather than having to trust it.
     */
    private fun throughputTable(c: Ctx, b: SessionStats.ThroughputBreakdown) {
        fun mbps(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        fun range(lo: Double?, hi: Double?, n: Int) =
            if (n < 2 || lo == null || hi == null) "—" else "${mbps(lo)}–${mbps(hi)}"

        c.text(
            String.format(
                Locale.US, "%-20s %7s %13s %4s %7s %4s %6s",
                "", "DL med", "DL range", "n", "UL med", "n", "Failed",
            ),
            c.monoBold,
        )
        for (g in b.groups) {
            c.ensure(LINE * 2)
            c.text(
                String.format(
                    Locale.US, "%-20s %7s %13s %4d %7s %4d %6d",
                    g.label.take(20),
                    mbps(g.dlMedianMbps),
                    range(g.dlMinMbps, g.dlMaxMbps, g.downloadCount),
                    g.downloadCount,
                    mbps(g.ulMedianMbps),
                    g.uploadCount,
                    g.failedCount,
                ),
                c.mono,
            )
        }
        c.gap(4f)
        c.para("Mbps. n is the number of completed tests in that direction.")

        val thin = b.groups.filter { it.isThin }
        if (thin.isNotEmpty()) {
            c.para(
                "Fewer than ${SessionStats.MIN_THROUGHPUT_TESTS} completed tests, so the centre " +
                    "is indicative rather than representative: " +
                    thin.joinToString(", ") { it.label } + ". A speed test takes tens of seconds, " +
                    "so this is normal on a walk rather than a defect in the survey — but a " +
                    "sector should not be condemned on two runs.",
            )
        }
        val failed = b.groups.filter { it.failedCount > 0 }
        if (failed.isNotEmpty()) {
            c.para(
                "Tests that recorded an error are counted in the Failed column and are not in the " +
                    "medians: " + failed.joinToString(", ") { "${it.label} (${it.failedCount})" } +
                    ". A direction that mostly failed is a result about that band, not missing " +
                    "data — a test can also succeed one way and fail the other, and then it " +
                    "appears in both a count and the failure tally.",
            )
        }
        if (b.unlabelled > 0) {
            c.para(
                "${b.unlabelled} completed test${if (b.unlabelled == 1) "" else "s"} carried no " +
                    "serving band and ${if (b.unlabelled == 1) "is" else "are"} excluded from " +
                    "this table.",
            )
        }
    }

    /** Colour key for whichever scale this session is plotted on. */
    private fun legendEntries(kpi: SessionStats.Kpi): List<Pair<String, Int>> =
        if (kpi == SessionStats.Kpi.CELL_RSRP) {
            com.nhnengineering.rftest.model.RsrpBucket.entries.map { it.label to it.argb }
        } else {
            com.nhnengineering.rftest.model.RssiBucket.entries.map { it.label to it.argb }
        }

    private fun drawLegend(c: Ctx, kpi: SessionStats.Kpi, left: Float, top: Float): Float {
        val canvas = c.canvas ?: return 0f
        val entries = legendEntries(kpi)
        val swatch = Paint().apply { isAntiAlias = true }
        val label = c.paint(8f)
        var x = left
        val boxH = 8f
        for ((text, argb) in entries) {
            swatch.color = argb
            canvas.drawRect(x, top, x + 11f, top + boxH, swatch)
            canvas.drawText(text, x + 15f, top + boxH - 0.5f, label)
            x += 15f + label.measureText(text) + 16f
        }
        return boxH + 4f
    }

    /**
     * A scale bar whose length is a round number of metres.
     *
     * Snapped to 1/2/5 x 10^n so the bar reads "25 m" rather than "23.7 m" — a bar nobody can
     * mentally multiply is decoration, not a scale.
     */
    private fun drawScaleBar(c: Ctx, left: Float, bottom: Float, pxPerMetre: Double, maxPx: Float) {
        val canvas = c.canvas ?: return
        if (pxPerMetre <= 0.0) return
        val targetM = maxPx / pxPerMetre
        if (targetM <= 0.0 || !targetM.isFinite()) return
        val exp = floor(log10(targetM))
        val base = 10.0.pow(exp)
        val niceM = listOf(5.0, 2.0, 1.0).map { it * base }.firstOrNull { it <= targetM } ?: base
        val barPx = (niceM * pxPerMetre).toFloat()
        if (barPx < 12f) return

        val bar = Paint().apply { color = Color.BLACK; strokeWidth = 1.4f; isAntiAlias = true }
        canvas.drawLine(left, bottom, left + barPx, bottom, bar)
        canvas.drawLine(left, bottom - 3f, left, bottom + 3f, bar)
        canvas.drawLine(left + barPx, bottom - 3f, left + barPx, bottom + 3f, bar)
        val txt = if (niceM >= 1000) String.format(Locale.US, "%.0f km", niceM / 1000)
                  else String.format(Locale.US, "%.0f m", niceM)
        canvas.drawText(txt, left + barPx + 5f, bottom + 3f, c.paint(8f))
    }

    /**
     * North arrow. **GPS plots only.**
     *
     * The equirectangular projection below puts north at the top by construction, so the arrow is
     * a statement of fact. A floorplan's orientation is not known to this app — the operator
     * uploads an image, not a georeferenced raster — so drawing one there would be an invention,
     * and the floorplan branch says so in words instead.
     */
    private fun drawNorthArrow(c: Ctx, cx: Float, top: Float) {
        val canvas = c.canvas ?: return
        val ink = Paint().apply { color = Color.rgb(60, 60, 60); isAntiAlias = true }
        val path = android.graphics.Path().apply {
            moveTo(cx, top)
            lineTo(cx - 4.5f, top + 13f)
            lineTo(cx, top + 9.5f)
            lineTo(cx + 4.5f, top + 13f)
            close()
        }
        canvas.drawPath(path, ink)
        val n = c.paint(8f, bold = true)
        canvas.drawText("N", cx - n.measureText("N") / 2f, top + 23f, n)
    }

    /** A tight walk should not zoom into GPS scatter -- mirrors [Mercator.Bounds.expandedToAtLeast]'s
     *  own stated rationale, applied to the report's basemap fallback. */
    private const val MIN_PLOT_SPAN_M = 50.0

    /**
     * Draws satellite/street tiles covering [bounds] at [zoom] into the rect at ([left],[top]),
     * [w] by [h]. Uses [TileProxy] directly rather than `TileCache`: this runs once, synchronously,
     * already off the main thread ([generate] is `withContext(Dispatchers.IO)`), so a blocking
     * fetch is exactly right and `TileCache`'s non-blocking/redraw-callback machinery -- built for
     * a Compose canvas that redraws every frame -- would be pure overhead here.
     *
     * Returns the number of tiles actually drawn, so the caller can detect "nothing came back"
     * (offline, an external outage) and fall through to the existing blank-background plot rather
     * than publish a half-drawn page.
     */
    private fun drawMercatorBasemap(
        canvas: Canvas,
        proxy: TileProxy,
        bounds: Mercator.Bounds,
        zoom: Int,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
    ): Int {
        val tilePx = Mercator.TILE_SIZE
        val centerLon = (bounds.minLon + bounds.maxLon) / 2
        val centerWx = Mercator.lonToTileX(centerLon, zoom) * tilePx
        val centerWy = Mercator.latToTileY(bounds.midLat, zoom) * tilePx
        val halfW = w / 2f
        val halfH = h / 2f

        val x0 = floor((centerWx - halfW) / tilePx).toInt()
        val x1 = floor((centerWx + halfW) / tilePx).toInt()
        val y0 = floor((centerWy - halfH) / tilePx).toInt()
        val y1 = floor((centerWy + halfH) / tilePx).toInt()
        val maxIndex = 1 shl zoom

        var drawn = 0
        for (tx in x0..x1) {
            for (ty in y0..y1) {
                if (tx < 0 || ty < 0 || tx >= maxIndex || ty >= maxIndex) continue
                val bytes = proxy.tile(zoom, tx, ty) ?: continue
                val bmp = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull() ?: continue
                val tileLeft = (tx * tilePx - centerWx).toFloat() + left + halfW
                val tileTop = (ty * tilePx - centerWy).toFloat() + top + halfH
                canvas.drawBitmap(
                    bmp, null,
                    RectF(tileLeft, tileTop, tileLeft + tilePx, tileTop + tilePx),
                    null,
                )
                drawn++
            }
        }
        return drawn
    }

    /**
     * The trail, sample dots, S/E markers, north arrow and scale bar over a Mercator-projected
     * basemap -- the same shapes [drawPlot]'s equirectangular GPS fallback below already draws,
     * projected the same way the tiles underneath them are (see [Mercator]'s own class doc for why
     * a different projection for the trail than the tiles is worse than no imagery at all).
     *
     * Returns the number of fixes flagged by [GpsOutlierFilter] and excluded from the trail, so
     * the caller can add a caveat line below the plot -- this function only draws within the
     * plot's own fixed rect and never touches [Ctx.y], so it cannot add flowing text itself.
     */
    private fun drawMercatorTrackOverlay(
        c: Ctx,
        gps: List<TrackPoint>,
        bounds: Mercator.Bounds,
        zoom: Int,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
    ): Int {
        val canvas = c.canvas ?: return 0
        val tilePx = Mercator.TILE_SIZE
        val centerLon = (bounds.minLon + bounds.maxLon) / 2
        val centerWx = Mercator.lonToTileX(centerLon, zoom) * tilePx
        val centerWy = Mercator.latToTileY(bounds.midLat, zoom) * tilePx
        val halfW = w / 2f
        val halfH = h / 2f

        fun project(lat: Double, lon: Double): FloatArray = floatArrayOf(
            (Mercator.lonToTileX(lon, zoom) * tilePx - centerWx).toFloat() + left + halfW,
            (Mercator.latToTileY(lat, zoom) * tilePx - centerWy).toFloat() + top + halfH,
        )

        val projected = gps.map { project(it.latitudeDeg!!, it.longitudeDeg!!) }
        val outlierFlags = gpsOutlierFlags(gps)

        // Bright and thick, same reasoning as the live map: a thin grey line (right for blank
        // paper) disappears over satellite imagery.
        val trail = Paint().apply {
            color = Color.WHITE; alpha = 190; strokeWidth = 2.2f; isAntiAlias = true
        }
        for (i in 0 until projected.size - 1) {
            if (outlierFlags[i] || outlierFlags[i + 1]) continue
            canvas.drawLine(projected[i][0], projected[i][1], projected[i + 1][0], projected[i + 1][1], trail)
        }
        val dot = Paint().apply { isAntiAlias = true }
        val ring = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f
            color = Color.BLACK; alpha = 140; isAntiAlias = true
        }
        val outlierRing = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1.4f
            color = Color.GRAY; isAntiAlias = true
        }
        gps.forEachIndexed { i, p ->
            if (outlierFlags[i]) {
                // Hollow and grey rather than RSRP-coloured: a suspect position, not a reading.
                canvas.drawCircle(projected[i][0], projected[i][1], 2.6f, outlierRing)
            } else {
                dot.color = pointColor(p)
                val x = projected[i][0]; val y = projected[i][1]
                // Square rather than a second colour: shape carries the Indoor/Outdoor mark, so
                // the existing RSRP-bucket colour legend keeps its meaning unchanged.
                if (SessionStats.areaOf(p) == SessionStats.Area.INDOOR) {
                    canvas.drawRect(x - 2.6f, y - 2.6f, x + 2.6f, y + 2.6f, dot)
                } else {
                    canvas.drawCircle(x, y, 2.6f, dot)
                }
                canvas.drawCircle(x, y, 2.6f, ring)
            }
        }

        val marker = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1.3f
            color = Color.BLACK; isAntiAlias = true
        }
        val tag = c.paint(8f, bold = true)
        // The trusted first/last fix, not necessarily the literal first/last sample -- an S or E
        // marker sitting at a flagged position would misreport where the walk actually started.
        val startIdx = outlierFlags.indexOfFirst { !it }.takeIf { it >= 0 } ?: 0
        val endIdx = outlierFlags.indexOfLast { !it }.takeIf { it >= 0 } ?: projected.lastIndex
        listOf(projected[startIdx] to "S", projected[endIdx] to "E").forEach { (pt, label) ->
            canvas.drawCircle(pt[0], pt[1], 5.5f, marker)
            canvas.drawText(label, pt[0] + 7f, pt[1] + 3f, tag)
        }

        drawNorthArrow(c, left + w - 18f, top + 8f)
        val pxPerMetre = 1.0 / Mercator.metresPerPixel(bounds.midLat, zoom)
        drawScaleBar(c, left + 16f, top + h - 10f, pxPerMetre, w / 4f)
        return outlierFlags.count { it }
    }

    /** [GpsOutlierFilter.flagOutliers] over [TrackPoint]'s own field names. */
    private fun gpsOutlierFlags(gps: List<TrackPoint>): List<Boolean> =
        GpsOutlierFilter.flagOutliers(
            gps.map { GpsOutlierFilter.Fix(it.latitudeDeg!!, it.longitudeDeg!!, it.timestampUtcMillis) },
        )

    /**
     * An extra page for a floorplan session that also recorded GPS: the walked track on satellite
     * imagery. The floorplan page shows where the operator *said* they were (hand-placed); this
     * shows where the handset's GPS put them. They are complementary, and the operator asked to see
     * both — denser, automatic GPS sampling is the better ground truth where the fix is trustworthy.
     *
     * Reuses the same Mercator basemap/overlay helpers as the primary GPS plot. On a tile-fetch
     * failure (offline) it says so rather than half-drawing — this is a supplementary page, so the
     * floorplan page still carries the positioned samples.
     */
    private fun drawGpsSatellitePage(
        context: Context,
        c: Ctx,
        summary: SessionSummary,
        gps: List<TrackPoint>,
        kpi: SessionStats.Kpi,
    ) {
        // newPage() finishes the previous page and starts a new one, so the drawing canvas must be
        // fetched AFTER it -- a canvas captured before newPage() belongs to a now-finished page and
        // throws the moment it is drawn on, crashing report generation. (Original bug, fixed.)
        c.newPage()
        c.text("Survey plot — GPS track", c.h2)
        c.gap()

        val canvas = c.canvas ?: return
        val availW = PAGE_W - 2 * MARGIN
        val availH = 420f
        val frame = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = Color.rgb(120, 120, 120)
            isAntiAlias = true
        }

        val bounds = Mercator.Bounds.of(gps.map { it.latitudeDeg!! to it.longitudeDeg!! })
            ?.expandedToAtLeast(MIN_PLOT_SPAN_M)
        val basemap = TileProxy.Basemap.SATELLITE
        var drew = false
        if (bounds != null) {
            val zoom = Mercator.fitZoom(bounds, availW.toInt(), availH.toInt(), basemap.maxZoom)
            val proxy = TileProxy(File(context.cacheDir, "tiles"), basemap)
            val top = c.y
            // Clip to the plot box: satellite tiles are square and the fitted grid overruns the box
            // edges, so without this the imagery spills past the grey boundary. Crop it to the frame.
            canvas.save()
            canvas.clipRect(MARGIN, top, MARGIN + availW, top + availH)
            val tilesDrawn = drawMercatorBasemap(canvas, proxy, bounds, zoom, MARGIN, top, availW, availH)
            val excluded = if (tilesDrawn > 0) {
                drawMercatorTrackOverlay(c, gps, bounds, zoom, MARGIN, top, availW, availH)
            } else 0
            canvas.restore()
            if (tilesDrawn > 0) {
                // Boundary drawn after the clip is lifted, so the grey line sits crisply on top of the
                // cropped imagery rather than under tiles that overran the box.
                canvas.drawRect(MARGIN, top, MARGIN + availW, top + availH, frame)
                c.y = top + availH + LINE
                c.y += drawLegend(c, kpi, MARGIN, c.y)
                c.gap(6f)
                c.text(basemap.attribution, c.small)
                val accs = gps.mapNotNull { it.accuracyM }
                val accNote = if (accs.isNotEmpty()) {
                    " Reported GPS accuracy ${accs.min().toInt()}–${accs.max().toInt()} m."
                } else ""
                c.para(
                    "${gps.size} GPS-located samples over satellite imagery, north up. S marks the " +
                        "start of the walk and E the end.$accNote Indoor GPS can be tens of metres " +
                        "off even where a fix is reported, so read this alongside the floorplan " +
                        "page, which carries the operator's own placement.",
                )
                drawGpsQualityNote(c, gps)
                if (excluded > 0) {
                    c.para(
                        "$excluded sample(s) excluded from the trail as suspect GPS positions " +
                            "(implied speed too high from the last trusted fix). Still counted in " +
                            "the statistics above.",
                    )
                }
                drew = true
            }
        }
        if (!drew) {
            c.text(
                "GPS satellite imagery could not be fetched (offline, or no coverage for this " +
                    "area). The floorplan page carries the positioned samples.",
                c.body,
            )
        }
    }

    /**
     * States how trustworthy the plotted GPS fix is, with the numbers and -- when it is not good --
     * why, and what the tester can do next walk. Rendered for every mixed session, good or bad: the
     * page itself is never hidden on a poor walk (that reads as a software bug), so it self-diagnoses
     * instead. See [SessionStats.gpsQuality].
     */
    private fun drawGpsQualityNote(c: Ctx, gps: List<TrackPoint>) {
        val q = SessionStats.gpsQuality(gps)
        val word = when (q.grade) {
            SessionStats.GpsQualityGrade.GOOD -> "GOOD"
            SessionStats.GpsQualityGrade.MARGINAL -> "MARGINAL"
            SessionStats.GpsQualityGrade.POOR -> "POOR"
        }
        val metrics = buildList {
            q.medianAccuracyM?.let { add("median accuracy ${it.toInt()} m") }
            q.medianAvgCn0?.let { add("avg C/N0 ${it.toInt()} dB-Hz") }
            if (q.medianSatsUsed != null && q.medianSatsInView != null) {
                add("${q.medianSatsUsed} of ${q.medianSatsInView} satellites used")
            } else {
                q.medianSatsUsed?.let { add("$it satellites used") }
            }
        }.joinToString(", ")
        c.gap(4f)
        c.text("GPS data quality: $word" + if (metrics.isNotEmpty()) "  ($metrics)" else "", c.body)
        if (q.reasons.isNotEmpty()) {
            c.para("Why: " + q.reasons.joinToString(" "))
        }
        if (q.advice.isNotEmpty()) {
            c.para("Next walk: " + q.advice.joinToString(" "))
        }
    }

    /**
     * Plots the survey — the floorplan where one was used, otherwise the GPS track.
     *
     * Indoor sessions are plotted on the plan because they have no geography to plot; treating a
     * missing GPS fix as a missing measurement would leave a venue report with no picture at all.
     */
    private fun drawPlot(
        context: Context,
        c: Ctx,
        summary: SessionSummary,
        points: List<TrackPoint>,
        kpi: SessionStats.Kpi,
    ) {
        val planId = summary.floorplanIds.firstOrNull()
        val indoor = points.filter { it.hasIndoorPosition }
        // A hand-placed sample on a georeferenced floor now also carries a real lat/lon (derived from
        // the plan), so it satisfies hasGpsPosition too. It belongs on the floorplan page, not the
        // GPS-track page -- it is not a GPS fix and must not be graded as one -- so the GPS track is
        // the samples that were placed by GPS alone.
        val gps = points.filter { it.hasGpsPosition && !it.hasIndoorPosition }

        // A floorplan session that also recorded usable GPS gets the walked GPS track on its own
        // page first, so the reader can compare the handset's ground truth against the operator's
        // hand placement on the plan. Pure-GPS and pure-floorplan sessions are unchanged -- their
        // single plot is drawn below.
        if (planId != null && indoor.isNotEmpty() && gps.size >= 2) {
            drawGpsSatellitePage(context, c, summary, gps, kpi)
        }

        c.newPage()
        c.text("Survey plot", c.h2)
        c.gap()

        val canvas = c.canvas ?: return
        val availW = PAGE_W - 2 * MARGIN
        val availH = 420f
        val frame = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = Color.rgb(120, 120, 120)
            isAntiAlias = true
        }
        val pad = 16f

        if (planId != null && indoor.isNotEmpty()) {
            val bmp = runCatching {
                BitmapFactory.decodeFile(FloorplanStore.file(context, planId).absolutePath)
            }.getOrNull()
            if (bmp != null) {
                val aspect = bmp.width.toFloat() / max(bmp.height, 1)
                var w = availW
                var h = w / aspect
                if (h > availH) { h = availH; w = h * aspect }
                val left = MARGIN + (availW - w) / 2f
                val top = c.y
                canvas.drawBitmap(
                    bmp, null,
                    Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt()), null,
                )

                // Interpolated coverage, under the sample dots so the real measurements stay
                // visible on top of the inference. Unmeasured area is left transparent rather than
                // filled: see Heatmap for why that is the whole point of the feature.
                val grid = heatmapFor(indoor, kpi, bmp.width.toFloat() / max(bmp.height, 1))
                val overlay = heatmapBitmap(grid, kpi)
                if (overlay != null) {
                    canvas.drawBitmap(
                        overlay, null,
                        Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt()),
                        Paint().apply { isFilterBitmap = true },
                    )
                }

                canvas.drawRect(left, top, left + w, top + h, frame)
                val dot = Paint().apply { isAntiAlias = true }
                indoor.forEach { p ->
                    dot.color = pointColor(p)
                    canvas.drawCircle(left + p.floorplanX!! * w, top + p.floorplanY!! * h, 3.2f, dot)
                }
                c.y = top + h + LINE
                c.y += drawLegend(c, kpi, MARGIN, c.y)
                c.gap(6f)
                c.text("$planId — ${indoor.size} positioned samples.", c.small)
                c.para(
                    "Shaded area is interpolated between measurements by inverse distance " +
                        "weighting: each point is the average of the samples near it, weighted by " +
                        // No markdown: this is drawn straight onto a PDF canvas, so asterisks
                        // arrive as asterisks. Emphasis comes from sentence order instead.
                        "one over the distance squared. Unshaded floor was not surveyed — " +
                        "nothing within the interpolation radius was measured there, so nothing " +
                        "is claimed about it. " +
                        String.format(
                            Locale.US,
                            "%.0f %% of the plan is within range of a measurement.",
                            grid.coveredFraction * 100,
                        ),
                )
                // Deliberately no north arrow and no scale bar. The operator supplies a plan image,
                // not a georeferenced raster, so neither its orientation nor its scale is known to
                // this app. Drawing either would be an invention the reader could not check.
                c.para(
                    "Positions were placed on the plan by the operator. The plan is not " +
                        "georeferenced, so no north arrow or distance scale is shown — neither " +
                        "its orientation nor its scale is known to the instrument.",
                )
                return
            }
            c.text("Floorplan image \"$planId\" was not available on this device.", c.small)
            c.gap()
        }

        if (planId == null && gps.size >= 2) {
            val bounds = Mercator.Bounds.of(gps.map { it.latitudeDeg!! to it.longitudeDeg!! })
                ?.expandedToAtLeast(MIN_PLOT_SPAN_M)
            if (bounds != null) {
                val basemap = TileProxy.Basemap.SATELLITE
                val zoom = Mercator.fitZoom(bounds, availW.toInt(), availH.toInt(), basemap.maxZoom)
                val proxy = TileProxy(File(context.cacheDir, "tiles"), basemap)
                val mercatorTop = c.y
                // Clip to the plot box so square tiles cannot spill past the grey boundary (and over
                // the title and captions); the boundary is stroked after the clip is lifted.
                canvas.save()
                canvas.clipRect(MARGIN, mercatorTop, MARGIN + availW, mercatorTop + availH)
                val tilesDrawn = drawMercatorBasemap(
                    canvas, proxy, bounds, zoom, MARGIN, mercatorTop, availW, availH,
                )
                val excluded = if (tilesDrawn > 0) {
                    drawMercatorTrackOverlay(c, gps, bounds, zoom, MARGIN, mercatorTop, availW, availH)
                } else 0
                canvas.restore()
                if (tilesDrawn > 0) {
                    canvas.drawRect(MARGIN, mercatorTop, MARGIN + availW, mercatorTop + availH, frame)
                    c.y = mercatorTop + availH + LINE
                    c.y += drawLegend(c, kpi, MARGIN, c.y)
                    c.gap(6f)
                    c.text(basemap.attribution, c.small)
                    c.para(
                        "${gps.size} GPS-located samples over satellite imagery, north up. S marks " +
                            "the start of the walk and E the end.",
                    )
                    drawGpsQualityNote(c, gps)
                    if (excluded > 0) {
                        c.para(
                            "$excluded sample(s) excluded from the trail as suspect GPS positions " +
                                "(implied speed too high from the last trusted fix -- likely " +
                                "multipath near a structure). Still counted in the statistics above.",
                        )
                    }
                    if (gps.any { SessionStats.areaOf(it) == SessionStats.Area.INDOOR }) {
                        c.para(
                            "Square markers are Indoor-tagged samples (set live via the " +
                                "Indoor/Outdoor buttons while walking); circles are Outdoor or " +
                                "unlabelled.",
                        )
                    }
                    return
                }
                // Zero tiles came back -- offline, or an external outage. Fall through to the
                // existing blank-background plot below rather than publish a half-drawn page.
            }
        }

        if (gps.size < 2) {
            c.text("No plottable track: this session has no GPS positions and no floorplan.", c.body)
            return
        }

        // Equirectangular, longitude scaled by cos(latitude) — without it the plot is stretched
        // east-west and anyone eyeballing distances is misled.
        val midLat = (summary.minLat + summary.maxLat) / 2
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(midLat))
        val spanX = max((summary.maxLon - summary.minLon) * mLon, 0.5)
        val spanY = max((summary.maxLat - summary.minLat) * mLat, 0.5)
        val plotW = availW - 2 * pad
        val plotH = availH - 2 * pad
        // One scale on both axes, so the plot is a map rather than a stretched scatter.
        val scale = minOf(plotW / spanX, plotH / spanY)
        val top = c.y
        val offX = MARGIN + pad + (plotW - spanX * scale).toFloat() / 2f
        val offY = top + pad + (plotH - spanY * scale).toFloat() / 2f

        canvas.drawRect(MARGIN, top, MARGIN + availW, top + availH, frame)

        val line = Paint().apply {
            color = Color.rgb(180, 180, 180); strokeWidth = 1f; isAntiAlias = true
        }
        val projected = gps.map {
            floatArrayOf(
                offX + ((it.longitudeDeg!! - summary.minLon) * mLon * scale).toFloat(),
                offY + ((summary.maxLat - it.latitudeDeg!!) * mLat * scale).toFloat(),
            )
        }
        val outlierFlags = gpsOutlierFlags(gps)
        for (i in 0 until projected.size - 1) {
            if (outlierFlags[i] || outlierFlags[i + 1]) continue
            canvas.drawLine(projected[i][0], projected[i][1], projected[i + 1][0], projected[i + 1][1], line)
        }
        val dot = Paint().apply { isAntiAlias = true }
        val outlierRing = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f
            color = Color.GRAY; isAntiAlias = true
        }
        gps.forEachIndexed { i, p ->
            if (outlierFlags[i]) {
                canvas.drawCircle(projected[i][0], projected[i][1], 2.6f, outlierRing)
            } else {
                dot.color = pointColor(p)
                val x = projected[i][0]; val y = projected[i][1]
                if (SessionStats.areaOf(p) == SessionStats.Area.INDOOR) {
                    canvas.drawRect(x - 2.6f, y - 2.6f, x + 2.6f, y + 2.6f, dot)
                } else {
                    canvas.drawCircle(x, y, 2.6f, dot)
                }
            }
        }

        // Start and end, so a reader can tell which way the walk ran. Without them an
        // out-and-back track is indistinguishable from a single pass.
        val marker = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1.3f
            color = Color.BLACK; isAntiAlias = true
        }
        val tag = c.paint(8f, bold = true)
        val startIdx = outlierFlags.indexOfFirst { !it }.takeIf { it >= 0 } ?: 0
        val endIdx = outlierFlags.indexOfLast { !it }.takeIf { it >= 0 } ?: projected.lastIndex
        listOf(projected[startIdx] to "S", projected[endIdx] to "E").forEach { (pt, label) ->
            canvas.drawCircle(pt[0], pt[1], 5.5f, marker)
            canvas.drawText(label, pt[0] + 7f, pt[1] + 3f, tag)
        }

        drawNorthArrow(c, MARGIN + availW - 18f, top + 8f)
        drawScaleBar(c, MARGIN + pad, top + availH - 10f, scale, plotW / 4f)

        c.y = top + availH + LINE
        c.y += drawLegend(c, kpi, MARGIN, c.y)
        c.gap(6f)
        c.para(
            "${gps.size} GPS-located samples, north up, equal scale on both axes. " +
                "S marks the start of the walk and E the end.",
        )
        drawGpsQualityNote(c, gps)
        val excluded = outlierFlags.count { it }
        if (excluded > 0) {
            c.para(
                "$excluded sample(s) excluded from the trail as suspect GPS positions (implied " +
                    "speed too high from the last trusted fix -- likely multipath near a " +
                    "structure). Still counted in the statistics above.",
            )
        }
        if (gps.any { SessionStats.areaOf(it) == SessionStats.Area.INDOOR }) {
            c.para(
                "Square markers are Indoor-tagged samples (set live via the Indoor/Outdoor " +
                    "buttons while walking); circles are Outdoor or unlabelled.",
            )
        }
    }

    /**
     * Builds the interpolation grid for the plan.
     *
     * Resolution is modest on purpose. A finer grid does not add information -- the information is
     * bounded by the samples, not the pixels -- it only makes a sparse survey look dense, which is
     * the failure this feature is built to avoid.
     */
    private fun heatmapFor(indoor: List<TrackPoint>, kpi: SessionStats.Kpi, aspectWH: Float): Heatmap.Grid {
        val samples = indoor.mapNotNull { p ->
            val v = if (kpi == SessionStats.Kpi.CELL_RSRP) p.rsrpDbm else p.rssiDbm
            v?.let { Heatmap.Sample(p.floorplanX!!, p.floorplanY!!, it.toFloat()) }
        }
        val gw = 200
        val gh = max(1, (gw / max(aspectWH, 0.05f)).toInt())
        // aspect here is height/width, which is what Heatmap measures distance in.
        return Heatmap.interpolate(samples, gw, gh, radius = HEATMAP_RADIUS, aspect = 1f / max(aspectWH, 0.05f))
    }

    /**
     * Interpolation influence radius, as a fraction of the plan width. Tightened from Heatmap's own
     * 0.12 default: on a large plan 12% of the width is a wide circle, so a sparse arbitrary walk
     * painted confident blobs across rooms it never reached. Smaller keeps the shaded area close to
     * where measurements actually were -- the uncovered floor simply stays blank, which is the
     * honest result the feature exists to produce.
     */
    private const val HEATMAP_RADIUS = 0.07f

    /** Renders the grid to a translucent bitmap, leaving unmeasured cells fully transparent. */
    private fun heatmapBitmap(grid: Heatmap.Grid, kpi: SessionStats.Kpi): Bitmap? {
        if (grid.covered == 0) return null
        val pixels = IntArray(grid.width * grid.height)
        for (i in pixels.indices) {
            val v = grid.values[i]
            pixels[i] = if (v == null) {
                Color.TRANSPARENT
            } else {
                val argb = if (kpi == SessionStats.Kpi.CELL_RSRP) {
                    com.nhnengineering.rftest.model.RsrpBucket.of(Math.round(v))?.argb
                } else {
                    com.nhnengineering.rftest.model.RssiBucket.of(Math.round(v))?.argb
                } ?: Color.GRAY
                // Translucent so the plan's walls and labels stay readable underneath. A heatmap
                // that hides the floorplan makes its own findings unplaceable.
                (argb and 0x00FFFFFF) or (0xA0 shl 24)
            }
        }
        return Bitmap.createBitmap(pixels, grid.width, grid.height, Bitmap.Config.ARGB_8888)
    }

    private fun pointColor(p: TrackPoint): Int {
        val argb = com.nhnengineering.rftest.model.RsrpBucket.of(p.rsrpDbm)?.argb
            ?: com.nhnengineering.rftest.model.RssiBucket.of(p.rssiDbm)?.argb
        return argb ?: Color.GRAY
    }

    /**
     * The limitations section.
     *
     * Built from what this session actually contains, so it states real caveats rather than
     * boilerplate. The first thing a competent reviewer looks for is what the instrument could not
     * measure; a report that volunteers it is more credible, not less.
     */
    private fun methodologyNotes(
        summary: SessionSummary,
        points: List<TrackPoint>,
        report: SessionStats.Report,
    ): List<Pair<String, String>> = buildList {
        // First, because it qualifies every number that follows.
        //
        // A level is only meaningful next to the receiver that measured it, and receivers differ by
        // more than most readers assume. The handset this project developed on -- a Pixel 6 Pro --
        // is widely reported to have among the weakest cellular reception of its generation, its
        // Exynos modem consistently capturing less than the Qualcomm parts it shipped against. A
        // survey walked on it reads lower than the same survey walked on a better receiver, and a
        // reader comparing this report against one produced elsewhere deserves to know which
        // instrument produced which.
        //
        // Naming the handset does not quantify the offset. It does let a reader ask.
        if (summary.devices.isNotEmpty()) {
            add(
                "Measuring handset" to
                    summary.devices.joinToString("; ") + ". " +
                        "Levels are what this receiver reported. Handsets differ materially in " +
                        "receive performance -- several dB between models is ordinary -- so " +
                        "readings are not directly comparable with a survey walked on different " +
                        "hardware, and an absolute level should be read as this device's view " +
                        "rather than as a property of the site alone."
            )
        }
        // Immediately after the handset, because it qualifies the statistics the same way.
        if (summary.bandLocks.isNotEmpty()) {
            val observed = points.mapNotNull { it.cellBand }.distinct()
            val findings = BandLockCheck.check(summary.bandLocks, observed)
            val verdict = when {
                // No bands observed is not agreement. BandLockCheck stays silent here because it
                // has nothing to compare, and reporting that silence as "consistent" would turn an
                // absence of evidence into a confirmation -- the precise error this whole section
                // exists to prevent.
                observed.isEmpty() ->
                    "No band was recorded anywhere in this session, so the declaration could not " +
                        "be checked against anything."
                findings.isEmpty() ->
                    "Bands observed are consistent with that lock."
                else ->
                    findings.joinToString(" ") { "${it.headline}: ${it.detail}" }
            }
            // Same rule as the technology lock below: only when every recorded value carries the
            // verified marker may the report say this app applied it.
            val bandsAppliedHere = summary.bandLocks.all { BandLock.isVerifiedLabel(it) }
            val bandProvenance = if (bandsAppliedHere) {
                "Applied by this app over a direct modem interface, and the modem's own band " +
                    "masks were read back to confirm it. That shows what the modem was allowed to " +
                    "use; the check against the bands actually seen follows. Only the standalone " +
                    "NR mask is restricted -- an NSA connection is not band-limited by it, and no " +
                    "specific channel (EARFCN/ARFCN) can be selected this way. "
            } else {
                "This was set outside this app, in the handset's own RF toolkit or a diagnostic " +
                    "tool, and is recorded here as the operator declared it -- nothing in this " +
                    "session's record confirms one is in force. "
            }
            add(
                (if (bandsAppliedHere) "Band lock applied" else "Band lock declared") to
                    "${summary.bandLocks.joinToString(", ")}. $bandProvenance" +
                        "A locked walk prevents the handset doing what a subscriber's phone would " +
                        "do, so compliance, dominance and overlap describe the locked band rather " +
                        "than the service a user would receive. " + verdict
            )
        }
        if (summary.ratLocks.isNotEmpty()) {
            val rats = points.mapNotNull { it.rat }
            val distribution = rats.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(", ") { (rat, n) ->
                    String.format(Locale.US, "%s %.0f %%", rat, 100.0 * n / rats.size)
                }
            // A declaration and a verified lock are different claims and must read differently.
            // Only when every value recorded this session carries the verified marker may the
            // report say the app itself confirmed it -- one unverified entry in the set means
            // some part of the session was self-reported, and the cautious wording is the honest
            // one for the whole thing.
            val allVerified = summary.ratLocks.isNotEmpty() &&
                summary.ratLocks.all { TechnologyLock.isVerifiedLabel(it) }
            val provenance = if (allVerified) {
                "Applied and confirmed by this app, over a direct modem interface rather than " +
                    "the platform's own network-type API. "
            } else {
                "Set outside this app and recorded as declared; nothing here performs or " +
                    "verifies it. "
            }
            add(
                "Technology lock declared" to
                    "${summary.ratLocks.joinToString(", ")}. $provenance" +
                        if (rats.isEmpty()) {
                            "No radio technology was recorded in this session, so there is nothing " +
                                "to compare the declaration against."
                        } else {
                            "Technologies actually observed: $distribution. These are stated rather " +
                                "than checked against the declaration, because the two are written " +
                                "in different vocabularies and a machine comparison would " +
                                "manufacture disagreements as readily as find them."
                        }
            )
        }
        add(
            "Sampling" to
                "Continuous logging at approximately one sample per second, written to CSV as " +
                    "recorded. The full dataset accompanies this report."
        )
        add(
            "Threshold" to
                "${report.thresholdDbm} dBm applied to ${report.kpi.label}. Wi-Fi and cellular " +
                    "use separate scales that differ by roughly 30 dB; they are not comparable to " +
                    "one another."
        )
        if (report.stats.missing > 0) {
            add(
                "Missing measurements" to
                    "${report.stats.missing} of ${report.stats.samples + report.stats.missing} " +
                        "samples carried no ${report.kpi.label} reading. These are excluded from " +
                        "the statistics rather than counted as zero, which would understate " +
                        "coverage substantially."
            )
        }
        if (summary.indoorPointCount > 0) {
            add(
                "Indoor positioning" to
                    "Positions inside the building were placed manually on a floorplan by the " +
                        "operator, because GPS is unreliable or unavailable indoors. They are " +
                        "accurate to the operator's judgement, not to a surveyed coordinate."
            )
        }
        if (summary.pointCount > 0) {
            val accs = points.mapNotNull { it.accuracyM }
            if (accs.isNotEmpty()) {
                val sats = points.mapNotNull { it.gnssSatellitesUsed }
                // Older sessions carry no satellite data at all -- omit the sentence rather than
                // show a placeholder. Informational only: this is not yet used to flag or exclude
                // any position, since satellite count correlating with a bad fix is a hypothesis
                // this project has not yet validated against a real bad segment.
                val satNote = if (sats.isNotEmpty()) {
                    " Satellites used per fix ranged from ${sats.min()} to ${sats.max()}."
                } else ""
                add(
                    "GPS accuracy" to
                        "Reported fix accuracy ranged from ${accs.min().toInt()} m to " +
                            "${accs.max().toInt()} m. Positions are no better than this figure." +
                            satNote
                )
            }
        }
        val buildingCrossings = SessionStats.buildingCrossings(points, report.kpi)
        if (buildingCrossings.isNotEmpty()) {
            add(
                "Building entry / wall loss" to
                    "Each figure compares one outdoor sample against one indoor sample at a single " +
                        "doorway crossing, not a controlled measurement -- ordinary fading affects " +
                        "each side independently. Only crossings where the serving cell was the " +
                        "same immediately before and after are scored, so a difference is never " +
                        "conflated with a handover to a different site. Treat this as a field " +
                        "indicator of which structures attenuate meaningfully, not a laboratory " +
                        "loss figure."
            )
        }
        val staleNeighbours = points.any { it.coChannel != null }
        if (staleNeighbours) {
            add(
                "Neighbour data" to
                    "Wi-Fi neighbour scans are rate-limited by the operating system to roughly " +
                        "four per two minutes, so co-channel and adjacent-channel counts reflect " +
                        "a slightly older observation than the serving-cell reading beside them. " +
                        "Each sample records the age of its neighbour data."
            )
        }
        if (report.kpi == SessionStats.Kpi.CELL_RSRP) {
            // Only stated when this session actually shows the disparity. A session where the
            // fields track one another should not carry a caveat that does not apply to it.
            val rsrp = SessionStats.cadence(points) { it.rsrpDbm }
            val quality = listOf(
                "SINR" to SessionStats.cadence(points) { it.sinrDb },
                "RSRQ" to SessionStats.cadence(points) { it.rsrqDb },
            ).filter { (_, c) -> c.samples > 0 && c.changes * 2 <= rsrp.changes }

            if (quality.isNotEmpty() && rsrp.changes > 0) {
                val detail = quality.joinToString("; ") { (name, c) ->
                    val held = c.longestRunSeconds
                        ?.let { s -> " and held one value for ${s.toInt()} s" }
                        ?: " and held one value for ${c.longestRunSamples} samples"
                    "$name changed ${c.changes} times$held"
                }
                add(
                    "Measurement cadence" to
                        "Every field is written on every sample, but the modem does not refresh " +
                            "them all at the same rate. Over this session RSRP changed " +
                            "${rsrp.changes} times, while $detail. The quality metrics are " +
                            "therefore not simultaneous with the RSRP printed beside them, and a " +
                            "single sample should not be read as one instant across all columns."
                )
            }

            add(
                "Cellular measurement" to
                    "Cellular values are read from the handset's own radio interface. Neighbour " +
                        "cell reporting and 5G NSA secondary-cell data are chipset-dependent and " +
                        "may be incomplete on this device."
            )
        }
        add(
            "Scope" to
                "This survey records what one handset observed along one route at one point in " +
                    "time. It is not a substitute for a multi-device or multi-operator assessment, " +
                    "and conditions vary with load, time of day and occupancy."
        )
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) {
            String.format(Locale.US, "%d h %02d m", s / 3600, (s % 3600) / 60)
        } else {
            String.format(Locale.US, "%d m %02d s", s / 60, s % 60)
        }
    }
}
