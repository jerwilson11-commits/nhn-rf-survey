package com.nhnengineering.rftest.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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

    val current by RecordingState.indoorPosition.collectAsState()
    val placed by RecordingState.placedPositions.collectAsState()
    val recording by RecordingState.active.collectAsState()
    val wifi by RecordingState.wifi.collectAsState()
    val cellular by RecordingState.cellular.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = FloorplanStore.import(context, uri, uri.lastPathSegment)
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
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Load floorplan image") }

                    if (plans.isNotEmpty()) {
                        HorizontalDivider()
                        plans.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
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
                            },
                        )
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            label = { Text("Waypoint label (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Pinch to zoom, drag to pan, tap to place. " +
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
                        "No floorplan loaded. Load an image above — a PNG or JPEG of the venue " +
                            "layout, a fire-evacuation plan, or a screenshot of a CAD drawing.",
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
@Composable
private fun FloorplanCanvas(
    plan: Floorplan,
    bitmap: ImageBitmap,
    placed: List<Pair<IndoorPosition, Int?>>,
    currentPosition: IndoorPosition?,
    onTap: (Float, Float) -> Unit,
) {
    var scale by remember(plan.id) { mutableStateOf(1f) }
    var offset by remember(plan.id) { mutableStateOf(Offset.Zero) }
    val outline = MaterialTheme.colorScheme.onSurface

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(plan.aspectRatio)
            .pointerInput(plan.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    // Clamp pan so the image cannot be dragged entirely off screen, which at high
                    // zoom is easy to do and leaves the operator staring at blank space.
                    val maxX = size.width * (scale - 1f) / 2f
                    val maxY = size.height * (scale - 1f) / 2f
                    offset = Offset(
                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                    )
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
                    onTap(xNorm, yNorm)
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

            // Placed points, coloured by the KPI recorded there.
            placed.forEach { (pos, argb) ->
                drawCircle(
                    color = argb?.let { Color(it) } ?: Color.Gray,
                    radius = 7f,
                    center = toScreen(pos.xNorm, pos.yNorm),
                )
            }

            // Current position, drawn last so it is never hidden under a logged point.
            currentPosition?.let {
                val c = toScreen(it.xNorm, it.yNorm)
                drawCircle(color = outline, radius = 16f, center = c, style = Stroke(width = 4f))
                drawCircle(color = outline, radius = 4f, center = c)
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
