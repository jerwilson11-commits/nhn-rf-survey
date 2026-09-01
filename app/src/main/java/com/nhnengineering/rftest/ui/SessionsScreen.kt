package com.nhnengineering.rftest.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nhnengineering.rftest.model.RsrpBucket
import com.nhnengineering.rftest.model.RssiBucket
import com.nhnengineering.rftest.session.FloorplanStore
import com.nhnengineering.rftest.session.SessionCsvWriter
import com.nhnengineering.rftest.session.SessionReader
import com.nhnengineering.rftest.session.SessionSummary
import com.nhnengineering.rftest.session.TrackExporters
import com.nhnengineering.rftest.session.TrackPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

private val LIST_DATE = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())

@Composable
fun SessionsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<Pair<SessionSummary, List<TrackPoint>>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        files = SessionReader.list(SessionCsvWriter.sessionsDir(context))
    }

    val current = selected
    if (current == null) {
        SessionList(
            modifier = modifier,
            files = files,
            onOpen = { f ->
                scope.launch {
                    status = null
                    selected = SessionReader.read(f)
                    if (selected == null) status = "Could not read ${f.name}"
                }
            },
        )
    } else {
        SessionDetail(
            modifier = modifier,
            summary = current.first,
            points = current.second,
            status = status,
            onBack = { selected = null; status = null },
            onExportKml = {
                scope.launch {
                    val out = exportFile(context, current.first.displayName, "kml")
                    TrackExporters.writeKml(current.first, current.second, out)
                    status = "Wrote ${out.name}"
                    share(context, out, "application/vnd.google-earth.kml+xml")
                }
            },
            onExportGeoJson = {
                scope.launch {
                    val out = exportFile(context, current.first.displayName, "geojson")
                    TrackExporters.writeGeoJson(current.first, current.second, out)
                    status = "Wrote ${out.name}"
                    share(context, out, "application/geo+json")
                }
            },
            onShareCsv = { share(context, current.first.file, "text/csv") },
        )
    }
}

@Composable
private fun SessionList(modifier: Modifier, files: List<File>, onOpen: (File) -> Unit) {
    if (files.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No sessions yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Record one on the Live tab. Sessions are written to " +
                    "Android/data/com.nhnengineering.rftest/files/sessions/",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        items(files, key = { it.absolutePath }) { f ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(f) },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        f.nameWithoutExtension,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        LIST_DATE.format(Date(f.lastModified())),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${(f.length() / 1024)} KB",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionDetail(
    modifier: Modifier,
    summary: SessionSummary,
    points: List<TrackPoint>,
    status: String?,
    onBack: () -> Unit,
    onExportKml: () -> Unit,
    onExportGeoJson: () -> Unit,
    onShareCsv: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            OutlinedButton(onClick = onBack) { Text("← All sessions") }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        summary.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    KeyValue("Rows", summary.rowCount.toString())
                    KeyValue(
                        "Geo-tagged",
                        "${summary.pointCount}" +
                            if (summary.pointCount < summary.rowCount) {
                                " (${summary.rowCount - summary.pointCount} without a fix)"
                            } else "",
                    )
                    KeyValue("Duration", formatDuration(summary.durationMs))
                    if (summary.hasIndoorTrack) {
                        KeyValue("Floorplan points", summary.indoorPointCount.toString())
                        if (summary.waypoints.isNotEmpty()) {
                            KeyValue("Waypoints", summary.waypoints.joinToString(", "))
                        }
                    }
                    KeyValue(
                        "RSSI range",
                        if (summary.rssiMax != null && summary.rssiMin != null) {
                            "${summary.rssiMax} to ${summary.rssiMin} dBm " +
                                "(${summary.rssiMax - summary.rssiMin} dB)"
                        } else "—",
                    )
                }
            }
        }
        if (summary.hasIndoorTrack) {
            item { SessionFloorplanCard(summary, points) }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Track", style = MaterialTheme.typography.titleMedium)
                    if (summary.hasTrack) {
                        TrackPlot(
                            summary = summary,
                            points = points,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                        RssiLegend()
                    } else {
                        Text(
                            if (summary.hasIndoorTrack) {
                                "No GPS track — this session was positioned on a floorplan " +
                                    "instead, which is expected indoors. KML and GeoJSON export " +
                                    "only GPS-located samples, so they will be sparse or empty."
                            } else {
                                "No usable track — all fixes are at effectively the same point. " +
                                    "Stationary sessions still export fine."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Export", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "KML opens in Google Earth with the RSSI colouring already applied. " +
                            "GeoJSON carries the same buckets for QGIS or a web map.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onExportKml, modifier = Modifier.fillMaxWidth()) {
                        Text("Export KML (Google Earth)")
                    }
                    Button(onClick = onExportGeoJson, modifier = Modifier.fillMaxWidth()) {
                        Text("Export GeoJSON")
                    }
                    OutlinedButton(onClick = onShareCsv, modifier = Modifier.fillMaxWidth()) {
                        Text("Share raw CSV")
                    }
                    status?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/**
 * Plots the track with each sample coloured by RSSI.
 *
 * Drawn directly rather than over a tile basemap: this tool is used inside buildings and
 * basements where a street map shows nothing useful and there is often no connectivity. The
 * geographic version is the KML export, opened in Google Earth. What matters on the handset is
 * the shape of the walk and where the signal fell away — which needs no basemap at all.
 */
@Composable
private fun TrackPlot(summary: SessionSummary, points: List<TrackPoint>, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier.clip(RoundedCornerShape(6.dp))) {
        val pad = 16f
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad

        // Equirectangular projection. Longitude degrees shrink by cos(latitude); ignoring that
        // stretches the plot east-west — at 26°N by about 11%, enough to make a square walk look
        // rectangular and to mislead anyone eyeballing distances.
        val midLat = (summary.minLat + summary.maxLat) / 2
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(midLat))

        val spanXm = max((summary.maxLon - summary.minLon) * mPerDegLon, 0.5)
        val spanYm = max((summary.maxLat - summary.minLat) * mPerDegLat, 0.5)
        // One scale for both axes, so the plot stays geometrically honest.
        val scale = minOf(w / spanXm, h / spanYm).toFloat()
        val offX = pad + (w - spanXm * scale).toFloat() / 2f
        val offY = pad + (h - spanYm * scale).toFloat() / 2f

        fun project(p: TrackPoint) = Offset(
            x = offX + ((p.longitudeDeg!! - summary.minLon) * mPerDegLon * scale).toFloat(),
            // Screen y grows downward, latitude grows northward — hence maxLat minus.
            y = offY + ((summary.maxLat - p.latitudeDeg!!) * mPerDegLat * scale).toFloat(),
        )

        val geoPoints = points.filter { it.hasGpsPosition }
        val projected = geoPoints.map { project(it) }
        for (i in 0 until projected.size - 1) {
            drawLine(
                color = outline.copy(alpha = 0.5f),
                start = projected[i],
                end = projected[i + 1],
                strokeWidth = 2f,
            )
        }
        geoPoints.forEachIndexed { i, p ->
            val bucket = RssiBucket.of(p.rssiDbm)
            drawCircle(
                color = bucket?.let { Color(it.argb) } ?: Color.Gray.copy(alpha = 0.4f),
                radius = 5f,
                center = projected[i],
            )
        }
        // Start and end markers: an out-and-back looks identical to a one-way trip without them.
        projected.firstOrNull()?.let {
            drawCircle(color = onSurface, radius = 9f, center = it, style = Stroke(width = 3f))
        }
        projected.lastOrNull()?.let {
            drawCircle(color = onSurface, radius = 5f, center = it)
        }

        // Scale bar, chosen as a round number covering about a quarter of the plot.
        val quarterM = spanXm / 4
        val niceM = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000)
            .lastOrNull { it <= quarterM } ?: 1
        val barPx = (niceM * scale).toFloat()
        val y = size.height - pad / 2
        drawLine(onSurface, Offset(pad, y), Offset(pad + barPx, y), strokeWidth = 3f)
    }
    Text(
        text = "○ start · ● end · one scale on both axes",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun RssiLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RssiBucket.entries.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(12.dp)) { drawCircle(Color(b.argb)) }
                Text(
                    "  ${b.label}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun exportFile(context: Context, baseName: String, ext: String): File {
    val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
    return File(dir, "$baseName.$ext")
}

private fun share(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) {
        String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    } else {
        String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}

/**
 * A recorded session drawn back onto its floorplan.
 *
 * Recording indoor positions is only half the feature — a walk you cannot review afterwards
 * produces no deliverable. Points are coloured by whichever radio was serving, cellular RSRP taking
 * precedence over Wi-Fi RSSI, so the plan reads the way the venue was actually surveyed.
 */
@Composable
private fun SessionFloorplanCard(summary: SessionSummary, points: List<TrackPoint>) {
    val context = LocalContext.current
    val planId = summary.floorplanIds.firstOrNull()
    var bitmap by remember(planId) { mutableStateOf<ImageBitmap?>(null) }
    var aspect by remember(planId) { mutableStateOf(1f) }

    LaunchedEffect(planId) {
        if (planId == null) return@LaunchedEffect
        val f = FloorplanStore.file(context, planId)
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        if (bmp != null) {
            aspect = if (bmp.height > 0) bmp.width.toFloat() / bmp.height else 1f
            bitmap = bmp.asImageBitmap()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Floorplan", style = MaterialTheme.typography.titleMedium)
            val bmp = bitmap
            val indoor = points.filter { it.hasIndoorPosition && it.floorplanId == planId }

            if (planId == null) {
                Text("No floorplan recorded.", style = MaterialTheme.typography.bodySmall)
            } else if (bmp == null) {
                // The image lives in app storage keyed by the filename in the CSV. If it is gone,
                // say so plainly — the positions are still in the file and still valid, they just
                // cannot be drawn.
                Text(
                    "Floorplan image \"$planId\" is not on this device. The positions are still " +
                        "in the CSV; re-import the image to view them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )
                    indoor.forEach { p ->
                        val c = Offset(p.floorplanX!! * size.width, p.floorplanY!! * size.height)
                        val argb = RsrpBucket.of(p.rsrpDbm)?.argb ?: RssiBucket.of(p.rssiDbm)?.argb
                        drawCircle(
                            color = argb?.let { Color(it) } ?: Color.Gray,
                            radius = 8f,
                            center = c,
                        )
                    }
                }
                Text(
                    "$planId · ${indoor.size} positioned samples",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                RssiLegend()
            }
        }
    }
}
