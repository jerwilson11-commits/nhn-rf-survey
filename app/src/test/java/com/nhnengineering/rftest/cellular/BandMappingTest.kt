package com.nhnengineering.rftest.cellular

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the band derivation.
 *
 * This is the one part of the cellular collector that can be verified before a live SIM exists, so
 * it is worth testing properly rather than trusting. Everything downstream — the CSV, any
 * band-specific analysis, the venue report — inherits whatever this gets wrong, and a mislabelled
 * band is exactly the kind of plausible-looking error this project keeps producing.
 *
 * Expected values are computed from 3GPP TS 36.101 table 5.7.3-1 and TS 38.104 section 5.4.2.1,
 * not from the implementation.
 */
class BandMappingTest {

    // -----------------------------------------------------------------------
    // LTE — the bands T-Mobile runs
    // -----------------------------------------------------------------------

    @Test
    fun `band 2 PCS lower edge`() {
        val b = BandMapping.lteBandFor(600)
        assertEquals(2, b?.band)
        // F = 1930.0 + 0.1 * (600 - 600)
        assertEquals(1930.0, BandMapping.lteDownlinkMhz(600)!!, 0.001)
    }

    @Test
    fun `band 2 PCS upper edge`() {
        assertEquals(2, BandMapping.lteBandFor(1199)?.band)
        // F = 1930.0 + 0.1 * (1199 - 600) = 1989.9
        assertEquals(1989.9, BandMapping.lteDownlinkMhz(1199)!!, 0.001)
    }

    @Test
    fun `band 66 AWS-3`() {
        assertEquals(66, BandMapping.lteBandFor(66486)?.band)
        // F = 2110.0 + 0.1 * (66486 - 66436) = 2115.0
        assertEquals(2115.0, BandMapping.lteDownlinkMhz(66486)!!, 0.001)
    }

    @Test
    fun `band 41 BRS mid`() {
        assertEquals(41, BandMapping.lteBandFor(40620)?.band)
        // F = 2496.0 + 0.1 * (40620 - 39650) = 2593.0
        assertEquals(2593.0, BandMapping.lteDownlinkMhz(40620)!!, 0.001)
    }

    @Test
    fun `band 71 600 MHz`() {
        assertEquals(71, BandMapping.lteBandFor(68686)?.band)
        // F = 617.0 + 0.1 * (68686 - 68586) = 627.0
        assertEquals(627.0, BandMapping.lteDownlinkMhz(68686)!!, 0.001)
    }

    @Test
    fun `unknown earfcn returns null rather than guessing`() {
        assertNull(BandMapping.lteBandFor(999_999))
        assertNull(BandMapping.lteDownlinkMhz(999_999))
    }

    @Test
    fun `earfcn ranges do not overlap`() {
        // Overlapping ranges would make lteBandFor order-dependent, which is the kind of bug that
        // only shows up on one carrier in one market.
        val bands = BandMapping.LTE_BANDS
        for (i in bands.indices) {
            for (j in i + 1 until bands.size) {
                val a = bands[i].earfcnRange
                val b = bands[j].earfcnRange
                val overlaps = a.first <= b.last && b.first <= a.last
                assertTrue(
                    "EARFCN ranges overlap: band ${bands[i].band} and band ${bands[j].band}",
                    !overlaps,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // NR — the global raster
    // -----------------------------------------------------------------------

    @Test
    fun `nrarfcn to frequency below 3 GHz uses 5 kHz raster`() {
        // N = 399000 -> 0.005 * 399000 = 1995.0 MHz
        assertEquals(1995.0, BandMapping.nrArfcnToMhz(399_000)!!, 0.001)
        // n41 centre-ish: N = 519000 -> 2595.0 MHz
        assertEquals(2595.0, BandMapping.nrArfcnToMhz(519_000)!!, 0.001)
    }

    @Test
    fun `nrarfcn to frequency above 3 GHz uses 15 kHz raster`() {
        // N = 620000 -> 3000 + 0.015 * 20000 = 3300.0 MHz
        assertEquals(3300.0, BandMapping.nrArfcnToMhz(620_000)!!, 0.001)
    }

    @Test
    fun `n41 is identified for a 2500 MHz channel`() {
        // 2595 MHz sits in n41 only
        val bands = BandMapping.nrBandsFor(519_000)
        assertEquals(listOf("n41"), bands.map { it.band })
        assertTrue("n41 is TDD", bands.first().tdd)
    }

    @Test
    fun `n71 is identified for a 600 MHz channel`() {
        // N = 126000 -> 630.0 MHz, inside n71 617-652
        assertEquals(630.0, BandMapping.nrArfcnToMhz(126_000)!!, 0.001)
        assertEquals(listOf("n71"), BandMapping.nrBandsFor(126_000).map { it.band })
    }

    @Test
    fun `overlapping NR allocations are reported as ambiguous, not guessed`() {
        // 1950 MHz is valid in both n2 (1930-1990) and n25 (1930-1995). The channel number alone
        // cannot resolve that, and pretending otherwise would put a wrong band in a report.
        val bands = BandMapping.nrBandsFor(390_000) // 1950.0 MHz
        assertEquals(1950.0, BandMapping.nrArfcnToMhz(390_000)!!, 0.001)
        assertTrue("expected both n2 and n25", bands.map { it.band }.containsAll(listOf("n2", "n25")))

        val label = BandMapping.nrBandLabel(390_000)
        assertNotNull(label)
        assertTrue("label should surface the ambiguity: $label", label!!.contains("or"))
    }

    @Test
    fun `n66 and n4 overlap is surfaced`() {
        // 2130 MHz is in both n4 (2110-2155) and n66 (2110-2200).
        val bands = BandMapping.nrBandsFor(426_000)
        assertEquals(2130.0, BandMapping.nrArfcnToMhz(426_000)!!, 0.001)
        assertTrue(bands.map { it.band }.containsAll(listOf("n4", "n66")))
    }

    @Test
    fun `out of range nrarfcn returns null`() {
        assertNull(BandMapping.nrArfcnToMhz(9_999_999))
        assertTrue(BandMapping.nrBandsFor(9_999_999).isEmpty())
        assertNull(BandMapping.nrBandLabel(9_999_999))
    }
}
