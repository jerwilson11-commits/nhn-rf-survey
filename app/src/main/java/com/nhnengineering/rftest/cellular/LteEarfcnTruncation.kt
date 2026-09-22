package com.nhnengineering.rftest.cellular

import com.nhnengineering.rftest.model.NeighborCell

/**
 * Recovers the true channel for an LTE neighbour whose EARFCN was truncated to 16 bits.
 *
 * ## Why this exists
 *
 * `QMI_NAS_GET_CELL_LOCATION_INFO` represents "EUTRA Absolute RF Channel Number" as `guint16` in
 * both the intrafrequency and interfrequency TLVs -- confirmed against libqmi's own service
 * definition, not inferred from a guess. 3GPP's extended EARFCN range goes well past 65535: Band
 * 66 downlink alone is 66436-67335, entirely above it. So this QMI message cannot represent a
 * Band 66 (or similar) channel at all -- the modem can only report `earfcn mod 65536`. That is a
 * protocol limitation, not a decode bug; [com.nhnengineering.rftest.modem.QmiCellParser] reads
 * the bytes exactly as specified.
 *
 * Android's own `CellInfoLte.getEarfcn()` carries the true 32-bit value. So the same physical
 * cell can appear under two different channel numbers depending which surface reported it. The
 * walk of 2026-09-22 recorded PCIs {67, 82, 173, 186, 421, 469} under channel 1300 *and* under
 * channel 66836, in matching proportion -- one physical neighbourhood, counted twice, because
 * [CellularCollector.mergeNeighbors] keys on channel, and channel was not actually a stable
 * identity here.
 *
 * ## Why the fix cannot look at the value alone
 *
 * Channel 1300 is independently a legitimate EARFCN in its own right -- Band 3's downlink range
 * starts at offset 1200 (3GPP TS 36.101 Table 5.7.3-1) -- so nothing about "1300" on its own says
 * it is really "66836". What is recoverable is the *relationship* between two sightings: a
 * neighbour reported at a wide channel (>65535) and one at a narrow channel equal to the wide one
 * modulo 65536, sharing a PCI, cannot both be genuine distinct physical cells. A UE cannot
 * neighbour two different real cells that coincide in every one of LTE's 504 possible PCI values
 * *and* sit exactly a multiple of 65536 apart in EARFCN by chance.
 *
 * So this never touches a narrow channel in isolation. It only rewrites one when a wide sighting
 * for the *same PCI* is already known -- from this report or one still retained -- and it backs
 * off entirely if two different wide channels are seen for one PCI in the same pass, because at
 * that point which one is real is genuinely ambiguous and a guess would be worse than the
 * duplicate it is trying to fix.
 *
 * Scoped to LTE only: NR's own ARFCN field in the modem's log is 32 bits (verified directly --
 * `0xB97F` carries the full channel with no truncation observed), so this class of bug is
 * specific to this one legacy QMI field.
 */
object LteEarfcnTruncation {

    private const val TRUNCATION_MODULUS = 0x1_0000

    /**
     * Rewrites the channel of any LTE neighbour in [seenNow] whose value looks like a 16-bit
     * truncation of a wide channel already seen for the same PCI, in [seenNow] or [retained].
     *
     * Returns a new list; [seenNow] and [retained] are read only. Cells that are not LTE, or that
     * carry no PCI or channel, pass through unchanged.
     */
    fun reconcile(seenNow: List<NeighborCell>, retained: Collection<NeighborCell> = emptyList()): List<NeighborCell> {
        val wideByPci = HashMap<Int, Int>()
        val ambiguousPci = HashSet<Int>()

        fun note(cell: NeighborCell) {
            if (cell.rat != "LTE") return
            val pci = cell.pci ?: return
            val ch = cell.channel ?: return
            if (ch < TRUNCATION_MODULUS) return
            val existing = wideByPci[pci]
            when {
                existing == null -> wideByPci[pci] = ch
                existing != ch -> ambiguousPci += pci
            }
        }
        seenNow.forEach(::note)
        retained.forEach(::note)

        if (wideByPci.isEmpty()) return seenNow

        return seenNow.map { cell ->
            if (cell.rat != "LTE") return@map cell
            val pci = cell.pci ?: return@map cell
            val ch = cell.channel ?: return@map cell
            if (ch >= TRUNCATION_MODULUS) return@map cell
            if (pci in ambiguousPci) return@map cell
            val wide = wideByPci[pci] ?: return@map cell
            if (wide % TRUNCATION_MODULUS == ch) cell.copy(channel = wide) else cell
        }
    }
}
