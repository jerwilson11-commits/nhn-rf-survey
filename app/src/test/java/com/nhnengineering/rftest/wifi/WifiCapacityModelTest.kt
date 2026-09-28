package com.nhnengineering.rftest.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the arithmetic, since this is exactly the kind of computed field a wrong constant or a
 * flipped percentage silently survives in -- the estimate would still look plausible, just wrong,
 * which is a worse failure mode than a crash.
 */
class WifiCapacityModelTest {

    private fun bssLoad(utilPct: Int, stations: Int) = BeaconElements.BssLoad(
        stationCount = stations,
        channelUtilisationPct = utilPct,
        availableAdmissionCapacity = 0,
    )

    /** No HT/VHT/HE capability at all -- exercises the fall-back-to-legacy-ceiling path, and the
     *  no-ceiling-at-all path when [maxLegacy] is also null. */
    private fun rates(maxLegacy: Double?) = BeaconElements.Rates(
        minBasicMbps = null,
        maxLegacyMbps = maxLegacy,
        basicMbps = emptyList(),
        ht = null,
        vht = null,
        he = null,
    )

    private fun beacon(load: BeaconElements.BssLoad?, r: BeaconElements.Rates?) = BeaconElements.Beacon(
        bssLoad = load, rates = r, countryCode = null, powerConstraintDb = null, dtimPeriod = null,
        fastTransition = false, radioMeasurement = false, bssTransition = false,
    )

    @Test
    fun `80 percent busy with a real VHT ceiling leaves exactly 20 percent of it as headroom`() {
        // 3 streams, MCS8, 80MHz, short GI: a real, verified VHT rate table entry -- the test uses
        // whatever a real capability produces rather than inventing a round ceiling number.
        val vht = BeaconElements.VhtCapability(
            maxMcsByNss = mapOf(1 to 7, 2 to 7, 3 to 8),
            channelWidth160 = false, shortGi80 = true, shortGi160 = false,
        )
        val r = BeaconElements.Rates(
            minBasicMbps = null, maxLegacyMbps = 54.0, basicMbps = emptyList(),
            ht = null, vht = vht, he = null,
        )
        val load = bssLoad(utilPct = 80, stations = 12)

        val c = WifiCapacityModel.compute(beacon(load, r))!!

        assertEquals(20, c.airtimeHeadroomPct)
        assertEquals(r.maxPhyRateMbps!!, c.phyCeilingMbps, 0.001)
        assertEquals(r.maxPhyRateMbps!! * 0.2, c.estimatedHeadroomMbps, 0.001)
        assertEquals(c.estimatedHeadroomMbps / 12, c.estimatedMbpsPerStation, 0.001)
    }

    @Test
    fun `falls back to the legacy ceiling when no HT, VHT or HE was decoded`() {
        val load = bssLoad(utilPct = 50, stations = 4)
        val r = rates(maxLegacy = 54.0)

        val c = WifiCapacityModel.compute(beacon(load, r))!!

        assertEquals(54.0, c.phyCeilingMbps, 0.001)
        assertEquals(27.0, c.estimatedHeadroomMbps, 0.001) // 50% of 54
        assertEquals(6.75, c.estimatedMbpsPerStation, 0.001) // 27 / 4
    }

    @Test
    fun `no BSS Load element means no estimate, not a zero one`() {
        val r = rates(maxLegacy = 54.0)

        assertNull(WifiCapacityModel.compute(beacon(null, r)))
    }

    @Test
    fun `no PHY ceiling of any kind means no estimate`() {
        val load = bssLoad(utilPct = 50, stations = 4)
        val r = rates(maxLegacy = null)

        assertNull(WifiCapacityModel.compute(beacon(load, r)))
    }

    @Test
    fun `no rates at all means no estimate`() {
        val load = bssLoad(utilPct = 50, stations = 4)

        assertNull(WifiCapacityModel.compute(beacon(load, null)))
    }

    @Test
    fun `zero associated stations divides by one, not by zero`() {
        val load = bssLoad(utilPct = 0, stations = 0)
        val r = rates(maxLegacy = 100.0)

        val c = WifiCapacityModel.compute(beacon(load, r))!!

        assertEquals(0, c.stationCount)
        assertEquals(100.0, c.estimatedHeadroomMbps, 0.001) // fully idle -> full ceiling
        assertEquals(100.0, c.estimatedMbpsPerStation, 0.001) // /1, not /0 or NaN
    }

    @Test
    fun `a fully busy channel leaves zero headroom, not a negative or undefined one`() {
        val load = bssLoad(utilPct = 100, stations = 5)
        val r = rates(maxLegacy = 300.0)

        val c = WifiCapacityModel.compute(beacon(load, r))!!

        assertEquals(0, c.airtimeHeadroomPct)
        assertEquals(0.0, c.estimatedHeadroomMbps, 0.001)
        assertEquals(0.0, c.estimatedMbpsPerStation, 0.001)
    }
}
