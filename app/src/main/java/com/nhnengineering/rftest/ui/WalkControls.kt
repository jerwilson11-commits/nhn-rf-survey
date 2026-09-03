package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The controls an operator touches **while walking**, and nothing else.
 *
 * Everything here is sized for one-handed use in motion: 56 dp targets, one word per button, and
 * the current selection visible in the button itself rather than in a label above it. The previous
 * layout put these mid-card, below a paragraph of explanation, which meant marking a threshold
 * crossing required finding the control first.
 *
 * Custom text entry is deliberately **not** here. Typing needs a stop and a keyboard, and it belongs
 * on the expanded setup panel — a walk-time control that opens a keyboard is a walk-time control
 * that will not get used.
 */
@Composable
fun WalkControls(
    area: String?,
    onArea: (String?) -> Unit,
    floor: String?,
    onFloor: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Indoor", "Outdoor").forEach { preset ->
                val selected = area == preset
                // The selected state is carried by the button's own fill. An operator glancing
                // down mid-walk should see which mode is active without reading a status line.
                if (selected) {
                    Button(
                        onClick = { onArea(preset) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) { Text(preset, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                } else {
                    OutlinedButton(
                        onClick = { onArea(preset) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) { Text(preset, fontSize = 15.sp, maxLines = 1) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val numeric = floor?.toIntOrNull()
            OutlinedButton(
                onClick = { onFloor(((numeric ?: 0) - 1).toString()) },
                modifier = Modifier.weight(1f).height(52.dp),
                enabled = floor == null || numeric != null,
                contentPadding = PaddingValues(2.dp),
            ) { Text("−", fontSize = 22.sp, maxLines = 1) }

            FilledTonalButton(
                onClick = { },
                modifier = Modifier.weight(2f).height(52.dp),
                contentPadding = PaddingValues(2.dp),
            ) {
                Text(
                    text = floor?.let { "Floor $it" } ?: "No floor",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }

            OutlinedButton(
                onClick = { onFloor(((numeric ?: 0) + 1).toString()) },
                modifier = Modifier.weight(1f).height(52.dp),
                enabled = floor == null || numeric != null,
                contentPadding = PaddingValues(2.dp),
            ) { Text("+", fontSize = 22.sp, maxLines = 1) }
        }

        if (floor != null && floor.toIntOrNull() == null) {
            Text(
                "\"$floor\" is not a number — use Setup to change it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Start / stop, sized so it cannot be missed or mistaken.
 *
 * Full width and 60 dp tall because it is pressed with a thumb, often while holding something else,
 * and because pressing Stop by accident mid-survey costs the walk.
 */
@Composable
fun RecordButton(recording: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Button(
        onClick = if (recording) onStop else onStart,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        colors = if (recording) {
            ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(
            text = if (recording) "STOP AND SAVE" else "START RECORDING",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Free-text labels, for a stop rather than a stride.
 *
 * Real buildings have areas the presets do not cover and floors that are not numbers — M, LL, B2,
 * PH. Those get typed once, standing still, which is why they live here and not in [WalkControls].
 */
@Composable
fun LabelEntry(onArea: (String?) -> Unit, onFloor: (String?) -> Unit) {
    var areaText by remember { mutableStateOf("") }
    var floorText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = areaText,
                onValueChange = { areaText = it },
                label = { Text("Area name") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            Button(
                onClick = { onArea(areaText.trim().ifBlank { null }); areaText = "" },
                modifier = Modifier.weight(1f),
            ) { Text("Set") }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = floorText,
                onValueChange = { floorText = it },
                label = { Text("Floor label") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            Button(
                onClick = { onFloor(floorText.trim().ifBlank { null }); floorText = "" },
                modifier = Modifier.weight(1f),
            ) { Text("Set") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onArea(null) }, modifier = Modifier.weight(1f)) {
                Text("Clear area", maxLines = 1)
            }
            OutlinedButton(onClick = { onFloor(null) }, modifier = Modifier.weight(1f)) {
                Text("Clear floor", maxLines = 1)
            }
        }
    }
}
