package com.nhnengineering.rftest.wifi

/**
 * Turns channel utilisation, station count, and the PHY rate ceiling into an actual capacity
 * estimate, instead of leaving a reader to do that arithmetic themselves from three separate
 * numbers on three separate lines of a report.
 *
 * ## Why this is the number that matters, and why it doesn't exist elsewhere
 *
 * Channel utilisation alone answers "is the medium busy," which is already more than most tools
 * report (see [BeaconElements]'s own doc). It does not answer the question a client actually asks
 * in a capacity review: **how much room is left, and for how many people**. A site reading
 * `-55 dBm` and `82% busy` is not obviously a problem until it is expressed as "roughly 180 Mbps
 * of this AP's ceiling is actually free, split across the 12 devices already on it" -- the same
 * measurement, said in the unit a decision gets made from.
 *
 * ## What this deliberately is not
 *
 * Not a throughput guarantee, and not a substitute for an actual load test. [estimatedHeadroomMbps]
 * is an airtime-weighted upper bound -- idle airtime fraction times the AP's best-case PHY rate --
 * with no allowance for protocol overhead, contention, retries, or unequal client behaviour, the
 * same way [BeaconElements.Rates.maxPhyRateMbps] is a ceiling and not a live measurement.
 * [estimatedMbpsPerStation] additionally assumes every currently-associated station (not
 * necessarily every one of them actively transmitting) gets an equal share, which is a simplifying
 * assumption stated once here rather than left for a reader to assume incorrectly. Both numbers
 * are optimistic, on purpose: a capacity estimate that already includes penalties nobody chose
 * would be one more model decision hidden inside a single field.
 */
object WifiCapacityModel {

    data class Capacity(
        val channelUtilisationPct: Int,
        /** 100 - [channelUtilisationPct]: the fraction of airtime that was idle, as a percentage. */
        val airtimeHeadroomPct: Int,
        /** The PHY ceiling this estimate is based on -- [BeaconElements.Rates.maxPhyRateMbps]
         *  when HT/VHT/HE was decoded, otherwise [BeaconElements.Rates.maxLegacyMbps]. */
        val phyCeilingMbps: Double,
        /** [phyCeilingMbps] weighted by [airtimeHeadroomPct]. An upper bound, not a promise --
         *  see the class doc. */
        val estimatedHeadroomMbps: Double,
        val stationCount: Int,
        /** [estimatedHeadroomMbps] divided evenly across [stationCount]. A BSS with zero stations
         *  divides by one, not by zero: the headroom is real and available to the next device
         *  that associates, not undefined. */
        val estimatedMbpsPerStation: Double,
    )

    /** Null when there's nothing to model from -- no BSS Load element (channel utilisation
     *  unknown) or no PHY rate ceiling at all (an AP whose Supported Rates, HT, VHT and HE
     *  elements were all absent or unparseable, which in practice means malformed capture rather
     *  than a real AP). Both are required: a capacity estimate with either missing is a guess
     *  wearing a number's clothes. */
    fun compute(beacon: BeaconElements.Beacon): Capacity? {
        val load = beacon.bssLoad ?: return null
        val ceiling = beacon.rates?.maxPhyRateMbps ?: beacon.rates?.maxLegacyMbps ?: return null

        val headroomPct = 100 - load.channelUtilisationPct
        val headroomMbps = ceiling * headroomPct / 100.0
        val perStation = headroomMbps / maxOf(load.stationCount, 1)

        return Capacity(
            channelUtilisationPct = load.channelUtilisationPct,
            airtimeHeadroomPct = headroomPct,
            phyCeilingMbps = ceiling,
            estimatedHeadroomMbps = headroomMbps,
            stationCount = load.stationCount,
            estimatedMbpsPerStation = perStation,
        )
    }
}
