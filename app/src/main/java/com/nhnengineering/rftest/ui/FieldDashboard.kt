package com.nhnengineering.rftest.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
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
        YieldingText(
            text = listOfNotNull(area, floor?.let { "Fl $it" })
                .joinToString(" · ").ifEmpty { "no label" },
            fontSize = 13.sp,
            textAlign = TextAlign.End,
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
fun HeroKpi(cell: CellularSample?, wifi: WifiSample?, forceCellular: Boolean? = null) {
    // forceCellular pins the radio when the operator has chosen a survey focus; null keeps the
    // automatic pick (cellular if it has a serving level, else Wi-Fi). When a radio is pinned, the
    // other is set aside so the headline is unambiguously the chosen one.
    val showCellular = forceCellular ?: (cell?.servingRsrpDbm != null)
    val cellForHero = if (forceCellular == false) null else cell
    val wifiForHero = if (forceCellular == true) null else wifi
    val cellular = if (showCellular) cellForHero?.servingRsrpDbm else null
    val value = cellular ?: wifiForHero?.rssiDbm
    val argb = if (cellular != null) {
        RsrpBucket.of(cellular)?.argb
    } else {
        RssiBucket.of(wifiForHero?.rssiDbm)?.argb
    }
    val color = argb?.let { Color(it) } ?: Color.Gray

    val unit = when {
        showCellular && cellForHero?.nr != null -> "dBm SS-RSRP"
        cellular != null -> "dBm RSRP"
        else -> "dBm RSSI"
    }
    val context = when {
        // Observed-but-not-registered (e.g. a present-but-deactivated SIM): the big number is a real
        // measurement of the strongest cell in view, but the device is NOT on it. Say so instead of
        // labelling it a serving RAT/band, which read as normal service.
        showCellular && cellForHero != null && !cellForHero.isRegistered &&
            (cellForHero.lte != null || cellForHero.nr != null) ->
            listOfNotNull("Not registered", cellForHero.servingBandLabel?.let { "observed $it" })
                .joinToString(" · ")
        showCellular && cellForHero != null ->
            listOfNotNull(cellForHero.rat.label, cellForHero.servingBandLabel).joinToString("  ")
        !showCellular && wifiForHero != null -> wifiForHero.ssid ?: "Wi-Fi"
        // Pinned to a radio that has nothing to show yet.
        forceCellular == true -> "cellular — no signal"
        forceCellular == false -> "Wi-Fi — not connected"
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
        if (cell?.onFirstNetBand14 == true) {
            FirstNetBand14Badge(Modifier.padding(top = 6.dp))
        }
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
fun KpiGrid(cell: CellularSample?, wifi: WifiSample?, fix: GeoPoint?, forceCellular: Boolean? = null) {
    val gap = 6.dp
    // Follow the same focus as the hero: a cellular grid under a Wi-Fi headline (RSRQ/PCI/EARFCN
    // while the big number is a Wi-Fi RSSI) is exactly the "which radio am I looking at" confusion
    // the focus control exists to remove. When Wi-Fi is featured, the grid shows Wi-Fi KPIs instead.
    val showCellular = forceCellular ?: (cell?.servingRsrpDbm != null)
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        if (!showCellular) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                RowScopeTile(Modifier.weight(1f), "CHANNEL", wifi?.channel?.toString() ?: "—")
                RowScopeTile(
                    Modifier.weight(1f), "WIDTH",
                    wifi?.channelWidthMhz?.let { "$it MHz" } ?: "—",
                )
                RowScopeTile(Modifier.weight(1f), "BAND", wifi?.band?.label ?: "—")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                RowScopeTile(Modifier.weight(1f), "CO-CHAN", wifi?.coChannelCount?.toString() ?: "—")
                RowScopeTile(
                    Modifier.weight(1f), "NEIGHBOURS",
                    wifi?.neighbors?.size?.toString() ?: "—",
                )
                RowScopeTile(
                    Modifier.weight(1f), "GPS ±m",
                    fix?.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                    color = when {
                        fix?.accuracyM == null -> Color.Unspecified
                        fix.accuracyM!! <= 6f -> Color(0xFF2E7D32)
                        fix.accuracyM!! <= 12f -> Color(0xFFF9A825)
                        else -> Color(0xFFC62828)
                    },
                )
            }
            return@Column
        }
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
                // A dash, not a zero, until this handset has reported a neighbour at least once.
                // Zero is a measurement of the site; on a handset that never reports any it is a
                // measurement of nothing, and the two look identical at a glance.
                if (cell?.neighboursEverSeen == true) {
                    cell.neighbors.size.toString()
                } else {
                    "—"
                },
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
 * Throughput, as twin live speedometers (download + upload).
 *
 * Replaced a one-line "throughput  X ↓ Y ↑ Mbps" strip that read as incidental — testers could not
 * tell at a glance that throughput was running and being logged. Each dial's needle animates, and
 * while a burst is transferring it tracks the running Mbps reported several times a second
 * ([RecordingState.liveDownloadMbps] / [liveUploadMbps]), so the gauge genuinely moves in real time
 * rather than stepping once per burst. Between bursts each dial holds the last logged figure.
 *
 * Stays visible whenever walk-throughput is enabled, even before the first burst, so an enabled-but-
 * failing walk is never indistinguishable from one that is not running.
 */
@Composable
fun ThroughputStrip(
    tp: ThroughputSample?,
    busy: Boolean,
    enabled: Boolean = false,
    liveDown: Double? = null,
    liveUp: Double? = null,
    lastAtElapsed: Long? = null,
    nextAtElapsed: Long? = null,
) {
    if (tp == null && !busy && !enabled) return
    // A live reading is the real-time rate of the burst in progress; otherwise the last logged
    // figure, which is STALE -- shown dimmed with its age so a 40-second-old number can never be
    // mistaken for a live one.
    val liveActive = liveDown != null || liveUp != null
    val down = liveDown ?: tp?.downloadMbps
    val up = liveUp ?: tp?.uploadMbps
    val hasNumbers = tp?.downloadMbps != null || tp?.uploadMbps != null || liveDown != null || liveUp != null
    val stale = !busy && !liveActive && hasNumbers

    // Tick once a second so the "N s ago" age and the countdown advance on their own.
    val nowElapsed by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val ageSec = lastAtElapsed?.let { ((nowElapsed - it) / 1000).coerceAtLeast(0) }
    val nextSec = nextAtElapsed?.let { ((it - nowElapsed) / 1000) }?.takeIf { it >= 0 }

    val status = when {
        liveDown != null -> "measuring download — radio loaded"
        liveUp != null -> "measuring upload — radio loaded"
        busy -> "measuring — radio loaded"
        stale -> buildString {
            append("last reading")
            ageSec?.let { append(" · ${it}s ago") }
            nextSec?.let { append(" · next in ${it}s") }
        }
        tp?.error != null -> "last burst failed" + (nextSec?.let { " · next in ${it}s" } ?: "")
        else -> "waiting for first reading…"
    }
    val statusColor = when {
        busy || liveActive -> Color(0xFFEF6C00)
        stale -> MaterialTheme.colorScheme.onSurfaceVariant
        tp?.error != null -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("THROUGHPUT", fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Solid dot only while actually transferring; a hollow ring when the shown numbers
                // are a stale last reading.
                if (busy || liveActive) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                } else if (stale) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor.copy(alpha = 0.4f)))
                }
                YieldingText(status, fontSize = 12.sp, color = statusColor)
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A dial is "active" (full colour) while that direction is the one transferring; a value
            // that is just the last logged figure is dimmed to read as not-live.
            ThroughputDial(Modifier.weight(1f), "DOWNLOAD", "↓", down, active = liveDown != null, dim = stale)
            ThroughputDial(Modifier.weight(1f), "UPLOAD", "↑", up, active = liveUp != null, dim = stale)
        }

        // A burst ran but produced no number: show WHY (e.g. "down: HTTP 429") rather than hiding it.
        if (tp?.error != null && !hasNumbers) {
            Spacer(Modifier.height(6.dp))
            Text(tp.error, fontSize = 11.sp, color = Color(0xFFEF6C00), textAlign = TextAlign.Center)
        }
    }
}

/** One labelled dial: the gauge, the big colour-coded number, and the direction. */
@Composable
private fun ThroughputDial(
    modifier: Modifier,
    label: String,
    arrow: String,
    mbps: Double?,
    active: Boolean,
    dim: Boolean = false,
) {
    // Stale (last-reading) values are dimmed so they never read as a live measurement.
    val numberAlpha = if (dim) 0.45f else 1f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = if (active) Color(0xFFEF6C00) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SpeedGauge(mbps, dim = dim)
        Text(
            text = mbps?.let { String.format(Locale.US, "%.0f", it) } ?: "—",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = speedColor(mbps).copy(alpha = numberAlpha),
        )
        Text("$arrow Mbps", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A nice round full-scale for the dial, stepped so the needle keeps a usable range at any speed. */
private fun gaugeMax(v: Double?): Double = when {
    v == null -> 100.0
    v <= 25 -> 25.0
    v <= 50 -> 50.0
    v <= 100 -> 100.0
    v <= 250 -> 250.0
    v <= 500 -> 500.0
    else -> 1000.0
}

/** Speed colour, shared by the dial arc, the needle and the big number. */
private fun speedColor(v: Double?): Color = when {
    v == null -> Color.Gray
    v >= 100 -> Color(0xFF2E7D32)   // green — comfortable for anything
    v >= 25 -> Color(0xFFF9A825)    // amber — fine for most
    v >= 5 -> Color(0xFFEF6C00)     // orange — usable, not fast
    else -> Color(0xFFC62828)       // red — struggling
}

/**
 * A 180° speedometer: grey track, a coloured value arc, and an animated needle. The needle target is
 * animated with a short tween, so the ~5-times-a-second live rate during a burst reads as a smoothly
 * moving needle rather than a series of jumps. Sized by its own height so it stays a compact dial on
 * a wide tablet rather than a half-screen arc.
 */
@Composable
private fun SpeedGauge(mbps: Double?, dim: Boolean = false) {
    val max = gaugeMax(mbps)
    val targetFrac = (((mbps ?: 0.0) / max).coerceIn(0.0, 1.0)).toFloat()
    val frac by animateFloatAsState(
        targetValue = targetFrac,
        animationSpec = tween(durationMillis = 350),
        label = "throughputNeedle",
    )
    // Dimmed when the value is a stale last reading rather than a live measurement.
    val arcAlpha = if (dim) 0.4f else 1f
    val arcColor = speedColor(mbps).copy(alpha = arcAlpha)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val needleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dim) 0.4f else 1f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(top = 4.dp),
    ) {
        val stroke = 12.dp.toPx()
        val r = (size.height - stroke)
        val cx = size.width / 2f
        val cy = size.height - stroke / 2f
        val topLeft = Offset(cx - r, cy - r)
        val arcSize = Size(r * 2f, r * 2f)

        drawArc(track, 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (mbps != null) {
            drawArc(arcColor, 180f, 180f * frac, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }

        // Needle. 180° = left (zero), 270° = up (half), 360° = right (full scale).
        val angle = Math.toRadians((180f + 180f * frac).toDouble())
        val tip = Offset(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat())
        val hub = if (mbps != null) needleColor else needleColor.copy(alpha = 0.35f)
        drawLine(hub, Offset(cx, cy), tip, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(hub, radius = 5.dp.toPx(), center = Offset(cx, cy))
    }
}

/** Thin colour bar: the serving level as a single glanceable stripe. */
@Composable
fun LevelBar(cell: CellularSample?, wifi: WifiSample?, forceCellular: Boolean? = null) {
    val showCellular = forceCellular ?: (cell?.servingRsrpDbm != null)
    val cellular = if (showCellular) cell?.servingRsrpDbm else null
    val argb = if (cellular != null) {
        RsrpBucket.of(cellular)?.argb
    } else {
        RssiBucket.of(if (forceCellular == true) null else wifi?.rssiDbm)?.argb
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
