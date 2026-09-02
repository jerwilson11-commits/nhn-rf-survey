package com.nhnengineering.rftest.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.map.Mercator
import com.nhnengineering.rftest.map.TileCache
import com.nhnengineering.rftest.model.RsrpBucket
import com.nhnengineering.rftest.model.RssiBucket
import com.nhnengineering.rftest.service.RecordingState
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Satellite map of the walk, on the handset.
 *
 * The same view the laptop gets, for when there is no laptop — which is most of the time on a
 * quick site visit. It answers one question the operator cannot otherwise answer while walking:
 * **have I already covered this ground?**
 *
 * Tiles come from the phone's own cache and connection (see `TileProxy`), so this costs cellular
 * data. The toggle and the cache figure are on screen rather than buried, because that cost lands
 * on the same radio the survey is measuring and the operator should be able to see it and switch
 * it off.
 */
@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val track by RecordingState.liveTrack.collectAsState()
    val recording by RecordingState.active.collectAsState()
    val fix by RecordingState.fix.collectAsState()

    var showImagery by remember { mutableStateOf(true) }
    // Bumped when a tile finishes loading, purely to force a redraw. Compose does not observe the
    // cache, and without this the imagery appears only when some other state happens to change.
    var tileGeneration by remember { mutableIntStateOf(0) }

    val tiles = remember { TileCache(context, scope) { tileGeneration++ } }
    DisposableEffect(Unit) { onDispose { } }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (recording) "Route walked" else "Last route",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Satellite", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = showImagery, onCheckedChange = { showImagery = it })
                    }
                }

                if (track.isEmpty()) {
                    Text(
                        if (recording) {
                            "Waiting for a GPS fix. Indoor samples with no fix are still recorded " +
                                "to the file; they simply cannot be drawn on a map."
                        } else {
                            "No route yet. Start a recording on the Live tab and the trail will " +
                                "build here as you walk."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    // Referenced so Compose redraws when a tile lands.
                    @Suppress("UNUSED_EXPRESSION") tileGeneration

                    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        // Clipped to the canvas. Tiles are drawn through the native canvas, which
                        // Compose does not bound for us: without this a tile whose edge falls
                        // outside the map spills over the caption beneath it and past the card,
                        // which is exactly what the first build did.
                        clipRect(0f, 0f, size.width, size.height) {
                            drawWalkMap(
                                track = track,
                                tiles = tiles,
                                showImagery = showImagery,
                                currentLat = fix?.latitudeDeg,
                                currentLon = fix?.longitudeDeg,
                            )
                        }
                    }
                    Text(
                        "${track.size} located samples. North is up. The ring is your current " +
                            "position.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Imagery", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Imagery © Esri, Maxar, Earthstar Geographics. Tiles are fetched over this " +
                        "phone's own connection and cached, so they cost mobile data — and they " +
                        "use the same radio the survey is measuring. Switch the layer off if a " +
                        "measurement matters more than the picture.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Cached: " + formatBytes(tiles.diskBytes()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { tiles.clear(); tileGeneration++ }) { Text("Clear cache") }
                }
            }
        }
    }
}

/**
 * Draws imagery, trail and furniture.
 *
 * Kept separate from the composable so the drawing logic reads as drawing logic. Everything is in
 * Web Mercator — see [Mercator] for why matching the tiles' projection is not optional.
 */
private fun DrawScope.drawWalkMap(
    track: List<RecordingState.LiveFix>,
    tiles: TileCache,
    showImagery: Boolean,
    currentLat: Double?,
    currentLon: Double?,
) {
    val bounds = Mercator.Bounds.of(track.map { it.lat to it.lon })
        ?.expandedToAtLeast(MIN_SPAN_M) ?: return

    val pad = 14f
    val w = size.width - 2 * pad
    val h = size.height - 2 * pad
    if (w <= 0 || h <= 0) return

    val z = Mercator.fitZoom(bounds, w.toInt(), h.toInt())
    val wx0 = Mercator.lonToTileX(bounds.minLon, z)
    val wx1 = Mercator.lonToTileX(bounds.maxLon, z)
    val wy0 = Mercator.latToTileY(bounds.maxLat, z)
    val wy1 = Mercator.latToTileY(bounds.minLat, z)

    val tilePx = Mercator.TILE_SIZE
    val scale = minOf(w / ((wx1 - wx0) * tilePx).toFloat(), h / ((wy1 - wy0) * tilePx).toFloat())
    val offX = pad + (w - ((wx1 - wx0) * tilePx * scale).toFloat()) / 2f
    val offY = pad + (h - ((wy1 - wy0) * tilePx * scale).toFloat()) / 2f

    fun project(lat: Double, lon: Double): Offset = Offset(
        offX + ((Mercator.lonToTileX(lon, z) - wx0) * tilePx * scale).toFloat(),
        offY + ((Mercator.latToTileY(lat, z) - wy0) * tilePx * scale).toFloat(),
    )

    if (showImagery) {
        val x0 = floor(wx0).toInt()
        val x1 = floor(wx1).toInt()
        val y0 = floor(wy0).toInt()
        val y1 = floor(wy1).toInt()
        // A hard ceiling, so a wide-area session cannot ask the phone for hundreds of tiles over
        // the link it is trying to measure.
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() <= MAX_TILES) {
            for (tx in x0..x1) {
                for (ty in y0..y1) {
                    val bitmap = tiles.get(z, tx, ty) ?: continue
                    val topLeft = Offset(
                        offX + ((tx - wx0) * tilePx * scale).toFloat(),
                        offY + ((ty - wy0) * tilePx * scale).toFloat(),
                    )
                    drawContext.canvas.nativeCanvas.drawBitmap(
                        bitmap,
                        null,
                        android.graphics.RectF(
                            topLeft.x,
                            topLeft.y,
                            topLeft.x + tilePx * scale,
                            topLeft.y + tilePx * scale,
                        ),
                        null,
                    )
                }
            }
        }
    }

    val points = track.map { project(it.lat, it.lon) }

    // Bright and thick: a thin grey line disappears over satellite imagery.
    for (i in 0 until points.size - 1) {
        drawLine(
            color = Color.White.copy(alpha = 0.75f),
            start = points[i],
            end = points[i + 1],
            strokeWidth = 2.5f,
        )
    }

    points.forEachIndexed { i, p ->
        val argb = RsrpBucket.of(track[i].rsrpDbm)?.argb ?: RssiBucket.of(track[i].rssiDbm)?.argb
        drawCircle(Color(argb ?: 0xFF7A7A7A.toInt()), radius = 6f, center = p)
        // A dark ring so a green dot stays readable over grass and a red one over a roof.
        drawCircle(Color.Black.copy(alpha = 0.55f), radius = 6f, center = p, style = Stroke(1.5f))
    }

    // Current position. The live fix rather than the last trail point, because the trail is
    // thinned to one point every couple of seconds and would lag the operator.
    val here = if (currentLat != null && currentLon != null) {
        project(currentLat, currentLon)
    } else {
        points.lastOrNull()
    }
    here?.let {
        drawCircle(Color.White, radius = 15f, center = it, style = Stroke(3.5f))
        drawCircle(Color.Black.copy(alpha = 0.5f), radius = 18f, center = it, style = Stroke(1.5f))
    }

    drawScaleBar(bounds.midLat, z, scale, size)
    drawNorthArrow(size)
}

/** Scale bar snapped to a round number of metres, derived at this latitude. */
private fun DrawScope.drawScaleBar(midLat: Double, zoom: Int, scale: Float, size: Size) {
    val mPerPx = Mercator.metresPerPixel(midLat, zoom) / scale
    if (mPerPx <= 0 || !mPerPx.isFinite()) return

    val targetM = size.width / 4f * mPerPx
    if (targetM <= 0) return
    val base = 10.0.pow(floor(log10(targetM)))
    val niceM = listOf(5.0, 2.0, 1.0).map { it * base }.firstOrNull { it <= targetM } ?: base
    val barPx = (niceM / mPerPx).toFloat()
    if (barPx < 20f || barPx > size.width) return

    val y = size.height - 22f
    val x = 20f
    drawLine(Color.White, Offset(x, y), Offset(x + barPx, y), strokeWidth = 3f)
    drawLine(Color.White, Offset(x, y - 5), Offset(x, y + 5), strokeWidth = 3f)
    drawLine(Color.White, Offset(x + barPx, y - 5), Offset(x + barPx, y + 5), strokeWidth = 3f)

    val label = if (niceM >= 1000) {
        String.format(Locale.US, "%.0f km", niceM / 1000)
    } else {
        String.format(Locale.US, "%.0f m", niceM)
    }
    drawContext.canvas.nativeCanvas.drawText(
        label,
        x + barPx + 10f,
        y + 6f,
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            isAntiAlias = true
            setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        },
    )
}

/**
 * North arrow.
 *
 * Honest here in a way it is not on a floorplan: Web Mercator is north-up by construction, so this
 * is a statement of fact rather than decoration.
 */
private fun DrawScope.drawNorthArrow(size: Size) {
    val cx = size.width - 30f
    val top = 20f
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(cx, top)
        lineTo(cx - 8f, top + 24f)
        lineTo(cx, top + 17f)
        lineTo(cx + 8f, top + 24f)
        close()
    }
    drawPath(path, Color.White)
    drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(1.5f))
    drawContext.canvas.nativeCanvas.drawText(
        "N",
        cx - 9f,
        top + 48f,
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            isFakeBoldText = true
            isAntiAlias = true
            setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f kB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Standing still is a few metres of GPS scatter; fitting to that would zoom into jitter. */
private const val MIN_SPAN_M = 25.0
private const val MAX_TILES = 40L
