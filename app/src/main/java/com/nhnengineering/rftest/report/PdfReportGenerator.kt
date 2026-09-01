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
import kotlin.math.max

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

        // ---- Header -------------------------------------------------------
        c.text("RF Coverage Survey Report", c.title)
        c.gap()
        c.text(summary.displayName, c.h2)
        summary.startedAtUtcMillis?.let { c.text("Recorded ${DATE.format(Date(it))}", c.small) }
        c.text(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            c.small,
        )
        c.text("NHN Engineering & Consultants", c.small)
        c.gap(); c.rule()

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
        c.text(
            "Percentiles are reported alongside the mean because a mean conceals the tail, and " +
                "coverage is judged on the worst areas rather than the average one.",
            c.small,
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

        // ---- Coverage holes -----------------------------------------------
        c.ensure(120f)
        c.text("Coverage holes", c.h2)
        if (report.holes.isEmpty()) {
            c.text("No contiguous run of samples fell below the threshold.", c.body)
        } else {
            c.text(
                "Contiguous runs below threshold, worst first. A compliance percentage alone " +
                    "cannot distinguish failures spread thinly across a site from failures " +
                    "concentrated in one place; only the latter tells an engineer where to return.",
                c.small,
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
        drawPlot(context, c, summary, points)

        // ---- Methodology --------------------------------------------------
        c.newPage()
        c.text("Methodology and limitations", c.h2)
        c.gap()
        methodologyNotes(summary, points, report).forEach {
            c.text("•  ${it.first}", c.body)
            wrap(it.second, 96).forEach { line -> c.text(line, c.small, indent = 12f) }
            c.gap(4f)
        }

        c.finish()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        out
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
    ) {
        val planId = summary.floorplanIds.firstOrNull()
        val indoor = points.filter { it.hasIndoorPosition }
        val gps = points.filter { it.hasGpsPosition }

        c.newPage()
        c.text("Survey plot", c.h2)
        c.gap()

        val canvas = c.canvas ?: return
        val availW = PAGE_W - 2 * MARGIN
        val availH = 430f

        if (planId != null && indoor.isNotEmpty()) {
            val bmp = runCatching {
                BitmapFactory.decodeFile(FloorplanStore.file(context, planId).absolutePath)
            }.getOrNull()
            if (bmp != null) {
                val aspect = bmp.width.toFloat() / max(bmp.height, 1)
                var w = availW
                var h = w / aspect
                if (h > availH) { h = availH; w = h * aspect }
                val left = MARGIN
                val top = c.y
                canvas.drawBitmap(bmp, null, Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt()), null)
                val dot = Paint().apply { isAntiAlias = true }
                indoor.forEach { p ->
                    dot.color = pointColor(p)
                    canvas.drawCircle(left + p.floorplanX!! * w, top + p.floorplanY!! * h, 3.2f, dot)
                }
                c.y = top + h + LINE
                c.text("$planId — ${indoor.size} positioned samples", c.small)
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
        val scale = minOf(availW / spanX, availH / spanY)
        val offX = MARGIN + (availW - spanX * scale).toFloat() / 2f
        val top = c.y
        val offY = top + (availH - spanY * scale).toFloat() / 2f

        val line = Paint().apply { color = Color.rgb(180, 180, 180); strokeWidth = 1f; isAntiAlias = true }
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
        c.y = top + availH + LINE
        c.text("${gps.size} GPS-located samples. One scale on both axes.", c.small)
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

    private fun wrap(text: String, width: Int): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (w in words) {
            if (line.isNotEmpty() && line.length + 1 + w.length > width) {
                lines += line.toString(); line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(w)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
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
