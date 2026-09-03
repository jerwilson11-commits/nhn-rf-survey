package com.nhnengineering.rftest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nhnengineering.rftest.model.CellularSample
import com.nhnengineering.rftest.model.GeoPoint
import com.nhnengineering.rftest.model.Rat
import com.nhnengineering.rftest.model.RsrpBucket
import com.nhnengineering.rftest.model.RssiBucket
import com.nhnengineering.rftest.model.ThroughputSample
import com.nhnengineering.rftest.model.WifiSample
import java.util.Locale

/**
 * The measurement surface, built for reading at arm's length while walking.
 *
 * ## Why this exists
 *
 * The first Live screen was a vertical stack of cards in the order they were built: recorder,
 * thresholds, speed test, then cellular, GPS and Wi-Fi. Every screenshot taken during development
 * showed the same thing — **the whole screen filled with setup controls and explanatory prose, with
 * the actual measurements below the fold.** On a tool whose entire job is to be glanced at while
 * moving, that is backwards.
 *
 * Three rules follow, and they are what separates a field instrument from a settings screen:
 *
 * 1. **Measurements first, and never behind a scroll.** While recording, everything on screen is
 *    either a number being measured or a control used while walking.
 * 2. **Density over prose.** A tile is a value and a two-word label. The explanations are genuinely
 *    valuable — they are part of what makes the reports defensible — but they belong behind a
 *    disclosure, not in front of an operator on their fiftieth walk.
 * 3. **Colour carries the reading.** The serving level is legible as good or bad without being
 *    read, from the colour alone, which is what actually happens at walking pace.
 */

/** Compact recording state. One line, always visible, never scrolled away. */
/**
 * Says, unmissably, that nothing is being saved.
 *
 * ## Why this exists
 *
 * Two comparison walks in a row were lost because the recorder was never started. Nothing
 * malfunctioned either time: the app sampled, the live readings updated, the verdict was correct,
 * and every number on screen was real. It simply wrote none of them to a session, and the only
 * thing saying so was a small IDLE chip that scrolls off the top of the screen.
 *
 * That is the failure mode worth designing against, because it is silent and it is only detectable
 * after the walk is over and cannot be repeated. A recording session announces itself with a
 * notification; **not** recording announced nothing, and the absence of a signal is not a signal.
 *
 * So this is deliberately loud, deliberately in the measurement column where the operator is
 * already looking, and deliberately tappable — the fix is one press away from the warning rather
 * than back up at a button that is off-screen during a walk.
 */
@Composable
fun NotRecordingBanner(onStart: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF8A5300))
            .clickable(onClick = onStart)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "NOT RECORDING",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Color.White,
            )
            Text(
                // States the consequence rather than the state. "Idle" is a status; "nothing is
                // being saved" is what the operator actually needs to know at a glance.
                text = "Nothing is being saved",
                fontSize = 13.sp,
                color = Color(0xFFFFE0B2),
            )
        }
        Text(
            text = "START",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = Color.White,
        )
    }
}

@Composable
fun StatusStrip(
    recording: Boolean,
    elapsedMs: Long,
    rowCount: Long,
    area: String?,
    floor: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (recording) Color(0xFF7F1D1D) else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (recording) "● REC" else "IDLE",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = if (recording) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatElapsed(elapsedMs),
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            color = if (recording) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$rowCount",
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            color = if (recording) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Area and floor sit here rather than in their own card, because during a walk they are
        // status, not settings — the operator needs to see at a glance what is being written to
        // every row, and a mislabelled stretch is not recoverable afterwards.
        Text(
            text = listOfNotNull(area, floor?.let { "Fl $it" })
                .joinToString(" · ").ifEmpty { "no label" },
            fontSize = 13.sp,
            color = if (recording) Color(0xFFFFCDD2) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one number that matters, sized to be read without stopping.
 *
 * Colour is the primary channel and the digits are secondary: at walking pace an operator reads
 * "red" long before they read "−107".
 */
@Composable
fun HeroKpi(cell: CellularSample?, wifi: WifiSample?) {
    val cellular = cell?.servingRsrpDbm
    val value = cellular ?: wifi?.rssiDbm
    val argb = if (cellular != null) {
        RsrpBucket.of(cellular)?.argb
    } else {
        RssiBucket.of(wifi?.rssiDbm)?.argb
    }
    val color = argb?.let { Color(it) } ?: Color.Gray

    val unit = when {
        cell?.nr != null -> "dBm SS-RSRP"
        cellular != null -> "dBm RSRP"
        else -> "dBm RSSI"
    }
    val context = when {
        cell != null -> listOfNotNull(cell.rat.label, cell.servingBandLabel).joinToString("  ")
        wifi != null -> wifi.ssid ?: "Wi-Fi"
        else -> "no signal"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value?.toString() ?: "—",
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(unit, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            context,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** One tile. Value large, label small, no punctuation, no explanation. */
@Composable
private fun RowScopeTile(
    modifier: Modifier,
    label: String,
    value: String,
    color: Color = Color.Unspecified,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            // Sized to the value rather than fixed. A six-digit NR-ARFCN does not fit a third of
            // the screen at 22sp, and Compose clips without an ellipsis by default -- so 521310
            // rendered as "52131", which is not a truncated number, it is a different one. It
            // feeds the frequency and GSCN derivations, and nothing on screen said it was cut.
            fontSize = when {
                value.length <= 4 -> 22.sp
                value.length == 5 -> 19.sp
                value.length == 6 -> 16.sp
                else -> 14.sp
            },
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
            maxLines = 1,
            softWrap = false,
            // If it ever still does not fit, say so rather than inventing a shorter number.
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Secondary KPIs, six to a screen.
 *
 * Chosen for what an engineer actually watches on a walk rather than for what the API happens to
 * expose: quality, identity, and how many neighbours are visible — the last being the early warning
 * that the handset is about to hand over.
 */
@Composable
fun KpiGrid(cell: CellularSample?, wifi: WifiSample?, fix: GeoPoint?) {
    val gap = 6.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            RowScopeTile(
                Modifier.weight(1f), "SINR",
                (cell?.nr?.ssSinrDb ?: cell?.lte?.rssnrDb)?.let { "$it" } ?: "—",
            )
            RowScopeTile(
                Modifier.weight(1f), "RSRQ",
                (cell?.nr?.ssRsrqDb ?: cell?.lte?.rsrqDb)?.let { "$it" } ?: "—",
            )
            RowScopeTile(
                Modifier.weight(1f), "PCI",
                (cell?.nr?.pci ?: cell?.lte?.pci)?.toString() ?: "—",
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            RowScopeTile(
                Modifier.weight(1f), "CHANNEL",
                (cell?.nr?.nrarfcn ?: cell?.lte?.earfcn)?.toString()
                    ?: wifi?.channel?.toString() ?: "—",
            )
            RowScopeTile(
                Modifier.weight(1f), "NEIGHBOURS",
                cell?.neighbors?.size?.toString() ?: "—",
            )
            RowScopeTile(
                Modifier.weight(1f), "GPS ±m",
                fix?.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                // Accuracy is coloured because a fix worse than about ten metres makes a
                // floorplan-scale result meaningless, and that is worth noticing while there is
                // still time to wait for a better one.
                color = when {
                    fix?.accuracyM == null -> Color.Unspecified
                    fix.accuracyM!! <= 6f -> Color(0xFF2E7D32)
                    fix.accuracyM!! <= 12f -> Color(0xFFF9A825)
                    else -> Color(0xFFC62828)
                },
            )
        }
    }
}

/**
 * Throughput, shown only once there is something to show.
 *
 * An empty pair of dashes occupying a third of the screen for the thirty seconds between bursts is
 * worse than nothing — it reads as broken.
 */
@Composable
fun ThroughputStrip(tp: ThroughputSample?, busy: Boolean) {
    if (tp == null && !busy) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (busy) "transferring — radio loaded" else "throughput",
            fontSize = 11.sp,
            color = if (busy) Color(0xFFEF6C00) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = buildString {
                append(tp?.downloadMbps?.let { String.format(Locale.US, "%.0f", it) } ?: "—")
                append(" ↓   ")
                append(tp?.uploadMbps?.let { String.format(Locale.US, "%.0f", it) } ?: "—")
                append(" ↑  Mbps")
            },
            fontSize = 17.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Thin colour bar: the serving level as a single glanceable stripe. */
@Composable
fun LevelBar(cell: CellularSample?, wifi: WifiSample?) {
    val cellular = cell?.servingRsrpDbm
    val argb = if (cellular != null) {
        RsrpBucket.of(cellular)?.argb
    } else {
        RssiBucket.of(wifi?.rssiDbm)?.argb
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(argb?.let { Color(it) } ?: Color.Gray),
    )
}

/** RAT colour, matching the semantics used on the cellular detail card. */
internal fun ratColor(rat: Rat): Color = when (rat) {
    Rat.NR_SA -> Color(0xFF2E7D32)
    Rat.NR_NSA -> Color(0xFF689F38)
    Rat.LTE -> Color(0xFF1565C0)
    else -> Color.Gray
}

internal fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
