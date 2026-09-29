package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.automation.AutomationStepResult

/** Everything [AutomationCard] needs, grouped the way [BandLockUi] groups its own control's state. */
data class AutomationConfig(
    val pingEnabled: Boolean = true,
    val pingHost: String = "8.8.8.8",
    val httpEnabled: Boolean = true,
    // Android's own connectivity-check endpoint: a plain 204 with no body, meant for exactly this.
    val httpUrl: String = "https://connectivitycheck.gstatic.com/generate_204",
    val throughputEnabled: Boolean = false,
    val ftpEnabled: Boolean = false,
    val ftpHost: String = "",
    val loop: Boolean = false,
    val intervalSeconds: Int = 30,
) {
    val hasAnyStep: Boolean get() = pingEnabled || httpEnabled || throughputEnabled || ftpEnabled

    /**
     * Whether [hasAnyStep] AND every enabled step is actually usable. Without this, an FTP step
     * enabled with an empty host silently resolves to loopback and reports a confusing
     * "connection refused" instead of the real problem -- an unconfigured field, caught before the
     * run starts rather than reported from three network calls deep into it.
     */
    val isRunnable: Boolean get() = hasAnyStep && (!ftpEnabled || ftpHost.isNotBlank())
}

/**
 * A script of network probes an operator assembles from checkboxes rather than code: ping, an HTTP
 * GET, the throughput test already configured above (Custom or NDT7, whichever the Throughput card
 * is set to), and an FTP upload. Run once or left looping while troubleshooting.
 *
 * Results are shown here, most recent pass first, and are not written into the session -- see
 * [com.nhnengineering.rftest.automation.AutomationRunner]'s doc for why that integration is
 * deliberately not part of this first version.
 */
@Composable
fun AutomationCard(
    running: Boolean,
    config: AutomationConfig,
    onConfigChange: (AutomationConfig) -> Unit,
    results: List<AutomationStepResult>,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Automation script", style = MaterialTheme.typography.titleMedium)
            Text(
                "Runs the steps below in order and reports every one, even if an earlier step " +
                    "failed -- the question a script answers is which thing is broken, not just " +
                    "whether something is.",
                style = MaterialTheme.typography.bodySmall,
            )

            StepRow(
                checked = config.pingEnabled,
                onCheckedChange = { onConfigChange(config.copy(pingEnabled = it)) },
                label = "Ping",
                enabled = !running,
            ) {
                OutlinedTextField(
                    value = config.pingHost,
                    onValueChange = { onConfigChange(config.copy(pingHost = it)) },
                    label = { Text("Host") },
                    singleLine = true,
                    enabled = !running && config.pingEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            StepRow(
                checked = config.httpEnabled,
                onCheckedChange = { onConfigChange(config.copy(httpEnabled = it)) },
                label = "HTTP GET",
                enabled = !running,
            ) {
                OutlinedTextField(
                    value = config.httpUrl,
                    onValueChange = { onConfigChange(config.copy(httpUrl = it)) },
                    label = { Text("URL") },
                    singleLine = true,
                    enabled = !running && config.httpEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            StepRow(
                checked = config.throughputEnabled,
                onCheckedChange = { onConfigChange(config.copy(throughputEnabled = it)) },
                label = "Throughput (download + upload)",
                enabled = !running,
            ) {
                Text(
                    "Uses the server configured in the Throughput card above, under Custom / LAN " +
                        "server -- regardless of which mode that card's own \"Run speed test\" " +
                        "button is currently set to.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            StepRow(
                checked = config.ftpEnabled,
                onCheckedChange = { onConfigChange(config.copy(ftpEnabled = it)) },
                label = "FTP upload",
                enabled = !running,
            ) {
                OutlinedTextField(
                    value = config.ftpHost,
                    onValueChange = { onConfigChange(config.copy(ftpHost = it)) },
                    label = { Text("FTP server (host[:port])") },
                    singleLine = true,
                    enabled = !running && config.ftpEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Anonymous login, 2 MB of random data. Point this at a server you control -- " +
                        "an anonymous-write FTP endpoint is not something to assume exists.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (config.ftpHost.isBlank()) {
                    Text(
                        "Run is disabled until a server is entered here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC62828),
                    )
                }
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Loop", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = config.loop,
                    onCheckedChange = { onConfigChange(config.copy(loop = it)) },
                    enabled = !running,
                )
            }
            if (config.loop) {
                OutlinedTextField(
                    value = config.intervalSeconds.toString(),
                    onValueChange = { s -> s.toIntOrNull()?.let { onConfigChange(config.copy(intervalSeconds = it)) } },
                    label = { Text("Interval (seconds)") },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRun,
                    enabled = !running && config.isRunnable,
                    modifier = Modifier.weight(1f),
                ) { Text(if (running) "Running…" else "Run") }
                OutlinedButton(
                    onClick = onStop,
                    enabled = running,
                    modifier = Modifier.weight(1f),
                ) { Text("Stop") }
            }

            if (results.isNotEmpty()) {
                HorizontalDivider()
                Text("Last pass", style = MaterialTheme.typography.titleSmall)
                results.forEach { r ->
                    // A label and a Row.SpaceBetween don't mix once the label is a full URL: the
                    // two ends collide instead of wrapping. Stacked, both can wrap on their own.
                    Column {
                        Text(r.label, style = MaterialTheme.typography.bodySmall)
                        Text(
                            r.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (r.success) Color(0xFF2E7D32) else Color(0xFFC62828),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    enabled: Boolean,
    detail: @Composable () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        if (checked) {
            Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                detail()
            }
        }
    }
}
