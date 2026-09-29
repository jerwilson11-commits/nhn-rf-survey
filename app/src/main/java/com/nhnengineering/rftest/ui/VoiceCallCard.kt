package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.voicecall.VoiceCallResult

/**
 * Call-state voice KPIs: places a real call and reports setup time, call duration, and a
 * possible-drop flag. See [com.nhnengineering.rftest.voicecall.VoiceCallTester] for exactly what
 * "setup time" does and does not mean on Android's 3-state call model.
 */
@Composable
fun VoiceCallCard(
    running: Boolean,
    stage: String?,
    number: String,
    onNumberChange: (String) -> Unit,
    result: VoiceCallResult?,
    onRun: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Voice call KPIs", style = MaterialTheme.typography.titleMedium)
            Text(
                "Places a real call and measures setup time and call duration. Defaults to this " +
                    "SIM's own voicemail number -- change it only to a number you control.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = number,
                onValueChange = onNumberChange,
                label = { Text("Number to call") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onRun,
                enabled = !running && number.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (running) stage ?: "Running…" else "Call and measure") }

            result?.let { r ->
                HorizontalDivider()
                if (!r.setupSucceeded) {
                    Text("Failed: ${r.error}", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        if (r.possibleDrop) "Possible drop" else "Connected",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (r.possibleDrop) Color(0xFFC62828) else Color(0xFF2E7D32),
                    )
                    KeyValue("Setup time", r.setupTimeMs.ms())
                    KeyValue("Call duration", r.callDurationMs.ms())
                    KeyValue(
                        "Ended automatically",
                        when (r.autoHangupSucceeded) {
                            true -> "yes"
                            false -> "no -- end it manually if it is still connected"
                            null -> "not needed, already ended"
                        },
                    )
                }
            }
        }
    }
}

private fun Long?.ms(): String = this?.let { "$it ms" } ?: "—"
