package com.nhnengineering.rftest.cellular

import android.telephony.TelephonyManager
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
 */
class TechnologyLockTest {

    private val nr = TelephonyManager.NETWORK_TYPE_BITMASK_NR
    private val lte = TelephonyManager.NETWORK_TYPE_BITMASK_LTE
    private val lteCa = TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA
    private val gsm = TelephonyManager.NETWORK_TYPE_BITMASK_GSM

    // ---- what the technologies mean ---------------------------------------

    @Test
    fun `NR only forces standalone, because NSA needs an LTE anchor`() {
        // The one thing this feature can say about SA vs NSA. NSA is NR carried on an LTE
        // anchor, so a mask with NR and without LTE cannot be satisfied by NSA at all.
        assertTrue(TechnologyLock.Technology.NR_ONLY.forcesStandalone)
    }

    @Test
    fun `5G plus LTE leaves NSA available and so forces nothing`() {
        assertFalse(TechnologyLock.Technology.NR_AND_LTE.forcesStandalone)
    }

    @Test
    fun `LTE only excludes NR entirely, aggregated carriers included`() {
        val mask = TechnologyLock.Technology.LTE_ONLY.mask

        assertEquals("No NR bit may survive.", 0L, mask and nr)
        assertTrue("Plain LTE must be allowed.", mask and lte != 0L)
        assertTrue("LTE_CA is tracked separately and must not be dropped.", mask and lteCa != 0L)
        assertFalse(TechnologyLock.Technology.LTE_ONLY.forcesStandalone)
    }

    @Test
    fun `a mask with neither NR nor LTE is not standalone-forcing`() {
        assertFalse(TechnologyLock.forcesStandalone(gsm))
        assertFalse(TechnologyLock.forcesStandalone(0L))
    }

    // ---- what a lock can actually change ----------------------------------

    @Test
    fun `a lock can only ever subtract, never add`() {
        // The framework ANDs the reasons. Asking for more than the carrier config permits does
        // not widen anything, which is why the app reports the effective mask and not the
        // requested one.
        val carrierAllows = lte or lteCa
        val weRequest = nr or lte or lteCa

        assertEquals(carrierAllows, TechnologyLock.effective(listOf(weRequest, carrierAllows)))
    }

    @Test
    fun `requesting a technology the carrier excludes leaves the radio with nothing`() {
        // The case that has to be caught before the lock is applied rather than after. A handset
        // whose carrier config has no NR, locked to NR only, has no technology left at all --
        // which on a real phone means no data service and no explanation on screen.
        val carrierAllows = lte or lteCa

        val preview = TechnologyLock.previewLock(
            requested = TechnologyLock.Technology.NR_ONLY.mask,
            otherReasons = listOf(carrierAllows),
        )

        assertEquals(0L, preview)
        assertEquals("none", TechnologyLock.describeMask(preview))
    }

    @Test
    fun `a request the carrier permits survives intact`() {
        val carrierAllows = nr or lte or lteCa

        val preview = TechnologyLock.previewLock(
            requested = TechnologyLock.Technology.NR_ONLY.mask,
            otherReasons = listOf(carrierAllows),
        )

        assertEquals(nr, preview)
        assertTrue(TechnologyLock.forcesStandalone(preview))
    }

    @Test
    fun `every reason in force is applied, not just the first`() {
        val preview = TechnologyLock.previewLock(
            requested = nr or lte,
            otherReasons = listOf(nr or lte or gsm, lte or gsm),
        )

        assertEquals(lte, preview)
    }

    @Test
    fun `no reasons at all allows nothing rather than everything`() {
        // A guard, not a scenario: an empty reduce must not fall through to a permissive
        // default, because the permissive default here is "let the radio do anything".
        assertEquals(0L, TechnologyLock.effective(emptyList()))
    }

    // ---- whether the lock actually held ------------------------------------

    @Test
    fun `the OnePlus case - written, accepted, and silently reverted`() {
        // Measured 2026-09-21. The mask is applied and a vendor layer recomputes it straight back
        // to the handset default; nothing throws and no error is returned. Reporting this as a
        // lock would label every subsequent reading with a constraint that was not in force.
        val requested = TechnologyLock.Technology.LTE_ONLY.mask
        val observedAfterRevert = 916479L   // everything the handset supports, the OOS default

        assertFalse(TechnologyLock.lockHeld(requested, observedAfterRevert))
    }

    @Test
    fun `a lock that held is confirmed`() {
        val requested = TechnologyLock.Technology.LTE_ONLY.mask

        assertTrue(TechnologyLock.lockHeld(requested, requested))
    }

    @Test
    fun `narrower than requested still counts as held`() {
        // The carrier's own reason is ANDed in, so the radio legitimately ends up with less than
        // was asked for. That is the lock working, not failing.
        val requested = TechnologyLock.Technology.LTE_AND_LEGACY.mask

        assertTrue(TechnologyLock.lockHeld(requested, lte))
    }

    @Test
    fun `one technology outside the request is enough to fail it`() {
        // The distinction that matters: not "did anything change" but "is anything still allowed
        // that we asked to exclude". A single stray NR bit means 5G is still on the table.
        val requested = TechnologyLock.Technology.LTE_ONLY.mask

        assertFalse(TechnologyLock.lockHeld(requested, lte or nr))
    }

    @Test
    fun `an empty allowance is not a successful lock`() {
        // Technically a subset of anything, and in practice a handset with no service. Treating
        // it as success would report a working lock over a dead radio.
        assertFalse(TechnologyLock.lockHeld(TechnologyLock.Technology.NR_ONLY.mask, 0L))
    }

    // ---- how it reads ------------------------------------------------------

    @Test
    fun `masks are described in technologies, not bits`() {
        assertEquals("5G NR, LTE", TechnologyLock.describeMask(nr or lte))
        assertEquals("5G NR", TechnologyLock.describeMask(nr))
        assertEquals("3G/2G", TechnologyLock.describeMask(gsm))
        assertEquals("none", TechnologyLock.describeMask(0L))
    }

    @Test
    fun `aggregated LTE is not announced as a second technology`() {
        // LTE and LTE_CA are two bits for one thing an engineer would call LTE.
        assertEquals("LTE", TechnologyLock.describeMask(lte or lteCa))
    }

    @Test
    fun `every technology offered describes itself without saying none`() {
        for (t in TechnologyLock.Technology.entries) {
            val described = TechnologyLock.describeMask(t.mask)
            assertTrue(
                "${t.name} describes as \"$described\", which tells an engineer nothing.",
                described != "none" && described != "other",
            )
        }
    }

    @Test
    fun `the choices that can cost service carry a warning`() {
        // NR_ONLY can leave a handset with no data where there is no SA coverage; LTE_ONLY gives
        // up 5G. Both are legitimate things to ask for and neither should be a surprise.
        assertTrue(TechnologyLock.Technology.NR_ONLY.warning != null)
        assertTrue(TechnologyLock.Technology.LTE_ONLY.warning != null)
    }
}
