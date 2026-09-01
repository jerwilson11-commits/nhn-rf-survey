package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.model.GeoPoint
import java.util.Locale
import kotlin.math.roundToInt

/** Session record/stop plus live counters for what has actually been written to file. */
@Composable
fun RecorderPanel(
    recording: Boolean,
    sessionName: String,
    onSessionNameChange: (String) -> Unit,
    rowCount: Long,
    elapsedMs: Long,
    distanceM: Double,
    fixesWithVelocity: Long,
    fixesWithoutVelocity: Long,
    lastFile: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (recording) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (recording) "● RECORDING" else "Session",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (recording) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurface,
            )

            OutlinedTextField(
                value = sessionName,
                onValueChange = onSessionNameChange,
                label = { Text("Site / venue / sector") },
                singleLine = true,
                enabled = !recording,
                modifier = Modifier.fillMaxWidth(),
            )

            if (recording) {
                HorizontalDivider()
                val totalFixes = fixesWithVelocity + fixesWithoutVelocity
                val missingPct = if (totalFixes > 0) {
                    (fixesWithoutVelocity * 100 / totalFixes).toInt()
                } else {
                    0
                }
                val approximate = missingPct >= 10

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Rows", rowCount.toString())
                    Stat("Elapsed", formatElapsed(elapsedMs))
                    Stat(
                        if (approximate) "Distance*" else "Distance",
                        (if (approximate) "~" else "") + formatDistance(distanceM),
                    )
                }
                // Distance comes from integrating GPS velocity. When the receiver stops
                // reporting velocity — degraded accuracy, indoors, poor sky view — those
                // stretches contribute nothing and the total reads low. Say so rather than
                // presenting a confident wrong number.
                if (approximate) {
                    Text(
                        text = "* $missingPct% of fixes had no GPS velocity, so distance is " +
                            "under-reported. Positions and RF data are unaffected.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = if (recording) onStop else onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = if (recording) {
                    ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (recording) "Stop and save" else "Start recording")
            }

            lastFile?.let {
                Text(
                    text = "Saved: $it",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "Android/data/com.nhnengineering.rftest/files/sessions/",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * GPS state.
 *
 * Accuracy is shown alongside the coordinates rather than tucked away, because a fix with 40 m of
 * error is a different measurement from one with 3 m, and a track built from the former will not
 * survive scrutiny in a report. The provider is shown for the same reason — a fused fix indoors
 * may be derived from Wi-Fi, which would make a Wi-Fi survey partly circular.
 */
@Composable
fun GpsCard(fix: GeoPoint?, providersEnabled: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Position", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 2.dp))

            when {
                !providersEnabled -> Text(
                    "Location Services is switched off at the OS level. Samples will log with no " +
                        "position — permission alone is not enough.",
                    style = MaterialTheme.typography.bodySmall,
                )

                fix == null -> Text(
                    "Waiting for a fix. Indoors this can take a while, or never arrive on GPS.",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    KeyValue("Latitude", String.format(Locale.US, "%.6f", fix.latitudeDeg))
                    KeyValue("Longitude", String.format(Locale.US, "%.6f", fix.longitudeDeg))
                    KeyValue("Accuracy", fix.accuracyM?.let { "±${it.roundToInt()} m" } ?: "—")
                    KeyValue("Altitude", fix.altitudeM?.let { "${it.roundToInt()} m" } ?: "—")
                    KeyValue(
                        "Speed",
                        fix.speedMps?.let { String.format(Locale.US, "%.1f m/s", it) } ?: "—",
                    )
                    KeyValue("Provider", fix.provider)
                }
            }
        }
    }
}

/** Shown in place of the Wi-Fi cards when the radio has nothing to report. */
@Composable
fun NoWifiCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No Wi-Fi data", style = MaterialTheme.typography.titleMedium)
            Text(
                "Not associated and no scan results. Recording still works — the session will " +
                    "log position with empty Wi-Fi columns.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

private fun formatDistance(m: Double): String = if (m < 1000) {
    "${m.roundToInt()} m"
} else {
    String.format(Locale.US, "%.2f km", m / 1000)
}
