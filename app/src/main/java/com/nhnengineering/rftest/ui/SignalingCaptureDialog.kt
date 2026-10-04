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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.cellular.BandMapping
import com.nhnengineering.rftest.modem.ModemNrStream
import com.nhnengineering.rftest.modem.Nas5gsOtaParser
import com.nhnengineering.rftest.modem.NrRrcTdd
import com.nhnengineering.rftest.modem.ProCapability
import com.nhnengineering.rftest.modem.ProModem
import com.nhnengineering.rftest.modem.ProModemCapability
import com.nhnengineering.rftest.modem.RrcOtaParser
import com.nhnengineering.rftest.profile.Sib1Parser
import com.nhnengineering.rftest.profile.TddProfile
import kotlinx.coroutines.delay

private const val POLL_INTERVAL_MS = 1_000L

/**
 * NAS-5GS / RRC OTA signaling capture, live in the app -- the repeatable version of the one-off
 * manual investigation (`docs/modem-diag-access.md`, "The runtime decision: the phone registers
 * on n41 SA, then leaves voluntarily") that used to mean staging a DCI helper by hand over `adb
 * shell` and piping hex through an external Python decoder. Root/Qualcomm-gated the same way band
 * lock and technology lock are -- see [com.nhnengineering.rftest.modem.ModemChipset] for why a
 * non-Qualcomm phone gets an accurate reason rather than a silent failure here too, the same as
 * everywhere else this app touches the modem directly.
 *
 * Deliberately a dialog, not a new bottom-nav tab: this is a power-user diagnostic action, not a
 * measurement surface most users will ever open, the same category as [Sib1PasteDialog].
 */
@Composable
internal fun SignalingCaptureDialog(
    capturing: Boolean,
    fetchEvents: () -> List<ModemNrStream.SignalingEvent>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExport: (List<ModemNrStream.SignalingEvent>) -> Unit,
    onSaveProfile: (TddProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var events by remember { mutableStateOf(emptyList<ModemNrStream.SignalingEvent>()) }

    LaunchedEffect(capturing) {
        while (capturing) {
            events = fetchEvents()
            delay(POLL_INTERVAL_MS)
        }
    }

    // Decode the TDD config out of any captured SIB1 / RRCReconfiguration (libnrrrc.so). Null when
    // there is no native lib, no such PDU, or the cell is FDD -- all handled as "nothing to show".
    val decodedTdd = remember(events) { NrRrcTdd.bestFromEvents(events) }

    // Can this device actually run live capture (root + Qualcomm)? Resolved once, so a non-root or
    // Exynos colleague gets a clear reason + the no-root alternative instead of an endless "waiting".
    var cap by remember { mutableStateOf<ProCapability?>(null) }
    LaunchedEffect(Unit) { cap = ProModem.capability() }
    val captureSupported = cap == null || cap?.state == ProModemCapability.CAPABLE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signaling capture") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text(
                        "Captures NAS-5GS (5GMM registration/deregistration) and NR RRC OTA " +
                            "messages while it runs -- for diagnosing why the radio leaves a cell " +
                            "or technology it should be able to hold. Needs root and a Qualcomm " +
                            "modem, the same as band lock and technology lock.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Device-specific reason + the no-root alternative, so a non-root/Exynos colleague
                // isn't left waiting at "waiting for the first event" with no explanation.
                when (cap?.state) {
                    ProModemCapability.NEEDS_ROOT -> item {
                        Text(
                            "Live capture needs root access, which isn't enabled on this device. " +
                                "No root? Use the import instead: Config tab → New profile → " +
                                "“Paste a SIB1 decode” also accepts a raw capture (hex) and decodes " +
                                "it on-device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    ProModemCapability.WRONG_CHIPSET -> item {
                        Text(
                            "Live capture needs a Qualcomm modem — this device is ${cap?.vendorLabel}, " +
                                "so it can't run here. Use the no-root import: Config tab → New " +
                                "profile → “Paste a SIB1 decode” accepts a raw capture (hex).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> Unit
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart, enabled = !capturing && captureSupported) { Text("Start") }
                        OutlinedButton(onClick = onStop, enabled = capturing) { Text("Stop") }
                        OutlinedButton(
                            onClick = { onExport(events) },
                            enabled = events.isNotEmpty(),
                        ) { Text("Export") }
                    }
                }
                item { HorizontalDivider() }
                decodedTdd?.let { d ->
                    item {
                        Text(
                            "TDD config decoded from capture",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    items(sib1FoundLines(d.profile)) { (k, v) -> KvRow(k, v) }
                    if (d.cellInfo.isNotEmpty()) {
                        item {
                            Text(
                                "Cell info",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        items(d.cellInfo) { (k, v) -> KvRow(k, v) }
                    }
                    item {
                        Button(onClick = {
                            val profile = blankProfile(null, d.profile.mcc, d.profile.mnc, d.profile.band)
                                .copy(source = "Signaling capture")
                                .withSib1(d.profile, System.currentTimeMillis())
                            onSaveProfile(profile)
                        }) { Text("Save as profile") }
                    }
                    item { HorizontalDivider() }
                }
                if (events.isEmpty()) {
                    item {
                        Text(
                            if (capturing) "Capturing… waiting for the first event." else "No events captured yet.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    items(events.asReversed()) { e -> SignalingEventRow(e) }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        },
        modifier = Modifier.heightIn(max = 560.dp),
    )
}

@Composable
private fun KvRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            k,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            v,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun SignalingEventRow(e: ModemNrStream.SignalingEvent) {
    val summary = when {
        e.nas != null -> {
            val dir = when (e.nas.direction) {
                Nas5gsOtaParser.Direction.INCOMING -> "↓"
                Nas5gsOtaParser.Direction.OUTGOING -> "↑"
                null -> "?"
            }
            val label = when {
                e.nas.securityProtected == true -> "security protected"
                e.nas.messageTypeName != null -> e.nas.messageTypeName
                else -> "unrecognised (0x%02x)".format(e.nas.messageType ?: -1)
            }
            "$dir NAS: $label"
        }
        e.rrc != null -> {
            val pdu = e.rrc.pduType?.label ?: "unrecognised PDU"
            "RRC: pci=${e.rrc.pci ?: "—"} arfcn=${e.rrc.nrArfcn ?: "—"} $pdu"
        }
        else -> "(empty)"
    }
    Text(
        text = "[%6d ms] %s".format(e.atElapsedMs, summary),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}
