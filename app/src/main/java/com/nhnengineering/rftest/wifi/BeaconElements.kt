package com.nhnengineering.rftest.wifi

/**
 * Parses the information elements carried in a beacon.
 *
 * ## Why bother, when `ScanResult` already has fields
 *
 * `ScanResult` gives RSSI, frequency, width, security and the 802.11 generation. Every Wi-Fi app on
 * the store shows those, because they are the easy API. Almost none go further, and the beacon
 * carries a great deal more — it is broadcast in the clear, ten times a second, by every AP in the
 * building.
 *
 * The one that matters most is **channel utilisation**, from the BSS Load element. It is the
 * fraction of airtime the medium was busy, measured and published by the AP itself. A site with
 * −55 dBm everywhere and 85% utilisation is a site with a capacity problem, and no amount of signal
 * measurement will find it. Ekahau reports this; the free Android tools do not.
 *
 * ## Layout notes, since these are the part that fails silently
 *
 * Every element is `id | length | bytes`. Android hands them over already split, so this parses
 * bodies only. Multi-byte integers are **little-endian**. Lengths are not trusted: a short body is
 * returned as absent rather than read past, because a malformed beacon from one AP must not take
 * out the scan.
 *
 * Pure Kotlin with no Android types, so the byte handling is unit-testable on the JVM — the same
 * split used for the GeoPackage writer and for the same reason.
 */
object BeaconElements {

    // Element IDs, from IEEE 802.11-2020 Table 9-92.
    const val ID_SUPPORTED_RATES = 1
    const val ID_TIM = 5
    const val ID_COUNTRY = 7
    const val ID_BSS_LOAD = 11
    const val ID_POWER_CONSTRAINT = 32
    const val ID_HT_CAPABILITIES = 45
    const val ID_EXTENDED_RATES = 50
    const val ID_MOBILITY_DOMAIN = 54
    const val ID_RM_ENABLED_CAPS = 70
    const val ID_EXTENDED_CAPS = 127
    const val ID_VHT_CAPABILITIES = 191

    /**
     * HE Capabilities. Not a real element ID -- HE (and EHT) elements all share the outer ID 255
     * ("Extension"), distinguished only by a second byte, exposed by Android as `idExt` rather
     * than as part of the body. [WifiCollector] folds `(255, idExt)` into this synthetic id
     * (`256 + idExt`) before this class ever sees it, so that this file's element lookup can stay
     * a flat id-to-bytes map like every other element here. 35 is the HE Capabilities extension
     * ID, IEEE 802.11-2020 Table 9-92.
     */
    const val ID_HE_CAPABILITIES = 256 + 35

    /** Extended Capabilities bit 19: BSS Transition Management, i.e. 802.11v. */
    private const val BIT_BSS_TRANSITION = 19

    data class BssLoad(
        val stationCount: Int,
        /** Airtime the medium was busy, as a percentage. */
        val channelUtilisationPct: Int,
        /** Admission capacity remaining, in units of 32 microseconds per second. */
        val availableAdmissionCapacity: Int,
    )

    /**
     * Decoded from the HT Capabilities element (802.11n / Wi-Fi 4). The Rx MCS bitmask's highest
     * set bit is used, not the Tx one: almost every real AP advertises a symmetric Tx/Rx MCS set,
     * and the Rx set is what every other Wi-Fi tool examines too.
     */
    data class HtCapability(
        val maxMcsIndex: Int,
        val channelWidth40: Boolean,
        val shortGi20: Boolean,
        val shortGi40: Boolean,
    ) {
        /** The best (width, GI) this capability advertises, at its highest MCS. */
        val maxRateMbps: Double?
            get() {
                val width = if (channelWidth40) 40 else 20
                val sgi = if (width == 40) shortGi40 else shortGi20
                val gi = if (sgi) WifiPhyRates.GuardInterval.SHORT else WifiPhyRates.GuardInterval.LONG
                return WifiPhyRates.htRateMbps(maxMcsIndex, width, gi)
            }
    }

    /**
     * Decoded from the VHT Capabilities element (802.11ac / Wi-Fi 5).
     *
     * [maxMcsByNss] carries every advertised spatial-stream count, not just the highest one, on
     * purpose: [WifiPhyRates]'s table has a few (spatial streams, MCS, width) gaps the standard
     * itself defines as invalid, so "highest NSS at its own top MCS" is not always the actual
     * maximum -- the true maximum has to search every advertised combination and skip the gaps.
     */
    data class VhtCapability(
        val maxMcsByNss: Map<Int, Int>,
        val channelWidth160: Boolean,
        val shortGi80: Boolean,
        val shortGi160: Boolean,
    ) {
        val maxRateMbps: Double?
            get() {
                val width = if (channelWidth160) 160 else 80
                val sgi = if (width == 160) shortGi160 else shortGi80
                val gi = if (sgi) WifiPhyRates.GuardInterval.SHORT else WifiPhyRates.GuardInterval.LONG
                return maxMcsByNss.entries
                    .flatMap { (nss, maxMcs) -> (0..maxMcs).map { mcs -> nss to mcs } }
                    .mapNotNull { (nss, mcs) -> WifiPhyRates.vhtRateMbps(nss, mcs, width, gi) }
                    .maxOrNull()
            }
    }

    /**
     * Decoded from the HE Capabilities element (802.11ax / Wi-Fi 6/6E).
     *
     * Only the Rx HE-MCS Map for <=80MHz's 1-spatial-stream group is read -- see
     * [WifiPhyRates.heRateMbps]'s own doc for why multi-stream and 160MHz are deliberately not
     * attempted. [maxRateMbps] takes the width to compute at as a parameter rather than deciding
     * it itself: an HE Capabilities element's own channel-width bits are considerably more
     * involved to decode correctly than VHT's, and every 5GHz AP advertising HE also advertises
     * VHT for backward compatibility on the same radio -- so [Rates.maxPhyRateMbps] reuses the
     * already-verified VHT width instead of a second, unverified HE-specific decode. A 6GHz-only
     * AP has no VHT element at all (6GHz beacons cannot carry one), so it falls back to 20MHz --
     * a real, if conservative, floor rather than a guess.
     */
    data class HeCapability(val maxMcsIndex: Int) {
        fun maxRateMbps(widthMhz: Int): Double? = WifiPhyRates.heRateMbps(maxMcsIndex, widthMhz)
    }

    data class Rates(
        /**
         * The lowest **basic** rate, in Mbps.
         *
         * Worth surfacing on its own: a basic rate of 1 Mbps is the single most common finding in a
         * badly configured WLAN. Every broadcast and every management frame goes out at the lowest
         * basic rate, so leaving 1 Mbps enabled spends airtime on beacons that a modern estate does
         * not need to spend, and lets distant clients cling to a cell they should have left.
         */
        val minBasicMbps: Double?,
        /**
         * Highest **legacy** rate advertised, in Mbps.
         *
         * Named for what it is. The Supported Rates and Extended Supported Rates elements carry
         * only 802.11a/b/g rates, so this tops out at 54 Mbps on every AP including Wi-Fi 6 ones --
         * the HT, VHT, HE and EHT rate sets live in their own elements. Calling this the AP's
         * maximum rate would put "54 Mbps" beside an 802.11ax radio in a client report, which is
         * the same species of error as printing RSRP without its sign. See [maxPhyRateMbps] for
         * the figure that actually accounts for HT/VHT/HE.
         */
        val maxLegacyMbps: Double?,
        val basicMbps: List<Double>,
        val ht: HtCapability?,
        val vht: VhtCapability?,
        val he: HeCapability?,
    ) {
        /**
         * The AP's best advertised PHY rate, considering HT, VHT and HE capability (whichever is
         * higher; a real AP's more recent standard always supersedes its older ones on the same
         * radio, but nothing here assumes that rather than checks it). Null when none of the three
         * elements were present or parseable -- an AP with only legacy rates.
         *
         * This is a **ceiling**, not a live measurement: the highest width and guard interval the
         * AP's capability elements advertise, not necessarily the channel width it is actually
         * running right now. The same honesty rule as [maxLegacyMbps] -- it says what the radio
         * could do, not what it is doing at this moment for any particular client. HE specifically
         * is further capped to what [WifiPhyRates.heRateMbps] covers (1 spatial stream, <=80MHz) --
         * see that function's doc for why the rest is left out rather than guessed, which means
         * this figure can undercount a high-end multi-stream 160MHz Wi-Fi 6E AP. Undercounting was
         * the deliberate choice; overclaiming was not.
         */
        val maxPhyRateMbps: Double?
            get() {
                val heWidth = if (vht != null) 80 else 20
                return listOfNotNull(ht?.maxRateMbps, vht?.maxRateMbps, he?.maxRateMbps(heWidth))
                    .maxOrNull()
            }
    }

    data class Beacon(
        val bssLoad: BssLoad?,
        val rates: Rates?,
        val countryCode: String?,
        /** Local power constraint in dB, subtracted from the regulatory maximum. */
        val powerConstraintDb: Int?,
        val dtimPeriod: Int?,
        /** 802.11r fast BSS transition, from the presence of a Mobility Domain element. */
        val fastTransition: Boolean,
        /** 802.11k radio measurement. */
        val radioMeasurement: Boolean,
        /** 802.11v BSS transition management. */
        val bssTransition: Boolean,
    )

    private fun ByteArray.u8(i: Int): Int? = if (i in indices) this[i].toInt() and 0xFF else null

    private fun ByteArray.u16le(i: Int): Int? {
        val lo = u8(i) ?: return null
        val hi = u8(i + 1) ?: return null
        return lo or (hi shl 8)
    }

    /**
     * @param elements element id to body. An id may legitimately appear more than once; the first
     *   is used, which is what a receiver does.
     */
    fun parse(elements: List<Pair<Int, ByteArray>>): Beacon {
        val byId = mutableMapOf<Int, ByteArray>()
        for ((id, bytes) in elements) byId.putIfAbsent(id, bytes)

        return Beacon(
            bssLoad = byId[ID_BSS_LOAD]?.let { parseBssLoad(it) },
            rates = parseRates(
                byId[ID_SUPPORTED_RATES],
                byId[ID_EXTENDED_RATES],
                byId[ID_HT_CAPABILITIES],
                byId[ID_VHT_CAPABILITIES],
                byId[ID_HE_CAPABILITIES],
            ),
            countryCode = byId[ID_COUNTRY]?.let { body ->
                if (body.size < 2) null else String(body, 0, 2, Charsets.US_ASCII)
                    .takeIf { s -> s.all { it.isLetterOrDigit() } }
            },
            powerConstraintDb = byId[ID_POWER_CONSTRAINT]?.u8(0),
            // TIM is DTIM Count then DTIM Period; the period describes the network's design, the
            // count is only where the current cycle has got to.
            //
            // Frequently absent, and legitimately so: TIM is carried in beacons but not in probe
            // responses, and a scan result may be either. An AP reporting no DTIM period here has
            // not been caught misbehaving -- we simply heard it answer a probe rather than heard
            // it beacon. Do not "fix" this by defaulting it.
            dtimPeriod = byId[ID_TIM]?.u8(1),
            fastTransition = byId.containsKey(ID_MOBILITY_DOMAIN),
            radioMeasurement = byId.containsKey(ID_RM_ENABLED_CAPS),
            bssTransition = byId[ID_EXTENDED_CAPS]?.let { hasBit(it, BIT_BSS_TRANSITION) } ?: false,
        )
    }

    private fun parseBssLoad(body: ByteArray): BssLoad? {
        val stations = body.u16le(0) ?: return null
        val raw = body.u8(2) ?: return null
        val capacity = body.u16le(3) ?: return null
        return BssLoad(
            stationCount = stations,
            // The field is a linear 0-255 scale of the fraction of time the medium was busy, not a
            // percentage. Reporting the raw byte as a percentage would overstate every site by
            // roughly 2.5x -- 255 would read as "255% busy" and 128 as 128% rather than 50%.
            channelUtilisationPct = Math.round(raw * 100f / 255f),
            availableAdmissionCapacity = capacity,
        )
    }

    private fun parseRates(
        supported: ByteArray?,
        extended: ByteArray?,
        htCaps: ByteArray?,
        vhtCaps: ByteArray?,
        heCaps: ByteArray?,
    ): Rates? {
        val all = (supported?.toList().orEmpty() + extended?.toList().orEmpty())
        val ht = htCaps?.let { parseHtCapability(it) }
        val vht = vhtCaps?.let { parseVhtCapability(it) }
        val he = heCaps?.let { parseHeCapability(it) }

        val basic = mutableListOf<Double>()
        val every = mutableListOf<Double>()
        for (b in all) {
            val v = b.toInt() and 0xFF
            // Rates are in units of 500 kbps, with the top bit marking the rate as basic.
            val mbps = (v and 0x7F) * 0.5
            if (mbps <= 0.0) continue
            every += mbps
            if (v and 0x80 != 0) basic += mbps
        }
        // A beacon with legacy rates and none of HT/VHT/HE is the ordinary case and every field
        // here is populated. One with HT/VHT/HE but no legacy rates parsed (malformed capture, or
        // a future encoding this build doesn't expect) still deserves a Rates object rather than
        // silently losing the PHY capability -- only give up entirely when there is nothing at all.
        if (every.isEmpty() && ht == null && vht == null && he == null) return null

        return Rates(
            minBasicMbps = basic.minOrNull(),
            maxLegacyMbps = every.maxOrNull(),
            basicMbps = basic.sorted(),
            ht = ht,
            vht = vht,
            he = he,
        )
    }

    private fun parseHtCapability(body: ByteArray): HtCapability? {
        val info0 = body.u8(0) ?: return null
        var maxMcs = -1
        for (byteIndex in 3..6) {
            val b = body.u8(byteIndex) ?: break
            for (bit in 0..7) {
                if (b and (1 shl bit) != 0) {
                    val mcs = (byteIndex - 3) * 8 + bit
                    if (mcs > maxMcs) maxMcs = mcs
                }
            }
        }
        if (maxMcs < 0) return null
        return HtCapability(
            maxMcsIndex = maxMcs,
            channelWidth40 = info0 and 0x02 != 0,
            shortGi20 = info0 and 0x20 != 0,
            shortGi40 = info0 and 0x40 != 0,
        )
    }

    private fun parseVhtCapability(body: ByteArray): VhtCapability? {
        val info0 = body.u8(0) ?: return null
        val mcsMap = body.u16le(4) ?: return null

        val maxMcsByNss = mutableMapOf<Int, Int>()
        for (nss in 1..8) {
            val bits = (mcsMap shr ((nss - 1) * 2)) and 0x03
            if (bits == 3) continue // not supported at this spatial stream count
            maxMcsByNss[nss] = when (bits) {
                0 -> 7
                1 -> 8
                else -> 9
            }
        }
        if (maxMcsByNss.isEmpty()) return null

        return VhtCapability(
            maxMcsByNss = maxMcsByNss,
            channelWidth160 = (info0 shr 2) and 0x03 != 0,
            shortGi80 = info0 and 0x20 != 0,
            shortGi160 = info0 and 0x40 != 0,
        )
    }

    /**
     * Reads only the Rx HE-MCS Map for <=80MHz, at a fixed offset -- HE MAC Capabilities
     * Information (6 octets, offset 0) then HE PHY Capabilities Information (11 octets, offset 6)
     * are both fixed-length regardless of what they advertise, so the map always starts at offset
     * 17 without needing to decode either field first. Confirmed against a real AP capture: byte
     * 0 here decoded to plausible HE MAC Capabilities bits (not the constant HE Capabilities
     * extension ID, which [WifiCollector] already strips before this class sees the body), and the
     * map itself decoded to a self-consistent, plausible 4-spatial-stream capability rather than
     * noise -- see BeaconElementsTest's fixture for the exact bytes.
     *
     * Everything past the map (PPE Thresholds, and the 160MHz/80+80MHz MCS maps that only appear
     * when the PHY Capabilities bits say they do) is intentionally not read; see
     * [WifiPhyRates.heRateMbps] for why this build never needs a spatial stream above 1 or a width
     * above 80MHz.
     */
    private fun parseHeCapability(body: ByteArray): HeCapability? {
        val mcsMap = body.u16le(17) ?: return null
        val group = mcsMap and 0x03
        val maxMcs = when (group) {
            0 -> 7
            1 -> 9
            2 -> 11
            else -> return null // 3 = not supported at 1 spatial stream
        }
        return HeCapability(maxMcs)
    }

    private fun hasBit(body: ByteArray, bit: Int): Boolean {
        val index = bit / 8
        val value = body.u8(index) ?: return false
        return value and (1 shl (bit % 8)) != 0
    }
}
