package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import kotlin.math.abs

/**
 * PCI planning conflicts — co-channel cells whose Physical Cell IDs collide on a reference-signal
 * dimension, which a coverage plot never reveals and which free apps don't compute.
 *
 * The gating conflict is **PCI mod 3**: it sets the PSS and the downlink reference-signal position, so
 * two *co-channel* cells sharing it interfere on the DL RS (LTE and NR alike). Two refinements, both
 * LTE-only and both **mathematically subsets of mod 3** (6 and 30 are multiples of 3, so they can only
 * occur on a pair that already collides on mod 3), are flagged on top of it:
 *  - **mod 6** — the LTE cell-specific RS frequency shift (CRS) also collides.
 *  - **mod 30** — the LTE uplink RS (DMRS) sequence group also collides.
 *
 * Works on whatever neighbour list a sample carried — the Android public-API cells (Field) or the
 * fuller modem neighbour read (Pro). Soundness: only **co-channel** cells with a real level are
 * compared (a cross-frequency pair cannot RS-interfere), and the closest they came in strength
 * (`minMarginDb`) is kept so the report shows severity, not just existence.
 *
 * What this deliberately does NOT claim: true PCI **collision/confusion** (two *distinct* physical
 * cells sharing one PCI). That needs each neighbour's cell identity (NCI), which neighbour measurement
 * reports do not carry — so it is not guessed here.
 */
object PciConflicts {

    data class Conflict(
        val channel: Int?,
        val band: String?,
        val pciA: Int,
        val pciB: Int,
        /** Always true for a reported conflict — the gating DL RS / PSS collision. */
        val mod3: Boolean,
        /** LTE CRS (mod 6) also collides. */
        val mod6: Boolean,
        /** LTE uplink RS (mod 30) also collides. */
        val mod30: Boolean,
        /** Samples in which both cells were seen co-channel with a level. */
        val samples: Int,
        /** Smallest level difference observed between the two — smaller means worse interference. */
        val minMarginDb: Int?,
    ) {
        /** 0 (mod-3 only) … 2 (also mod-6 and mod-30). Higher is worse. */
        val severity: Int get() = (if (mod6) 1 else 0) + (if (mod30) 1 else 0)
    }

    data class Summary(val analysed: Int, val conflicts: List<Conflict>) {
        val hasConflicts: Boolean get() = conflicts.isNotEmpty()
    }

    private data class Key(val channel: Int?, val pciLo: Int, val pciHi: Int, val band: String?)
    private class Agg(var samples: Int = 0, var minMargin: Int? = null, var mod6: Boolean = false, var mod30: Boolean = false)

    private fun isLte(band: String?) = band?.startsWith("B") == true

    fun analyse(points: List<TrackPoint>, minDetectableDbm: Int = -115): Summary {
        val agg = HashMap<Key, Agg>()
        for (p in points) {
            val cs = p.cells.filter { it.pci != null && it.rsrpDbm != null && it.rsrpDbm >= minDetectableDbm }
            for (i in cs.indices) for (j in i + 1 until cs.size) {
                val a = cs[i]
                val b = cs[j]
                if (a.channel != b.channel) continue           // only co-channel can RS-interfere
                val pa = a.pci!!
                val pb = b.pci!!
                if (pa == pb) continue                          // same PCI+channel: a dup report, not a planning conflict
                if (pa % 3 != pb % 3) continue                  // gating: DL RS / PSS collision
                val lte = isLte(a.band) && isLte(b.band)
                val mod6 = lte && pa % 6 == pb % 6
                val mod30 = lte && pa % 30 == pb % 30
                val margin = abs(a.rsrpDbm!! - b.rsrpDbm!!)
                val key = Key(a.channel, minOf(pa, pb), maxOf(pa, pb), a.band ?: b.band)
                val g = agg.getOrPut(key) { Agg() }
                g.samples++
                if (g.minMargin == null || margin < g.minMargin!!) g.minMargin = margin
                g.mod6 = g.mod6 || mod6
                g.mod30 = g.mod30 || mod30
            }
        }
        val conflicts = agg.entries.map { (k, v) ->
            Conflict(k.channel, k.band, k.pciLo, k.pciHi, mod3 = true, mod6 = v.mod6, mod30 = v.mod30, v.samples, v.minMargin)
        }.sortedWith(
            compareByDescending<Conflict> { it.severity }
                .thenBy { it.minMarginDb ?: Int.MAX_VALUE }
                .thenByDescending { it.samples },
        )
        return Summary(points.size, conflicts)
    }
}
