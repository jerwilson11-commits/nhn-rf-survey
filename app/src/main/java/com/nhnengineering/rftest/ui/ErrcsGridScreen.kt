package com.nhnengineering.rftest.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.map.CoverageOutlineDetector
import com.nhnengineering.rftest.map.GeoReference
import com.nhnengineering.rftest.model.CoverageArea
import com.nhnengineering.rftest.model.CoverageRegion
import com.nhnengineering.rftest.model.hasCoverage
import com.nhnengineering.rftest.model.errcsFloorGridCompliance
import com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_COLS
import com.nhnengineering.rftest.model.ERRCS_DEFAULT_GRID_ROWS
import com.nhnengineering.rftest.model.ErrcsAreaClass
import com.nhnengineering.rftest.model.ErrcsCriticalArea
import com.nhnengineering.rftest.model.ErrcsGridPoint
import com.nhnengineering.rftest.model.Floorplan
import com.nhnengineering.rftest.model.IndoorPosition
import com.nhnengineering.rftest.model.PublicSafetyThresholds
import com.nhnengineering.rftest.model.effectiveAreaClass
import com.nhnengineering.rftest.model.errcsCompliance
import com.nhnengineering.rftest.model.errcsGridCompliance
import com.nhnengineering.rftest.model.errcsGridCellSize
import com.nhnengineering.rftest.model.errcsGridSquares
import com.nhnengineering.rftest.model.fitErrcsGrid
import com.nhnengineering.rftest.model.coverageAreaSquareMetres
import com.nhnengineering.rftest.model.isInCriticalArea
import com.nhnengineering.rftest.model.squareMetresToFeet
import com.nhnengineering.rftest.session.BuildingStore
import com.nhnengineering.rftest.session.CoverageAreaStore
import com.nhnengineering.rftest.session.ErrcsCriticalAreaStore
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
    var markMode by remember { mutableStateOf(false) }
    var criticalAreas by remember { mutableStateOf<List<ErrcsCriticalArea>>(emptyList()) }
    // First corner of a critical-area rectangle being drawn (normalised); the second tap closes it.
    var pendingCorner by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // Coverage regions for the selected floor (a floor can hold several: a main building plus a
    // detached outbuilding). Each region carries its own grid; [selectedRegion] is the one the grid
    // steppers/Fit act on.
    var regions by remember { mutableStateOf<List<CoverageRegion>>(emptyList()) }
    var selectedRegion by remember { mutableStateOf(0) }
    var editingCoverage by remember { mutableStateOf(false) }
    // Auto-detect: when armed, the next canvas tap samples the bold outline there and traces it.
    var detectArmed by remember { mutableStateOf(false) }
    var detectMsg by remember { mutableStateOf<String?>(null) }
    var geoRef by remember { mutableStateOf<GeoReference?>(null) }

    var georefIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val store = remember { ErrcsGridStore(File(context.filesDir, "errcs_grid.jsonl")) }
    val criticalStore = remember { ErrcsCriticalAreaStore(File(context.filesDir, "errcs_critical.jsonl")) }
    val coverageStore = remember { CoverageAreaStore(File(context.filesDir, "coverage_area.jsonl")) }

    // Import a floorplan without leaving the tab (same PDF/image picker as the Plan tab).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = runCatching {
                    context.contentResolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
                    )?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull() ?: uri.lastPathSegment
                val imported = FloorplanStore.import(context, uri, name)
                plans = FloorplanStore.list(context)
                imported?.let { selected = it }
            }
        }
    }

    LaunchedEffect(Unit) {
        plans = FloorplanStore.list(context)
        points = store.load().points
    }

    LaunchedEffect(plans) {
        georefIds = runCatching { BuildingStore.georeferencedIds(context) }.getOrElse { emptySet() }
    }

    LaunchedEffect(selected) {
        val plan = selected
        bitmap = if (plan == null) null else {
            runCatching {
                BitmapFactory.decodeFile(FloorplanStore.file(context, plan.id).absolutePath)
                    ?.asImageBitmap()
            }.getOrNull()
        }
        // Each floorplan remembers its own grid size (floors differ in shape and size) and its own
        // AHJ-designated critical areas.
        plan?.let {
            criticalAreas = criticalStore.areasFor(it.id)
            regions = coverageStore.regionsFor(it.id)
        }
        selectedRegion = 0
        geoRef = plan?.let { runCatching { BuildingStore.geoReferenceFor(context, it.id) }.getOrNull() }
        editingCoverage = false
        detectArmed = false
        detectMsg = null
    }

    val plan = selected
    val pointsHere = plan?.let { p -> points.filter { it.floorplanId == p.id } } ?: emptyList()
    val compliance = errcsCompliance(pointsHere, thresholds, criticalAreas)
    // Each region graded on its own grid, summed per area class across the floor.
    val floorGrid = errcsFloorGridCompliance(pointsHere, regions, thresholds, criticalAreas)
    // Per-region squares, aligned to `regions` by index: drives the overlay shading and the
    // inside-counts, so screen and compliance can never disagree.
    val squaresByRegion = regions.map { r ->
        errcsGridSquares(pointsHere, r.rows, r.cols, criticalAreas, r.polygon)
    }
    val sel = selectedRegion.coerceIn(0, (regions.size - 1).coerceAtLeast(0))

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        if (plan != null) {
            val bmp = bitmap
            if (bmp != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Coverage areas: traced floor outlines the grids lay out inside. A floor
                            // can hold several (main building + a detached outbuilding).
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Coverage areas", style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(onClick = {
                                        detectArmed = true
                                        detectMsg = "Tap the bold coloured coverage outline on the plan."
                                    }) { Text("Detect") }
                                    OutlinedButton(onClick = { editingCoverage = true }) { Text("Add area") }
                                }
                            }
                            detectMsg?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (detectArmed) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (!regions.hasCoverage) {
                                Text(
                                    "No coverage area yet — grids cover the whole page. Add the floor " +
                                        "outline(s) so the grids land on the floor, not the PDF margins.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            if (editingCoverage) {
                                CoverageAreaEditor(
                                    plan = plan,
                                    bitmap = bmp,
                                    initial = CoverageArea.EMPTY,
                                    onSave = { area ->
                                        editingCoverage = false
                                        // Auto-fit the new region's grid to >= 20 squares inside it.
                                        val (r, c) = fitErrcsGrid(area, plan.aspectRatio)
                                        val updated = regions + CoverageRegion(area, r, c)
                                        regions = updated
                                        selectedRegion = updated.lastIndex
                                        scope.launch { coverageStore.setRegions(plan.id, updated) }
                                    },
                                    onCancel = { editingCoverage = false },
                                )
                            }

                            if (!editingCoverage) {
                            // Region list: select one to edit its grid, or delete it.
                            regions.forEachIndexed { i, region ->
                                val insideN = squaresByRegion.getOrNull(i)?.count { it.testable } ?: 0
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Area ${i + 1}: ${region.rows}×${region.cols}, $insideN inside" +
                                            (if (insideN < 20) " (<20)" else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (i == sel) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(onClick = { selectedRegion = i }) {
                                            Text(if (i == sel) "Editing" else "Edit")
                                        }
                                        TextButton(onClick = {
                                            val updated = regions.toMutableList().also { it.removeAt(i) }
                                            regions = updated
                                            selectedRegion = 0
                                            scope.launch { coverageStore.setRegions(plan.id, updated) }
                                        }) { Text("Delete") }
                                    }
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Grid overlay (NFPA method)", style = MaterialTheme.typography.titleSmall)
                                Switch(checked = showGrid, onCheckedChange = { showGrid = it })
                            }
                            if (showGrid && regions.isNotEmpty()) {
                                val region = regions[sel]
                                fun updateSel(newRegion: CoverageRegion) {
                                    val updated = regions.toMutableList().also { it[sel] = newRegion }
                                    regions = updated
                                    scope.launch { coverageStore.setRegions(plan.id, updated) }
                                }
                                Text("Grid — Area ${sel + 1}", style = MaterialTheme.typography.labelMedium)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Stepper("Cols", region.cols) { updateSel(region.copy(cols = it)) }
                                    Stepper("Rows", region.rows) { updateSel(region.copy(rows = it)) }
                                    OutlinedButton(onClick = {
                                        val (r, c) = fitErrcsGrid(region.polygon, plan.aspectRatio)
                                        updateSel(region.copy(rows = r, cols = c))
                                    }) { Text("Fit ~20") }
                                }
                                val inside = squaresByRegion.getOrNull(sel)?.count { it.testable } ?: 0
                                if (inside < 20) {
                                    Text(
                                        "Only $inside inside Area ${sel + 1} — NFPA wants ≥ 20; Fit or add.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }

                            // Tap target: add a reading, or draw an AHJ-designated critical-area
                            // rectangle (independent of the grid, so a stairwell can be outlined tightly).
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = !markMode,
                                    onClick = { markMode = false; pendingCorner = null },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) { Text("Add reading") }
                                SegmentedButton(
                                    selected = markMode,
                                    onClick = { markMode = true },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                ) { Text("Mark critical") }
                            }
                            FloorplanCanvas(
                                plan = plan,
                                bitmap = bmp,
                                placed = pointsHere.map { pt ->
                                    val pass = pt.passesAs(pt.effectiveAreaClass(criticalAreas), thresholds)
                                    IndoorPosition(plan.id, pt.xNorm, pt.yNorm, pt.systemLabel) to
                                        (if (pass) PASS_ARGB else FAIL_ARGB)
                                },
                                currentPosition = pendingCorner?.let { (x, y) ->
                                    IndoorPosition(plan.id, x, y, "corner 1")
                                },
                                onTap = { x, y ->
                                    when {
                                        detectArmed -> {
                                            detectArmed = false
                                            detectMsg = "Detecting…"
                                            val bm = bmp.asAndroidBitmap()
                                            scope.launch {
                                                val area = CoverageOutlineDetector.detect(bm, x, y)
                                                if (area != null && area.isDefined) {
                                                    val (r, c) = fitErrcsGrid(area, plan.aspectRatio)
                                                    val updated = regions + CoverageRegion(area, r, c)
                                                    regions = updated
                                                    selectedRegion = updated.lastIndex
                                                    coverageStore.setRegions(plan.id, updated)
                                                    detectMsg = "Detected a coverage area (${area.vertices.size} " +
                                                        "points). Check it, adjust the grid, or delete if wrong."
                                                } else {
                                                    detectMsg = "No outline found there. Tap directly on the bold " +
                                                        "coloured line (zoom in first)."
                                                }
                                            }
                                        }
                                        !markMode -> pendingTap = x to y
                                        else -> {
                                            val existing = criticalAreas.firstOrNull { it.contains(x, y) }
                                            val corner = pendingCorner
                                            when {
                                                corner == null && existing != null -> {
                                                    val updated = criticalAreas - existing
                                                    criticalAreas = updated
                                                    scope.launch { criticalStore.setAreas(plan.id, updated) }
                                                }
                                                corner == null -> pendingCorner = x to y
                                                else -> {
                                                    val rect = ErrcsCriticalArea(corner.first, corner.second, x, y)
                                                    pendingCorner = null
                                                    if (kotlin.math.abs(rect.x1 - rect.x0) > 0.003f &&
                                                        kotlin.math.abs(rect.y1 - rect.y0) > 0.003f
                                                    ) {
                                                        val updated = criticalAreas + rect
                                                        criticalAreas = updated
                                                        scope.launch { criticalStore.setAreas(plan.id, updated) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                gridOverlays = if (!showGrid) {
                                    emptyList()
                                } else {
                                    regions.mapIndexed { i, region ->
                                        val byCell = squaresByRegion.getOrNull(i).orEmpty()
                                            .associateBy { it.row to it.col }
                                        GridOverlay(
                                            rows = region.rows,
                                            cols = region.cols,
                                            bounds = region.polygon.bounds()
                                                .let { Rect(it[0], it[1], it[2], it[3]) },
                                            cellArgb = { row, col ->
                                                val sq = byCell[row to col]
                                                when {
                                                    sq == null || !sq.testable || !sq.tested -> null
                                                    sq.passes(thresholds) -> PASS_CELL_ARGB
                                                    else -> FAIL_CELL_ARGB
                                                }
                                            },
                                        )
                                    }
                                },
                                criticalRegions = criticalAreas.map {
                                    Rect(it.x0, it.y0, it.x1, it.y1)
                                },
                                polygons = regions.map { r -> r.polygon.vertices.map { Offset(it.x, it.y) } },
                            )
                            Text(
                                if (markMode) {
                                    if (pendingCorner == null) {
                                        "Mark mode: tap one corner of a critical area, then the opposite " +
                                            "corner to draw it. Zoom in for a tight fit. Tap inside an " +
                                            "existing blue area to remove it."
                                    } else {
                                        "Now tap the opposite corner to finish the rectangle."
                                    }
                                } else {
                                    "Pinch to zoom, drag to pan, tap to add a reading. Dots/squares: " +
                                        "green passes, red fails, unshaded = no reading yet. Teal = a " +
                                        "coverage area; blue = a designated critical area."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (markMode && pendingCorner != null) {
                                TextButton(onClick = { pendingCorner = null }) { Text("Cancel corner") }
                            }
                            if (criticalAreas.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${criticalAreas.size} critical area" +
                                            (if (criticalAreas.size == 1) "" else "s"),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(onClick = {
                                        criticalAreas = emptyList()
                                        pendingCorner = null
                                        scope.launch { criticalStore.setAreas(plan.id, emptyList()) }
                                    }) { Text("Clear critical") }
                                }
                            }
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Floor sizing", style = MaterialTheme.typography.titleSmall)
                        val gr = geoRef
                        if (gr == null) {
                            Text(
                                "Georeference this floor on the Plan tab (satellite tie points) to get " +
                                    "floor area in ft² and the NFPA 80-ft grid-dimension check.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            if (regions.hasCoverage) {
                                val m2 = regions.sumOf {
                                    coverageAreaSquareMetres(
                                        it.polygon, gr.widthPx, gr.heightPx, gr.metresPerPixel,
                                    )
                                }
                                Text(
                                    "Floor area: %,.0f ft² (%,.0f m²)"
                                        .format(squareMetresToFeet(m2), m2),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (regions.size > 1) {
                                    Text(
                                        "${regions.size} areas on this floor; floor area is their sum.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            } else {
                                Text(
                                    "Add a coverage area above to measure floor area.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            // Per-region 80-ft check: flag the largest offending grid dimension.
                            val over = regions.mapNotNull { r ->
                                errcsGridCellSize(
                                    r.polygon, r.rows, r.cols, gr.widthPx, gr.heightPx, gr.metresPerPixel,
                                )?.takeIf { it.exceedsNfpaMax() }?.maxDimFt
                            }
                            if (over.isNotEmpty()) {
                                Text(
                                    ("⚠ A grid exceeds the NFPA 80-ft maximum dimension (largest %.0f " +
                                        "ft). Add rows/cols to that area, or sector the floor.")
                                        .format(over.max()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else if (regions.isNotEmpty()) {
                                val r = regions[sel]
                                errcsGridCellSize(
                                    r.polygon, r.rows, r.cols, gr.widthPx, gr.heightPx, gr.metresPerPixel,
                                )?.let { cell ->
                                    Text(
                                        "Area ${sel + 1} grid ≈ %.0f × %.0f ft"
                                            .format(cell.widthM / 0.3048, cell.heightM / 0.3048),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
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
                            "Compliance — by grid square" +
                                (if (regions.size > 1) " (all ${regions.size} areas)" else ""),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        floorGrid.forEach { c ->
                            val pct = c.actualPct
                            Text(
                                "${c.areaClass.label}: " +
                                    if (pct == null) {
                                        "no squares"
                                    } else {
                                        "%d/%d tested, %.0f%% pass (needs %d%%) -- %s%s".format(
                                            c.testedCells,
                                            c.totalCells,
                                            pct,
                                            c.requiredPct,
                                            if (c.meetsRequirement) "meets" else "does not meet",
                                            if (c.complete) "" else " · incomplete",
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
                        val effClass = pt.effectiveAreaClass(criticalAreas)
                        val inDesignated = effClass == ErrcsAreaClass.CRITICAL &&
                            pt.areaClass != ErrcsAreaClass.CRITICAL
                        Column {
                            Text(
                                "${effClass.label}${if (inDesignated) " (designated)" else ""} · " +
                                    "${pt.signalDbm} dBm DL" +
                                    (pt.inboundDbm?.let { " · $it dBm UL" } ?: "") +
                                    (pt.daq?.let { " · DAQ %.1f".format(it) } ?: "") +
                                    (pt.systemLabel?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (pt.passesAs(effClass, thresholds)) "Passes" else "Fails",
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

        item {
            FloorplanPickerCard(
                plans = plans,
                selectedId = selected?.id,
                georefIds = georefIds,
                onSelect = { selected = it },
                onDelete = { p ->
                    scope.launch {
                        FloorplanStore.delete(context, p.id)
                        BuildingStore.removeFloorplan(context, p.id)
                        plans = FloorplanStore.list(context)
                        if (selected?.id == p.id) selected = null
                    }
                },
                onImport = {
                    picker.launch(arrayOf("application/pdf", "image/png", "image/jpeg", "image/webp"))
                },
                subtitle = "Public safety coverage — pick a floor, then tap readings on the map above.",
            )
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
    }

    val tap = pendingTap
    if (tap != null && plan != null) {
        GridPointDialog(
            inheritedCritical = isInCriticalArea(tap.first, tap.second, criticalAreas),
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
    inheritedCritical: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (ErrcsAreaClass, Double, Double?, Double?, String, String) -> Unit,
) {
    var areaClass by remember {
        mutableStateOf(if (inheritedCritical) ErrcsAreaClass.CRITICAL else ErrcsAreaClass.GENERAL)
    }
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
                if (inheritedCritical) {
                    // The tap landed in an AHJ-designated critical area; the class is inherited and the
                    // reading is graded critical regardless, so lock it rather than invite a confusing
                    // override that compliance would ignore.
                    Text(
                        "In a designated critical area — graded at the critical requirement.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ErrcsAreaClass.entries.forEachIndexed { index, ac ->
                            SegmentedButton(
                                selected = areaClass == ac,
                                onClick = { areaClass = ac },
                                shape = SegmentedButtonDefaults.itemShape(index, ErrcsAreaClass.entries.size),
                            ) { Text(ac.label) }
                        }
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
