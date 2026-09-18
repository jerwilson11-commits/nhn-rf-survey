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
    const val ID_EXTENDED_RATES = 50
    const val ID_MOBILITY_DOMAIN = 54
    const val ID_RM_ENABLED_CAPS = 70
    const val ID_EXTENDED_CAPS = 127

    /** Extended Capabilities bit 19: BSS Transition Management, i.e. 802.11v. */
    private const val BIT_BSS_TRANSITION = 19

    data class BssLoad(
        val stationCount: Int,
        /** Airtime the medium was busy, as a percentage. */
        val channelUtilisationPct: Int,
        /** Admission capacity remaining, in units of 32 microseconds per second. */
        val availableAdmissionCapacity: Int,
    )

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
         * the HT, VHT, HE and EHT rate sets live in their own elements and are not parsed here.
         * Calling this the AP's maximum rate would put "54 Mbps" beside an 802.11ax radio in a
         * client report, which is the same species of error as printing RSRP without its sign.
         */
        val maxLegacyMbps: Double?,
        val basicMbps: List<Double>,
    )

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
            rates = parseRates(byId[ID_SUPPORTED_RATES], byId[ID_EXTENDED_RATES]),
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

    private fun parseRates(supported: ByteArray?, extended: ByteArray?): Rates? {
        val all = (supported?.toList().orEmpty() + extended?.toList().orEmpty())
        if (all.isEmpty()) return null

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
        if (every.isEmpty()) return null

        return Rates(
            minBasicMbps = basic.minOrNull(),
            maxLegacyMbps = every.maxOrNull(),
            basicMbps = basic.sorted(),
        )
    }

    private fun hasBit(body: ByteArray, bit: Int): Boolean {
        val index = bit / 8
        val value = body.u8(index) ?: return false
        return value and (1 shl (bit % 8)) != 0
    }
}
