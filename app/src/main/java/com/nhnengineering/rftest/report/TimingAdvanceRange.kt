package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import kotlin.math.roundToInt

/**
 * Distance to the serving eNodeB from LTE timing advance (roadmap A5, the public-API half). Field
 * tier, no root. The modem reports the round-trip timing correction it has applied to the uplink;
 * one TA unit is 16·Ts, which is **78.125 m of one-way distance**. Converting it gives a cheap,
 * independent cross-check on which cell is really serving a location.
 *
 * ## What this is good for, and the trap it must not walk into
 *
 * On a **macro / outdoor** cell this is a real distance to the tower, and the useful finding is
 * **overshoot**: a venue served from 2–3 km away by a macro that should have been handed off to the
 * in-building system is a classic remediation case, and a large TA is the evidence. Confirming the
 * handset is on the *near* cell is the other everyday use.
 *
 * On a **DAS / repeater**, TA is **not** a physical distance. It reflects the cable length and the
 * head-end processing delay of the distributed system, so a basement served by a DAS can report a TA
 * of hundreds of metres while the operator stands under the antenna. The report says this in as many
 * words — read as physical distance on a DAS it is simply wrong, and this tool's own users are the
 * people most likely to be on a DAS.
 *
 * Only **LTE** carries TA here: Android exposes LTE timing advance through the public API, and NR TA
 * (and PHR / Tx power) need the DIAG path — a separate Pro follow-up. Quantisation is 78 m, so a
 * single reading is coarse; the distribution over a walk is what carries the signal.
 */
object TimingAdvanceRange {

    /** One LTE TA unit in one-way metres (16·Ts round-trip, halved). */
    const val METRES_PER_TA_UNIT = 78.125

    fun distanceMetres(ta: Int): Double = ta * METRES_PER_TA_UNIT

    data class CellTa(
        val pci: Int?,
        val earfcn: Int?,
        val samples: Int,
        val medianTa: Int,
        val medianDistanceM: Int,
        val maxDistanceM: Int,
    )

    data class Result(
        /** LTE-serving samples that carried a timing advance — the denominator. */
        val samples: Int,
        val minDistanceM: Int,
        val medianDistanceM: Int,
        val maxDistanceM: Int,
        /** TA == 0 samples (at/under the cell, or a modem reporting the floor). Called out separately. */
        val zeroTaSamples: Int,
        val byCell: List<CellTa>,
    ) {
        val hasData: Boolean get() = samples > 0
    }

    fun analyse(points: List<TrackPoint>): Result {
        val withTa = points.filter { it.lteTimingAdvance != null }
        if (withTa.isEmpty()) return Result(0, 0, 0, 0, 0, emptyList())

        val tas = withTa.map { it.lteTimingAdvance!! }.sorted()
        val distances = tas.map { distanceMetres(it) }

        val byCell = withTa
            .groupBy { it.lteServingPci ?: it.servingPci }
            .map { (pci, pts) ->
                val cellTas = pts.mapNotNull { it.lteTimingAdvance }.sorted()
                val medTa = cellTas[cellTas.size / 2]
                CellTa(
                    pci = pci,
                    earfcn = pts.firstNotNullOfOrNull { it.lteServingEarfcn },
                    samples = cellTas.size,
                    medianTa = medTa,
                    medianDistanceM = distanceMetres(medTa).roundToInt(),
                    maxDistanceM = distanceMetres(cellTas.last()).roundToInt(),
                )
            }
            .sortedByDescending { it.medianDistanceM }

        return Result(
            samples = withTa.size,
            minDistanceM = distances.first().roundToInt(),
            medianDistanceM = distances[distances.size / 2].roundToInt(),
            maxDistanceM = distances.last().roundToInt(),
            zeroTaSamples = tas.count { it == 0 },
            byCell = byCell,
        )
    }
}
