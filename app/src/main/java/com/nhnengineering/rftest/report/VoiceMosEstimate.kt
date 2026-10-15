package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import kotlin.math.roundToInt

/**
 * Estimated voice quality (roadmap E2) — a VoLTE / VoNR MOS estimate from the ITU-T G.107 E-model,
 * derived from the radio conditions already measured. Field tier, no root.
 *
 * ## What this is, and just as importantly what it is not
 *
 * A passive RF survey does not place a call, so it measures no packets, no loss, no jitter and no
 * mouth-to-ear delay. It therefore **cannot measure MOS**. What it can do is estimate the voice
 * quality the radio conditions would *support*: VoLTE/VoNR link adaptation holds residual block
 * error low until SINR falls, and below that the effective packet loss — the dominant driver of
 * conversational quality — climbs. So this maps the measured serving **SINR** to an effective loss,
 * runs it through the E-model for the assumed codec, and converts the resulting R-factor to MOS.
 *
 * The report states this plainly: it is an **estimate of the voice quality the coverage can support,
 * from radio conditions only**, not a measured call score. It assumes a codec per radio technology
 * (AMR-WB for VoLTE, EVS for VoNR) and a fixed typical one-way delay; it does not model the core
 * network, jitter buffer, or handset. A true MOS needs an active voice test (POLQA/PESQ), which is a
 * separate, licensed piece of work — see the roadmap. Treating this estimate as a measured score is
 * exactly the error the wording is written to prevent.
 *
 * ## The model (narrowband R-scale, G.107 / G.113)
 *
 *   R   = Ro − Id − Ie_eff        (Is = 0 and A = 0: no loudness/quantisation impairment, no advantage)
 *   Ie_eff = Ie + (95 − Ie) · Ppl / (Ppl + Bpl)      (random loss, G.107 Annex; burst ratio = 1)
 *   MOS = 1 + 0.035·R + 7e-6·R·(R−60)·(100−R)         (clamped to [1, 4.5])
 *
 * Ro = 93.2. Ppl (effective loss %) comes from [lossForSinr]. Codec Ie/Bpl and the assumed delay
 * impairment Id are per [VoiceTech]. The constants are the published reference values; the SINR→loss
 * curve is the one stated convention, chosen to be monotonic and to cross ~2 % at the VoLTE operating
 * point and ~20 % where a call becomes unusable.
 */
object VoiceMosEstimate {

    enum class VoiceTech(val label: String, val codec: String, val ie: Double, val bpl: Double, val idDelay: Double) {
        // AMR-WB, the VoLTE wideband codec. EVS (VoNR) is more loss-robust (higher Bpl) and VoNR's
        // lower latency gives a smaller delay impairment.
        VOLTE("VoLTE", "AMR-WB", ie = 0.0, bpl = 17.0, idDelay = 6.0),
        VONR("VoNR", "EVS", ie = 0.0, bpl = 20.0, idDelay = 4.0),
    }

    private const val RO = 93.2

    /** Toll-quality-ish floor: at/above this estimated MOS a call is comfortably intelligible. */
    const val ACCEPTABLE_MOS = 3.6

    /**
     * Which voice stack carries a call on this radio technology. 5G SA → VoNR; LTE and 5G NSA →
     * VoLTE (NSA voice rides the LTE anchor). WCDMA/GSM are circuit-switched and out of scope.
     */
    fun voiceTechFor(rat: String?): VoiceTech? {
        val r = rat?.trim() ?: return null
        return when {
            // NSA first: "NSA" contains the substring "SA", so it must be ruled out before the SA test.
            r.contains("NSA", true) -> VoiceTech.VOLTE
            r.equals("5G SA", true) || r.contains("NR SA", true) ||
                (r.contains("5G", true) && r.endsWith("SA", true)) -> VoiceTech.VONR
            r.equals("LTE", true) -> VoiceTech.VOLTE
            else -> null
        }
    }

    /** Effective packet-loss % as a function of serving SINR (dB). Piecewise-linear, clamped. */
    fun lossForSinr(sinrDb: Double): Double {
        val knots = listOf(
            -10.0 to 40.0, -6.0 to 20.0, -3.0 to 10.0, 0.0 to 4.0,
            2.0 to 2.0, 5.0 to 0.5, 10.0 to 0.0,
        )
        if (sinrDb <= knots.first().first) return knots.first().second
        if (sinrDb >= knots.last().first) return knots.last().second
        for (i in 1 until knots.size) {
            val (x1, y1) = knots[i - 1]
            val (x2, y2) = knots[i]
            if (sinrDb <= x2) {
                val t = (sinrDb - x1) / (x2 - x1)
                return y1 + t * (y2 - y1)
            }
        }
        return knots.last().second
    }

    /** E-model R-factor → MOS (narrowband conversion, clamped to [1.0, 4.5]). */
    fun mosForR(r: Double): Double = when {
        r <= 0 -> 1.0
        r >= 100 -> 4.5
        else -> (1 + 0.035 * r + 7e-6 * r * (r - 60) * (100 - r)).coerceIn(1.0, 4.5)
    }

    /** Estimated MOS for a serving SINR under a given voice technology. */
    fun estimateMos(sinrDb: Int, tech: VoiceTech): Double {
        val ppl = lossForSinr(sinrDb.toDouble())
        val ieEff = tech.ie + (95 - tech.ie) * ppl / (ppl + tech.bpl)
        val r = RO - tech.idDelay - ieEff
        return mosForR(r)
    }

    data class TechStat(val tech: VoiceTech, val samples: Int, val medianMos: Double, val worstMos: Double)

    data class Result(
        /** Voice-capable samples with a SINR reading — the denominator. */
        val samples: Int,
        val medianMos: Double?,
        val worstMos: Double?,
        /** Of [samples], the share at or above [ACCEPTABLE_MOS]. */
        val pctAcceptable: Double?,
        val byTech: List<TechStat>,
    ) {
        val hasData: Boolean get() = samples > 0
    }

    fun analyse(points: List<TrackPoint>): Result {
        data class Scored(val tech: VoiceTech, val mos: Double)
        val scored = points.mapNotNull { p ->
            val tech = voiceTechFor(p.rat) ?: return@mapNotNull null
            val sinr = p.sinrDb ?: return@mapNotNull null
            Scored(tech, estimateMos(sinr, tech))
        }
        if (scored.isEmpty()) return Result(0, null, null, null, emptyList())

        val all = scored.map { it.mos }.sorted()
        val acceptable = scored.count { it.mos >= ACCEPTABLE_MOS }
        val byTech = VoiceTech.entries.mapNotNull { tech ->
            val m = scored.filter { it.tech == tech }.map { it.mos }.sorted()
            if (m.isEmpty()) null
            else TechStat(tech, m.size, median(m), m.first())
        }
        return Result(
            samples = scored.size,
            medianMos = median(all),
            worstMos = all.first(),
            pctAcceptable = 100.0 * acceptable / scored.size,
            byTech = byTech,
        )
    }

    /** Lower-median of an ascending list, kept to 0.1 MOS (the resolution the estimate can justify). */
    private fun median(ascending: List<Double>): Double =
        (ascending[ascending.size / 2] * 10).roundToInt() / 10.0
}
