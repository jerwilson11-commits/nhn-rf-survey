package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.model.CellularSample
import com.nhnengineering.rftest.model.Rat
import com.nhnengineering.rftest.model.RsrpBucket
import com.nhnengineering.rftest.model.SimState

/**
 * Live cellular readout.
 *
 * Layout mirrors the Wi-Fi card: serving-cell KPI large and colour-coded, identity and channel
 * detail below, neighbours last. The RSRP colour scale is cellular-specific — reusing the Wi-Fi
 * thresholds would paint a perfectly healthy DAS as a problem area.
 */
@Composable
fun CellularCard(sample: CellularSample?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

            if (sample == null || sample.simState == SimState.ABSENT) {
                Text("Cellular", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Text(
                    sample?.simState?.label?.replaceFirstChar { it.uppercase() } ?: "Not available",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "No SIM is installed, so there is no cellular service to measure. Wi-Fi " +
                        "recording is unaffected and the cellular columns in the CSV stay empty — " +
                        "which is the correct record of what happened, not missing data.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Cellular", style = MaterialTheme.typography.titleMedium)
                Text(
                    sample.rat.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (sample.rat) {
                        Rat.NR_SA -> Color(0xFF2E7D32)
                        Rat.NR_NSA -> Color(0xFF689F38)
                        Rat.LTE -> MaterialTheme.colorScheme.onSurface
                        else -> Color.Gray
                    },
                )
            }

            sample.operator?.let {
                Text(
                    it + (sample.mcc?.let { m -> "  ($m/${sample.mnc})" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Serving-cell coverage KPI, whichever radio is serving.
            val rsrp = sample.servingRsrpDbm
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = rsrp?.toString() ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = rsrpColor(rsrp),
                )
                Text(
                    text = if (sample.nr != null) "  dBm SS-RSRP" else "  dBm RSRP",
                    style = MaterialTheme.typography.titleMedium,
                    color = rsrpColor(rsrp),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            if (sample.permissionLimited) {
                Text(
                    "Phone-state permission not granted — NSA/SA cannot be distinguished and the " +
                        "RAT shown is inferred from which cells are visible. Grant it in Settings " +
                        "for a reliable reading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFEF6C00),
                )
            }

            sample.nr?.let { nr ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("NR", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                KeyValue("Band", nr.bandLabel ?: "—")
                nr.bandConflict?.let {
                    Text(
                        "⚠ Band mismatch: the modem reports this band but $it. One of the two " +
                            "fields is wrong and the handset does not say which — treat the band " +
                            "for this sample as unreliable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFEF6C00),
                    )
                }
                KeyValue("NR-ARFCN", nr.nrarfcn?.toString() ?: "—")
                KeyValue("Frequency", nr.dlFreqMhz?.let { "%.1f MHz".format(it) } ?: "—")
                KeyValue("PCI", nr.pci?.toString() ?: "—")
                KeyValue("NCI", nr.nci?.toString() ?: "—")
                KeyValue("TAC", nr.tac?.toString() ?: "—")
                KeyValue("SS-RSRP", nr.ssRsrpDbm?.let { "$it dBm" } ?: "—")
                KeyValue("SS-RSRQ", nr.ssRsrqDb?.let { "$it dB" } ?: "—")
                KeyValue("SS-SINR", nr.ssSinrDb?.let { "$it dB" } ?: "—")
            }

            sample.lte?.let { lte ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    if (sample.rat == Rat.NR_NSA) "LTE (NSA anchor)" else "LTE",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                KeyValue("Band", lte.bandLabel ?: "—")
                KeyValue("EARFCN", lte.earfcn?.toString() ?: "—")
                KeyValue("Frequency", lte.dlFreqMhz?.let { "%.1f MHz".format(it) } ?: "—")
                KeyValue("Bandwidth", lte.bandwidthKhz?.let { "${it / 1000} MHz" } ?: "—")
                KeyValue("PCI", lte.pci?.toString() ?: "—")
                KeyValue("eNB / sector", if (lte.enbId != null) "${lte.enbId} / ${lte.sectorId}" else "—")
                KeyValue("TAC", lte.tac?.toString() ?: "—")
                KeyValue("RSRP", lte.rsrpDbm?.let { "$it dBm" } ?: "—")
                KeyValue("RSRQ", lte.rsrqDb?.let { "$it dB" } ?: "—")
                KeyValue("SINR (RSSNR)", lte.rssnrDb?.let { "$it dB" } ?: "—")
                KeyValue("CQI", lte.cqi?.toString() ?: "—")
                // Timing advance is a genuine distance estimate — worth surfacing on a DAS walk,
                // where a suddenly large TA can indicate you are being served by a macro rather
                // than the in-building system.
                KeyValue(
                    "Timing advance",
                    lte.timingAdvance?.let { "$it  (~${(it * 78.125).toInt()} m)" } ?: "—",
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            KeyValue("NR state", sample.nrState.label)
            KeyValue("Status bar shows", sample.overrideNetworkType ?: "—")
            KeyValue("Roaming", if (sample.isRoaming) "yes" else "no")
            KeyValue(
                "Neighbours",
                sample.neighbors.size.toString() +
                    sample.neighbors.count { it.ageMs == 0L }.let { " ($it this report)" },
            )

            if (sample.neighbors.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                sample.neighbors.sortedByDescending { it.rsrpDbm ?: Int.MIN_VALUE }.take(8)
                    .forEach { n ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                buildString {
                                    append("${n.rat} PCI ${n.pci ?: "?"} ${n.band ?: ""} ")
                                    append("ch ${n.channel ?: "?"}")
                                    // Only shown once it is old enough to matter, so the common
                                    // case stays uncluttered but a retained entry is never
                                    // mistaken for a fresh measurement.
                                    if (n.ageMs > 2000) append("  ${n.ageMs / 1000}s ago")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                n.rsrpDbm?.let { "$it dBm" } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = rsrpColor(n.rsrpDbm),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
            }
        }
    }
}

/** Cellular RSRP scale. Deliberately not the Wi-Fi scale — see [RsrpBucket]. */
internal fun rsrpColor(rsrpDbm: Int?): Color =
    RsrpBucket.of(rsrpDbm)?.let { Color(it.argb) } ?: Color.Gray
