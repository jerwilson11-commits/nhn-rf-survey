package com.nhnengineering.rftest.wifi

/**
 * Standardized PHY data rate tables for 802.11n (HT), 802.11ac (VHT), and a deliberately narrow
 * slice of 802.11ax (HE), turning an advertised MCS/NSS/channel-width/guard-interval combination
 * into a single Mbps figure.
 *
 * The HT and VHT values are transcribed from the IEEE 802.11-2016 standard's own rate tables and
 * cross-checked against multiple independently published references (2026-09-28) -- including
 * several famous router marketing figures that turn out to be exact table entries: "433 Mbps" is
 * 1 spatial stream, 80MHz, short GI, MCS9; "867 Mbps" is the same at 2 spatial streams.
 *
 * The VHT table is hand-transcribed rather than derived from a formula on purpose. A handful of
 * (spatial stream count, MCS, channel width) combinations are genuinely invalid per the standard
 * -- not merely uncommon -- and a formula that does not know about those exceptions would
 * silently produce a number for a combination that does not exist. Those gaps are captured here
 * as literal absences in the table (`null`), the same way [BeaconElements.Rates.maxLegacyMbps]
 * would rather report nothing than overclaim.
 *
 * HE (802.11ax) is covered only for 1 spatial stream, up to 80MHz, at the (universally mandatory)
 * 0.8us guard interval -- not the full standard, which has on the order of 2,880 valid
 * combinations across three guard intervals, DCM, extended range, and RU-based subcarrier counts.
 * That narrow slice is computed from first principles (data subcarrier counts x modulation x code
 * rate / symbol time) and cross-checked, all 36 values, against an independently published
 * reference table -- see [heRateMbps]'s own doc for why the rest is left out rather than
 * extrapolated the same way VHT's multi-stream exceptions turned out not to be derivable.
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

    // ---- HE (802.11ax) ----------------------------------------------------

    // 1 spatial stream, 0.8us guard interval (the shortest, and every HE station is required to
    // support all three GI options -- unlike HT/VHT's optional short GI, there is no capability
    // bit to check, so the best one is always usable and is the only one worth carrying here).
    // [20MHz][40MHz][80MHz], indexed by MCS 0-11.
    //
    // Computed from first principles -- data subcarriers (234/468/980 for 20/40/80MHz, confirmed
    // against a real AP capture's plausible MCS/NSS map, see BeaconElementsTest) x bits-per-symbol
    // x code rate / OFDM symbol time (12.8us + 0.8us GI = 13.6us) -- then cross-checked against an
    // independently published reference table: every one of these 36 values matched that source
    // exactly (to its published rounding), which is the reason this table exists as a formula
    // result rather than a second hand transcription.
    //
    // Deliberately 1 spatial stream and up to 80MHz only. VHT's table proved that some
    // (spatial streams, MCS, width) combinations are invalid for standard-specific reasons a
    // formula does not know about on its own (encoder-count constraints, not a subcarrier
    // divisibility rule); HE's own multi-stream and 160MHz behavior was not independently
    // verifiable the same way this 1-stream/<=80MHz table was, so it is not included rather than
    // guessed. See [BeaconElements.HeCapability] for how the width is chosen without needing to
    // decode HE's own (considerably more involved) channel-width capability bits.
    private val HE_NSS1_GI08 = arrayOf(
        doubleArrayOf(8.6, 17.2, 36.0),
        doubleArrayOf(17.2, 34.4, 72.1),
        doubleArrayOf(25.8, 51.6, 108.1),
        doubleArrayOf(34.4, 68.8, 144.1),
        doubleArrayOf(51.6, 103.2, 216.2),
        doubleArrayOf(68.8, 137.6, 288.2),
        doubleArrayOf(77.4, 154.9, 324.3),
        doubleArrayOf(86.0, 172.1, 360.3),
        doubleArrayOf(103.2, 206.5, 432.4),
        doubleArrayOf(114.7, 229.4, 480.4),
        doubleArrayOf(129.0, 258.1, 540.4),
        doubleArrayOf(143.4, 286.8, 600.5),
    )

    /** @param mcsIndex 0-11. @param widthMhz 20, 40 or 80 -- see the table's own doc for why not
     *  160, and why this is 1 spatial stream only. */
    fun heRateMbps(mcsIndex: Int, widthMhz: Int): Double? {
        if (mcsIndex !in 0..11) return null
        val col = when (widthMhz) {
            20 -> 0
            40 -> 1
            80 -> 2
            else -> return null
        }
        return HE_NSS1_GI08[mcsIndex][col]
    }
}
