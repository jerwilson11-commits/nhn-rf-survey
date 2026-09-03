package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.model.GeoPoint
import java.util.Locale
import kotlin.math.roundToInt

/**
 * GPS state.
 *
 * Accuracy is shown alongside the coordinates rather than tucked away, because a fix with 40 m of
 * error is a different measurement from one with 3 m, and a track built from the former will not
 * survive scrutiny in a report. The provider is shown for the same reason — a fused fix indoors
 * may be derived from Wi-Fi, which would make a Wi-Fi survey partly circular.
 */
@Composable
fun GpsCard(fix: GeoPoint?, providersEnabled: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Position", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 2.dp))

            when {
                !providersEnabled -> Text(
                    "Location Services is switched off at the OS level. Samples will log with no " +
                        "position — permission alone is not enough.",
                    style = MaterialTheme.typography.bodySmall,
                )

                fix == null -> Text(
                    "Waiting for a fix. Indoors this can take a while, or never arrive on GPS.",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    KeyValue("Latitude", String.format(Locale.US, "%.6f", fix.latitudeDeg))
                    KeyValue("Longitude", String.format(Locale.US, "%.6f", fix.longitudeDeg))
                    KeyValue("Accuracy", fix.accuracyM?.let { "±${it.roundToInt()} m" } ?: "—")
                    KeyValue("Altitude", fix.altitudeM?.let { "${it.roundToInt()} m" } ?: "—")
                    KeyValue(
                        "Speed",
                        fix.speedMps?.let { String.format(Locale.US, "%.1f m/s", it) } ?: "—",
                    )
                    KeyValue("Provider", fix.provider)
                }
            }
        }
    }
}

/** Shown in place of the Wi-Fi cards when the radio has nothing to report. */
@Composable
fun NoWifiCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No Wi-Fi data", style = MaterialTheme.typography.titleMedium)
            Text(
                "Not associated and no scan results. Recording still works — the session will " +
                    "log position with empty Wi-Fi columns.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
