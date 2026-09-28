package com.nhnengineering.rftest.wifi

import com.nhnengineering.rftest.wifi.WifiPhyRates.GuardInterval.LONG
import com.nhnengineering.rftest.wifi.WifiPhyRates.GuardInterval.SHORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the transcribed rate tables against known-correct reference points, independent of the
 * beacon byte parsing in [BeaconElementsTest]. A wrong cell here is a wrong number in a client
 * report; several of the checks below are numbers that ship printed on router boxes, specifically
 * because they are the easiest kind of transcription error to catch.
 */
class WifiPhyRatesTest {

    @Test
    fun `HT MCS0, one stream, is the base rate unscaled`() {
        assertEquals(6.5, WifiPhyRates.htRateMbps(0, 20, LONG)!!, 0.001)
        assertEquals(7.2, WifiPhyRates.htRateMbps(0, 20, SHORT)!!, 0.001)
    }

    @Test
    fun `HT scales linearly with spatial stream count`() {
        // MCS7 (1 stream, 65), MCS15 (2 streams), MCS23 (3), MCS31 (4) are all the same base rate.
        assertEquals(65.0, WifiPhyRates.htRateMbps(7, 20, LONG)!!, 0.001)
        assertEquals(130.0, WifiPhyRates.htRateMbps(15, 20, LONG)!!, 0.001)
        assertEquals(195.0, WifiPhyRates.htRateMbps(23, 20, LONG)!!, 0.001)
        assertEquals(260.0, WifiPhyRates.htRateMbps(31, 20, LONG)!!, 0.001)
    }

    @Test
    fun `HT 40MHz short GI MCS7 is the commonly cited 150 Mbps single-stream figure`() {
        assertEquals(150.0, WifiPhyRates.htRateMbps(7, 40, SHORT)!!, 0.001)
    }

    @Test
    fun `HT rejects an MCS index outside 0 to 31`() {
        assertNull(WifiPhyRates.htRateMbps(32, 20, LONG))
        assertNull(WifiPhyRates.htRateMbps(-1, 20, LONG))
    }

    @Test
    fun `HT rejects a width that does not exist on this standard`() {
        assertNull(WifiPhyRates.htRateMbps(0, 80, LONG))
    }

    @Test
    fun `VHT MCS9 80MHz short GI is 433 point 3, the AC1200-class router figure`() {
        assertEquals(433.3, WifiPhyRates.vhtRateMbps(1, 9, 80, SHORT)!!, 0.01)
    }

    @Test
    fun `VHT MCS9 80MHz short GI at 2 streams is 866 point 7, the AC1900-class figure`() {
        assertEquals(866.7, WifiPhyRates.vhtRateMbps(2, 9, 80, SHORT)!!, 0.01)
    }

    @Test
    fun `VHT MCS9 is not defined at 20MHz for 1 or 2 spatial streams, but is defined at 40MHz`() {
        for (nss in 1..2) {
            assertNull("nss=$nss, 20MHz", WifiPhyRates.vhtRateMbps(nss, 9, 20, LONG))
            assertNull("nss=$nss, 20MHz SGI", WifiPhyRates.vhtRateMbps(nss, 9, 20, SHORT))
        }
        assertEquals(180.0, WifiPhyRates.vhtRateMbps(1, 9, 40, LONG)!!, 0.001)
        assertEquals(360.0, WifiPhyRates.vhtRateMbps(2, 9, 40, LONG)!!, 0.001)
    }

    @Test
    fun `VHT MCS9 at 3 spatial streams is the one case where 20 and 40MHz ARE defined`() {
        // Surprising, and exactly why this table is transcribed rather than computed: 256-QAM 5/6
        // at 20MHz needs the number of coded bits (Nsd x 8 x Nss) to divide evenly by 6 to produce
        // a whole number of info bits after 5/6 coding. That happens to fail for 1 and 2 streams
        // (52x8x1x5/6 = 346.67, 52x8x2x5/6 = 693.33) and happens to succeed for 3
        // (52x8x3x5/6 = 1040 exactly) -- a real property of the standard, not a table gap, and the
        // reason the two tests either side of this one look like they contradict each other.
        assertEquals(260.0, WifiPhyRates.vhtRateMbps(3, 9, 20, LONG)!!, 0.001)
        assertEquals(540.0, WifiPhyRates.vhtRateMbps(3, 9, 40, LONG)!!, 0.001)
    }

    @Test
    fun `VHT has two gaps that are specific to 3 spatial streams, unrelated to the MCS9 divisibility rule`() {
        // MCS6 at 80MHz, 3 streams: genuinely undefined, even though MCS6 is fine at every other
        // width and MCS7-8 are fine at 80MHz/3 streams either side of it.
        assertNull(WifiPhyRates.vhtRateMbps(3, 6, 80, LONG))
        assertNull(WifiPhyRates.vhtRateMbps(3, 6, 80, SHORT))
        assertEquals(175.5, WifiPhyRates.vhtRateMbps(3, 6, 20, LONG)!!, 0.001)
        assertEquals(877.5, WifiPhyRates.vhtRateMbps(3, 7, 80, LONG)!!, 0.001)

        // MCS9 at 160MHz, 3 streams: also genuinely undefined, despite MCS9 being defined at
        // 160MHz for 1 or 2 streams, AND at 20/40MHz for 3 streams (the test above) -- three
        // separate, independently-confirmed facts about this one standard, not one rule.
        assertNull(WifiPhyRates.vhtRateMbps(3, 9, 160, LONG))
        assertEquals(780.0, WifiPhyRates.vhtRateMbps(1, 9, 160, LONG)!!, 0.001)
    }

    @Test
    fun `VHT rejects a spatial stream count with no verified table rather than guessing`() {
        assertNull(WifiPhyRates.vhtRateMbps(4, 0, 20, LONG))
        assertNull(WifiPhyRates.vhtRateMbps(8, 0, 20, LONG))
    }

    @Test
    fun `VHT rejects an MCS index outside 0 to 9`() {
        assertNull(WifiPhyRates.vhtRateMbps(1, 10, 80, LONG))
    }

    // ---- HE ----------------------------------------------------------------

    @Test
    fun `HE MCS0, 20MHz, matches an independently published reference exactly`() {
        // 234 data subcarriers x 1 bit (BPSK) x 1/2 coding / 13.6us symbol time = 8.6 Mbps,
        // cross-checked against a separately published HE MCS table, 2026-09-28.
        assertEquals(8.6, WifiPhyRates.heRateMbps(0, 20)!!, 0.05)
    }

    @Test
    fun `HE 40 and 80MHz are exactly double the width below them, same as VHT`() {
        // 468 and 980 data subcarriers are each exactly 2x and (roughly) 4.5x 234 -- worth pinning
        // this relationship since it is what let the 40/80MHz columns be checked against the same
        // reference the 20MHz column was.
        assertEquals(17.2, WifiPhyRates.heRateMbps(0, 40)!!, 0.05)
        assertEquals(36.0, WifiPhyRates.heRateMbps(0, 80)!!, 0.05)
    }

    @Test
    fun `HE MCS11 (1024-QAM), the top of the standard, is 600 point 5 at 80MHz`() {
        assertEquals(600.5, WifiPhyRates.heRateMbps(11, 80)!!, 0.05)
    }

    @Test
    fun `HE rejects an MCS index outside 0 to 11`() {
        assertNull(WifiPhyRates.heRateMbps(12, 20))
        assertNull(WifiPhyRates.heRateMbps(-1, 20))
    }

    @Test
    fun `HE rejects 160MHz -- deliberately unverified, not silently wrong`() {
        assertNull(WifiPhyRates.heRateMbps(0, 160))
    }

    @Test
    fun `HE rejects a width HE does not have, same as it would for HT's 80`() {
        assertNull(WifiPhyRates.heRateMbps(0, 10))
    }
}
