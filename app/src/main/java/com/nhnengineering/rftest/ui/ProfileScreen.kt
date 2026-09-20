package com.nhnengineering.rftest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nhnengineering.rftest.profile.ProfileMatcher
import com.nhnengineering.rftest.profile.ProfileStore
import com.nhnengineering.rftest.profile.TddProfile
import com.nhnengineering.rftest.service.RecordingState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.compose.material3.FilterChip

/**
 * The TDD and SSB profile library.
 *
 * Most of this was told to a person rather than measured. Some of it was not: SIB1 carries the
 * slot pattern, SSB positions and subcarrier spacing, and an engineer with a diagnostic tool on
 * a rooted handset can read them off the air and paste the decode straight in. The rendering
 * keeps the difference visible -- every entry carries its provenance, its source and the date,
 * and a reported value is never shown with the weight of a reading.
 *
 * Starts empty deliberately. Seeding it with typical values would be the worst of both worlds — a
 * plausible slot pattern nobody verified, presented with the same weight as one an operator
 * confirmed.
 */
@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { ProfileStore(File(context.filesDir, "tdd-profiles.jsonl")) }

    var loaded by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<TddProfile?>(null) }

    // Samples for itself rather than reading RecordingState, which only the recording service
    // populates. The first build showed "no cellular service" on this screen unless a session
    // happened to be running -- useless at exactly the moment it is opened, which is standing on
    // site with the operator on the phone.
    val serviceCell by RecordingState.cellular.collectAsState()
    val recording by RecordingState.active.collectAsState()
    var localCell by remember { mutableStateOf<com.nhnengineering.rftest.model.CellularSample?>(null) }

    val collector = remember { com.nhnengineering.rftest.cellular.CellularCollector(context) }
    DisposableEffect(recording) {
        if (!recording) collector.start()
        onDispose { if (!recording) collector.stop() }
    }
    LaunchedEffect(recording) {
        if (recording) return@LaunchedEffect
        while (true) {
            localCell = collector.snapshot()
            kotlinx.coroutines.delay(1000)
        }
    }
    val cell = if (recording) serviceCell else localCell

    val current = ProfileMatcher.match(
        loaded.profiles,
        ProfileMatcher.Query(
            mcc = cell?.mcc, mnc = cell?.mnc, operator = cell?.operator,
            band = cell?.servingBandLabel,
        ),
    )

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Configuration profiles", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "SSB periodicity, the slot pattern and CSI-RS periodicity cannot be read " +
                            "by any ordinary app — they live in SIB1 and the PHY layer. But they " +
                            "are vendor defaults a carrier adopts across a market, so learning " +
                            "them once covers most sites. Record what you are told here and every " +
                            "later site is a lookup.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Most of this is not measured. SSB periodicity, the slot pattern and CSI-RS " +
                        "periodicity cannot be read by this app — but a diagnostic tool on a " +
                        "rooted handset reads them out of SIB1, so an entry can be marked as " +
                        "measured. Every value records where it came from, and the report says " +
                        "which kind it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFEF6C00),
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Serving now", style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            cell?.operator, cell?.servingBandLabel,
                            cell?.mcc?.let { "$it/${cell?.mnc}" },
                        ).joinToString("  ·  ").ifEmpty { "no cellular service" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (current != null) {
                        ProfileValues(current)
                    } else if (cell?.servingBandLabel != null) {
                        Text(
                            "No profile recorded for this operator and band yet.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = { editing = blankProfile(cell?.operator, cell?.mcc, cell?.mnc, cell?.servingBandLabel) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add a profile") }
        }

        if (loaded.skipped > 0) {
            item {
                Text(
                    "${loaded.skipped} saved profile(s) could not be read and were skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC62828),
                )
            }
        }

        items(loaded.profiles, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        YieldingText(p.title, fontWeight = FontWeight.SemiBold)
                        Row {
                            TextButton(onClick = { editing = p }) { Text("Edit") }
                            TextButton(onClick = {
                                store.delete(p.id); loaded = store.load()
                            }) { Text("Delete") }
                        }
                    }
                    if (p.isSiteOverride) {
                        Text(
                            "Site override — used only at this site.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32),
                        )
                    }
                    ProfileValues(p)
                }
            }
        }

        if (loaded.profiles.isEmpty()) {
            item {
                Text(
                    "The library is empty. Add the first profile when an operator, a scanner or a " +
                        "protocol tool tells you a configuration — then it is available at every " +
                        "site on that vendor and band.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    editing?.let { p ->
        ProfileEditor(
            initial = p,
            onCancel = { editing = null },
            onSave = {
                store.upsert(it)
                loaded = store.load()
                editing = null
            },
        )
    }
}

/** The recorded values, always with their provenance. */
@Composable
private fun ProfileValues(p: TddProfile) {
    val fields = listOfNotNull(
        p.tddPattern?.takeIf { it.isNotBlank() }?.let { "Pattern" to it },
        p.tddPeriodicityMs?.takeIf { it.isNotBlank() }?.let { "Periodicity" to "$it ms" },
        p.dlSlots?.let { "DL slots" to "$it" },
        p.dlSymbols?.let { "DL symbols" to "$it" },
        p.ulSlots?.let { "UL slots" to "$it" },
        p.ulSymbols?.let { "UL symbols" to "$it" },
        p.ssbPeriodicityMs?.let { "SSB periodicity" to "$it ms" },
        p.ssbPositionsInBurst?.takeIf { it.isNotBlank() }?.let { "SSB in burst" to it },
        p.scsKhz?.let { "SCS" to "$it kHz" },
    )
    if (fields.isEmpty()) {
        Text("No values recorded yet.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            fields.joinToString("   ") { "${it.first} ${it.second}" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
    // The provenance is not optional decoration. A remembered value without a source is a rumour,
    // and this is the line that stops one being configured into equipment a year later.
    Text(
        "from: ${p.source.ifBlank { "unrecorded" }}" +
            if (p.recordedAtUtcMillis > 0) {
                "  ·  " + SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                    .format(Date(p.recordedAtUtcMillis))
            } else {
                ""
            },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    p.note?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProfileEditor(
    initial: TddProfile,
    onCancel: () -> Unit,
    onSave: (TddProfile) -> Unit,
) {
    var p by remember { mutableStateOf(initial) }
    var pasting by remember { mutableStateOf(false) }

    if (pasting) {
        Sib1PasteDialog(
            onCancel = { pasting = false },
            onApply = { r ->
                p = p.withSib1(r, System.currentTimeMillis())
                pasting = false
            },
        )
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (initial.source.isBlank()) "New profile" else "Edit profile") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    // First, because it is the fast path: an engineer who has just decoded SIB1
                    // in a diagnostic tool should not have to retype nine fields off the screen.
                    OutlinedButton(
                        onClick = { pasting = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Paste a SIB1 decode") }
                    Text(
                        "Fills the configuration below from a decode and marks it measured. " +
                            "Everything stays editable afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Field("Vendor (Ericsson, Nokia, Samsung)", p.vendor) { p = p.copy(vendor = it) }
                }
                item { Field("Operator", p.operator) { p = p.copy(operator = it) } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("MCC", p.mcc ?: "") { p = p.copy(mcc = it.ifBlank { null }) }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("MNC", p.mnc ?: "") { p = p.copy(mnc = it.ifBlank { null }) }
                        }
                    }
                }
                item { Field("Band", p.band) { p = p.copy(band = it) } }
                item { Field("Market (optional)", p.market ?: "") { p = p.copy(market = it.ifBlank { null }) } }
                item {
                    Field("Site (leave blank for a general profile)", p.siteName ?: "") {
                        p = p.copy(siteName = it.ifBlank { null })
                    }
                }
                item { HorizontalDivider() }
                item { Field("TDD pattern (e.g. DDDSU)", p.tddPattern ?: "") { p = p.copy(tddPattern = it.ifBlank { null }) } }
                item { Field("TDD periodicity ms", p.tddPeriodicityMs ?: "") { p = p.copy(tddPeriodicityMs = it.ifBlank { null }) } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("DL slots", p.dlSlots?.toString() ?: "") { p = p.copy(dlSlots = it.toIntOrNull()) }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("DL symbols", p.dlSymbols?.toString() ?: "") { p = p.copy(dlSymbols = it.toIntOrNull()) }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("UL slots", p.ulSlots?.toString() ?: "") { p = p.copy(ulSlots = it.toIntOrNull()) }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("UL symbols", p.ulSymbols?.toString() ?: "") { p = p.copy(ulSymbols = it.toIntOrNull()) }
                        }
                    }
                }
                item { Field("SSB periodicity ms", p.ssbPeriodicityMs?.toString() ?: "") { p = p.copy(ssbPeriodicityMs = it.toIntOrNull()) } }
                item { Field("SSB position in burst", p.ssbPositionsInBurst ?: "") { p = p.copy(ssbPositionsInBurst = it.ifBlank { null }) } }
                item { Field("Subcarrier spacing kHz", p.scsKhz?.toString() ?: "") { p = p.copy(scsKhz = it.toIntOrNull()) } }
                item { HorizontalDivider() }
                item {
                    // Required, and the dialog will not save without it.
                    // Provenance first, because it changes what the field below is asking for:
                    // a person's name, or the tool the values were read with.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (option in com.nhnengineering.rftest.profile.Provenance.entries) {
                            FilterChip(
                                selected = p.provenance == option,
                                onClick = { p = p.copy(provenance = option) },
                                label = { Text(option.label, maxLines = 1) },
                            )
                        }
                    }
                    Text(
                        text = when (p.provenance) {
                            com.nhnengineering.rftest.profile.Provenance.MEASURED ->
                                "Read off the air — SIB1 carries tdd-UL-DL-ConfigurationCommon, " +
                                    "ssb-PositionsInBurst and subcarrierSpacing in full. The " +
                                    "report will say these were measured. Only choose this for " +
                                    "values you actually saw decoded."
                            com.nhnengineering.rftest.profile.Provenance.REPORTED ->
                                "Somebody told you. The report will say so, and will not present " +
                                    "these values as measurements."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Field(
                        if (p.provenance == com.nhnengineering.rftest.profile.Provenance.MEASURED) {
                            "Source — what you read it with, and when"
                        } else {
                            "Source — who told you, and when"
                        },
                        p.source,
                    ) { p = p.copy(source = it) }
                }
                item { Field("Note (optional)", p.note ?: "") { p = p.copy(note = it.ifBlank { null }) } }
                if (p.source.isBlank()) {
                    item {
                        Text(
                            "A source is required. A configuration without a provenance cannot be " +
                                "defended a year later, and this is data nobody can re-derive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF6C00),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(p.copy(recordedAtUtcMillis = System.currentTimeMillis())) },
                enabled = p.source.isNotBlank() && p.band.isNotBlank() && p.operator.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun blankProfile(operator: String?, mcc: String?, mnc: String?, band: String?) = TddProfile(
    id = UUID.randomUUID().toString(),
    vendor = "",
    operator = operator ?: "",
    mcc = mcc,
    mnc = mnc,
    // Prefilled from what is serving right now, which is the moment someone is most likely to be
    // told a configuration — standing on site with the operator on the phone.
    band = band?.substringBefore('/') ?: "",
    market = null,
    siteName = null,
    tddPattern = null, tddPeriodicityMs = null,
    dlSlots = null, dlSymbols = null, ulSlots = null, ulSymbols = null,
    ssbPeriodicityMs = null, ssbPositionsInBurst = null, scsKhz = null,
    source = "",
    recordedAtUtcMillis = 0L,
    note = null,
)
