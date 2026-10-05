package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.model.CoverageArea
import com.nhnengineering.rftest.model.CoverageVertex
import com.nhnengineering.rftest.model.Floorplan

/**
 * Trace a floor-outline polygon corner by corner with the crosshair — the iBwave-style coverage-area
 * capture, shared by the P. Safety tab (grid method) and the Plan tab (cellular coverage).
 *
 * Uses [FloorplanCanvas]'s crosshair mode rather than finger taps because wall corners need to be
 * placed precisely: the operator pans so the fixed centre crosshair sits on a corner, then taps
 * "Add corner". The in-progress polygon is drawn open; it is closed on save.
 */
@Composable
internal fun CoverageAreaEditor(
    plan: Floorplan,
    bitmap: ImageBitmap,
    initial: CoverageArea,
    onSave: (CoverageArea) -> Unit,
    onCancel: () -> Unit,
) {
    var vertices by remember { mutableStateOf(initial.vertices) }
    // Normalised (u,v) under the crosshair, updated as the operator pans/zooms.
    var crosshair by remember { mutableStateOf(0.5f to 0.5f) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Trace the floor outline", style = MaterialTheme.typography.titleSmall)
        Text(
            "Pan so the crosshair sits on a wall corner, then Add corner. Work around the walls, " +
                "then Save area. This defines the coverage area — the grids lay out inside it, and " +
                "cellular coverage is reported against it.",
            style = MaterialTheme.typography.bodySmall,
        )
        FloorplanCanvas(
            plan = plan,
            bitmap = bitmap,
            placed = emptyList(),
            currentPosition = null,
            onTap = { _, _ -> }, // placement is crosshair-driven, not tap-driven
            showCrosshair = true,
            onCenterChange = { u, v -> crosshair = u to v },
            openPolygon = vertices.map { Offset(it.x, it.y) },
        )
        Text("${vertices.size} corners placed", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                vertices = vertices + CoverageVertex(crosshair.first, crosshair.second)
            }) { Text("Add corner") }
            OutlinedButton(
                enabled = vertices.isNotEmpty(),
                onClick = { vertices = vertices.dropLast(1) },
            ) { Text("Undo") }
            OutlinedButton(
                enabled = vertices.isNotEmpty(),
                onClick = { vertices = emptyList() },
            ) { Text("Clear") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = vertices.size >= 3,
                onClick = { onSave(CoverageArea(vertices)) },
            ) { Text("Save area") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
