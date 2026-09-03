package com.nhnengineering.rftest.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Everything chosen once per session, folded away for the rest of it.
 *
 * The explanatory text this app carries is a real asset — it is the same instinct that puts a
 * limitations page in the report, and no competitor does it. But an explanation is read once and
 * then occupies the screen forever. Collapsing is what lets the app be both **teachable on first
 * use and dense on the fiftieth**, which is the tension every field tool has to resolve somehow.
 *
 * Opens by default before a session and closes when recording starts.
 */
@Composable
fun SetupPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    recording: Boolean,
    sessionName: String,
    onSessionNameChange: (String) -> Unit,
    distanceM: Double,
    fixesWithVelocity: Long,
    fixesWithoutVelocity: Long,
    lastFile: String?,
    onArea: (String?) -> Unit,
    onFloor: (String?) -> Unit,
    walkThroughput: Boolean,
    onWalkThroughputChange: (Boolean) -> Unit,
    liveView: Boolean,
    onLiveViewChange: (Boolean) -> Unit,
    liveViewError: String?,
) {
    var showHelp by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (recording) "Session setup" else "Setup",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (expanded) "Hide ▲" else "Show ▼",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Distance lives here rather than on the measurement surface: it is a session total,
            // not a live reading, and its caveat needs more words than a tile can hold.
            if (recording) {
                val total = fixesWithVelocity + fixesWithoutVelocity
                val missingPct = if (total > 0) (fixesWithoutVelocity * 100 / total).toInt() else 0
                val approximate = missingPct >= 10
                Text(
                    text = (if (approximate) "~" else "") +
                        String.format(Locale.US, "%.0f m walked", distanceM) +
                        if (approximate) "  (approximate)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (approximate) {
                    Text(
                        "$missingPct% of fixes had no GPS velocity, so distance is under-reported. " +
                            "Positions and RF data are unaffected.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (!expanded) return@Column

            HorizontalDivider()

            OutlinedTextField(
                value = sessionName,
                onValueChange = onSessionNameChange,
                label = { Text("Site / venue / sector") },
                singleLine = true,
                enabled = !recording,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Labels", style = MaterialTheme.typography.titleSmall)
            LabelEntry(onArea = onArea, onFloor = onFloor)

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Throughput during walk", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = walkThroughput,
                    onCheckedChange = onWalkThroughputChange,
                    enabled = !recording,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Live view on laptop", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = liveView, onCheckedChange = onLiveViewChange)
            }
            if (liveView) {
                Text(
                    "Serving now — open http://localhost:8787 on the laptop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32),
                )
            }
            liveViewError?.let {
                Text(
                    "Live view failed to start: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFEF6C00),
                )
            }

            // The prose is kept, not deleted — it is what makes this app teachable — but it is one
            // tap away rather than permanently between the operator and the numbers.
            Text(
                text = if (showHelp) "What these do ▲" else "What these do ▼",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showHelp = !showHelp },
            )
            if (showHelp) {
                Text(
                    "Throughput: download then upload, with a 30-second idle gap — about one " +
                        "reading every 40 seconds, since the burst itself takes time. Each is " +
                        "logged with the position and RF conditions it ran under. Between bursts " +
                        "the radio is idle, so the rest of the survey is not measured under load. " +
                        "Uses mobile data, and cannot be changed once recording starts: a gap in " +
                        "the series is indistinguishable from a network failure.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Live view: serves a live map and readings over the USB cable. On the laptop " +
                        "run \"adb forward tcp:8787 tcp:8787\" then open http://localhost:8787. " +
                        "The phone listens on loopback only, so nothing is exposed to the venue " +
                        "network. If the laptop says adb is not recognised it is not on PATH — " +
                        "run it by full path, usually " +
                        "%LOCALAPPDATA%\\Android\\Sdk\\platform-tools\\adb.exe",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Labels: area and floor are written to every sample from the moment they are " +
                        "set until changed, and drive the per-area and per-floor sections of the " +
                        "report. Floor is stored exactly as typed, so M, LL, B2 and PH survive.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            lastFile?.let {
                HorizontalDivider()
                Text(
                    text = "Saved: $it",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
