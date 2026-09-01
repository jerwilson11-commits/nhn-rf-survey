package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.model.ThroughputSample
import java.util.Locale

/**
 * Speed test control and last result.
 *
 * The server field is exposed rather than buried in settings because on a DAS or Private 5G
 * acceptance job the correct server is usually one on the venue LAN — testing to the internet
 * measures the client's backhaul and ISP rather than the radio system under test. Making that a
 * one-line change in the field is the point.
 */
@Composable
fun SpeedTestCard(
    running: Boolean,
    stage: String?,
    liveMbps: Double?,
    result: ThroughputSample?,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onRun: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Throughput", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Server base URL") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Point this at a LAN server on site — testing to the internet measures backhaul, " +
                    "not the radio system.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (running) {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stage ?: "Running…", style = MaterialTheme.typography.titleMedium)
                    Text(
                        liveMbps?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Button(onClick = onRun, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "Running…" else "Run speed test")
            }

            result?.let { r ->
                HorizontalDivider()
                if (r.error != null) {
                    Text("Failed: ${r.error}", style = MaterialTheme.typography.bodyMedium)
                } else {
                    KeyValue("Download", r.downloadMbps.mbps())
                    KeyValue("Upload", r.uploadMbps.mbps())
                    KeyValue("Latency (median)", r.latencyMedianMs.ms())
                    KeyValue(
                        "Latency min / max",
                        if (r.latencyMinMs != null && r.latencyMaxMs != null) {
                            "${r.latencyMinMs.ms()} / ${r.latencyMaxMs.ms()}"
                        } else "—",
                    )
                    KeyValue("Jitter", r.jitterMs.ms())
                    KeyValue(
                        "Packet loss",
                        r.lossPct?.let { String.format(Locale.US, "%.1f %%", it) }
                            ?: "not measurable",
                    )
                    KeyValue("Server", r.server)
                    if (r.lossPct == null) {
                        Text(
                            "Packet loss needs ICMP, which this device or network did not permit. " +
                                "It is left blank rather than substituted with HTTP failures — " +
                                "those are a different measurement.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    "Recorded into the session row at the moment the test finished, so the result " +
                        "carries the position and RF conditions it ran under.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun Double?.mbps(): String =
    this?.let { String.format(Locale.US, "%.2f Mbps", it) } ?: "—"

private fun Double?.ms(): String =
    this?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"
