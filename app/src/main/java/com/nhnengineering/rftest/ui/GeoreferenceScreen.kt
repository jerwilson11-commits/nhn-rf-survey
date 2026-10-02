package com.nhnengineering.rftest.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.map.GeoReference
import com.nhnengineering.rftest.map.TiePoint
import com.nhnengineering.rftest.model.Building
import com.nhnengineering.rftest.model.Floorplan
import com.nhnengineering.rftest.model.FloorGeoref
import com.nhnengineering.rftest.model.IndoorPosition
import com.nhnengineering.rftest.service.RecordingState
import com.nhnengineering.rftest.session.BuildingStore
import com.nhnengineering.rftest.session.FloorplanStore
import kotlinx.coroutines.launch

private enum class PlanTapMode { TIE_POINT, STACK_ANCHOR }

/**
 * Georeference a building's floorplans against satellite imagery, iBwave-style.
 *
 * The reference floor is pinned to the ground with >= 2 tie points: tap a feature on the plan, then
 * the same real spot on the satellite map. Upper floors are stacked by marking one shared vertical
 * feature (a lift core or stair) on each sheet -- they inherit the reference floor's scale and
 * rotation. Everything is persisted through [BuildingStore]; the maths is [GeoReference]/[Building].
 */
@Composable
fun GeoreferenceScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var plans by remember { mutableStateOf<List<Floorplan>>(emptyList()) }
    var buildingName by remember { mutableStateOf("") }
    var referenceId by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(PlanTapMode.TIE_POINT) }

    val included = remember { mutableStateListOf<String>() }
    // Reference-floor tie points, and each floor's stack anchor (u,v).
    val tiePoints: SnapshotStateList<TiePoint> = remember { mutableStateListOf() }
    var pendingPlanPick by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val anchors = remember { mutableStateMapOf<String, Pair<Float, Float>>() }

    var editingBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    // Start the satellite view over the last GPS fix if there is one, else a wide default.
    val fix by RecordingState.fix.collectAsState()
    var satLat by remember { mutableStateOf(fix?.latitudeDeg ?: 26.05) }
    var satLon by remember { mutableStateOf(fix?.longitudeDeg ?: -80.14) }
    var satZoom by remember { mutableStateOf(if (fix != null) 19 else 16) }

    LaunchedEffect(Unit) {
        plans = FloorplanStore.list(context)
        if (referenceId == null) plans.firstOrNull()?.let {
            referenceId = it.id
            editingId = it.id
            if (it.id !in included) included.add(it.id)
        }
    }

    LaunchedEffect(editingId) {
        val id = editingId
        editingBitmap = if (id == null) null else runCatching {
            BitmapFactory.decodeFile(FloorplanStore.file(context, id).absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    val editingPlan = plans.firstOrNull { it.id == editingId }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Georeference floors", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = onExit) { Text("Done") }
            }
        }

        item {
            OutlinedTextField(
                value = buildingName,
                onValueChange = { buildingName = it },
                label = { Text("Building name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Floors: include, pick the reference, choose which to edit.
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Floors", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tick the floors in this building, pick the reference floor (the one you " +
                            "georeference against satellite), and tap a floor to edit it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    plans.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = p.id in included,
                                onClick = {
                                    if (p.id in included) {
                                        if (p.id != referenceId) included.remove(p.id)
                                    } else included.add(p.id)
                                },
                                label = { Text("in") },
                            )
                            RadioButton(
                                selected = p.id == referenceId,
                                onClick = {
                                    referenceId = p.id
                                    if (p.id !in included) included.add(p.id)
                                    editingId = p.id
                                },
                            )
                            YieldingText(
                                p.displayName + buildString {
                                    if (p.id == referenceId) append("  (reference, ${tiePoints.size} ties)")
                                    if (anchors.containsKey(p.id)) append("  ⚓")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (p.id == editingId) FontWeight.Bold else FontWeight.Normal,
                            )
                            OutlinedButton(onClick = { editingId = p.id }) {
                                Text(if (p.id == editingId) "Editing" else "Edit")
                            }
                        }
                    }
                }
            }
        }

        // Editing area for the selected floor.
        if (editingPlan != null && editingBitmap != null) {
            val isReference = editingId == referenceId
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isReference) {
                                FilterChip(
                                    selected = mode == PlanTapMode.TIE_POINT,
                                    onClick = { mode = PlanTapMode.TIE_POINT },
                                    label = { Text("Tie point") },
                                )
                            }
                            FilterChip(
                                selected = mode == PlanTapMode.STACK_ANCHOR,
                                onClick = { mode = PlanTapMode.STACK_ANCHOR },
                                label = { Text("Shared anchor") },
                            )
                        }

                        val planMarks = buildList {
                            if (isReference) {
                                tiePoints.forEach {
                                    add(IndoorPosition(editingPlan.id, it.uNorm.toFloat(), it.vNorm.toFloat(), "tie") to null)
                                }
                            }
                            anchors[editingPlan.id]?.let {
                                add(IndoorPosition(editingPlan.id, it.first, it.second, "anchor") to 0xFFFFEB3B.toInt())
                            }
                        }
                        val pendingMark = pendingPlanPick?.takeIf { mode == PlanTapMode.TIE_POINT && isReference }
                            ?.let { IndoorPosition(editingPlan.id, it.first, it.second, "pick") }

                        FloorplanCanvas(
                            plan = editingPlan,
                            bitmap = editingBitmap!!,
                            placed = planMarks,
                            currentPosition = pendingMark,
                            onTap = { u, v ->
                                when (mode) {
                                    PlanTapMode.TIE_POINT -> if (isReference) pendingPlanPick = u to v
                                    PlanTapMode.STACK_ANCHOR -> anchors[editingPlan.id] = u to v
                                }
                            },
                        )

                        if (isReference && mode == PlanTapMode.TIE_POINT) {
                            Text(
                                if (pendingPlanPick == null) {
                                    "Tap the feature on the plan, then tap the same spot on the map below."
                                } else {
                                    "Now tap that same spot on the satellite map below to pair it."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text(
                                "Tap the shared vertical feature (lift core, stair) on this sheet — " +
                                    "mark the same feature on every floor so they stack.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // Satellite map, only while pairing tie points on the reference floor.
            if (isReference && mode == PlanTapMode.TIE_POINT) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Satellite — tap the real location", style = MaterialTheme.typography.titleMedium)
                            SatelliteMap(
                                modifier = Modifier.fillMaxWidth().height(320.dp),
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
                            Text(
                                "Esri satellite. Drag to pan, pinch to zoom. ${tiePoints.size} tie point(s) set.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (tiePoints.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { tiePoints.removeAt(tiePoints.lastIndex) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Undo last tie point") }
                            }
                        }
                    }
                }
            }
        }

        item { HorizontalDivider() }

        item {
            val refTies = tiePoints.size
            val canSave = referenceId != null && refTies >= 2
            Button(
                onClick = {
                    val refId = referenceId ?: return@Button
                    val floors = plans.filter { it.id in included }.map { p ->
                        val a = anchors[p.id]
                        FloorGeoref(
                            floorplanId = p.id,
                            widthPx = p.widthPx,
                            heightPx = p.heightPx,
                            label = p.displayName,
                            tiePoints = if (p.id == refId) tiePoints.toList() else emptyList(),
                            stackAnchorU = a?.first?.toDouble(),
                            stackAnchorV = a?.second?.toDouble(),
                        )
                    }
                    val building = Building(
                        id = "bldg_${System.currentTimeMillis()}",
                        name = buildingName.ifBlank { "Building" },
                        referenceFloorId = refId,
                        floors = floors,
                    )
                    scope.launch {
                        val existing = BuildingStore.load(context)
                            .filterNot { b -> b.floors.any { it.floorplanId == refId } }
                        BuildingStore.save(context, existing + building)
                        val resolved = building.resolve()
                        val ref = resolved[refId]
                        savedMessage = if (ref != null) {
                            "Saved. Reference scale ≈ ${"%.3f".format(ref.metresPerPixel)} m/px, " +
                                "rotation ${"%.1f".format(ref.rotationDeg)}°, ${resolved.size} floor(s) placed."
                        } else {
                            "Saved, but the reference floor could not be solved — check the tie points."
                        }
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (canSave) "Save georeference" else "Need 2+ tie points on the reference floor") }
        }

        savedMessage?.let { msg ->
            item {
                Text(msg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
