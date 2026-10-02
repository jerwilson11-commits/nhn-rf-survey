package com.nhnengineering.rftest.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.model.Building
import com.nhnengineering.rftest.model.Floorplan
import com.nhnengineering.rftest.model.FloorGeoref
import com.nhnengineering.rftest.model.IndoorPosition
import com.nhnengineering.rftest.map.TiePoint
import com.nhnengineering.rftest.service.RecordingState
import com.nhnengineering.rftest.session.BuildingStore
import com.nhnengineering.rftest.session.FloorplanStore
import kotlinx.coroutines.launch

/**
 * Georeference a building's floorplans against satellite imagery.
 *
 * The flow is deliberately short: include the building's floors, pick the reference floor, pair two
 * or more tie points (tap a feature on the plan, then the same spot on the satellite map), and save.
 * Every floor that shares the reference floor's drawing frame -- the normal case for one multi-page
 * PDF -- inherits the result automatically, so the whole stack is placed from one floor's tie points.
 *
 * The plan and the map are both on screen at once (no scrolling to reach the map), with the zoom
 * controls between them and Save always visible, because pairing is a back-and-forth between the two.
 */
@Composable
fun GeoreferenceScreen(modifier: Modifier = Modifier, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var plans by remember { mutableStateOf<List<Floorplan>>(emptyList()) }
    var buildingName by remember { mutableStateOf("") }
    val included = remember { mutableStateListOf<String>() }
    var referenceId by remember { mutableStateOf<String?>(null) }
    var showFloorPicker by remember { mutableStateOf(false) }

    val tiePoints = remember { mutableStateListOf<TiePoint>() }
    var pendingPlanPick by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var refBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    val fix by RecordingState.fix.collectAsState()
    var satLat by remember { mutableStateOf(fix?.latitudeDeg ?: 26.05) }
    var satLon by remember { mutableStateOf(fix?.longitudeDeg ?: -80.14) }
    var satZoom by remember { mutableStateOf(if (fix != null) 19 else 16) }

    LaunchedEffect(Unit) {
        // Name order, so a multi-page set reads p01..p17 and the stepper walks the floors in order.
        plans = FloorplanStore.list(context).sortedBy { it.displayName }
        // Default to including every floorplan; the operator unticks the odd ones out.
        if (included.isEmpty()) {
            included.addAll(plans.map { it.id })
            referenceId = plans.firstOrNull()?.id
        }
    }

    // Changing the reference floor invalidates tie points placed against the previous one.
    LaunchedEffect(referenceId) {
        tiePoints.clear()
        pendingPlanPick = null
        val id = referenceId
        refBitmap = if (id == null) null else runCatching {
            BitmapFactory.decodeFile(FloorplanStore.file(context, id).absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    val includedPlans = plans.filter { it.id in included }
    val refPlan = plans.firstOrNull { it.id == referenceId }
    val refIndex = includedPlans.indexOfFirst { it.id == referenceId }

    if (showFloorPicker) {
        FloorPickerDialog(
            plans = plans,
            included = included,
            referenceId = referenceId,
            buildingName = buildingName,
            onNameChange = { buildingName = it },
            onToggle = { id ->
                if (id in included) {
                    included.remove(id)
                    if (id == referenceId) referenceId = included.firstOrNull()
                } else included.add(id)
            },
            onAll = { included.clear(); included.addAll(plans.map { it.id }); if (referenceId == null) referenceId = plans.firstOrNull()?.id },
            onNone = { included.clear(); referenceId = null },
            onDismiss = { showFloorPicker = false },
        )
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header: reference-floor stepper + floors/done, compact so the two map panes get the height.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { if (refIndex > 0) referenceId = includedPlans[refIndex - 1].id },
                enabled = refIndex > 0,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) { Text("◀") }
            Text(
                refPlan?.displayName ?: "Reference floor",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { if (refIndex in 0 until includedPlans.lastIndex) referenceId = includedPlans[refIndex + 1].id },
                enabled = refIndex in 0 until includedPlans.lastIndex,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) { Text("▶") }
            OutlinedButton(
                onClick = { showFloorPicker = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) { Text("Floors ${included.size}") }
            OutlinedButton(
                onClick = onExit,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) { Text("Done") }
        }

        // Plan pane.
        val bmp = refBitmap
        val plan = refPlan
        if (plan != null && bmp != null) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val fit = if (maxWidth.value / maxHeight.value > plan.aspectRatio) {
                    Modifier.fillMaxHeight().aspectRatio(plan.aspectRatio)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(plan.aspectRatio)
                }
                FloorplanCanvas(
                    plan = plan,
                    bitmap = bmp,
                    placed = tiePoints.map {
                        IndoorPosition(plan.id, it.uNorm.toFloat(), it.vNorm.toFloat(), "tie") to 0xFFFFEB3B.toInt()
                    },
                    currentPosition = pendingPlanPick?.let { IndoorPosition(plan.id, it.first, it.second, "pick") },
                    onTap = { u, v -> pendingPlanPick = u to v },
                    boxModifier = fit,
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Load a floorplan first, then pick a reference floor.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Zoom / locate controls, clearly between the two panes.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { if (satZoom > 2) satZoom-- }, modifier = Modifier.weight(1f)) { Text("Zoom −") }
            FilledTonalButton(onClick = { if (satZoom < 21) satZoom++ }, modifier = Modifier.weight(1f)) { Text("Zoom +") }
            FilledTonalButton(
                onClick = { fix?.let { satLat = it.latitudeDeg; satLon = it.longitudeDeg; satZoom = 20 } },
                enabled = fix != null,
                modifier = Modifier.weight(1f),
            ) { Text("On GPS") }
        }

        // Satellite pane.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SatelliteMap(
                modifier = Modifier.fillMaxSize(),
                centerLat = satLat,
                centerLon = satLon,
                zoom = satZoom,
                onCenterChange = { la, lo -> satLat = la; satLon = lo },
                onZoomChange = { satZoom = it },
                onTapLatLon = { la, lo ->
                    val pick = pendingPlanPick
                    if (pick != null) {
                        tiePoints.add(TiePoint(pick.first.toDouble(), pick.second.toDouble(), la, lo))
                        pendingPlanPick = null
                    }
                },
                markers = tiePoints.map { it.lat to it.lon },
            )
        }

        val inheritCount = (includedPlans.size - 1).coerceAtLeast(0)
        Text(
            when {
                refPlan == null -> "Pick a reference floor with the Floors button."
                pendingPlanPick != null -> "Now tap that same spot on the map. (${tiePoints.size} set)"
                tiePoints.size < 2 -> "Tap a feature on the plan, then the same spot on the map — " +
                    "${tiePoints.size}/2. $inheritCount other same-size floor(s) inherit."
                else -> "${tiePoints.size} tie points — ready. $inheritCount floor(s) inherit. Zoom $satZoom."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tiePoints.isNotEmpty()) {
                OutlinedButton(onClick = { tiePoints.removeAt(tiePoints.lastIndex) }) { Text("Undo") }
            }
            Button(
                onClick = {
                    val refId = referenceId ?: return@Button
                    val floors = includedPlans.map { p ->
                        FloorGeoref(
                            floorplanId = p.id, widthPx = p.widthPx, heightPx = p.heightPx,
                            label = p.displayName,
                            tiePoints = if (p.id == refId) tiePoints.toList() else emptyList(),
                        )
                    }
                    val building = Building(
                        id = "bldg_${System.currentTimeMillis()}",
                        name = buildingName.ifBlank { refPlan?.displayName ?: "Building" },
                        referenceFloorId = refId, floors = floors,
                    )
                    scope.launch {
                        val others = BuildingStore.load(context)
                            .filterNot { b -> b.floors.any { it.floorplanId == refId } }
                        BuildingStore.save(context, others + building)
                        val resolved = building.resolve()
                        val g = resolved[refId]
                        savedMessage = if (g != null) {
                            "Saved ✓  ${resolved.size}/${floors.size} floors placed · " +
                                "scale ${"%.3f".format(g.metresPerPixel)} m/px · rot ${"%.1f".format(g.rotationDeg)}°"
                        } else {
                            "Saved, but the reference floor would not solve — re-check the tie points."
                        }
                    }
                },
                enabled = referenceId != null && tiePoints.size >= 2,
                modifier = Modifier.weight(1f),
            ) { Text(if (tiePoints.size >= 2) "Save georeference" else "Need 2+ tie points") }
        }

        savedMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FloorPickerDialog(
    plans: List<Floorplan>,
    included: List<String>,
    referenceId: String?,
    buildingName: String,
    onNameChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            Row {
                TextButton(onClick = onAll) { Text("All") }
                TextButton(onClick = onNone) { Text("None") }
            }
        },
        title = { Text("Building & floors") },
        text = {
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = buildingName,
                    onValueChange = onNameChange,
                    label = { Text("Building name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Tick the floors in this building:", style = MaterialTheme.typography.bodySmall)
                plans.forEach { p ->
                    FilterChip(
                        selected = p.id in included,
                        onClick = { onToggle(p.id) },
                        label = { Text(p.displayName + if (p.id == referenceId) "  (reference)" else "") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}
