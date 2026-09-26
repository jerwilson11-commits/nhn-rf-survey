package com.nhnengineering.rftest.cellular

import com.nhnengineering.rftest.report.BandLockCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandLockTest {

    private val lte = setOf(1, 2, 4, 5, 12, 66)
    private val nr = setOf(2, 25, 41, 71)

    @Test
    fun `a reasonable request validates`() {
        assertNull(BandLock.validate(setOf(4), emptySet(), lte, nr))
        assertNull(BandLock.validate(emptySet(), setOf(41), lte, nr))
        assertNull(BandLock.validate(setOf(2), setOf(25), lte, nr))
    }

    @Test
    fun `nothing selected is refused`() {
        assertNotNull(BandLock.validate(emptySet(), emptySet(), lte, nr))
    }

    @Test
    fun `a band the modem does not list is refused and named`() {
        assertTrue(BandLock.validate(setOf(7), emptySet(), lte, nr)!!.contains("B7"))
        assertTrue(BandLock.validate(emptySet(), setOf(77), lte, nr)!!.contains("n77"))
    }

    @Test
    fun `only bands 1 to 64 are selectable for LTE`() {
        assertEquals(setOf(2, 4), BandLock.selectableLte(setOf(2, 4, 66, 71)))
    }

    @Test
    fun `the label uses the serving-cell band spelling`() {
        // Must match what the report compares against: LTE "B4", NR "n41".
        assertEquals(
            "B4, n41 (locked and verified by this app)",
            BandLock.verifiedLabel(setOf(4), setOf(41)),
        )
    }

    @Test
    fun `tokens come back out of a label and only out of a verified one`() {
        assertEquals(listOf("B2", "B4", "n41"), BandLock.tokens(BandLock.verifiedLabel(setOf(4, 2), setOf(41))))
        assertTrue(BandLock.tokens("n41").isEmpty())
        assertFalse(BandLock.isVerifiedLabel("n41, n25"))
    }

    // ---- the report cross-check --------------------------------------------------

    @Test
    fun `an app lock that held says nothing`() {
        val label = BandLock.verifiedLabel(setOf(4), emptySet())
        assertTrue(BandLockCheck.check(listOf(label), listOf("B4")).isEmpty())
    }

    @Test
    fun `an LTE-only lock is not contradicted by the NR leg`() {
        // With LTE held to B4 and NR left free, an observed n41 is NR working, not a failed lock.
        val label = BandLock.verifiedLabel(setOf(4), emptySet())
        assertTrue(BandLockCheck.check(listOf(label), listOf("B4", "n41")).isEmpty())
    }

    @Test
    fun `an LTE band outside the app lock is still caught`() {
        val label = BandLock.verifiedLabel(setOf(4), emptySet())
        val f = BandLockCheck.check(listOf(label), listOf("B4", "B2"))

        assertTrue(f.single().headline.contains("excludes"))
        assertTrue(f.single().detail.contains("B2"))
    }

    @Test
    fun `an app lock whose band never appeared is caught`() {
        val label = BandLock.verifiedLabel(emptySet(), setOf(41))
        assertTrue(BandLockCheck.check(listOf(label), listOf("n25")).any { it.headline.contains("never seen") })
    }

    @Test
    fun `a free-text declaration still compares the whole session`() {
        // Old behaviour, kept: free text does not say which technology it means.
        assertTrue(BandLockCheck.check(listOf("n41"), listOf("n41", "B2")).any { it.headline.contains("excludes") })
    }
}
