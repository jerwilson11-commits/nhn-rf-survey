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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf

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
    val liveCell by RecordingState.cellular.collectAsState()

    // RecordingState is fed by the recording service, so when idle it holds nothing. The Live tab
    // polls locally for the same reason. Only one tab composes at a time, so this does not double
    // the sampling rate.
    val cellular = remember { com.nhnengineering.rftest.cellular.CellularCollector(context) }
    val locations = remember { com.nhnengineering.rftest.location.LocationCollector(context) }
    var localCell by remember { mutableStateOf<com.nhnengineering.rftest.model.CellularSample?>(null) }
    var localFix by remember { mutableStateOf<com.nhnengineering.rftest.model.GeoPoint?>(null) }

    DisposableEffect(recording) {
        if (!recording) { cellular.start(); locations.start() }
        onDispose { cellular.stop(); locations.stop() }
    }
    LaunchedEffect(recording) {
        if (recording) return@LaunchedEffect
        while (true) {
            localCell = cellular.snapshot()
            localFix = locations.snapshot()
            kotlinx.coroutines.delay(1000)
        }
    }

    // Free-camera state. `following` is the important one: while true the map tracks the operator,
    // and any pan turns it off, because a map that yanks itself back under your thumb every second
    // is unusable. The Recentre button turns it back on.
    var centerLat by remember { mutableStateOf<Double?>(null) }
    var centerLon by remember { mutableStateOf<Double?>(null) }
    var zoom by remember { mutableFloatStateOf(DEFAULT_ZOOM) }
    var following by remember { mutableStateOf(true) }

    val shownCell = if (recording) liveCell else localCell
    val shownFix = if (recording) fix else localFix

    LaunchedEffect(shownFix, following) {
        val f = shownFix ?: return@LaunchedEffect
        if (following || centerLat == null) {
            centerLat = f.latitudeDeg
            centerLon = f.longitudeDeg
        }
    }

    var showImagery by remember { mutableStateOf(true) }
    var basemap by remember {
        mutableStateOf(com.nhnengineering.rftest.live.TileProxy.Basemap.SATELLITE)
    }
    // Bumped when a tile finishes loading, purely to force a redraw. Compose does not observe the
    // cache, and without this the imagery appears only when some other state happens to change.
    var tileGeneration by remember { mutableIntStateOf(0) }

    val tiles = remember(basemap) {
        TileCache(context, scope, basemap) { tileGeneration++ }
    }
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
                        when {
                            recording -> "Route walked"
                            // "Last route" over a map with no route on it is a small lie, and the
                            // operator is standing there checking whether the app knows where it is.
                            track.isEmpty() -> "Position"
                            else -> "Last route"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Satellite shows what a building looks like; street shows what it is
                        // called. An operator deciding where to walk next needs the second more
                        // often than the first, and the app only offered the first.
                        for (option in com.nhnengineering.rftest.live.TileProxy.Basemap.entries) {
                            FilterChip(
                                selected = showImagery && basemap == option,
                                onClick = {
                                    if (showImagery && basemap == option) {
                                        showImagery = false
                                    } else {
                                        basemap = option
                                        showImagery = true
                                    }
                                },
                                label = { Text(option.label, maxLines = 1) },
                            )
                        }
                    }
                }

                if (track.isEmpty() && shownFix == null) {
                    Text(
                        if (recording) {
                            "Waiting for a GPS fix. Indoor samples with no fix are still recorded " +
                                "to the file; they simply cannot be drawn on a map."
                        } else {
                            "No position yet. Waiting for a GPS fix — the map appears as soon " +
                                "as there is one, and the trail builds on it once you start " +
                                "recording on the Live tab."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    // Referenced so Compose redraws when a tile lands.
                    @Suppress("UNUSED_EXPRESSION") tileGeneration

                    MapKpiStrip(shownCell, shownFix)

                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoomChange, _ ->
                                    val cLat = centerLat ?: return@detectTransformGestures
                                    val cLon = centerLon ?: return@detectTransformGestures

                                    val newZoom = (zoom * zoomChange)
                                        .coerceIn(MIN_ZOOM.toFloat(), MAX_ZOOM.toFloat())
                                    val zi = floor(newZoom).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    val sc = 2.0.pow(newZoom.toDouble() - zi)

                                    // Drag moves the map with the finger, so the centre moves the
                                    // opposite way, in world pixels at the current scale.
                                    val wx = Mercator.lonToTileX(cLon, zi) * Mercator.TILE_SIZE -
                                        pan.x / sc
                                    val wy = Mercator.latToTileY(cLat, zi) * Mercator.TILE_SIZE -
                                        pan.y / sc

                                    centerLon = Mercator.tileXToLon(wx / Mercator.TILE_SIZE, zi)
                                    centerLat = Mercator.tileYToLat(wy / Mercator.TILE_SIZE, zi)
                                    zoom = newZoom
                                    if (pan.getDistance() > 1f) following = false
                                }
                            },
                    ) {
                        // Clipped to the canvas. Tiles are drawn through the native canvas, which
                        // Compose does not bound for us: without this a tile whose edge falls
                        // outside the map spills over the caption beneath it and past the card,
                        // which is exactly what the first build did.
                        clipRect(0f, 0f, size.width, size.height) {
                            drawWalkMap(
                                track = track,
                                tiles = tiles,
                                showImagery = showImagery,
                                currentLat = shownFix?.latitudeDeg,
                                currentLon = shownFix?.longitudeDeg,
                                centerLat = centerLat ?: shownFix?.latitudeDeg ?: 0.0,
                                centerLon = centerLon ?: shownFix?.longitudeDeg ?: 0.0,
                                zoom = zoom,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            // Zoom is worth showing because the base maps behave differently at
                            // different scales: the street layer has nothing to draw past 18.
                            "z%.1f".format(zoom),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (!following) {
                            TextButton(onClick = {
                                following = true
                                shownFix?.let {
                                    centerLat = it.latitudeDeg
                                    centerLon = it.longitudeDeg
                                }
                            }) { Text("Recentre") }
                        } else {
                            Text(
                                "Following",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Text(
                        if (track.isEmpty()) {
                            "Current position. Drag to pan, pinch to zoom. The trail builds here " +
                                "once recording starts."
                        } else {
                            "${track.size} located samples. Drag to pan, pinch to zoom. The " +
                                "ring is your current position."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Imagery", style = MaterialTheme.typography.titleSmall)
                Text(
                    basemap.attribution + ". Tiles are fetched over this phone's own " +
                        "connection and cached, so they cost mobile data — and they use the same " +
                        "radio the survey is measuring. Tap the selected layer again to turn the " +
                        "base map off if a measurement matters more than the picture.",
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
    centerLat: Double,
    centerLon: Double,
    zoom: Float,
) {
    // Centre-and-zoom rather than fit-to-bounds.
    //
    // Fitting the track was right while the map was a static picture of a finished walk. It is
    // wrong for a map the operator drives: it forced a 25 m view around a single position, which
    // is zoom 19, and the street base map has nothing to draw at 69 m per tile -- a house number
    // and fill colour. The satellite layer looked fine there, which is why the problem only
    // appeared when a second layer existed.
    val zi = floor(zoom).toInt().coerceIn(1, MAX_ZOOM)
    // Fractional zoom is carried as a scale on integer tiles, which is how every slippy map does
    // it: tiles exist only at whole zooms, and pinching between them must not snap.
    val scale = 2.0.pow(zoom.toDouble() - zi).toFloat()

    val tilePx = Mercator.TILE_SIZE
    val centerWx = Mercator.lonToTileX(centerLon, zi) * tilePx
    val centerWy = Mercator.latToTileY(centerLat, zi) * tilePx
    val halfW = size.width / 2f
    val halfH = size.height / 2f

    fun project(lat: Double, lon: Double): Offset = Offset(
        ((Mercator.lonToTileX(lon, zi) * tilePx - centerWx) * scale).toFloat() + halfW,
        ((Mercator.latToTileY(lat, zi) * tilePx - centerWy) * scale).toFloat() + halfH,
    )

    if (showImagery) {
        // Only the tiles the viewport actually covers, which is what makes panning affordable:
        // the old code fetched everything inside the track's bounds whatever was on screen.
        val tileSpan = tilePx * scale
        val x0 = floor((centerWx - halfW / scale) / tilePx).toInt()
        val x1 = floor((centerWx + halfW / scale) / tilePx).toInt()
        val y0 = floor((centerWy - halfH / scale) / tilePx).toInt()
        val y1 = floor((centerWy + halfH / scale) / tilePx).toInt()

        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() <= MAX_TILES) {
            val maxIndex = 1 shl zi
            for (tx in x0..x1) {
                for (ty in y0..y1) {
                    if (tx < 0 || ty < 0 || tx >= maxIndex || ty >= maxIndex) continue
                    val bitmap = tiles.get(zi, tx, ty) ?: continue
                    val left = ((tx * tilePx - centerWx) * scale).toFloat() + halfW
                    val top = ((ty * tilePx - centerWy) * scale).toFloat() + halfH
                    drawContext.canvas.nativeCanvas.drawBitmap(
                        bitmap,
                        null,
                        android.graphics.RectF(left, top, left + tileSpan, top + tileSpan),
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

    drawScaleBar(centerLat, zi, scale, size)
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
private const val MAX_ZOOM = 19
private const val MIN_ZOOM = 3

/**
 * Where the map opens.
 *
 * Zoom 17 is about 275 m across, chosen by fetching street tiles at each level and looking: at 18
 * the layer is two street names and fill, at 19 it is a house number. 17 still shows enough street
 * to orient by while keeping a walk-sized area on screen. The operator can pinch from there.
 */
private const val DEFAULT_ZOOM = 17f
private const val MAX_TILES = 40L

/**
 * The numbers an operator needs while looking at the map.
 *
 * Deliberately compact and deliberately here rather than only on the Live tab. During a walk the
 * two questions are "what am I reading" and "where am I", and making the operator switch tabs to
 * alternate between them is how a survey ends up with a stretch nobody noticed was bad. G-MoN pins
 * the same values above its map for the same reason.
 *
 * Two rows rather than a grid: this sits above a map that should keep most of the screen.
 */
@Composable
private fun MapKpiStrip(
    cell: com.nhnengineering.rftest.model.CellularSample?,
    fix: com.nhnengineering.rftest.model.GeoPoint?,
) {
    val level = cell?.servingRsrpDbm
    val sinr = cell?.nr?.ssSinrDb ?: cell?.lte?.rssnrDb

    @Composable
    fun RowScope.cellText(label: String, value: String) {
        // Equal weight plus a single clipped line. Without the weight the columns size to their
        // content and a long value pushes the others off the row; without maxLines the label wraps
        // one character per line, which is what "B66 (1700/2100 AWS-3)" did to PCI on the first
        // build. Compose clips rather than ellipsising by default, so both are needed.
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            cellText("RAT", cell?.rat?.label ?: "—")
            cellText(
                "BAND",
                cell?.nr?.bands?.firstOrNull()?.let { "n$it".removePrefix("nn") }
                    ?: cell?.lte?.band?.let { "B$it" }
                    ?: "—",
            )
            cellText("PCI", cell?.nr?.pci?.toString() ?: cell?.lte?.pci?.toString() ?: "—")
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            cellText("dBm", level?.toString() ?: "—")
            cellText("SINR", sinr?.toString() ?: "—")
            // Accuracy rather than coordinates: a position is only as good as its uncertainty, and
            // six decimal places of latitude tell the operator nothing they can act on.
            cellText("GPS ±m", fix?.accuracyM?.let { "%.0f".format(it) } ?: "—")
        }
    }
}
