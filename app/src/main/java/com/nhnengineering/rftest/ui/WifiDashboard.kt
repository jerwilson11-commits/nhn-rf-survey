package com.nhnengineering.rftest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.location.LocationCollector
import com.nhnengineering.rftest.model.GeoPoint
import com.nhnengineering.rftest.model.RssiBucket
import com.nhnengineering.rftest.model.ThroughputSample
import com.nhnengineering.rftest.model.WifiNeighbor
import com.nhnengineering.rftest.model.WifiSample
import com.nhnengineering.rftest.service.RecordingService
import com.nhnengineering.rftest.service.RecordingState
import com.nhnengineering.rftest.speedtest.SpeedTestConfig
import com.nhnengineering.rftest.speedtest.SpeedTester
import com.nhnengineering.rftest.wifi.WifiCollector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SAMPLE_INTERVAL_MS = 1_000L

/**
 * Live KPI readout.
 *
 * Since Phase 6 this screen no longer owns the recording — [RecordingService] does, so a session
 * survives tab switches, backgrounding and screen lock. The consequence here is two data sources,
 * and it must use exactly one at a time:
 *
 *  - **Not recording:** its own collectors, alive only while this screen is composed.
 *  - **Recording:** values published by the service. The local collectors are stopped, because two
 *    WifiCollectors would mean two network callbacks and two scan requesters competing for the
 *    same throttled OS scan budget.
 */
@Composable
fun WifiDashboard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recording by RecordingState.active.collectAsState()
    val serviceWifi by RecordingState.wifi.collectAsState()
    val serviceFix by RecordingState.fix.collectAsState()
    val rowCount by RecordingState.rowCount.collectAsState()
    val elapsedMs by RecordingState.elapsedMs.collectAsState()
    val distanceM by RecordingState.distanceM.collectAsState()
    val withVel by RecordingState.fixesWithVelocity.collectAsState()
    val withoutVel by RecordingState.fixesWithoutVelocity.collectAsState()
    val lastFile by RecordingState.lastSavedFile.collectAsState()
    val breaches by RecordingState.breaches.collectAsState()
    val thresholds by RecordingState.thresholds.collectAsState()
    val serviceError by RecordingState.error.collectAsState()

    val collector = remember { WifiCollector(context) }
    val locations = remember { LocationCollector(context) }
    var localWifi by remember { mutableStateOf<WifiSample?>(null) }
    var localFix by remember { mutableStateOf<GeoPoint?>(null) }

    var sessionName by remember { mutableStateOf("") }

    var speedServer by remember { mutableStateOf(SpeedTestConfig().downloadUrl) }
    var speedRunning by remember { mutableStateOf(false) }
    var speedStage by remember { mutableStateOf<String?>(null) }
    var speedLiveMbps by remember { mutableStateOf<Double?>(null) }
    var speedResult by remember { mutableStateOf<ThroughputSample?>(null) }

    // Keyed on `recording`, so the handover between local collectors and the service happens
    // automatically in both directions.
    DisposableEffect(recording) {
        if (!recording) {
            collector.start()
            locations.start()
        }
        onDispose {
            if (!recording) {
                collector.stop()
                locations.stop()
            }
        }
    }

    LaunchedEffect(recording) {
        if (recording) return@LaunchedEffect
        while (true) {
            collector.requestScanRefresh()
            localWifi = collector.snapshot()
            localFix = locations.snapshot()
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    val wifi = if (recording) serviceWifi else localWifi
    val fix = if (recording) serviceFix else localFix

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            RecorderPanel(
                recording = recording,
                sessionName = sessionName,
                onSessionNameChange = { sessionName = it },
                rowCount = rowCount,
                elapsedMs = elapsedMs,
                distanceM = distanceM,
                fixesWithVelocity = withVel,
                fixesWithoutVelocity = withoutVel,
                lastFile = lastFile,
                onStart = { RecordingService.start(context, sessionName) },
                onStop = { RecordingService.stop(context) },
            )
        }
        serviceError?.let { err ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Recording error: $err",
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (breaches.isNotEmpty()) {
            item { AlarmCard(breaches.map { it.label }) }
        }
        item {
            ThresholdsCard(
                thresholds = thresholds,
                onChange = { RecordingState.thresholds.value = it },
            )
        }
        item {
            SpeedTestCard(
                running = speedRunning,
                stage = speedStage,
                liveMbps = speedLiveMbps,
                result = speedResult,
                serverUrl = speedServer,
                onServerUrlChange = { speedServer = it },
                onRun = {
                    scope.launch {
                        speedRunning = true
                        speedResult = null
                        speedLiveMbps = null
                        val base = speedServer.trim()
                        val host = runCatching { java.net.URL(base).host }.getOrNull()
                            ?: SpeedTestConfig().pingHost
                        val cfg = SpeedTestConfig(
                            downloadUrl = base,
                            uploadUrl = base.substringBefore("/__down") + "/__up",
                            latencyUrl = base + "0",
                            pingHost = host,
                        )
                        val r = SpeedTester(cfg).runAll { st, mbps ->
                            speedStage = st
                            speedLiveMbps = mbps
                        }
                        speedResult = r
                        // Handed to the service so the file keeps a single writer.
                        RecordingState.pendingThroughput.value = r
                        speedRunning = false
                        speedStage = null
                        speedLiveMbps = null
                    }
                },
            )
        }
        item { GpsCard(fix, providersEnabled = locations.isAnyProviderEnabled()) }

        if (wifi == null) {
            item { NoWifiCard() }
        } else {
            item { ServingApCard(wifi) }
            item { InterferenceCard(wifi) }
            item {
                Text(
                    text = "Neighbours (${wifi.neighbors.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(
                items = wifi.neighbors.sortedByDescending { it.rssiDbm },
                key = { it.bssid },
            ) { neighbor ->
                NeighborRow(neighbor, isServing = neighbor.bssid == wifi.bssid)
            }
        }
    }
}

@Composable
private fun AlarmCard(labels: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFC62828))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "⚠ THRESHOLD BREACH",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            labels.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
    }
}

@Composable
private fun ServingApCard(sample: WifiSample) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = sample.ssid ?: "(not associated)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = sample.bssid ?: "—",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = sample.rssiDbm?.toString() ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = rssiColor(sample.rssiDbm),
                )
                Text(
                    text = "  dBm",
                    style = MaterialTheme.typography.titleMedium,
                    color = rssiColor(sample.rssiDbm),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            KeyValue("Band", sample.band.label)
            KeyValue("Channel", sample.channel?.toString() ?: "—")
            KeyValue("Frequency", sample.frequencyMhz?.let { "$it MHz" } ?: "—")
            KeyValue("Width", sample.channelWidthMhz?.let { "$it MHz" } ?: "—")
            KeyValue("Standard", sample.standard.label)
            KeyValue("Security", sample.security.label)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            KeyValue("Tx link rate", sample.txLinkMbps?.let { "$it Mbps" } ?: "—")
            KeyValue("Rx link rate", sample.rxLinkMbps?.let { "$it Mbps" } ?: "—")
            KeyValue("Max supported Tx", sample.maxSupportedTxMbps?.let { "$it Mbps" } ?: "—")
        }
    }
}

@Composable
private fun InterferenceCard(sample: WifiSample) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Channel utilisation", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Other APs above −85 dBm. Overlap is computed from centre frequency " +
                    "and channel width, not channel number — so wide-channel overlap counts.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            KeyValue("Co-channel", sample.coChannelCount.toString())
            KeyValue("Adjacent / overlapping", sample.adjacentChannelCount.toString())
            KeyValue(
                "Neighbour scan age",
                sample.neighborScanAgeMs?.let { "${it / 1000} s" } ?: "—",
            )
        }
    }
}

@Composable
private fun NeighborRow(neighbor: WifiNeighbor, isServing: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isServing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = neighbor.ssid ?: "(hidden)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isServing) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = neighbor.bssid,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${neighbor.rssiDbm} dBm",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = rssiColor(neighbor.rssiDbm),
            )
            Text(
                text = buildString {
                    append("ch ${neighbor.channel ?: "?"}")
                    append(" · ${neighbor.band.label}")
                    neighbor.channelWidthMhz?.let { append(" · $it MHz") }
                    if (neighbor.ageMs > 15_000) append(" · ${neighbor.ageMs / 1000}s ago")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f),
        )
    }
}

/** Single source for RSSI colour, shared with the exporters via [RssiBucket]. */
internal fun rssiColor(rssiDbm: Int?): Color =
    RssiBucket.of(rssiDbm)?.let { Color(it.argb) } ?: Color.Gray
