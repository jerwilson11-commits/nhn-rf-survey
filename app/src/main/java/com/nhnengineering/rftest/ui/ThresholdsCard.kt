package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.model.Thresholds

/**
 * Pass/fail limits.
 *
 * Empty means "do not check this KPI", which is different from zero. Blanking a field disables its
 * alarm rather than setting a limit of 0 — the latter would fire constantly and get the whole
 * feature switched off.
 */
@Composable
fun ThresholdsCard(thresholds: Thresholds, onChange: (Thresholds) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Thresholds",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Switch(
                    checked = thresholds.enabled,
                    onCheckedChange = { onChange(thresholds.copy(enabled = it)) },
                )
            }
            Text(
                "Alarms fire during recording so a coverage hole gets investigated while you are " +
                    "standing in it, not found weeks later during analysis.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (thresholds.enabled) {
                HorizontalDivider()
                LimitField(
                    label = "RSSI below (dBm)",
                    value = thresholds.rssiMinDbm,
                    onChange = { onChange(thresholds.copy(rssiMinDbm = it)) },
                )
                LimitField(
                    label = "Co-channel APs above",
                    value = thresholds.coChannelMax,
                    onChange = { onChange(thresholds.copy(coChannelMax = it)) },
                )
                LimitField(
                    label = "GPS accuracy worse than (m)",
                    value = thresholds.gpsAccuracyMaxM,
                    onChange = { onChange(thresholds.copy(gpsAccuracyMaxM = it)) },
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Audible beep", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = thresholds.audible,
                        onCheckedChange = { onChange(thresholds.copy(audible = it)) },
                    )
                }
                Text(
                    "Leave a field empty to stop checking that KPI. A null reading never counts " +
                        "as a breach — \"no measurement\" and \"bad measurement\" are different.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LimitField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { text ->
            val trimmed = text.trim()
            when {
                trimmed.isEmpty() -> onChange(null)
                // Accept a lone "-" mid-typing so a negative dBm limit can actually be entered.
                trimmed == "-" -> Unit
                else -> trimmed.toIntOrNull()?.let(onChange)
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
