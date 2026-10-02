package com.nhnengineering.rftest.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nhnengineering.rftest.model.Floorplan
import com.nhnengineering.rftest.model.IndoorPosition
import com.nhnengineering.rftest.service.RecordingService
import com.nhnengineering.rftest.service.RecordingState

/**
 * Full-screen floorplan tapping — "walk mode".
 *
 * A field tester marking positions on a plan needs the plan as big as the glass allows; boxed into a
 * scrolling column of cards it is a postage stamp, which is why the job usually migrates to a tablet.
 * This hands the whole screen to the canvas, hides the system and app chrome, and docks the controls
 * a walk actually needs into one thin strip at the foot of the screen.
 *
 * The plan keeps its true aspect ratio (tap-to-image math in [FloorplanCanvas] depends on it) and is
 * fitted to the larger screen dimension, so a wide plan rotated to landscape fills the long edge.
 */
@Composable
fun FloorplanWalkScreen(
    plan: Floorplan,
    bitmap: ImageBitmap,
    label: String,
    onLabelChange: (String) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current

    // Immersive while walking: hide the status and navigation bars, restore them on the way out. The
    // activity is already edge-to-edge (MainActivity.enableEdgeToEdge), so this just hides the bars.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Hardware/gesture back leaves walk mode rather than the app -- the nav bar is hidden, so this is
    // the primary way out alongside the Exit button.
    BackHandler { onExit() }

    val placed by RecordingState.placedPositions.collectAsState()
    val current by RecordingState.indoorPosition.collectAsState()

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Reserve room so the strip does not sit on top of the lower part of the plan. Landscape has less
    // height to give, so the reserve is smaller there.
    val stripReserve = if (landscape) 104.dp else 150.dp

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(bottom = stripReserve),
            contentAlignment = Alignment.Center,
        ) {
            // Fit to whichever edge binds: fill height when the box is wider than the plan, else fill
            // width. Either way the box stays exactly the plan's aspect ratio, which the tap math needs.
            val boxAspect = maxWidth.value / maxHeight.value
            val fitModifier = if (boxAspect > plan.aspectRatio) {
                Modifier.fillMaxHeight().aspectRatio(plan.aspectRatio)
            } else {
                Modifier.fillMaxWidth().aspectRatio(plan.aspectRatio)
            }
            FloorplanCanvas(
                plan = plan,
                bitmap = bitmap,
                placed = placed.filter { it.first.floorplanId == plan.id },
                currentPosition = current?.takeIf { it.floorplanId == plan.id },
                onTap = { x, y ->
                    RecordingState.indoorPosition.value = IndoorPosition(
                        floorplanId = plan.id,
                        xNorm = x,
                        yNorm = y,
                        label = label.trim().ifBlank { null },
                    )
                    // Waypoint labels are per-landmark references ("Entrance", "AP 11"), not a sticky
                    // zone, so the field clears after each placement -- the next spot starts fresh.
                    onLabelChange("")
                },
                boxModifier = fitModifier,
            )
        }

        WalkStrip(
            modifier = Modifier.align(Alignment.BottomCenter),
            plan = plan,
            label = label,
            onLabelChange = onLabelChange,
            onExit = onExit,
        )
    }
}

/** The docked control strip: live KPI, session counters, position tagging, area/floor, walk
 *  throughput, record start/stop, and exit — everything a walk needs without leaving the canvas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalkStrip(
    modifier: Modifier,
    plan: Floorplan,
    label: String,
    onLabelChange: (String) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current

    val recording by RecordingState.active.collectAsState()
    val cellular by RecordingState.cellular.collectAsState()
    val wifi by RecordingState.wifi.collectAsState()
    val current by RecordingState.indoorPosition.collectAsState()
    val placed by RecordingState.placedPositions.collectAsState()
    val area by RecordingState.areaLabel.collectAsState()
    val floor by RecordingState.floor.collectAsState()
    val walkThroughput by RecordingState.walkThroughputEnabled.collectAsState()
    val elapsedMs by RecordingState.elapsedMs.collectAsState()
    val rows by RecordingState.rowCount.collectAsState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
        tonalElevation = 3.dp,
    ) {
        // Landscape has far less height to spare, so the strip tightens there: the two status lines
        // collapse into one and paddings shrink. Portrait keeps the roomier two-line layout.
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = if (landscape) 3.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (landscape) 3.dp else 4.dp),
        ) {
            val kpi = kpiLine(cellular, wifi) + counterSuffix(recording, elapsedMs, rows)
            val pos = buildString {
                val c = current?.takeIf { it.floorplanId == plan.id }
                if (c == null) append("No position — tap the plan")
                else append("At ${c.label ?: "unlabelled"} (%.2f, %.2f)".format(c.xNorm, c.yNorm))
                append("  ·  ${placed.count { it.first.floorplanId == plan.id }} pts")
            }

            if (landscape) {
                Text(
                    "$kpi    ·    $pos",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(kpi, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(pos, style = MaterialTheme.typography.labelSmall)
            }

            // Portrait gives the label its own full-width row; landscape folds it into the control
            // row (below) to save a whole line of precious height.
            if (!landscape) {
                OutlinedTextField(
                    value = label,
                    onValueChange = onLabelChange,
                    label = { Text("Waypoint — type, then tap", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                )
            }

            // Controls wrap onto a second line on a narrow phone rather than hiding off the edge; in
            // landscape they sit on one line (with the label field leading).
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (landscape) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = onLabelChange,
                        label = { Text("Waypoint — type, then tap", style = MaterialTheme.typography.labelSmall) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(230.dp).heightIn(min = 44.dp),
                    )
                }
                ChipButton("Indoor", selected = area == "Indoor") {
                    RecordingState.areaLabel.value = "Indoor"
                }
                ChipButton("Outdoor", selected = area == "Outdoor") {
                    RecordingState.areaLabel.value = "Outdoor"
                }
                FloorStepper(floor)
                ChipButton("TP ${if (walkThroughput) "on" else "off"}", selected = walkThroughput) {
                    RecordingState.walkThroughputEnabled.value = !walkThroughput
                }
                if (current?.floorplanId == plan.id) {
                    ChipButton("Clear") { RecordingState.indoorPosition.value = null }
                }
                if (recording) {
                    ChipButton("Stop", danger = true) { RecordingService.stop(context) }
                } else {
                    ChipButton("Start", filled = true) { RecordingService.start(context, "") }
                }
                FilledTonalButton(
                    onClick = onExit,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) { Text("Exit", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/** A compact strip control. [filled]/[selected] mark an active or primary state; [danger] a stop. */
@Composable
private fun ChipButton(
    text: String,
    selected: Boolean = false,
    filled: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val pad = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    val mod = Modifier.heightIn(min = 36.dp)
    when {
        danger -> Button(
            onClick = onClick,
            contentPadding = pad,
            modifier = mod,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text(text, style = MaterialTheme.typography.labelSmall) }
        filled || selected -> FilledTonalButton(onClick = onClick, contentPadding = pad, modifier = mod) {
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
        else -> OutlinedButton(onClick = onClick, contentPadding = pad, modifier = mod) {
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** One compact −/+ pill for the floor, reading "Floor n" (or "Floor —" when unset). Replaces the
 *  three loose Fl− / Fl / Fl+ items, which wrapped apart awkwardly in the control flow. */
@Composable
private fun FloorStepper(floor: String?) {
    val numeric = floor?.toIntOrNull()
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "−",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clickable { RecordingState.floor.value = ((numeric ?: 0) - 1).toString() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(
            floor?.let { "Floor $it" } ?: "Floor —",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "+",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clickable { RecordingState.floor.value = ((numeric ?: 0) + 1).toString() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Serving KPI in one compact line, cellular first, Wi-Fi as the fallback indoors. */
private fun kpiLine(
    cell: com.nhnengineering.rftest.model.CellularSample?,
    wifi: com.nhnengineering.rftest.model.WifiSample?,
): String {
    val rsrp = cell?.servingRsrpDbm
    if (rsrp != null) {
        val parts = mutableListOf("RSRP $rsrp dBm")
        cell.rat.label.takeIf { it.isNotBlank() }?.let { parts += it }
        cell.servingBandLabel?.let { parts += it }
        (cell.nr?.pci ?: cell.lte?.pci)?.let { parts += "PCI $it" }
        return parts.joinToString("  ·  ")
    }
    wifi?.rssiDbm?.let { return "Wi-Fi $it dBm" + (wifi.ssid?.let { s -> "  ·  $s" } ?: "") }
    return "No serving signal"
}

private fun counterSuffix(recording: Boolean, elapsedMs: Long, rows: Long): String {
    if (!recording) return "  ·  not recording"
    val totalSec = elapsedMs / 1000
    val mm = totalSec / 60
    val ss = totalSec % 60
    return "  ·  %d:%02d".format(mm, ss) + "  ·  $rows rows"
}
