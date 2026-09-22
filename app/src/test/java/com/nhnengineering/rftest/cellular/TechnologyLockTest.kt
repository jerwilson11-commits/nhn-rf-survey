package com.nhnengineering.rftest.cellular

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what a technology lock can and cannot promise.
 *
 * The lock exists to answer questions a roaming handset cannot: what LTE looks like at a site,
 * and whether standalone NR is usable there at all. Both answers are worthless if the lock is
 * reported as applied when the modem quietly ignored it, so the arithmetic that decides whether a
 * request survives -- the AND across every reason in force -- is tested here directly rather than
 * inferred from a handset that happened to cooperate.
 *
 * Bit values are QMI's Mode Preference numbering, not Android's `TelephonyManager` bitmask -- the
 * two are unrelated encodings for the same idea. `0x005F` and `0x0010` are the two values this
 * project has actually observed on the handset: the baseline allowance, and the mask that moved
 * the radio to LTE in under five seconds.
 */
class TechnologyLockTest {

    private val nr = TechnologyLock.RAT_NR
    private val lte = TechnologyLock.RAT_LTE
    private val legacy = 0x0004 // GSM bit, part of the observed baseline

    // ---- what the technologies mean ---------------------------------------

    @Test
    fun `SA only forces standalone, because NSA needs an LTE anchor`() {
        // The one thing this feature can say about SA vs NSA. NSA is NR carried on an LTE
        // anchor, so a mask with NR and without LTE cannot be satisfied by NSA at all.
        assertTrue(TechnologyLock.Technology.NR_ONLY.forcesStandalone)
    }

    @Test
    fun `LTE only excludes NR entirely`() {
        val mask = TechnologyLock.Technology.LTE_ONLY.modePref

        assertEquals("No NR bit may survive.", 0, mask and nr)
        assertTrue("LTE must be allowed.", mask and lte != 0)
        assertFalse(TechnologyLock.Technology.LTE_ONLY.forcesStandalone)
    }

    @Test
    fun `a mask with neither NR nor LTE is not standalone-forcing`() {
        assertFalse(TechnologyLock.forcesStandalone(legacy))
        assertFalse(TechnologyLock.forcesStandalone(0))
    }

    @Test
    fun `NR and LTE together forces nothing, because NSA is then available too`() {
        assertFalse(TechnologyLock.forcesStandalone(nr or lte))
    }

    // ---- what a lock can actually change ----------------------------------

    @Test
    fun `a lock can only ever subtract, never add`() {
        // The modem ANDs its own reasons together. Asking for more than the carrier config
        // permits does not widen anything, which is why the app reports the effective mask and
        // not the requested one.
        val carrierAllows = lte
        val weRequest = nr or lte

        assertEquals(carrierAllows, TechnologyLock.effective(listOf(weRequest, carrierAllows)))
    }

    @Test
    fun `requesting a technology the carrier excludes leaves the radio with nothing`() {
        // The case that has to be caught before the lock is applied rather than after. A handset
        // whose carrier config has no NR, locked to SA only, has no technology left at all --
        // which on a real phone means no data service and no explanation on screen.
        val carrierAllows = lte

        val preview = TechnologyLock.previewLock(
            requested = TechnologyLock.Technology.NR_ONLY.modePref,
            otherReasons = listOf(carrierAllows),
        )

        assertEquals(0, preview)
        assertEquals("none", TechnologyLock.describeMask(preview))
    }

    @Test
    fun `a request the carrier permits survives intact`() {
        val carrierAllows = nr or lte

        val preview = TechnologyLock.previewLock(
            requested = TechnologyLock.Technology.NR_ONLY.modePref,
            otherReasons = listOf(carrierAllows),
        )

        assertEquals(nr, preview)
        assertTrue(TechnologyLock.forcesStandalone(preview))
    }

    @Test
    fun `every reason in force is applied, not just the first`() {
        val preview = TechnologyLock.previewLock(
            requested = nr or lte,
            otherReasons = listOf(nr or lte or legacy, lte or legacy),
        )

        assertEquals(lte, preview)
    }

    @Test
    fun `no reasons at all allows nothing rather than everything`() {
        // A guard, not a scenario: an empty reduce must not fall through to a permissive
        // default, because the permissive default here is "let the radio do anything".
        assertEquals(0, TechnologyLock.effective(emptyList()))
    }

    // ---- whether the lock actually held ------------------------------------

    @Test
    fun `the OnePlus framework case - written, accepted, and silently reverted`() {
        // Measured 2026-09-21 against the now-abandoned framework mechanism. The mask was
        // applied and a vendor layer recomputed it straight back to the handset default;
        // nothing threw and no error was returned. This is exactly the pattern lockHeld exists
        // to catch, whichever transport a future change might route through.
        val requested = TechnologyLock.Technology.LTE_ONLY.modePref
        val observedAfterRevert = 0x005F // everything the handset supports, the OOS default

        assertFalse(TechnologyLock.lockHeld(requested, observedAfterRevert))
    }

    @Test
    fun `a lock that held is confirmed`() {
        val requested = TechnologyLock.Technology.LTE_ONLY.modePref

        assertTrue(TechnologyLock.lockHeld(requested, requested))
    }

    @Test
    fun `narrower than requested still counts as held`() {
        // The carrier's own reason is ANDed in, so the radio legitimately ends up with less than
        // was asked for. That is the lock working, not failing.
        val requested = lte or legacy

        assertTrue(TechnologyLock.lockHeld(requested, lte))
    }

    @Test
    fun `one technology outside the request is enough to fail it`() {
        // The distinction that matters: not "did anything change" but "is anything still allowed
        // that we asked to exclude". A single stray NR bit means 5G is still on the table.
        val requested = TechnologyLock.Technology.LTE_ONLY.modePref

        assertFalse(TechnologyLock.lockHeld(requested, lte or nr))
    }

    @Test
    fun `an empty allowance is not a successful lock`() {
        // Technically a subset of anything, and in practice a handset with no service. Treating
        // it as success would report a working lock over a dead radio.
        assertFalse(TechnologyLock.lockHeld(TechnologyLock.Technology.NR_ONLY.modePref, 0))
    }

    // ---- how it reads --------------------------------------------------------

    @Test
    fun `the observed baseline decodes to every family it actually contains`() {
        // 0x005F, read back from the handset on 2026-09-21 and again on 2026-09-22 after a
        // reboot. Confirms the bit layout end to end, not just the two bits this app writes.
        assertEquals("5G NR, LTE, 3G/2G", TechnologyLock.describeMask(0x005F))
    }

    @Test
    fun `masks are described in technologies, not bits`() {
        assertEquals("5G NR, LTE", TechnologyLock.describeMask(nr or lte))
        assertEquals("5G NR", TechnologyLock.describeMask(nr))
        assertEquals("3G/2G", TechnologyLock.describeMask(legacy))
        assertEquals("none", TechnologyLock.describeMask(0))
    }

    @Test
    fun `every technology offered describes itself without saying none`() {
        for (t in TechnologyLock.Technology.entries) {
            val described = TechnologyLock.describeMask(t.modePref)
            assertTrue(
                "${t.name} describes as \"$described\", which tells an engineer nothing.",
                described != "none" && described != "other",
            )
        }
    }

    @Test
    fun `the choice that can cost service carries a warning`() {
        // SA only can leave a handset with no data where there is no SA coverage, which is a
        // real and observed risk on this project's own test sites.
        assertTrue(TechnologyLock.Technology.NR_ONLY.warning != null)
    }

    @Test
    fun `exactly the two technologies verified on this handset are offered`() {
        // NSA-only needs a second lever -- the NR5G SA Band Preference TLV -- that has not been
        // tried yet (see the class doc). Until it is verified, offering it as a Technology here
        // would be a claim this project has specifically not earned.
        assertEquals(
            setOf("5G SA only", "LTE only"),
            TechnologyLock.Technology.entries.map { it.label }.toSet(),
        )
    }
}
