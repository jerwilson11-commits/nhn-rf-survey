package com.nhnengineering.rftest.report

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
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
            c.kv("Mean dominant sectors", String.format(Locale.US, "%.2f", dom.meanCount))
            c.kv(
                "Overlap (2 or more within ${dom.windowDb} dB)",
                String.format(Locale.US, "%.1f %% of samples", dom.overlapPct),
            )
            if (dom.excluded > 0) {
                c.kv("Samples excluded", "${dom.excluded}  (cells seen, none with a level)")
            }
            c.gap()
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

        // ---- Coverage holes -----------------------------------------------
        c.ensure(120f)
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
        methodologyNotes(summary, points, report).forEach {
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
        val gps = points.filter { it.hasGpsPosition }

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
        for (i in 0 until projected.size - 1) {
            canvas.drawLine(projected[i][0], projected[i][1], projected[i + 1][0], projected[i + 1][1], line)
        }
        val dot = Paint().apply { isAntiAlias = true }
        gps.forEachIndexed { i, p ->
            dot.color = pointColor(p)
            canvas.drawCircle(projected[i][0], projected[i][1], 2.6f, dot)
        }

        // Start and end, so a reader can tell which way the walk ran. Without them an
        // out-and-back track is indistinguishable from a single pass.
        val marker = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1.3f
            color = Color.BLACK; isAntiAlias = true
        }
        val tag = c.paint(8f, bold = true)
        listOf(projected.first() to "S", projected.last() to "E").forEach { (pt, label) ->
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
                add(
                    "GPS accuracy" to
                        "Reported fix accuracy ranged from ${accs.min().toInt()} m to " +
                            "${accs.max().toInt()} m. Positions are no better than this figure."
                )
            }
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
