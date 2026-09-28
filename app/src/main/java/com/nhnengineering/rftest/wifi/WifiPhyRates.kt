package com.nhnengineering.rftest.wifi

/**
 * Standardized PHY data rate tables for 802.11n (HT) and 802.11ac (VHT), turning an advertised
 * MCS/NSS/channel-width/guard-interval combination into a single Mbps figure.
 *
 * Values are transcribed from the IEEE 802.11-2016 standard's own rate tables and cross-checked
 * against multiple independently published references (2026-09-28) -- including several famous
 * router marketing figures that turn out to be exact table entries: "433 Mbps" is 1 spatial
 * stream, 80MHz, short GI, MCS9; "867 Mbps" is the same at 2 spatial streams.
 *
 * The VHT table is hand-transcribed rather than derived from a formula on purpose. A handful of
 * (spatial stream count, MCS, channel width) combinations are genuinely invalid per the standard
 * -- not merely uncommon -- and a formula that does not know about those exceptions would
 * silently produce a number for a combination that does not exist. Those gaps are captured here
 * as literal absences in the table (`null`), the same way [BeaconElements.Rates.maxLegacyMbps]
 * would rather report nothing than overclaim.
 *
 * HE (802.11ax) is deliberately not covered here. Its rate table has on the order of 2,880 valid
 * combinations -- three guard interval options, DCM, extended range, and RU-based subcarrier
 * counts on top of the same MCS/NSS/width axes -- and no independently verifiable full reference
 * table was available to check a transcription against. Shipping a wrong number would be worse
 * than shipping none.
 */
object WifiPhyRates {

    enum class GuardInterval { LONG, SHORT }

    // ---- HT (802.11n) ----------------------------------------------------

    // MCS 0-7, 1 spatial stream. MCS 8-31 (2-4 streams) are an exact multiple of this table --
    // the standard defines HT that way, with no exceptions, confirmed against the same reference
    // used for VHT below.
    private val HT_20_LGI = doubleArrayOf(6.5, 13.0, 19.5, 26.0, 39.0, 52.0, 58.5, 65.0)
    private val HT_20_SGI = doubleArrayOf(7.2, 14.4, 21.7, 28.9, 43.3, 57.8, 65.0, 72.2)
    private val HT_40_LGI = doubleArrayOf(13.5, 27.0, 40.5, 54.0, 81.0, 108.0, 121.5, 135.0)
    private val HT_40_SGI = doubleArrayOf(15.0, 30.0, 45.0, 60.0, 90.0, 120.0, 135.0, 150.0)

    /** @param mcsIndex 0-31. @param widthMhz 20 or 40 -- an HT radio is never wider. */
    fun htRateMbps(mcsIndex: Int, widthMhz: Int, gi: GuardInterval): Double? {
        if (mcsIndex !in 0..31) return null
        val nss = mcsIndex / 8 + 1
        val base = mcsIndex % 8
        val table = when {
            widthMhz == 20 && gi == GuardInterval.LONG -> HT_20_LGI
            widthMhz == 20 && gi == GuardInterval.SHORT -> HT_20_SGI
            widthMhz == 40 && gi == GuardInterval.LONG -> HT_40_LGI
            widthMhz == 40 && gi == GuardInterval.SHORT -> HT_40_SGI
            else -> return null
        }
        return table[base] * nss
    }

    // ---- VHT (802.11ac) ----------------------------------------------------

    // [MCS][20 LGI, 20 SGI, 40 LGI, 40 SGI, 80 LGI, 80 SGI, 160 LGI, 160 SGI], null = not valid
    // per the standard. Verified for 1-3 spatial streams only -- see the class doc for why 4+ is
    // deliberately absent rather than guessed.
    private val VHT_NSS1 = arrayOf(
        doubleArrayOf(6.5, 7.2, 13.5, 15.0, 29.3, 32.5, 58.5, 65.0),
        doubleArrayOf(13.0, 14.4, 27.0, 30.0, 58.5, 65.0, 117.0, 130.0),
        doubleArrayOf(19.5, 21.7, 40.5, 45.0, 87.8, 97.5, 175.5, 195.0),
        doubleArrayOf(26.0, 28.9, 54.0, 60.0, 117.0, 130.0, 234.0, 260.0),
        doubleArrayOf(39.0, 43.3, 81.0, 90.0, 175.5, 195.0, 351.0, 390.0),
        doubleArrayOf(52.0, 57.8, 108.0, 120.0, 234.0, 260.0, 468.0, 520.0),
        doubleArrayOf(58.5, 65.0, 121.5, 135.0, 263.3, 292.5, 526.5, 585.0),
        doubleArrayOf(65.0, 72.2, 135.0, 150.0, 292.5, 325.0, 585.0, 650.0),
        doubleArrayOf(78.0, 86.7, 162.0, 180.0, 351.0, 390.0, 702.0, 780.0),
        // MCS9 (256-QAM 5/6) at 20MHz: invalid at 1 and 2 spatial streams, because 52 data
        // subcarriers x 8 bits x {1,2} streams x 5/6 coding is not a whole number of coded bits
        // (346.67, 693.33). Genuinely valid again at 3 streams (52x8x3x5/6 = 1040 exactly) --
        // WifiPhyRatesTest pins that surprising case explicitly, since it looks at first glance
        // like it should follow the same rule as here. 40/80/160MHz are unaffected at any NSS.
        doubleArrayOf(Double.NaN, Double.NaN, 180.0, 200.0, 390.0, 433.3, 780.0, 866.7),
    )
    private val VHT_NSS2 = arrayOf(
        doubleArrayOf(13.0, 14.4, 27.0, 30.0, 58.5, 65.0, 117.0, 130.0),
        doubleArrayOf(26.0, 28.9, 54.0, 60.0, 117.0, 130.0, 234.0, 260.0),
        doubleArrayOf(39.0, 43.3, 81.0, 90.0, 175.5, 195.0, 351.0, 390.0),
        doubleArrayOf(52.0, 57.8, 108.0, 120.0, 234.0, 260.0, 468.0, 520.0),
        doubleArrayOf(78.0, 86.7, 162.0, 180.0, 351.0, 390.0, 702.0, 780.0),
        doubleArrayOf(104.0, 115.6, 216.0, 240.0, 468.0, 520.0, 936.0, 1040.0),
        doubleArrayOf(117.0, 130.3, 243.0, 270.0, 526.5, 585.0, 1053.0, 1170.0),
        doubleArrayOf(130.0, 144.4, 270.0, 300.0, 585.0, 650.0, 1170.0, 1300.0),
        doubleArrayOf(156.0, 173.3, 324.0, 360.0, 702.0, 780.0, 1404.0, 1560.0),
        doubleArrayOf(Double.NaN, Double.NaN, 360.0, 400.0, 780.0, 866.7, 1560.0, 1733.3),
    )
    private val VHT_NSS3 = arrayOf(
        doubleArrayOf(19.5, 21.7, 40.5, 45.0, 87.8, 97.5, 175.5, 195.0),
        doubleArrayOf(39.0, 43.3, 81.0, 90.0, 175.5, 195.0, 351.0, 390.0),
        doubleArrayOf(58.5, 65.0, 121.5, 135.0, 263.3, 292.5, 526.5, 585.0),
        doubleArrayOf(78.0, 86.7, 162.0, 180.0, 351.0, 390.0, 702.0, 780.0),
        doubleArrayOf(117.0, 130.0, 243.0, 270.0, 526.5, 585.0, 1053.0, 1170.0),
        doubleArrayOf(156.0, 173.3, 324.0, 360.0, 702.0, 780.0, 1404.0, 1560.0),
        // MCS6 (64-QAM 3/4) at 3 streams, 80MHz: genuinely invalid per the standard -- unlike the
        // MCS9/20-40MHz case above, this is an NSS-specific interleaver constraint, not a
        // single-stream divisibility one. Transcribed as a gap, not derived, for exactly that
        // reason: it does not follow the same rule as the other gaps in this table.
        doubleArrayOf(175.5, 195.0, 364.5, 405.0, Double.NaN, Double.NaN, 1579.5, 1755.0),
        doubleArrayOf(195.0, 216.7, 405.0, 450.0, 877.5, 975.0, 1755.0, 1950.0),
        doubleArrayOf(234.0, 260.0, 486.0, 540.0, 1053.0, 1170.0, 2106.0, 2340.0),
        // MCS9 at 3 streams, 160MHz: also invalid, also NSS-specific rather than the general
        // MCS9/20-40MHz rule (which this row's 20/40 columns already show are populated here).
        doubleArrayOf(260.0, 288.9, 540.0, 600.0, 1170.0, 1300.0, Double.NaN, Double.NaN),
    )

    private val VHT_TABLES = mapOf(1 to VHT_NSS1, 2 to VHT_NSS2, 3 to VHT_NSS3)

    /**
     * @param nss spatial stream count. Only 1-3 are backed by a verified table; anything else
     *   returns null rather than guess -- see the class doc.
     * @param mcsIndex 0-9. @param widthMhz 20, 40, 80 or 160.
     */
    fun vhtRateMbps(nss: Int, mcsIndex: Int, widthMhz: Int, gi: GuardInterval): Double? {
        val table = VHT_TABLES[nss] ?: return null
        if (mcsIndex !in 0..9) return null
        val col = when {
            widthMhz == 20 && gi == GuardInterval.LONG -> 0
            widthMhz == 20 && gi == GuardInterval.SHORT -> 1
            widthMhz == 40 && gi == GuardInterval.LONG -> 2
            widthMhz == 40 && gi == GuardInterval.SHORT -> 3
            widthMhz == 80 && gi == GuardInterval.LONG -> 4
            widthMhz == 80 && gi == GuardInterval.SHORT -> 5
            widthMhz == 160 && gi == GuardInterval.LONG -> 6
            widthMhz == 160 && gi == GuardInterval.SHORT -> 7
            else -> return null
        }
        val v = table[mcsIndex][col]
        return v.takeUnless { it.isNaN() }
    }
}
