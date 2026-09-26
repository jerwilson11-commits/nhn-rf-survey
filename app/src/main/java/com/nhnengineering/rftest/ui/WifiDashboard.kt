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
import androidx.compose.runtime.mutableFloatStateOf
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
import com.nhnengineering.rftest.cellular.BandLock
import com.nhnengineering.rftest.cellular.BandLockController
import com.nhnengineering.rftest.cellular.CellularCollector
import com.nhnengineering.rftest.cellular.TechnologyLock
import com.nhnengineering.rftest.cellular.TechnologyLockController
import com.nhnengineering.rftest.location.LocationCollector
import com.nhnengineering.rftest.model.CellularSample
import com.nhnengineering.rftest.model.VerdictStabiliser
import com.nhnengineering.rftest.model.GeoPoint
import com.nhnengineering.rftest.model.RssiBucket
import com.nhnengineering.rftest.model.ThroughputSample
import com.nhnengineering.rftest.model.WifiNeighbor
import com.nhnengineering.rftest.model.WifiSample
import com.nhnengineering.rftest.service.RecordingService
import com.nhnengineering.rftest.model.Verdict
import com.nhnengineering.rftest.service.RecordingState
import com.nhnengineering.rftest.speedtest.SpeedTestConfig
import com.nhnengineering.rftest.speedtest.SpeedTester
import com.nhnengineering.rftest.wifi.WifiCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ten seconds at the sampling interval — long enough to average out jitter, short enough to
 *  stand still for. */
private const val SPOT_CHECK_SAMPLES = 10

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
    val serviceCell by RecordingState.cellular.collectAsState()
    val rowCount by RecordingState.rowCount.collectAsState()
    val elapsedMs by RecordingState.elapsedMs.collectAsState()
    val distanceM by RecordingState.distanceM.collectAsState()
    val withVel by RecordingState.fixesWithVelocity.collectAsState()
    val withoutVel by RecordingState.fixesWithoutVelocity.collectAsState()
    val lastFile by RecordingState.lastSavedFile.collectAsState()
    val breaches by RecordingState.breaches.collectAsState()
    val areaLabel by RecordingState.areaLabel.collectAsState()
    val floor by RecordingState.floor.collectAsState()
    val bandLock by RecordingState.bandLock.collectAsState()
    // Collected, not read as .value inside composition: the pre-walk card must clear its block the
    // moment a floorplan is chosen, and a raw .value read does not recompose when it changes.
    val indoorPosition by RecordingState.indoorPosition.collectAsState()
    val ratLock by RecordingState.ratLock.collectAsState()
    val walkThroughput by RecordingState.walkThroughputEnabled.collectAsState()
    val liveView by RecordingState.liveViewEnabled.collectAsState()
    val liveViewError by RecordingState.liveServerError.collectAsState()
    val lastThroughput by RecordingState.lastThroughput.collectAsState()
    val throughputBusy by RecordingState.throughputBusy.collectAsState()
    val thresholds by RecordingState.thresholds.collectAsState()
    val serviceError by RecordingState.error.collectAsState()

    val collector = remember { WifiCollector(context) }
    val locations = remember { LocationCollector(context) }
    val cellular = remember { CellularCollector(context) }
    val techLock = remember { TechnologyLockController(context) }
    var localWifi by remember { mutableStateOf<WifiSample?>(null) }
    var localFix by remember { mutableStateOf<GeoPoint?>(null) }
    var localCell by remember { mutableStateOf<CellularSample?>(null) }

    // Checked once per visit to this screen, off the composition thread: the check shells out
    // (qrtr-lookup, then the helper), and calling that from a bare `remember {}` initializer --
    // an earlier draft of this did exactly that -- would block first composition on a subprocess.
    // techLockChecking is a real third state, not a loading gloss on top of the other two:
    // showing "unavailable" before the check has actually run would be its own wrong answer.
    var techLockChecking by remember { mutableStateOf(true) }
    var techLockUnavailableReason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        techLockUnavailableReason = withContext(Dispatchers.IO) { techLock.unavailableReason }
        techLockChecking = false
    }
    var techLockBusy by remember { mutableStateOf(false) }
    var techLockStatus by remember { mutableStateOf<String?>(null) }
    val onTechnologyLock: (TechnologyLock.Technology?) -> Unit = { tech ->
        techLockBusy = true
        techLockStatus = null
        scope.launch {
            if (tech == null) {
                val outcome = withContext(Dispatchers.IO) { techLock.release() }
                techLockStatus = outcome.message
                if (outcome.applied) RecordingState.ratLock.value = null
            } else {
                val requested = withContext(Dispatchers.IO) { techLock.lock(tech) }
                techLockStatus = requested.message
                if (requested.applied) {
                    // The framework mechanism this replaced reverted within about a second; QMI
                    // has not shown that in testing, but the wait and the read afterwards are the
                    // only reason that claim can be trusted rather than assumed.
                    delay(4_000)
                    val verified = withContext(Dispatchers.IO) { techLock.verify() }
                    techLockStatus = verified.message
                    RecordingState.ratLock.value =
                        if (verified.applied) TechnologyLock.verifiedLabel(tech) else null
                }
                // If the request itself failed, nothing changed on the modem, so ratLock is left
                // exactly as it was rather than being overwritten with a guess either way.
            }
            techLockBusy = false
        }
    }

    // Band lock: same availability-check-off-thread pattern as the technology lock above.
    val bandLockController = remember { BandLockController(context) }
    var bandUi by remember { mutableStateOf(BandLockUi()) }
    LaunchedEffect(Unit) {
        val supported = withContext(Dispatchers.IO) { bandLockController.supported() }
        bandUi = supported.fold(
            onSuccess = {
                bandUi.copy(
                    checking = false,
                    unavailableReason = null,
                    supportedLte = it.lte,
                    supportedNrSa = it.nrSa,
                    pendingRestore = bandLockController.pendingRestore,
                )
            },
            onFailure = { bandUi.copy(checking = false, unavailableReason = it.message ?: "Unavailable.") },
        )
    }
    val onBandApply: (Set<Int>, Set<Int>) -> Unit = { lte, nr ->
        bandUi = bandUi.copy(busy = true, status = null)
        scope.launch {
            val requested = withContext(Dispatchers.IO) { bandLockController.lock(lte, nr) }
            bandUi = bandUi.copy(status = requested.message)
            if (requested.applied) {
                // Same reason as the technology lock: accepted is not held. Read it back.
                delay(4_000)
                val verified = withContext(Dispatchers.IO) { bandLockController.verify() }
                bandUi = bandUi.copy(status = verified.message)
                RecordingState.bandLock.value =
                    if (verified.applied) BandLock.verifiedLabel(lte, nr) else null
            }
            bandUi = bandUi.copy(busy = false, pendingRestore = bandLockController.pendingRestore)
        }
    }
    val onBandRelease: () -> Unit = {
        bandUi = bandUi.copy(busy = true, status = null)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { bandLockController.release() }
            bandUi = bandUi.copy(status = outcome.message)
            if (outcome.applied) RecordingState.bandLock.value = null
            bandUi = bandUi.copy(busy = false, pendingRestore = bandLockController.pendingRestore)
        }
    }

    var sessionName by remember { mutableStateOf("") }

    var speedServer by remember { mutableStateOf(SpeedTestConfig().downloadUrl) }
    // Setup starts open before a session and closed during one: the things in it are chosen once,
    // and every pixel it occupies mid-walk is a pixel not showing a measurement.
    var setupExpanded by remember { mutableStateOf(true) }

    // Spot check state. Lives here rather than in the service because it is not a recording: it
    // starts, samples, answers and is gone, and nothing about it should outlive the screen.
    var spotRunning by remember { mutableStateOf(false) }
    var spotProgress by remember { mutableFloatStateOf(0f) }
    var spotResult by remember { mutableStateOf<com.nhnengineering.rftest.spot.SpotResult?>(null) }

    // Steadies the displayed conclusion only. Nothing here touches what is written to the CSV --
    // the analysis depends on the real distribution, outliers included.
    val stabiliser = remember { com.nhnengineering.rftest.model.VerdictStabiliser() }
    // Published so the walk bursts use the same endpoint the operator typed here.
    LaunchedEffect(speedServer) { RecordingState.speedTestBaseUrl.value = speedServer }
    var speedRunning by remember { mutableStateOf(false) }
    var speedStage by remember { mutableStateOf<String?>(null) }
    var speedLiveMbps by remember { mutableStateOf<Double?>(null) }
    var speedResult by remember { mutableStateOf<ThroughputSample?>(null) }

    // Keyed on `recording`, so the handover between local collectors and the service happens
    // automatically in both directions.
    DisposableEffect(recording) {
        // Captured, not read again inside onDispose. The dispose runs *after* `recording` has
        // changed, so re-reading it there asks the wrong question: on the handover into
        // recording the guard saw true and skipped the stop, leaving these collectors running
        // alongside the service's for the whole session. Harmless while everything was polling;
        // not harmless once a collector owns a modem log subscription, where the service's copy
        // then could not stage its helper because this one still had it open.
        val startedHere = !recording
        if (startedHere) {
            collector.start()
            locations.start()
            cellular.start()
        }
        onDispose {
            if (startedHere) {
                collector.stop()
                locations.stop()
                cellular.stop()
            }
        }
    }

    LaunchedEffect(recording) {
        if (recording) return@LaunchedEffect
        while (true) {
            collector.requestScanRefresh()
            localWifi = collector.snapshot()
            localFix = locations.snapshot()
            localCell = cellular.snapshot()
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    LaunchedEffect(recording) { setupExpanded = !recording }

    val wifi = if (recording) serviceWifi else localWifi
    val fix = if (recording) serviceFix else localFix
    val cell = if (recording) serviceCell else localCell

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        // ---- Measurement surface, first and unscrolled --------------------
        //
        // Order is deliberate and reverses the original: what is being measured, then the controls
        // used while measuring, then setup. The first version put three cards of configuration
        // above the cellular reading, so the numbers the app exists to show sat below the fold on
        // every screenshot taken during development.
        item {
            StatusStrip(
                recording = recording,
                elapsedMs = elapsedMs,
                rowCount = rowCount,
                area = areaLabel,
                floor = floor,
            )
        }
        // Re-read on every recomposition rather than remembered: these are exactly the settings
        // an operator changes in the minute before setting off, and a cached answer would
        // describe the state the app started in.
        val preWalk = if (recording) {
            emptyList()
        } else {
            com.nhnengineering.rftest.model.PreWalkCheck.evaluate(
                preWalkInputs(
                    context = context,
                    hasGpsFix = fix != null,
                    floorplanSelected = indoorPosition != null,
                    simPresent = cell?.simState?.name?.contains("READY") == true,
                ),
            )
        }
        val preWalkHasFindings = preWalk.any {
            it.status != com.nhnengineering.rftest.model.PreWalkCheck.Status.OK
        }

        if (!recording) {
            item { NotRecordingBanner(onStart = { RecordingService.start(context, sessionName) }) }
            // A finding outranks the reading: it is the thing that makes the walk not worth
            // taking, and the operator needs it before they press START. A clean result does not
            // outrank anything, so it waits below the numbers as one line.
            if (preWalkHasFindings) item { PreWalkCard(preWalk) }
        }
        item { LevelBar(cell, wifi) }
        item { HeroKpi(cell, wifi) }
        if (!recording && !preWalkHasFindings) item { PreWalkPassLine() }
        item {
            // Which radio this screen is speaking about. Decided once per sample and passed
            // explicitly, because the stabiliser keeps a window and a window that changes
            // quantity mid-flight produces a median of two different things.
            //
            // The condition deliberately asks whether cellular is *present at all*, not whether
            // this particular sample carried a level: with no SIM the serving RSRP appears and
            // vanishes between samples, and keying off the level alone made the screen alternate
            // between the two radios several times a second.
            val onCellular = cell?.servingRsrpDbm != null || wifi == null
            val steady = if (onCellular) {
                stabiliser.update(
                    VerdictStabiliser.Source.CELLULAR,
                    cell?.servingRsrpDbm,
                    cell?.nr?.ssSinrDb ?: cell?.lte?.rssnrDb,
                )
            } else {
                stabiliser.update(
                    VerdictStabiliser.Source.WIFI,
                    wifi.rssiDbm,
                    null,
                    wifiCoChannel = wifi.coChannelCount,
                )
            }
            VerdictLine(steady, spreadDb = stabiliser.spreadDb)
        }
        item { KpiGrid(cell, wifi, fix) }
        item { ThroughputStrip(lastThroughput, throughputBusy) }

        if (recording) {
            item {
                WalkControls(
                    area = areaLabel,
                    onArea = { RecordingState.areaLabel.value = it },
                    floor = floor,
                    onFloor = { RecordingState.floor.value = it },
                )
            }
        }

        if (!recording) {
            item {
                SpotCheckCard(
                    running = spotRunning,
                    progress = spotProgress,
                    result = spotResult,
                    onClear = { spotResult = null },
                    onRun = {
                        spotResult = null
                        spotRunning = true
                        scope.launch {
                            val acc = com.nhnengineering.rftest.spot.SpotCheckAccumulator()
                            acc.start(System.currentTimeMillis())
                            val steps = SPOT_CHECK_SAMPLES
                            for (i in 0 until steps) {
                                acc.add(cellular.snapshot(), collector.snapshot())
                                spotProgress = (i + 1f) / steps
                                delay(SAMPLE_INTERVAL_MS)
                            }
                            spotResult = acc.result(System.currentTimeMillis())
                            spotRunning = false
                            spotProgress = 0f
                        }
                    },
                )
            }
        }

        item {
            RecordButton(
                recording = recording,
                onStart = { RecordingService.start(context, sessionName) },
                onStop = { RecordingService.stop(context) },
            )
        }

        // ---- Setup, collapsed during a session ----------------------------
        item {
            SetupPanel(
                expanded = setupExpanded,
                onToggle = { setupExpanded = !setupExpanded },
                recording = recording,
                sessionName = sessionName,
                onSessionNameChange = { sessionName = it },
                distanceM = distanceM,
                fixesWithVelocity = withVel,
                fixesWithoutVelocity = withoutVel,
                lastFile = lastFile,
                onArea = { RecordingState.areaLabel.value = it },
                onFloor = { RecordingState.floor.value = it },
                bandLock = bandLock,
                onBandLock = { RecordingState.bandLock.value = it },
                bandLockUi = bandUi.copy(onApply = onBandApply, onRelease = onBandRelease),
                ratLock = ratLock,
                techLockChecking = techLockChecking,
                techLockUnavailableReason = techLockUnavailableReason,
                techLockPendingRestore = techLock.pendingRestore,
                techLockBusy = techLockBusy,
                techLockStatus = techLockStatus,
                onTechnologyLock = onTechnologyLock,
                walkThroughput = walkThroughput,
                onWalkThroughputChange = { RecordingState.walkThroughputEnabled.value = it },
                liveView = liveView,
                onLiveViewChange = { com.nhnengineering.rftest.live.LiveView.set(context, it) },
                liveViewError = liveViewError,
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
                        val cfg = SpeedTestConfig.fromDownloadUrl(speedServer)
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
        item { CellularCard(cell) }
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
                YieldingText(
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
