package com.nhnengineering.rftest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nhnengineering.rftest.model.Verdict
import com.nhnengineering.rftest.spot.SpotResult
import java.util.Locale

/** Severity colour, reusing the coverage scale so the verdict and the map never disagree. */
private fun severityColor(s: Verdict.Severity): Color = when (s) {
    Verdict.Severity.GOOD -> Color(0xFF2E7D32)
    Verdict.Severity.FAIR -> Color(0xFFF9A825)
    Verdict.Severity.POOR -> Color(0xFFC62828)
    Verdict.Severity.UNKNOWN -> Color.Gray
}

/**
 * The plain-language answer, directly under the number.
 *
 * Placed here rather than in a separate card because the two are one statement: the number is the
 * evidence and the verdict is the finding, and separating them invites reading either alone.
 */
@Composable
fun VerdictLine(verdict: Verdict) {
    val color = severityColor(verdict.severity)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(verdict.headline, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color)
            if (verdict.interferenceLimited) {
                Text(
                    "  · interference",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(verdict.detail, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

/**
 * Spot check: the answer for one place, without starting a session.
 *
 * Most uses of a tool like this are not surveys — they are somebody standing somewhere asking
 * whether it is all right here. Making that require a recording, a walk of zero distance and a
 * report about a single point would be a workflow nobody would use twice.
 */
@Composable
fun SpotCheckCard(
    running: Boolean,
    progress: Float,
    result: SpotResult?,
    onRun: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (running) {
            Text("Checking this spot — hold still", fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        } else if (result == null) {
            Button(onClick = onRun, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("SPOT CHECK  ·  10s", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Measures where you are standing and gives a plain answer. No session, no file.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            VerdictLine(result.verdict)

            Text(
                text = buildString {
                    append(result.meanDbm?.let { "$it dBm mean" } ?: "no level")
                    result.meanSinrDb?.let { append("   ·   SINR $it dB") }
                    result.spread?.let { append("   ·   ${it} dB spread") }
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            Text(
                text = listOfNotNull(
                    result.technology, result.operator, result.band,
                    result.pci?.let { "PCI $it" },
                    result.channel?.let { "ch $it" },
                    "${result.neighboursSeen} neighbours",
                ).joinToString("  ·  "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = String.format(
                    Locale.US, "%d samples over %.0f s",
                    result.samples, result.durationMs / 1000.0,
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Stability is stated rather than buried: a mean over a reading that moved 13 dB is
            // not a measurement of a place, and whoever is shown this screenshot cannot tell that
            // from the number alone.
            if (result.unstable) {
                Text(
                    "Signal moved ${result.spread} dB during the check — this spot is not stable, " +
                        "so treat the average as indicative. Re-check standing still, or walk it.",
                    fontSize = 12.sp,
                    color = Color(0xFFEF6C00),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Check again")
                }
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Clear")
                }
            }
        }
    }
}
