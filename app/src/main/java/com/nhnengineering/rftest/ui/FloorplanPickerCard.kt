package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.model.Floorplan

/**
 * Shared floorplan picker used by the Plan and P. Safety tabs so they can't drift: a library list with
 * an import button, a "✓ georeferenced" marker per floor, and per-row Use / Delete.
 *
 * The caller owns the data and actions; this is presentation only.
 */
@Composable
fun FloorplanPickerCard(
    plans: List<Floorplan>,
    selectedId: String?,
    georefIds: Set<String>,
    onSelect: (Floorplan) -> Unit,
    onDelete: (Floorplan) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Floorplan library",
    subtitle: String? = null,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text("Load floorplan (PDF or image)")
            }
            if (plans.isEmpty()) {
                Text("No floorplan loaded yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                plans.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName, style = MaterialTheme.typography.bodyMedium)
                            if (p.id in georefIds) {
                                Text(
                                    "✓ georeferenced",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = { onSelect(p) }) {
                                Text(if (p.id == selectedId) "Selected" else "Use")
                            }
                            TextButton(onClick = { onDelete(p) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
