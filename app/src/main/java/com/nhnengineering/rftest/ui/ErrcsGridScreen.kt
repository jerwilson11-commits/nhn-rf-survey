package com.nhnengineering.rftest.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_COLS
import com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_ROWS
import com.nhnengineering.rftest.model.ErrcsAreaClass
import com.nhnengineering.rftest.model.ErrcsGridPoint
import com.nhnengineering.rftest.model.Floorplan
import com.nhnengineering.rftest.model.IndoorPosition
import com.nhnengineering.rftest.model.PublicSafetyThresholds
import com.nhnengineering.rftest.model.errcsCompliance
import com.nhnengineering.rftest.model.errcsGridCell
import com.nhnengineering.rftest.model.errcsGridCompliance
import com.nhnengineering.rftest.session.ErrcsGridConfigStore
import com.nhnengineering.rftest.session.ErrcsGridStore
import com.nhnengineering.rftest.session.FloorplanStore
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Track A: manual public-safety-coverage grid entry.
 *
 * The dBm value at each point comes from the operator's own equipment, tuned to whatever the AHJ's
 * actual radio system is -- this app never measures or verifies it, only records, classifies, and
 * reports it. See `model/PublicSafetyCoverage.kt`'s own doc for why a phone cannot do the
 * measurement itself.
 */
@Composable
fun ErrcsGridScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var plans by remember { mutableStateOf<List<Floorplan>>(emptyList()) }
    var selected by remember { mutableStateOf<Floorplan?>(null) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var points by remember { mutableStateOf<List<ErrcsGridPoint>>(emptyList()) }
    var thresholds by remember { mutableStateOf(PublicSafetyThresholds()) }
    var pendingTap by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var thresholdsExpanded by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(true) }
    var gridRows by remember { mutableStateOf(ERRCS_DEFAULT_GRID_ROWS) }
    var gridCols by remember { mutableStateOf(ERRCS_DEFAULT_GRID_COLS) }

    val store = remember { ErrcsGridStore(File(context.filesDir, "errcs_grid.jsonl")) }
    val configStore = remember { ErrcsGridConfigStore(File(context.filesDir, "errcs_grid_config.jsonl")) }

    LaunchedEffect(Unit) {
        plans = FloorplanStore.list(context)
        points = store.load().points
    }

    LaunchedEffect(selected) {
        val plan = selected
        bitmap = if (plan == null) null else {
            runCatching {
                BitmapFactory.decodeFile(FloorplanStore.file(context, plan.id).absolutePath)
                    ?.asImageBitmap()
            }.getOrNull()
        }
        // Each floorplan remembers its own grid size (floors differ in shape and size).
        plan?.let {
            val cfg = configStore.configFor(it.id)
            gridRows = cfg.rows
            gridCols = cfg.cols
        }
    }

    val plan = selected
    val pointsHere = plan?.let { p -> points.filter { it.floorplanId == p.id } } ?: emptyList()
    val compliance = errcsCompliance(pointsHere, thresholds)
    val gridCompliance = errcsGridCompliance(pointsHere, gridRows, gridCols, thresholds)

    // Per-square verdict for the overlay: green when every reading in the square passes, red when any
    // fails, unshaded when the square holds no reading. Keyed on (row, col) so the canvas lambda is a
    // cheap lookup rather than a scan per cell per frame.
    val cellVerdict: Map<Pair<Int, Int>, Boolean> = pointsHere
        .groupBy { errcsGridCell(it.xNorm, it.yNorm, gridRows, gridCols) }
        .mapValues { (_, cellPoints) -> cellPoints.all { it.passes(thresholds) } }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Public safety coverage — manual grid", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap where you took a reading with your own tuned meter, then enter the " +
                            "dBm value. This app does not measure or verify the reading -- it " +
                            "records what you enter.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (plans.isEmpty()) {
                        Text(
                            "No floorplan loaded yet. Load one on the Plan tab first.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        plans.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                YieldingText(p.displayName, style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton(onClick = { selected = p }) {
                                    Text(if (p.id == selected?.id) "Selected" else "Use")
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Thresholds", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { thresholdsExpanded = !thresholdsExpanded }) {
                            Text(if (thresholdsExpanded) "Hide" else "Edit")
                        }
                    }
                    Text(
                        "General ${thresholds.generalMinDbm} dBm / ${thresholds.generalPct}%  ·  " +
                            "Critical ${thresholds.criticalMinDbm} dBm / ${thresholds.criticalPct}%" +
                            (thresholds.minDaq?.let { "  ·  DAQ ≥ %.1f".format(it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Defaults only -- IFC 510 and NFPA 72/1225 do not agree on the exact " +
                            "general-area percentage, and your AHJ may amend either. Confirm the " +
                            "actual figures for this jurisdiction before relying on these.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (thresholdsExpanded) {
                        HorizontalDivider()
                        IntField("General dBm", thresholds.generalMinDbm) {
                            thresholds = thresholds.copy(generalMinDbm = it)
                        }
                        IntField("General %", thresholds.generalPct) {
                            thresholds = thresholds.copy(generalPct = it)
                        }
                        IntField("Critical dBm", thresholds.criticalMinDbm) {
                            thresholds = thresholds.copy(criticalMinDbm = it)
                        }
                        IntField("Critical %", thresholds.criticalPct) {
                            thresholds = thresholds.copy(criticalPct = it)
                        }
                        DoubleField(
                            label = "Min DAQ (blank = don't grade DAQ)",
                            value = thresholds.minDaq,
                        ) { thresholds = thresholds.copy(minDaq = it) }
                        Text(
                            "DAQ grades a reading only when you also record a DAQ value for it. " +
                                "Common objectives: 3.0 (ERRCS acceptance), 3.4 (TSB-88 wide-area P25).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (plan != null) {
            val bmp = bitmap
            if (bmp != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Grid overlay (NFPA method)", style = MaterialTheme.typography.titleSmall)
                                Switch(checked = showGrid, onCheckedChange = { showGrid = it })
                            }
                            if (showGrid) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Stepper("Cols", gridCols) {
                                        gridCols = it
                                        scope.launch { configStore.set(plan.id, gridRows, gridCols) }
                                    }
                                    Stepper("Rows", gridRows) {
                                        gridRows = it
                                        scope.launch { configStore.set(plan.id, gridRows, gridCols) }
                                    }
                                    Text(
                                        "${gridRows * gridCols} squares",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            FloorplanCanvas(
                                plan = plan,
                                bitmap = bmp,
                                placed = pointsHere.map { pt ->
                                    IndoorPosition(plan.id, pt.xNorm, pt.yNorm, pt.systemLabel) to
                                        (if (pt.passes(thresholds)) PASS_ARGB else FAIL_ARGB)
                                },
                                currentPosition = null,
                                onTap = { x, y -> pendingTap = x to y },
                                gridOverlay = if (!showGrid) null else GridOverlay(
                                    rows = gridRows,
                                    cols = gridCols,
                                ) { row, col ->
                                    when (cellVerdict[row to col]) {
                                        true -> PASS_CELL_ARGB
                                        false -> FAIL_CELL_ARGB
                                        null -> null
                                    }
                                },
                            )
                            Text(
                                "Pinch to zoom, drag to pan, tap to add a grid point. Point dots and " +
                                    "shaded squares: green passes, red fails; an unshaded square has no " +
                                    "reading yet.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Compliance — by point", style = MaterialTheme.typography.titleSmall)
                        compliance.forEach { c ->
                            val pct = c.actualPct
                            Text(
                                "${c.areaClass.label}: ${c.pointCount} points" +
                                    if (pct == null) {
                                        ", none tested yet"
                                    } else {
                                        ", %.0f%% passing (needs %d%%) -- %s".format(
                                            pct,
                                            c.requiredPct,
                                            if (c.meetsRequirement) "meets" else "does not meet",
                                        )
                                    },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        Text(
                            "Compliance — by grid square (${gridRows}×${gridCols})",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        gridCompliance.forEach { c ->
                            val pct = c.actualPct
                            Text(
                                "${c.areaClass.label}: ${c.testedCells} squares tested" +
                                    if (pct == null) {
                                        ", none yet"
                                    } else {
                                        ", %.0f%% passing (needs %d%%) -- %s".format(
                                            pct,
                                            c.requiredPct,
                                            if (c.meetsRequirement) "meets" else "does not meet",
                                        )
                                    },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            items(pointsHere.sortedByDescending { it.recordedAtUtcMillis }) { pt ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "${pt.areaClass.label} · ${pt.signalDbm} dBm DL" +
                                    (pt.inboundDbm?.let { " · $it dBm UL" } ?: "") +
                                    (pt.daq?.let { " · DAQ %.1f".format(it) } ?: "") +
                                    (pt.systemLabel?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (pt.passes(thresholds)) "Passes" else "Fails",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = {
                            points = store.delete(pt.id)
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }

    val tap = pendingTap
    if (tap != null && plan != null) {
        GridPointDialog(
            onDismiss = { pendingTap = null },
            onSave = { areaClass, dbm, inbound, daq, systemLabel, note ->
                val point = ErrcsGridPoint(
                    id = UUID.randomUUID().toString(),
                    floorplanId = plan.id,
                    xNorm = tap.first,
                    yNorm = tap.second,
                    areaClass = areaClass,
                    signalDbm = dbm,
                    inboundDbm = inbound,
                    daq = daq,
                    systemLabel = systemLabel.trim().ifBlank { null },
                    note = note.trim().ifBlank { null },
                    recordedAtUtcMillis = System.currentTimeMillis(),
                )
                scope.launch { points = store.upsert(point) }
                pendingTap = null
            },
        )
    }
}

private const val PASS_ARGB = 0xFF4C7A3D.toInt()
private const val FAIL_ARGB = 0xFFB3261E.toInt()

// Semi-transparent square fills for the grid overlay -- translucent so the floorplan stays legible
// through the shading, unlike the opaque point dots above.
private const val PASS_CELL_ARGB = 0x554C7A3D
private const val FAIL_CELL_ARGB = 0x55B3261E

@Composable
private fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            new.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Optional double field: an empty box means "not set" (`null`), any parseable number sets the value.
 * Used for DAQ, which is genuinely optional — distinct from [IntField], where blank is just invalid.
 */
@Composable
private fun DoubleField(label: String, value: Double?, onChange: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            onChange(if (new.isBlank()) null else new.toDoubleOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Compact −/value/+ control for the grid dimensions, clamped to a sane 1..20 range. */
@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onChange((value - 1).coerceIn(1, 20)) }) { Text("−") }
        Text("$value", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onChange((value + 1).coerceIn(1, 20)) }) { Text("+") }
    }
}

@Composable
private fun GridPointDialog(
    onDismiss: () -> Unit,
    onSave: (ErrcsAreaClass, Double, Double?, Double?, String, String) -> Unit,
) {
    var areaClass by remember { mutableStateOf(ErrcsAreaClass.GENERAL) }
    var dbmText by remember { mutableStateOf("") }
    var inboundText by remember { mutableStateOf("") }
    var daqText by remember { mutableStateOf("") }
    var systemLabel by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val dbm = dbmText.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grid point reading") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ErrcsAreaClass.entries.forEachIndexed { index, ac ->
                        SegmentedButton(
                            selected = areaClass == ac,
                            onClick = { areaClass = ac },
                            shape = SegmentedButtonDefaults.itemShape(index, ErrcsAreaClass.entries.size),
                        ) { Text(ac.label) }
                    }
                }
                OutlinedTextField(
                    value = dbmText,
                    onValueChange = { dbmText = it },
                    label = { Text("Outbound / DL (dBm), from your meter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = inboundText,
                    onValueChange = { inboundText = it },
                    label = { Text("Inbound / UL (dBm) — optional") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = daqText,
                    onValueChange = { daqText = it },
                    label = { Text("DAQ 1–5 — optional") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = systemLabel,
                    onValueChange = { systemLabel = it },
                    label = { Text("System tested (e.g. Fire Dept VHF)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = dbm != null,
                onClick = {
                    dbm?.let {
                        onSave(
                            areaClass, it,
                            inboundText.toDoubleOrNull(),
                            daqText.toDoubleOrNull(),
                            systemLabel, note,
                        )
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
