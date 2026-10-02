package com.nhnengineering.rftest.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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

private enum class Capture { NONE, PLAN, SATELLITE }

/**
 * Georeference a building's floorplans against satellite imagery.
 *
 * A tie point is captured one full screen at a time -- the floorplan fills the display to find the
 * feature, then the satellite fills the display to find the same real spot -- because pairing on a
 * phone needs room to pan, zoom, and tap precisely, which a cramped split view cannot give. Two or
 * more tie points on the reference floor place it; every floor sharing its drawing frame (the normal
 * case for one multi-page PDF) inherits the result, so the whole stack is placed from one floor.
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
    var refBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    var capture by remember { mutableStateOf(Capture.NONE) }
    var planPick by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // The (u,v) under the plan crosshair right now, updated as the operator pans/zooms.
    var planCenter by remember { mutableStateOf(0.5f to 0.5f) }

    val fix by RecordingState.fix.collectAsState()
    var satLat by remember { mutableStateOf(fix?.latitudeDeg ?: 26.05) }
    var satLon by remember { mutableStateOf(fix?.longitudeDeg ?: -80.14) }
    var satZoom by remember { mutableStateOf(if (fix != null) 19 else 16) }

    LaunchedEffect(Unit) {
        plans = FloorplanStore.list(context).sortedBy { it.displayName }
        if (included.isEmpty()) {
            included.addAll(plans.map { it.id })
            referenceId = plans.firstOrNull()?.id
        }
    }
    LaunchedEffect(referenceId) {
        tiePoints.clear()
        planPick = null
        val id = referenceId
        refBitmap = if (id == null) null else runCatching {
            BitmapFactory.decodeFile(FloorplanStore.file(context, id).absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    val includedPlans = plans.filter { it.id in included }
    val refPlan = plans.firstOrNull { it.id == referenceId }
    val refIndex = includedPlans.indexOfFirst { it.id == referenceId }
    val bmp = refBitmap

    // ---- Full-screen capture: step 1, the floorplan ----
    if (capture == Capture.PLAN && refPlan != null && bmp != null) {
        FullScreenCapture(
            modifier = modifier,
            title = "Step 1 — pinch/drag so the crosshair sits on the feature",
            onCancel = { capture = Capture.NONE; planPick = null },
            primaryLabel = "Next: find it on the map ▶",
            primaryEnabled = true,
            onPrimary = { planPick = planCenter; capture = Capture.SATELLITE },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val fit = if (maxWidth.value / maxHeight.value > refPlan.aspectRatio) {
                    Modifier.fillMaxHeight().aspectRatio(refPlan.aspectRatio)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(refPlan.aspectRatio)
                }
                FloorplanCanvas(
                    plan = refPlan,
                    bitmap = bmp,
                    placed = emptyList(),
                    currentPosition = null,
                    onTap = { _, _ -> },
                    boxModifier = fit,
                    onCenterChange = { u, v -> planCenter = u to v },
                    showCrosshair = true,
                )
            }
        }
        return
    }

    // ---- Full-screen capture: step 2, the satellite ----
    if (capture == Capture.SATELLITE) {
        FullScreenCapture(
            modifier = modifier,
            title = "Step 2 — pan so the crosshair sits on the same spot",
            onCancel = { capture = Capture.NONE; planPick = null },
            primaryLabel = "Confirm tie point ✓",
            primaryEnabled = true,
            onPrimary = {
                planPick?.let { tiePoints.add(TiePoint(it.first.toDouble(), it.second.toDouble(), satLat, satLon)) }
                planPick = null
                capture = Capture.NONE
            },
            controls = {
                FilledTonalButton(onClick = { if (satZoom > 2) satZoom-- }, modifier = Modifier.weight(1f)) { Text("Zoom −") }
                FilledTonalButton(onClick = { if (satZoom < 21) satZoom++ }, modifier = Modifier.weight(1f)) { Text("Zoom +") }
                FilledTonalButton(
                    onClick = { fix?.let { satLat = it.latitudeDeg; satLon = it.longitudeDeg; satZoom = 20 } },
                    enabled = fix != null, modifier = Modifier.weight(1f),
                ) { Text("On GPS") }
            },
        ) {
            SatelliteMap(
                modifier = Modifier.fillMaxSize(),
                centerLat = satLat, centerLon = satLon, zoom = satZoom,
                onCenterChange = { la, lo -> satLat = la; satLon = lo },
                onZoomChange = { satZoom = it },
                onTapLatLon = { _, _ -> },   // aim with the fixed crosshair; Confirm commits the centre
                markers = tiePoints.map { it.lat to it.lon },
            )
        }
        return
    }

    // ---- Main screen (compact; the maps live in the full-screen capture above) ----
    if (showFloorPicker) {
        FloorPickerDialog(
            plans = plans, included = included, referenceId = referenceId,
            buildingName = buildingName, onNameChange = { buildingName = it },
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
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Georeference",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { showFloorPicker = true }) { Text("Floors ${included.size}") }
            OutlinedButton(onClick = onExit) { Text("Done") }
        }

        // Reference-floor stepper.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Reference floor:", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { if (refIndex > 0) referenceId = includedPlans[refIndex - 1].id },
                enabled = refIndex > 0,
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text("◀") }
            Text(
                refPlan?.displayName ?: "none",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { if (refIndex in 0 until includedPlans.lastIndex) referenceId = includedPlans[refIndex + 1].id },
                enabled = refIndex in 0 until includedPlans.lastIndex,
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text("▶") }
        }

        val inheritCount = (includedPlans.size - 1).coerceAtLeast(0)
        Text(
            "Add 2+ tie points to the reference floor — place them far apart, ideally opposite " +
                "corners, for the best fit. The other $inheritCount same-size floor(s) inherit " +
                "automatically.",
            style = MaterialTheme.typography.bodySmall,
        )

        // Tie-point list.
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (tiePoints.isEmpty()) {
                Text("No tie points yet.", style = MaterialTheme.typography.bodyMedium)
            }
            tiePoints.forEachIndexed { i, t ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "#${i + 1}  plan ${"%.2f".format(t.uNorm)},${"%.2f".format(t.vNorm)}  →  " +
                            "${"%.5f".format(t.lat)}, ${"%.5f".format(t.lon)}",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { tiePoints.removeAt(i) },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text("✕") }
                }
            }
        }

        Button(
            onClick = { planPick = null; capture = Capture.PLAN },
            enabled = refPlan != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("＋ Add tie point") }

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
                    val others = BuildingStore.load(context).filterNot { b -> b.floors.any { it.floorplanId == refId } }
                    BuildingStore.save(context, others + building)
                    val resolved = building.resolve()
                    val g = resolved[refId]
                    savedMessage = if (g != null) {
                        "Saved ✓  ${resolved.size}/${floors.size} floors placed · scale " +
                            "${"%.3f".format(g.metresPerPixel)} m/px · rot ${"%.1f".format(g.rotationDeg)}°"
                    } else {
                        "Saved, but the reference floor would not solve — re-check the tie points."
                    }
                }
            },
            enabled = referenceId != null && tiePoints.size >= 2,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (tiePoints.size >= 2) "Save georeference" else "Need 2+ tie points (${tiePoints.size})") }

        savedMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * A full-screen capture surface with a translucent title bar on top and an action bar at the bottom:
 * optional [controls] above a Cancel + primary action. The [content] (floorplan or satellite) fills
 * the space between, getting the whole display to pan, zoom, and tap on.
 */
@Composable
private fun FullScreenCapture(
    modifier: Modifier = Modifier,
    title: String,
    onCancel: () -> Unit,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    controls: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        content()

        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(12.dp),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (controls != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.weight(2f)) { Text(primaryLabel) }
                }
            }
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
                    value = buildingName, onValueChange = onNameChange,
                    label = { Text("Building name") }, singleLine = true,
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
