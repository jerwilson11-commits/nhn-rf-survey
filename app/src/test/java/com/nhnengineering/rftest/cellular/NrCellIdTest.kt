package com.nhnengineering.rftest.cellular

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NrCellIdTest {

    @Test
    fun `splits the observed T-Mobile identity the same way WalkTest does`() {
        // The one real cross-check available: their report printed gNodeB 1609421 for the same
        // cell this app logged as NCI 6592188719 on the same handset on the same afternoon.
        val s = NrCellId.split(6_592_188_719L)!!

        assertEquals(1_609_421L, s.gnbId)
        assertEquals(303, s.cellId)
        assertEquals(24, s.gnbIdBits)
    }

    @Test
    fun `the boundary is configurable, and moving it changes both halves`() {
        // The point of carrying gnbIdBits: an operator using 22 bits gets a different, equally
        // valid answer from the same NCI. Neither is derivable from the identity itself.
        val a = NrCellId.split(6_592_188_719L, gnbIdBits = 22)!!
        val b = NrCellId.split(6_592_188_719L, gnbIdBits = 32)!!

        assertEquals(402_355L, a.gnbId)
        assertEquals(4_399L, a.cellId.toLong())
        assertEquals(412_011_794L, b.gnbId)
        assertEquals(15, b.cellId)
    }

    @Test
    fun `an identity that cannot be 36 bits is refused rather than halved`() {
        // A wrong gNB ID is worse than none: it reads as a real site and gets looked up as one.
        assertNull(NrCellId.split(1L shl 36))
        assertNull(NrCellId.split(-1L))
        assertNull(NrCellId.split(null))
    }

    @Test
    fun `a boundary outside the standard is refused`() {
        assertNull(NrCellId.split(6_592_188_719L, gnbIdBits = 21))
        assertNull(NrCellId.split(6_592_188_719L, gnbIdBits = 33))
    }

    @Test
    fun `the largest valid identity still splits`() {
        val s = NrCellId.split((1L shl 36) - 1)!!
        assertEquals((1L shl 24) - 1, s.gnbId)
        assertEquals(4095, s.cellId)
    }
}
