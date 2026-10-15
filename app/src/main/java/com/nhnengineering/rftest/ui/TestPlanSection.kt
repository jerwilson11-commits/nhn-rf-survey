package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.automation.TestPlan
import com.nhnengineering.rftest.billing.EntitlementRepository
import com.nhnengineering.rftest.billing.UpgradePrompt
import com.nhnengineering.rftest.service.RecordingState

/**
 * Scripted test plan (E1) controls. Self-contained — reads and writes [RecordingState] directly, the
 * same UI-sets / service-reacts pattern the throughput cadence uses, so it needs no new plumbing
 * through [SetupPanel]. Field-gated.
 *
 * Idle: configure N points, a dwell per point, optional throughput, and a repeat count, then start.
 * Running: shows which point is in progress and offers Stop. A plan runs only while recording — the
 * service drives the dwells, bursts and point labels and writes them into the session.
 */
@Composable
fun TestPlanSection(recording: Boolean) {
    val tier by EntitlementRepository.tier.collectAsState()
    val field = tier.grantsField
    val progress by RecordingState.activePlanProgress.collectAsState()

    var pointsText by remember { mutableStateOf("5") }
    var dwellText by remember { mutableStateOf("20") }
    var repeatText by remember { mutableStateOf("1") }
    var throughput by remember { mutableStateOf(true) }

    HorizontalDivider()
    Text("Test plan (automation)", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)

    val running = progress != null
    if (running) {
        val p = progress!!
        Text(
            "Running ${p.planName}: step ${p.index} of ${p.total}" +
                (p.label?.let { " — $it" } ?: "") + "  (${p.kind.name.lowercase()})",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { RecordingState.planRunRequested.value = false },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop plan") }
        return
    }

    Text(
        "Runs a hands-free sequence while recording: hold at each point for the dwell, optionally " +
            "run a throughput burst, then advance. Each point's samples are labelled, so they land " +
            "in the report's per-area section.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = pointsText, onValueChange = { pointsText = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Points") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = dwellText, onValueChange = { dwellText = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Dwell s") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = repeatText, onValueChange = { repeatText = it.filter { c -> c.isDigit() }.take(2) },
            label = { Text("Repeat") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = throughput, onCheckedChange = { throughput = it })
        Text("Throughput burst at each point", style = MaterialTheme.typography.bodyMedium)
    }

    val points = pointsText.toIntOrNull() ?: 0
    val dwell = dwellText.toIntOrNull() ?: 0
    val repeat = repeatText.toIntOrNull() ?: 1
    val plan = TestPlan.points(points.coerceIn(0, 999), dwell.coerceIn(0, 999), throughput, repeat.coerceIn(1, 99))
    val ready = field && recording && plan.isValid

    if (field && !recording) {
        Text(
            "Start recording first, then run the plan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    val estMin = plan.estimatedSeconds() / 60.0

    Button(
        onClick = {
            if (!field) { UpgradePrompt.open(); return@Button }
            RecordingState.testPlan.value = plan
            RecordingState.planRunRequested.value = true
        },
        enabled = !field || ready,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                !field -> "Run test plan (Field)"
                plan.isValid -> "Run plan · ${plan.stepCount} steps · ~%.0f min".format(estMin)
                else -> "Set points and dwell"
            },
        )
    }
}
