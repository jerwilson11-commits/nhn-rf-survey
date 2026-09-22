package com.nhnengineering.rftest.cellular

import com.nhnengineering.rftest.model.CellSource
import com.nhnengineering.rftest.model.NeighborCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the fix for the defect the walk of 2026-09-22 exposed.
 *
 * That walk recorded neighbour PCIs {67, 82, 173, 186, 421, 469} under channel 1300 *and* under
 * channel 66836, with matching proportions -- one physical neighbourhood on Band 66, counted
 * twice, because the modem's own QMI message truncates EARFCN to 16 bits (66836 mod 65536 =
 * 1300, confirmed against libqmi's service definition) while Android's CellInfoLte reports the
 * true 32-bit value. Fixed by recognising the relationship between two sightings, never by
 * judging a channel value on its own -- 1300 is independently a legitimate Band 3 EARFCN.
 */
class LteEarfcnTruncationTest {

    private fun lte(pci: Int, channel: Int, source: CellSource = CellSource.MODEM) = NeighborCell(
        rat = "LTE", pci = pci, channel = channel, band = null,
        rsrpDbm = -100, rsrqDb = -15, source = source,
    )

    // ---- the walk's own numbers ---------------------------------------------

    @Test
    fun `the walk's PCIs reconcile to the wide channel`() {
        for (pci in listOf(67, 82, 173, 186, 421, 469)) {
            val seenNow = listOf(lte(pci, 1300), lte(pci, 66836, CellSource.ANDROID))
            val r = LteEarfcnTruncation.reconcile(seenNow)

            assertEquals("PCI $pci should merge to one channel", 1, r.map { it.channel }.distinct().size)
            assertEquals(66836, r.first().channel)
        }
    }

    @Test
    fun `only the truncated entry is rewritten, the wide one is untouched`() {
        val wide = lte(67, 66836, CellSource.ANDROID)
        val r = LteEarfcnTruncation.reconcile(listOf(lte(67, 1300), wide))

        assertSame("The wide entry must not be reallocated", wide, r[1])
        assertEquals(66836, r[0].channel)
    }

    // ---- the mechanism, not just the anecdote --------------------------------

    @Test
    fun `a narrow channel with no wide sighting anywhere is left alone`() {
        // Nothing has ever reported the true channel, so there is nothing to recover from.
        // Inventing one here would be worse than the duplicate this class exists to remove.
        val r = LteEarfcnTruncation.reconcile(listOf(lte(67, 1300)))

        assertEquals(1300, r.single().channel)
    }

    @Test
    fun `a wide sighting only in the retained set still reconciles this sample`() {
        // Android saw the true channel two samples ago and it is still retained; this sample the
        // modem alone reports the truncated one. The fix must reach into retained state, not
        // just the current report, or the merge still produces two entries.
        val retained = listOf(lte(67, 66836, CellSource.ANDROID))
        val r = LteEarfcnTruncation.reconcile(listOf(lte(67, 1300)), retained)

        assertEquals(66836, r.single().channel)
    }

    @Test
    fun `a channel value alone is never enough, even one that looks small`() {
        // 1300 is a legitimate independent EARFCN (Band 3 downlink starts at offset 1200). With
        // no wide sighting for this PCI at all, it must be read as exactly what it says.
        val r = LteEarfcnTruncation.reconcile(listOf(lte(200, 1300)))

        assertEquals(1300, r.single().channel)
    }

    @Test
    fun `two different wide channels for one PCI is ambiguous and is not touched`() {
        // If this PCI genuinely appears on two different wide-band carriers in the same pass,
        // there is no way to know which one (if either) the narrow entry belongs to. Guessing
        // would risk merging two real, distinct cells into one.
        val seenNow = listOf(
            lte(67, 1300),
            lte(67, 66836, CellSource.ANDROID),
            lte(67, 133172, CellSource.ANDROID),
        )
        val r = LteEarfcnTruncation.reconcile(seenNow)

        assertEquals(1300, r.first { it.channel != 66836 && it.channel != 133172 }.channel)
    }

    @Test
    fun `a different PCI's wide channel does not leak across`() {
        val seenNow = listOf(lte(67, 66836, CellSource.ANDROID), lte(99, 1300))
        val r = LteEarfcnTruncation.reconcile(seenNow)

        assertEquals(1300, r.first { it.pci == 99 }.channel)
    }

    @Test
    fun `NR is never touched, only LTE`() {
        val nr = NeighborCell(
            rat = "5G NR", pci = 67, channel = 1300, band = null,
            rsrpDbm = -100, rsrqDb = -15, source = CellSource.MODEM,
        )
        val wideLte = lte(67, 66836, CellSource.ANDROID)
        val r = LteEarfcnTruncation.reconcile(listOf(nr, wideLte))

        assertEquals(1300, r.first { it.rat == "5G NR" }.channel)
    }

    @Test
    fun `cells with no pci or no channel pass through untouched`() {
        val noPci = lte(0, 1300).copy(pci = null)
        val noChannel = lte(67, 1300).copy(channel = null)
        val wide = lte(67, 66836, CellSource.ANDROID)

        val r = LteEarfcnTruncation.reconcile(listOf(noPci, noChannel, wide))

        assertEquals(null, r.first { it.pci == null }.pci)
        assertEquals(null, r.first { it.channel == null }.channel)
    }

    @Test
    fun `an already-wide channel is never rewritten to another wide value`() {
        // Only a narrow (sub-65536) channel is ever a candidate for rewriting.
        val r = LteEarfcnTruncation.reconcile(listOf(lte(67, 66836), lte(67, 66836, CellSource.ANDROID)))

        assertEquals(setOf(66836), r.map { it.channel }.toSet())
    }

    @Test
    fun `no wide channels present at all is a no-op`() {
        val seenNow = listOf(lte(1, 100), lte(2, 200), lte(3, 300))
        assertEquals(seenNow, LteEarfcnTruncation.reconcile(seenNow))
    }
}
