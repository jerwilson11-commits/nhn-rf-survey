package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.nhnengineering.rftest.videoqoe.VideoQoeRating
import com.nhnengineering.rftest.videoqoe.VideoQoeResult
import java.util.Locale

/**
 * Video Streaming QoE test: plays a real stream for a fixed window and reports launch time, load
 * time, mid-playback stalls, and whether the resolution ever stepped down -- the same shape
 * RantCell's own Video Streaming Test reports. See [com.nhnengineering.rftest.videoqoe.VideoQoeTester]
 * for the exact definitions used, since RantCell's own formula split isn't published.
 */
@Composable
fun VideoStreamingCard(
    running: Boolean,
    stage: String?,
    url: String,
    onUrlChange: (String) -> Unit,
    result: VideoQoeResult?,
    onRun: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Video streaming QoE", style = MaterialTheme.typography.titleMedium)
            Text(
                "Plays a real stream for 20 seconds and measures launch time, load time, " +
                    "rebuffering, and whether the resolution ever stepped down.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                label = { Text("Stream URL (HLS)") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = onRun, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) stage ?: "Running…" else "Run test")
            }

            result?.let { r ->
                HorizontalDivider()
                if (r.error != null) {
                    Text("Failed: ${r.error}", style = MaterialTheme.typography.bodyMedium)
                } else {
                    r.rating?.let {
                        Text(
                            it.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = it.color(),
                        )
                    }
                    KeyValue("Launch time", r.launchTimeMs.ms())
                    KeyValue("Load time", r.loadTimeMs.ms())
                    KeyValue("Stalls", "${r.stallCount} (${r.totalStallMs.ms()})")
                    KeyValue(
                        "Resolutions seen",
                        r.resolutions.takeIf { it.isNotEmpty() }?.joinToString(" → ") ?: "—",
                    )
                    KeyValue("Resolution dropped", if (r.resolutionDropped) "yes" else "no")
                    KeyValue(
                        "Score",
                        r.qualityScorePct?.let { String.format(Locale.US, "%.2f %%", it) } ?: "—",
                    )
                }
            }
        }
    }
}

private fun VideoQoeRating.color(): Color = when (this) {
    VideoQoeRating.GOOD -> Color(0xFF2E7D32)
    VideoQoeRating.FAIR -> Color(0xFFEF6C00)
    VideoQoeRating.POOR -> Color(0xFFC62828)
}

private fun Long?.ms(): String = this?.let { "$it ms" } ?: "—"
