package com.nhnengineering.rftest.spot

import com.nhnengineering.rftest.model.CellularSample
import com.nhnengineering.rftest.model.Verdict
import com.nhnengineering.rftest.model.WifiSample
import kotlin.math.roundToInt

/**
 * A measurement of **one place**, taken standing still, in a few seconds.
 *
 * ## Why this is a separate thing from a walk
 *
 * A walk session answers "how does this building perform". Most actual uses of a tool like this
 * answer something much smaller: *is it all right here, right now?* Spot-checking after
 * commissioning, standing where a user complained, showing an executive the lift lobby, a
 * salesperson demonstrating a problem to a building owner. None of those want a CSV, a route or a
 * report — they want an answer and something to show someone.
 *
 * Building that on top of a session would have meant starting a recording, walking nowhere,
 * stopping it, and generating a report about a single point. The workflows are different enough to
 * deserve their own shape.
 *
 * ## Why it samples over several seconds rather than reading once
 *
 * A single instantaneous reading is not a measurement of a place. RSRP moves several dB
 * sample-to-sample while standing still, and the walk of 2026-09-02 showed SS-SINR holding one
 * value for 103 seconds while RSRP changed 88 times — so a single sample can catch a stale quality
 * figure and present it as current.
 *
 * Averaging over a window fixes the first problem and **exposes** the second: [spread] reports how
 * much the level moved during the check, so a reading taken somewhere unstable says so rather than
 * presenting a confident mean.
 */
data class SpotResult(
    val samples: Int,
    val durationMs: Long,
    /** Mean serving level over the window, dBm. Null when nothing was measurable. */
    val meanDbm: Int?,
    val minDbm: Int?,
    val maxDbm: Int?,
    /** Mean quality over the window, dB. Null when the modem never reported one. */
    val meanSinrDb: Int?,
    val verdict: Verdict,
    val band: String?,
    val technology: String?,
    val operator: String?,
    val pci: Int?,
    val channel: Int?,
    /** How many distinct neighbours were seen at any point during the check. */
    val neighboursSeen: Int,
    val label: String?,
) {
    /** Level spread across the window. Large values mean the reading is not of a stable place. */
    val spread: Int? get() = if (minDbm != null && maxDbm != null) maxDbm - minDbm else null

    /**
     * True when the level moved enough during the check that the mean should not be quoted alone.
     *
     * 6 dB is a factor of four in power. A spot check that varied by more than that was not
     * measuring one condition, and saying so is the difference between a measurement and a number.
     */
    val unstable: Boolean get() = (spread ?: 0) > 6

    val summaryLine: String
        get() = buildString {
            append(verdict.headline)
            meanDbm?.let { append("  ·  $it dBm") }
            meanSinrDb?.let { append("  ·  SINR $it dB") }
            band?.let { append("  ·  $it") }
        }
}

/**
 * Accumulates samples during a check and folds them into a result.
 *
 * Deliberately a plain accumulator with no coroutines or timers of its own: the caller already has
 * a sampling loop, and this stays unit-testable as a result.
 */
class SpotCheckAccumulator(val label: String? = null) {

    private val levels = mutableListOf<Int>()
    private val sinrs = mutableListOf<Int>()
    private val neighbourKeys = mutableSetOf<String>()

    private var band: String? = null
    private var technology: String? = null
    private var operator: String? = null
    private var pci: Int? = null
    private var channel: Int? = null
    private var coChannel: Int? = null
    private var wifiSeen = false

    var startedAtMillis: Long = 0
        private set

    fun start(nowMillis: Long) {
        startedAtMillis = nowMillis
    }

    fun add(cell: CellularSample?, wifi: WifiSample?) {
        // Cellular takes precedence when both are present, matching every other surface in the app:
        // a session with both is a cellular session that happened to see Wi-Fi.
        val level = cell?.servingRsrpDbm ?: wifi?.rssiDbm
        if (level != null) levels += level

        if (cell != null) {
            (cell.nr?.ssSinrDb ?: cell.lte?.rssnrDb)?.let { sinrs += it }
            // Identity fields take the last non-null seen rather than the first: if the handset
            // hands over mid-check, the place is better described by where it ended up.
            cell.servingBandLabel?.let { band = it }
            technology = cell.rat.label
            cell.operator?.let { operator = it }
            (cell.nr?.pci ?: cell.lte?.pci)?.let { pci = it }
            (cell.nr?.nrarfcn ?: cell.lte?.earfcn)?.let { channel = it }
            for (n in cell.neighbors) {
                neighbourKeys += "${n.rat}|${n.pci}|${n.channel}"
            }
        } else if (wifi != null) {
            wifiSeen = true
            band = wifi.band.label
            technology = "Wi-Fi"
            operator = wifi.ssid
            channel = wifi.channel
            coChannel = wifi.coChannelCount
        }
    }

    fun result(nowMillis: Long): SpotResult {
        val mean = levels.takeIf { it.isNotEmpty() }?.let { (it.sum().toDouble() / it.size).roundToInt() }
        val meanSinr = sinrs.takeIf { it.isNotEmpty() }?.let { (it.sum().toDouble() / it.size).roundToInt() }

        // The verdict is computed from the averages, not from the last sample, so a momentary dip
        // during the window does not become the reported conclusion.
        val verdict = if (wifiSeen && technology == "Wi-Fi") {
            Verdict.wifi(mean, coChannel)
        } else {
            Verdict.cellular(mean, meanSinr)
        }

        return SpotResult(
            samples = levels.size,
            durationMs = (nowMillis - startedAtMillis).coerceAtLeast(0),
            meanDbm = mean,
            minDbm = levels.minOrNull(),
            maxDbm = levels.maxOrNull(),
            meanSinrDb = meanSinr,
            verdict = verdict,
            band = band,
            technology = technology,
            operator = operator,
            pci = pci,
            channel = channel,
            neighboursSeen = neighbourKeys.size,
            label = label,
        )
    }
}
