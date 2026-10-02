package com.nhnengineering.rftest.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.model.Floorplan
import com.nhnengineering.rftest.model.IndoorPosition
import com.nhnengineering.rftest.model.RsrpBucket
import com.nhnengineering.rftest.model.RssiBucket
import com.nhnengineering.rftest.service.RecordingState
import com.nhnengineering.rftest.session.FloorplanStore
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Indoor positioning by hand.
 *
 * Load a floorplan, tap where you are. Samples carry that position until you tap somewhere else —
 * which matches how an indoor walk actually goes: move to a spot, mark it, dwell, move on. The
 * marker is deliberately sticky rather than one-shot, so a dwell of thirty seconds produces thirty
 * samples at a known location rather than one located sample and twenty-nine orphans.
 */
@Composable
fun FloorplanScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var plans by remember { mutableStateOf<List<Floorplan>>(emptyList()) }
    var selected by remember { mutableStateOf<Floorplan?>(null) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var label by remember { mutableStateOf("") }
    var geoMode by remember { mutableStateOf(false) }

    val current by RecordingState.indoorPosition.collectAsState()
    val placed by RecordingState.placedPositions.collectAsState()
    val recording by RecordingState.active.collectAsState()
    val wifi by RecordingState.wifi.collectAsState()
    val cellular by RecordingState.cellular.collectAsState()
    val walk by WalkMode.active.collectAsState()

    // OpenDocument rather than the photo picker: the photo picker (PickVisualMedia) cannot offer
    // PDFs, and walk-test floor plans ship as vendor PDFs far more often than as PNG/JPEG. This
    // accepts both; FloorplanStore rasterises a PDF's first page on import.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val name = runCatching {
                    context.contentResolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null,
                    )?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull() ?: uri.lastPathSegment
                val imported = FloorplanStore.import(context, uri, name)
                plans = FloorplanStore.list(context)
                imported?.let { selected = it }
            }
        }
    }

    LaunchedEffect(Unit) { plans = FloorplanStore.list(context) }

    LaunchedEffect(selected) {
        val plan = selected
        bitmap = if (plan == null) null else {
            runCatching {
                BitmapFactory.decodeFile(FloorplanStore.file(context, plan.id).absolutePath)
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }

    // Full-screen walk mode: when it is on and a plan is loaded, the whole tab becomes the immersive
    // canvas. Guard on bitmap too -- entering with nothing to tap would strand the user on a blank
    // screen behind a hidden nav bar. If the bitmap is somehow absent, fall through to the normal
    // layout (which carries its own loader) rather than showing an empty walk screen.
    // Georeferencing is its own full-tab flow (satellite tie points + floor stacking); the bottom
    // nav stays so the operator can step away from it.
    if (geoMode) {
        GeoreferenceScreen(modifier = modifier, onExit = { geoMode = false })
        return
    }

    val plan0 = selected
    val bmp0 = bitmap
    if (walk && plan0 != null && bmp0 != null) {
        FloorplanWalkScreen(
            plan = plan0,
            bitmap = bmp0,
            label = label,
            onLabelChange = { label = it },
            onExit = { WalkMode.exit() },
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Floorplan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "GPS is unreliable or absent indoors. Load a floorplan and tap your " +
                            "position — samples carry it until you tap again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            picker.launch(
                                arrayOf(
                                    "application/pdf",
                                    "image/png", "image/jpeg", "image/webp",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Load floorplan (PDF or image)") }

                    OutlinedButton(
                        onClick = { geoMode = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Georeference floors (building)") }

                    if (plans.isNotEmpty()) {
                        HorizontalDivider()
                        plans.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                YieldingText(
                                    p.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (p.id == selected?.id) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                                OutlinedButton(onClick = { selected = p }) {
                                    Text(if (p.id == selected?.id) "Selected" else "Use")
                                }
                            }
                        }
                    }
                }
            }
        }

        val plan = selected
        val bmp = bitmap
        if (plan != null && bmp != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FloorplanCanvas(
                            plan = plan,
                            bitmap = bmp,
                            placed = placed.filter { it.first.floorplanId == plan.id },
                            currentPosition = current?.takeIf { it.floorplanId == plan.id },
                            onTap = { x, y ->
                                RecordingState.indoorPosition.value = IndoorPosition(
                                    floorplanId = plan.id,
                                    xNorm = x,
                                    yNorm = y,
                                    label = label.trim().ifBlank { null },
                                )
                                // Per-landmark reference, not a sticky zone: clear after placing so
                                // the next waypoint starts fresh.
                                label = ""
                            },
                        )
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            label = { Text("Waypoint — type a name, then tap (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { WalkMode.enter() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Walk mode (full screen)") }
                        Text(
                            "Pinch to zoom, drag to pan, tap to place. To name a landmark, type the " +
                                "label first, then tap — it stamps that point and the field clears. " +
                                if (recording) {
                                    "Recording — every sample carries this position until moved."
                                } else {
                                    "Not recording — start a session on the Live tab first."
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        current?.let {
                            KeyValue(
                                "Current position",
                                (it.label ?: "unlabelled") +
                                    "  (%.3f, %.3f)".format(it.xNorm, it.yNorm),
                            )
                        }
                        KeyValue("Points this session", placed.size.toString())
                        if (current != null) {
                            OutlinedButton(
                                onClick = { RecordingState.indoorPosition.value = null },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Clear position (back to GPS only)") }
                        }
                    }
                }
            }
        } else {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "No floorplan loaded. Load one above — a vendor PDF (the first page is " +
                            "used), or a PNG/JPEG of the venue layout, a fire-evacuation plan, or a " +
                            "screenshot of a CAD drawing.",
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * The interactive floorplan.
 *
 * Sized to the image's own aspect ratio so the bitmap exactly fills the box. That removes
 * letterboxing, which would otherwise mean screen coordinates and image coordinates diverge and
 * every tap would be placed slightly wrong — an error that is invisible on screen and corrupts
 * every position in the session.
 */
/** Package-visible rather than private: [com.nhnengineering.rftest.ui.ErrcsGridScreen] reuses this
 *  exact canvas for grid-point placement rather than reimplementing pinch/zoom/pan/tap handling. */
@Composable
internal fun FloorplanCanvas(
    plan: Floorplan,
    bitmap: ImageBitmap,
    placed: List<Pair<IndoorPosition, Int?>>,
    currentPosition: IndoorPosition?,
    onTap: (Float, Float) -> Unit,
    // The box MUST stay exactly the image's aspect ratio (no letterboxing inside it) or the tap-to-
    // image coordinate math below diverges and every placed point is slightly wrong. Callers vary
    // only HOW it fits -- fill width (the scrolling screen) or fit height (walk mode in landscape) --
    // so the sizing is injected while the aspect-ratio lock lives here.
    boxModifier: Modifier = Modifier.fillMaxWidth().aspectRatio(plan.aspectRatio),
    // When set, the normalized (u,v) currently under the box centre is reported on every pan/zoom.
    // Paired with [showCrosshair], this drives aim-the-crosshair-then-confirm placement, which is
    // far steadier than trying to land a precise tap (and sidesteps tap/transform gesture contention).
    onCenterChange: ((Float, Float) -> Unit)? = null,
    showCrosshair: Boolean = false,
) {
    var scale by remember(plan.id) { mutableStateOf(1f) }
    var offset by remember(plan.id) { mutableStateOf(Offset.Zero) }
    val outline = MaterialTheme.colorScheme.onSurface
    // The gestures below live in a pointerInput keyed only on plan.id, so they do NOT restart when a
    // new lambda arrives on recomposition -- that would freeze the first lambda (and anything it
    // closed over). rememberUpdatedState keeps them calling the current handlers.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnCenter by rememberUpdatedState(onCenterChange)

    fun reportCentre(w: Float, h: Float) {
        // (u,v) under the box centre given the current pan/zoom. See the inverse-transform note below.
        val u = (0.5f - offset.x / (scale * w)).coerceIn(0f, 1f)
        val v = (0.5f - offset.y / (scale * h)).coerceIn(0f, 1f)
        currentOnCenter?.invoke(u, v)
    }

    Box(
        boxModifier
            .pointerInput(plan.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    // Pan clamp. For normal viewing, (scale-1)/2 keeps the image filling the viewport
                    // so the operator never stares at blank space. For crosshair placement the limit
                    // is scale/2 instead: that lets the centre crosshair travel to any point of the
                    // image -- every edge and corner -- which viewing-clamp made unreachable, so a
                    // feature on the far (east) side of the plan could not be aimed at.
                    val panFactor = if (showCrosshair) scale else (scale - 1f)
                    val maxX = size.width * panFactor / 2f
                    val maxY = size.height * panFactor / 2f
                    offset = Offset(
                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                    )
                    reportCentre(size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(plan.id) {
                detectTapGestures { tap ->
                    // Invert the display transform to get image coordinates. The layer is scaled
                    // about the centre and then translated, so:
                    //     screen = (content - centre) * scale + centre + offset
                    // and therefore:
                    //     content = (screen - centre - offset) / scale + centre
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val contentX = (tap.x - cx - offset.x) / scale + cx
                    val contentY = (tap.y - cy - offset.y) / scale + cy
                    val xNorm = (contentX / size.width).coerceIn(0f, 1f)
                    val yNorm = (contentY / size.height).coerceIn(0f, 1f)
                    currentOnTap(xNorm, yNorm)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f

            fun toScreen(xn: Float, yn: Float) = Offset(
                x = (xn * w - cx) * scale + cx + offset.x,
                y = (yn * h - cy) * scale + cy + offset.y,
            )

            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, pivot = androidx.compose.ui.geometry.Offset(cx, cy))
            }) {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(w.toInt(), h.toInt()),
                )
            }

            // A native text paint for waypoint labels: a dark glyph with a white halo so a named
            // landmark ("Entrance", "AP 11") stays legible over either a light or dark floor plan.
            // Drawn in screen space (not under the image transform) so it keeps a constant size as
            // the plan is zoomed.
            val labelPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = outline.toArgb()
                textSize = 28f
                setShadowLayer(4f, 0f, 0f, android.graphics.Color.WHITE)
            }

            // Placed points, coloured by the KPI recorded there, each tagged with its waypoint label.
            placed.forEach { (pos, argb) ->
                val sc = toScreen(pos.xNorm, pos.yNorm)
                drawCircle(
                    color = argb?.let { Color(it) } ?: Color.Gray,
                    radius = 7f,
                    center = sc,
                )
                pos.label?.let { drawContext.canvas.nativeCanvas.drawText(it, sc.x + 10f, sc.y + 4f, labelPaint) }
            }

            // Current position, drawn last so it is never hidden under a logged point.
            currentPosition?.let {
                val c = toScreen(it.xNorm, it.yNorm)
                drawCircle(color = outline, radius = 16f, center = c, style = Stroke(width = 4f))
                drawCircle(color = outline, radius = 4f, center = c)
                it.label?.let { l -> drawContext.canvas.nativeCanvas.drawText(l, c.x + 20f, c.y + 4f, labelPaint) }
            }

            // Fixed centre crosshair for aim-then-confirm placement: the operator pans the plan so the
            // feature sits under this, then confirms -- no precise tap required.
            if (showCrosshair) {
                val red = Color(0xFFD32F2F)
                drawLine(Color.White, Offset(cx - 26f, cy), Offset(cx + 26f, cy), strokeWidth = 4f)
                drawLine(Color.White, Offset(cx, cy - 26f), Offset(cx, cy + 26f), strokeWidth = 4f)
                drawLine(red, Offset(cx - 24f, cy), Offset(cx + 24f, cy), strokeWidth = 2f)
                drawLine(red, Offset(cx, cy - 24f), Offset(cx, cy + 24f), strokeWidth = 2f)
                drawCircle(red, radius = 4f, center = Offset(cx, cy))
            }
        }
    }
}

/** Colour a placed point by whichever radio was serving, so the plan reads at a glance. */
internal fun indoorPointColor(rssiDbm: Int?, rsrpDbm: Int?): Int? = when {
    rsrpDbm != null -> RsrpBucket.of(rsrpDbm)?.argb
    rssiDbm != null -> RssiBucket.of(rssiDbm)?.argb
    else -> null
}
