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

    // ---- NSA: the scope that actually reaches n41 at a site with no SA n41 ------

    @Test
    fun `NSA validates against its own supported set, independent of SA`() {
        // n41 reachable in NSA but absent from the SA list (this project's own 2026-09-26 test
        // desk) must still validate for an NSA-only request.
        assertNull(BandLock.validate(emptySet(), emptySet(), lte, supportedNrSa = setOf(25), nrNsa = setOf(41), supportedNrNsa = nr))
    }

    @Test
    fun `an NSA band the modem does not list is refused and named`() {
        val msg = BandLock.validate(emptySet(), emptySet(), lte, nr, nrNsa = setOf(77), supportedNrNsa = nr)
        assertTrue(msg!!.contains("n77"))
        assertTrue(msg.contains("NSA"))
    }

    @Test
    fun `SA and NSA locked to the same band collapse to one token, not two`() {
        // BandLockCheck matches by band existence, not by which NR scope carried it, so a token
        // per scope would be redundant -- and if it were duplicated the label would misleadingly
        // suggest two separate restrictions were requested rather than one band on both scopes.
        val label = BandLock.verifiedLabel(emptySet(), setOf(41), setOf(41))
        assertEquals("n41 (locked and verified by this app)", label)
        assertEquals(listOf("n41"), BandLock.tokens(label))
    }

    @Test
    fun `an NSA-only lock is not contradicted by the SA leg, and vice versa`() {
        // Mirrors "an LTE-only lock is not contradicted by the NR leg" -- BandLockCheck's
        // same-technology scoping does not know about SA vs NSA, only B vs n, so this is really
        // checking that an NR lock (either scope) does not get compared against unrelated bands.
        val label = BandLock.verifiedLabel(emptySet(), emptySet(), setOf(41))
        assertTrue(BandLockCheck.check(listOf(label), listOf("n41")).isEmpty())
    }

    @Test
    fun `an NSA lock whose band never appeared is caught the same as an SA one`() {
        val label = BandLock.verifiedLabel(emptySet(), emptySet(), setOf(41))
        assertTrue(BandLockCheck.check(listOf(label), listOf("n25")).any { it.headline.contains("never seen") })
    }
}
