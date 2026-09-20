package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.profile.Provenance
import com.nhnengineering.rftest.profile.Sib1Parser
import com.nhnengineering.rftest.profile.TddProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accepts a pasted SIB1 decode and shows what could be read out of it before anything is filled.
 *
 * The review step is the point. A parser that silently filled nine fields would be worse than
 * retyping them, because a transcription error you made yourself is one you might notice. So this
 * reports what it found, what it did not, and anything it refused to guess at, and the engineer
 * confirms it against the screen they read it off.
 */
@Composable
internal fun Sib1PasteDialog(
    onCancel: () -> Unit,
    onApply: (Sib1Parser.Result) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    val result = remember(text) { Sib1Parser.parse(text) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Paste a SIB1 decode") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "This app cannot reach SIB1 — it sits below Android's RIL boundary. A " +
                            "diagnostic tool on a rooted handset can, so paste what it printed and " +
                            "the configuration will be read out of it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { clipboard.getText()?.text?.let { text = it } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Paste from clipboard") }
                }
                item {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("SIB1 decode") },
                        singleLine = false,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 110.dp, max = 200.dp),
                    )
                }

                if (text.isNotBlank() && !result.looksLikeSib1) {
                    item {
                        Text(
                            result.notes.firstOrNull() ?: "Nothing recognisable in that text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF6C00),
                        )
                    }
                }

                if (result.looksLikeSib1) {
                    val lines = sib1FoundLines(result)
                    item { HorizontalDivider() }
                    item {
                        Text(
                            "Read ${lines.size} value" + if (lines.size == 1) "" else "s",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    items(lines) { line ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                line.first,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Text(
                                line.second,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                        }
                    }
                    if (result.missing.isNotEmpty()) {
                        item {
                            Text(
                                "Not in this paste: " + result.missing.joinToString(", ") {
                                    it.label.lowercase(Locale.US)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(result.conflicts) { c ->
                        Text(
                            c,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF6C00),
                        )
                    }
                    items(result.notes) { n ->
                        Text(
                            n,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(result) },
                enabled = result.anythingFound,
            ) { Text("Fill the form") }
        },
        dismissButton = { OutlinedButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** What the parse actually produced, in the order an engineer would check it off the screen. */
internal fun sib1FoundLines(r: Sib1Parser.Result): List<Pair<String, String>> = buildList {
    r.band?.let { add("Band" to it) }
    if (r.mcc != null || r.mnc != null) add("PLMN" to "${r.mcc ?: "?"}-${r.mnc ?: "?"}")
    r.pci?.let { add("PCI" to it.toString()) }
    r.scsKhz?.let { add("Subcarrier spacing" to "$it kHz") }
    r.carrierBandwidthRb?.let { add("Carrier bandwidth" to "$it RB") }
    r.tddPeriodicityMs?.let { add("TDD periodicity" to "$it ms") }
    r.derivedPattern?.let { add("Slot pattern (derived)" to it) }
    r.dlSlots?.let { add("DL slots / symbols" to "$it / " + (r.dlSymbols?.toString() ?: "—")) }
    r.ulSlots?.let { add("UL slots / symbols" to "$it / " + (r.ulSymbols?.toString() ?: "—")) }
    r.ssbPeriodicityMs?.let { add("SSB periodicity" to "$it ms") }
    r.ssbPositionsInBurst?.let { add("SSB positions in burst" to it) }
}

/**
 * Merges a parse into the profile being edited.
 *
 * Only fills what the parse actually produced, so a second paste covering a different part of the
 * message adds to the first rather than blanking it. Provenance becomes MEASURED because that is
 * what these values now are — and the source is prefilled only when empty, since a source the
 * engineer already wrote is better than one this code invented.
 */
internal fun TddProfile.withSib1(r: Sib1Parser.Result, now: Long): TddProfile {
    val stamp = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(now))
    val extras = buildList {
        r.carrierBandwidthRb?.let { add("carrierBandwidth $it") }
        r.pci?.let { add("PCI $it") }
    }
    return copy(
        band = r.band ?: band,
        mcc = r.mcc ?: mcc,
        mnc = r.mnc ?: mnc,
        tddPattern = r.derivedPattern ?: tddPattern,
        tddPeriodicityMs = r.tddPeriodicityMs ?: tddPeriodicityMs,
        dlSlots = r.dlSlots ?: dlSlots,
        dlSymbols = r.dlSymbols ?: dlSymbols,
        ulSlots = r.ulSlots ?: ulSlots,
        ulSymbols = r.ulSymbols ?: ulSymbols,
        ssbPeriodicityMs = r.ssbPeriodicityMs ?: ssbPeriodicityMs,
        ssbPositionsInBurst = r.ssbPositionsInBurst ?: ssbPositionsInBurst,
        scsKhz = r.scsKhz ?: scsKhz,
        provenance = Provenance.MEASURED,
        source = source.ifBlank { "SIB1 paste, $stamp" },
        note = note ?: extras.takeIf { it.isNotEmpty() }?.joinToString(", "),
    )
}
