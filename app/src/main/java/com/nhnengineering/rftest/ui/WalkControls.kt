package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nhnengineering.rftest.service.RecordingState

/**
 * The controls an operator touches **while walking**, and nothing else.
 *
 * Everything here is sized for one-handed use in motion: 56 dp targets, one word per button, and
 * the current selection visible in the button itself rather than in a label above it. The previous
 * layout put these mid-card, below a paragraph of explanation, which meant marking a threshold
 * crossing required finding the control first.
 *
 * Custom text entry is deliberately **not** here. Typing needs a stop and a keyboard, and it belongs
 * on the expanded setup panel — a walk-time control that opens a keyboard is a walk-time control
 * that will not get used.
 */
@Composable
fun WalkControls(
    area: String?,
    onArea: (String?) -> Unit,
    floor: String?,
    onFloor: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Indoor", "Outdoor").forEach { preset ->
                val selected = area == preset
                // The selected state is carried by the button's own fill. An operator glancing
                // down mid-walk should see which mode is active without reading a status line.
                if (selected) {
                    Button(
                        onClick = { onArea(preset) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) { Text(preset, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                } else {
                    OutlinedButton(
                        onClick = { onArea(preset) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) { Text(preset, fontSize = 15.sp, maxLines = 1) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val numeric = floor?.toIntOrNull()
            OutlinedButton(
                onClick = { onFloor(((numeric ?: 0) - 1).toString()) },
                modifier = Modifier.weight(1f).height(52.dp),
                enabled = floor == null || numeric != null,
                contentPadding = PaddingValues(2.dp),
            ) { Text("−", fontSize = 22.sp, maxLines = 1) }

            FilledTonalButton(
                onClick = { },
                modifier = Modifier.weight(2f).height(52.dp),
                contentPadding = PaddingValues(2.dp),
            ) {
                Text(
                    text = floor?.let { "Floor $it" } ?: "No floor",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }

            OutlinedButton(
                onClick = { onFloor(((numeric ?: 0) + 1).toString()) },
                modifier = Modifier.weight(1f).height(52.dp),
                enabled = floor == null || numeric != null,
                contentPadding = PaddingValues(2.dp),
            ) { Text("+", fontSize = 22.sp, maxLines = 1) }
        }

        if (floor != null && floor.toIntOrNull() == null) {
            Text(
                "\"$floor\" is not a number — use Setup to change it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Start / stop, sized so it cannot be missed or mistaken.
 *
 * Full width and 60 dp tall because it is pressed with a thumb, often while holding something else,
 * and because pressing Stop by accident mid-survey costs the walk.
 */
@Composable
fun RecordButton(recording: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Button(
        onClick = if (recording) onStop else onStart,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        colors = if (recording) {
            ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(
            text = if (recording) "STOP AND SAVE" else "START RECORDING",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Free-text labels, for a stop rather than a stride.
 *
 * Real buildings have areas the presets do not cover and floors that are not numbers — M, LL, B2,
 * PH. Those get typed once, standing still, which is why they live here and not in [WalkControls].
 */
/**
 * Records that the handset has been band-locked elsewhere.
 *
 * Shown only where the modem cannot be driven (no root), where a declaration is the only honest
 * thing left: the control asks what the operator did, and the report says so in those terms and
 * cross-checks it against the bands the walk actually saw. On a rooted handset [BandLockControl]
 * replaces it and applies the lock itself; technology likewise has [TechnologyLockControl].
 * `ratLock` is still shown here because it is worth reading alongside the band.
 *
 * It sits in setup rather than the walk controls because it is set once, standing still, alongside
 * the site name.
 */
@Composable
fun BandLockEntry(
    current: String?,
    onBandLock: (String?) -> Unit,
    ratLock: String?,
) {
    var text by remember(current) { mutableStateOf(current.orEmpty()) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Band locked to (blank = not locked)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onBandLock(text.trim().ifBlank { null }) }) { Text("Set") }
        }
        Text(
            text = listOfNotNull(
                current?.let { "Band: $it" },
                ratLock?.let { "Technology: $it" },
            ).ifEmpty { null }?.joinToString("  ·  ")?.let {
                "$it. Statistics will describe what the handset was restricted to, not the " +
                    "service a subscriber would get."
            } ?: "Free-running. Set the band only after restricting the modem in the handset RF " +
                "toolkit or RadioInfo screen — this app records what was done there, it does not " +
                "do it. Technology has its own control below, which this app performs itself.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Holds the radio on one technology via
 * [com.nhnengineering.rftest.cellular.TechnologyLockController], or clears the hold.
 *
 * Unlike [BandLockEntry] beside it, this genuinely performs and verifies the lock over a direct
 * modem interface, rather than only recording what the operator says was done in Settings. See
 * `TechnologyLockController` for the mechanism. The four names an engineer expects -- 5G SA only /
 * 5G NSA only / LTE only / Automatic -- are all real; NSA-only is the one that also empties the SA
 * band mask, verified by hand on 2026-09-26.
 *
 * Root-only, like every other modem-level feature in this app, so [unavailableReason] hides the
 * whole control on a handset that is privileged-installed but not rooted rather than showing a
 * button that would silently do nothing.
 */
@Composable
fun TechnologyLockControl(
    checking: Boolean,
    unavailableReason: String?,
    pendingRestore: Boolean,
    activeLabel: String?,
    busy: Boolean,
    status: String?,
    onSelect: (com.nhnengineering.rftest.cellular.TechnologyLock.Technology?) -> Unit,
) {
    // A real third state, not a loading gloss on the other two: showing "unavailable" before the
    // check has actually run, even for a moment, would be its own wrong answer on a rooted phone.
    if (checking) {
        Text(
            "Technology lock: checking availability…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (unavailableReason != null) {
        Text(
            "Technology lock: unavailable. $unavailableReason",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Technology lock", style = MaterialTheme.typography.titleSmall)

        // pendingRestore is true for the whole time any lock is held, including one this very
        // session just applied -- activeLabel already says that plainly through the selected
        // button. The warning earns its place only when the two disagree: a persisted baseline
        // exists but nothing in this session's own state explains it, which is what "the app was
        // killed while a lock was active" actually looks like on the next launch.
        if (pendingRestore && activeLabel == null) {
            Text(
                "A lock from an earlier session may still be in force, and this launch has no " +
                    "record of what it was. Tap Automatic to clear it, or restart the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val options = listOf(
            "5G SA only" to com.nhnengineering.rftest.cellular.TechnologyLock.Technology.NR_ONLY,
            "5G NSA only" to com.nhnengineering.rftest.cellular.TechnologyLock.Technology.NSA_ONLY,
            "LTE only" to com.nhnengineering.rftest.cellular.TechnologyLock.Technology.LTE_ONLY,
            "Automatic" to null,
        )
        // Two per row: four across leaves "5G NSA only" too narrow to read on a phone.
        options.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pair.forEach { (label, tech) ->
                    // activeLabel carries the verified string TechnologyLockController records
                    // (label plus a suffix marking it confirmed, not the bare button text), so the
                    // match has to go through the same helper that produced it.
                    val isCurrent = if (tech == null) {
                        activeLabel == null
                    } else {
                        activeLabel == com.nhnengineering.rftest.cellular.TechnologyLock.verifiedLabel(tech)
                    }
                    if (isCurrent) {
                        FilledTonalButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(tech) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    }
                }
            }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "Clears itself on Airplane Mode or a restart, even if this app is closed or killed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Everything [BandLockControl] needs from the screen that owns the controller. */
data class BandLockUi(
    val checking: Boolean = true,
    /** Non-null when the modem cannot be driven, in which case the declaration field is shown. */
    val unavailableReason: String? = null,
    val supportedLte: Set<Int> = emptySet(),
    val supportedNrSa: Set<Int> = emptySet(),
    val supportedNrNsa: Set<Int> = emptySet(),
    /**
     * What is actually locked right now, straight from
     * [com.nhnengineering.rftest.cellular.BandLockController.lockedNrSa] /
     * `.lockedNrNsa`, not re-derived from [BandLockControl]'s `activeLabel`. The recorded label
     * folds both NR scopes into the same bare band tokens (see
     * [com.nhnengineering.rftest.cellular.BandLock.verifiedLabel] for why), so it cannot say which
     * scope a given band came from -- only the controller's own separately persisted state can.
     */
    val heldNrSa: Set<Int> = emptySet(),
    val heldNrNsa: Set<Int> = emptySet(),
    val busy: Boolean = false,
    val status: String? = null,
    val pendingRestore: Boolean = false,
    val onApply: (lte: Set<Int>, nrSa: Set<Int>, nrNsa: Set<Int>) -> Unit = { _, _, _ -> },
    val onRelease: () -> Unit = {},
)

/**
 * Restricts the modem to chosen bands via [com.nhnengineering.rftest.cellular.BandLockController].
 *
 * Bands the modem does not list are not offered. What cannot be done is said here rather than
 * discovered later: no specific channel, no LTE band above 64 (see
 * [com.nhnengineering.rftest.cellular.BandLock]).
 *
 * NR is two independent scopes, not one choice: **5G standalone** restricts which band the radio
 * may camp on as its own standalone NR cell, and **5G non-standalone** restricts which band may be
 * added as a secondary leg alongside an LTE anchor. A site can carry one without the other -- this
 * app's own 2026-09-26 test desk had n41 reachable in NSA but only n25 available as a standalone
 * cell, so an SA-only lock to n41 left the radio with nowhere to camp while an NSA-only lock to n41
 * held. Pick whichever scope the technology lock alongside this is actually going to use.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BandLockControl(ui: BandLockUi, activeLabel: String?) {
    val heldLte = remember(activeLabel) { bandsIn(activeLabel, 'B') }
    var lte by remember(activeLabel) { mutableStateOf(heldLte) }
    var sa by remember(ui.heldNrSa) { mutableStateOf(ui.heldNrSa) }
    var nsa by remember(ui.heldNrNsa) { mutableStateOf(ui.heldNrNsa) }
    // A free-text declaration in the same field is not a lock this app is holding.
    val held = activeLabel != null && com.nhnengineering.rftest.cellular.BandLock.isVerifiedLabel(activeLabel)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Band lock", style = MaterialTheme.typography.titleSmall)

        if (ui.pendingRestore && !held) {
            Text(
                "A band restriction from an earlier session may still be in force. Tap Release, " +
                    "toggle Airplane Mode, or restart the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (ui.supportedLte.isNotEmpty()) {
            Text("LTE", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ui.supportedLte.sorted().forEach { b ->
                    FilterChip(
                        selected = b in lte,
                        onClick = { lte = if (b in lte) lte - b else lte + b },
                        enabled = !ui.busy,
                        label = { Text("B$b") },
                    )
                }
            }
        }
        if (ui.supportedNrSa.isNotEmpty()) {
            Text("5G standalone", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ui.supportedNrSa.sorted().forEach { b ->
                    FilterChip(
                        selected = b in sa,
                        onClick = { sa = if (b in sa) sa - b else sa + b },
                        enabled = !ui.busy,
                        label = { Text("n$b") },
                    )
                }
            }
        }
        if (ui.supportedNrNsa.isNotEmpty()) {
            Text("5G non-standalone", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ui.supportedNrNsa.sorted().forEach { b ->
                    FilterChip(
                        selected = b in nsa,
                        onClick = { nsa = if (b in nsa) nsa - b else nsa + b },
                        enabled = !ui.busy,
                        label = { Text("n$b") },
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { ui.onApply(lte, sa, nsa) },
                enabled = !ui.busy && (lte.isNotEmpty() || sa.isNotEmpty() || nsa.isNotEmpty()),
                modifier = Modifier.weight(1f),
            ) { Text(if (held) "Change lock" else "Apply lock") }
            OutlinedButton(
                onClick = ui.onRelease,
                enabled = !ui.busy && (held || ui.pendingRestore),
                modifier = Modifier.weight(1f),
            ) { Text("Release") }
        }

        ui.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (held) {
            Text("Recording as: $activeLabel", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Picks bands, not a channel: a specific EARFCN or ARFCN cannot be selected this way. " +
                "Leaving a scope empty leaves it unrestricted. A band this site does not carry in " +
                "that scope can leave the phone with no service until released. Clears itself on " +
                "Airplane Mode or a restart.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Band numbers in a verified label ("B4, n41 (locked ...)") for one technology prefix. Only LTE
 * ('B') is unambiguous this way -- NR's two scopes fold into the same bare `n<band>` tokens (see
 * [com.nhnengineering.rftest.cellular.BandLock.verifiedLabel]), so [BandLockUi.heldNrSa] /
 * `.heldNrNsa` carry those instead of trying to recover them from this string.
 */
private fun bandsIn(label: String?, prefix: Char): Set<Int> {
    if (label == null || !com.nhnengineering.rftest.cellular.BandLock.isVerifiedLabel(label)) return emptySet()
    return com.nhnengineering.rftest.cellular.BandLock.tokens(label)
        .filter { it.firstOrNull() == prefix }
        .mapNotNull { it.drop(1).toIntOrNull() }
        .toSet()
}

/**
 * Track B classification: is the current position a critical or general area for public-safety
 * coverage purposes, or not being classified at all right now.
 *
 * Deliberately three states, not two -- "not classified" must stay reachable and must be the
 * default, since an unclassified sample must never silently count as "general" in a compliance
 * figure. Only meaningful for samples on FirstNet Band 14/n14; the report filters on band
 * regardless of what this says. See `model/PublicSafetyCoverage.kt`.
 */
@Composable
fun ErrcsAreaClassControl(
    current: com.nhnengineering.rftest.model.ErrcsAreaClass?,
    onChange: (com.nhnengineering.rftest.model.ErrcsAreaClass?) -> Unit,
    band14Registered: Boolean? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Public safety area (Band 14 only)", style = MaterialTheme.typography.titleSmall)
        // Live, not a claim about the whole session -- the serving cell can change bands
        // mid-walk, and a classified sample only counts toward Track B while this is true.
        Text(
            text = when (band14Registered) {
                true -> "FirstNet Band 14/n14: registered now"
                false -> "FirstNet Band 14/n14: not registered — samples now won't count toward Track B"
                null -> "FirstNet Band 14/n14: no cellular reading yet"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (band14Registered == true) {
                Color(0xFF2E7D32)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val options = listOf(
                "Not classified" to null,
                "General" to com.nhnengineering.rftest.model.ErrcsAreaClass.GENERAL,
                "Critical" to com.nhnengineering.rftest.model.ErrcsAreaClass.CRITICAL,
            )
            options.forEach { (label, value) ->
                val selected = current == value
                if (selected) {
                    Button(
                        onClick = { onChange(value) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        contentPadding = PaddingValues(2.dp),
                    ) { Text(label, fontSize = 13.sp, maxLines = 1) }
                } else {
                    OutlinedButton(
                        onClick = { onChange(value) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        contentPadding = PaddingValues(2.dp),
                    ) { Text(label, fontSize = 13.sp, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
fun LabelEntry(onArea: (String?) -> Unit, onFloor: (String?) -> Unit) {
    var areaText by remember { mutableStateOf("") }
    var floorText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = areaText,
                onValueChange = { areaText = it },
                label = { Text("Area name") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            Button(
                onClick = { onArea(areaText.trim().ifBlank { null }); areaText = "" },
                modifier = Modifier.weight(1f),
            ) { Text("Set") }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = floorText,
                onValueChange = { floorText = it },
                label = { Text("Floor label") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            Button(
                onClick = { onFloor(floorText.trim().ifBlank { null }); floorText = "" },
                modifier = Modifier.weight(1f),
            ) { Text("Set") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onArea(null) }, modifier = Modifier.weight(1f)) {
                Text("Clear area", maxLines = 1)
            }
            OutlinedButton(onClick = { onFloor(null) }, modifier = Modifier.weight(1f)) {
                Text("Clear floor", maxLines = 1)
            }
        }
    }
}

/**
 * Sets the cell-lock target the app watches for — the PCI and ARFCN of a lock set in an external
 * tool (the handset's RF toolkit, Cellular-Pro, etc.). The app does not set the lock; this only
 * tells it what to verify the serving cell against, so a locked survey can be proven to have held.
 *
 * Reads and writes [RecordingState] directly rather than threading callbacks through the setup
 * panel: the target is process-scoped session state like the other sticky fields, and keeping it
 * self-contained avoids growing [SetupPanel]'s already-large signature for a two-field control.
 */
@Composable
fun LockWatchEntry() {
    // Cell lock watch is a Field feature. Free users see a short upgrade prompt instead of the
    // target-entry fields.
    val tier by com.nhnengineering.rftest.billing.EntitlementRepository.tier.collectAsState()
    if (!tier.grantsField) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Cell lock watch is a Field feature. Upgrade to verify an external PCI/ARFCN lock " +
                    "held across a survey.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { com.nhnengineering.rftest.billing.UpgradePrompt.open() }) {
                Text("See plans", maxLines = 1)
            }
        }
        return
    }

    val currentPci by RecordingState.lockWatchPci.collectAsState()
    val currentArfcn by RecordingState.lockWatchArfcn.collectAsState()
    var pciText by remember { mutableStateOf("") }
    var arfcnText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (currentPci != null && currentArfcn != null) {
                "Watching lock: PCI $currentPci / ARFCN $currentArfcn"
            } else {
                "No lock target set. Enter the PCI and ARFCN you locked to externally to verify it holds."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = pciText,
                onValueChange = { pciText = it.filter { c -> c.isDigit() } },
                label = { Text("Target PCI") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = arfcnText,
                onValueChange = { arfcnText = it.filter { c -> c.isDigit() } },
                label = { Text("Target ARFCN") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    val pci = pciText.trim().toIntOrNull()
                    val arfcn = arfcnText.trim().toIntOrNull()
                    // Both are required: a PCI is unique only within a frequency, so a target
                    // without its ARFCN cannot be checked against the serving cell unambiguously.
                    if (pci != null && arfcn != null) {
                        RecordingState.lockWatchPci.value = pci
                        RecordingState.lockWatchArfcn.value = arfcn
                        pciText = ""; arfcnText = ""
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Watch", maxLines = 1) }
            OutlinedButton(
                onClick = {
                    RecordingState.lockWatchPci.value = null
                    RecordingState.lockWatchArfcn.value = null
                },
                modifier = Modifier.weight(1f),
            ) { Text("Clear lock watch", maxLines = 1) }
        }
    }
}
