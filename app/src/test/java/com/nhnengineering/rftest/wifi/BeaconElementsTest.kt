package com.nhnengineering.rftest.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the beacon byte layouts.
 *
 * These are the parts that fail silently: a wrong endianness, or a 0-255 scale read as a
 * percentage, still produces a number, and a plausible number will be believed. Channel
 * utilisation in particular is destined for a client report, so a 2.5x error in it is worse than
 * not having the field at all.
 */
class BeaconElementsTest {

    private fun ie(id: Int, vararg bytes: Int) = id to bytes.map { it.toByte() }.toByteArray()

    @Test
    fun `BSS load reads stations little-endian and utilisation as a fraction of 255`() {
        // 300 stations = 0x012C, little-endian on the wire. 128/255 is a medium busy just over half
        // the time; reading the raw byte as a percentage would report it as 128% busy.
        val b = BeaconElements.parse(listOf(ie(11, 0x2C, 0x01, 128, 0x10, 0x00)))

        assertEquals(300, b.bssLoad!!.stationCount)
        assertEquals(50, b.bssLoad!!.channelUtilisationPct)
        assertEquals(16, b.bssLoad!!.availableAdmissionCapacity)
    }

    @Test
    fun `a fully busy channel reads as a hundred, not two hundred and fifty five`() {
        val b = BeaconElements.parse(listOf(ie(11, 0x00, 0x00, 255, 0x00, 0x00)))

        assertEquals(100, b.bssLoad!!.channelUtilisationPct)
    }

    @Test
    fun `a truncated BSS load is absent rather than half-read`() {
        // One malformed beacon must not produce a confident wrong answer, nor take down the scan.
        val b = BeaconElements.parse(listOf(ie(11, 0x2C, 0x01)))

        assertNull(b.bssLoad)
    }

    @Test
    fun `basic rates are the ones with the top bit set`() {
        // 0x82 = 1 Mbps basic, 0x84 = 2 Mbps basic, 0x0C = 6 Mbps supported but not basic.
        // A 1 Mbps basic rate is the finding this field exists to surface.
        val b = BeaconElements.parse(listOf(ie(1, 0x82, 0x84, 0x0C)))

        assertEquals(1.0, b.rates!!.minBasicMbps!!, 0.001)
        assertEquals(6.0, b.rates!!.maxLegacyMbps!!, 0.001)
        assertEquals(listOf(1.0, 2.0), b.rates!!.basicMbps)
    }

    @Test
    fun `the legacy rate ceiling is 54 even for a Wi-Fi 6 AP`() {
        // Observed on every AP in a real scan, including 802.11ax ones: the legacy rate set tops
        // out at 54 Mbps because the HT/VHT/HE rates are carried in separate elements. The field
        // is named maxLegacyMbps so it cannot be read as the AP's actual capability.
        val b = BeaconElements.parse(listOf(ie(1, 0x8C, 0x12, 0x98, 0x24, 0xB0, 0x48, 0x60, 0x6C)))

        assertEquals(54.0, b.rates!!.maxLegacyMbps!!, 0.001)
    }

    @Test
    fun `extended rates are folded in with the supported rates`() {
        val b = BeaconElements.parse(listOf(ie(1, 0x98, 0x24), ie(50, 0x30, 0x48, 0x6C)))

        assertEquals(12.0, b.rates!!.minBasicMbps!!, 0.001)
        assertEquals(54.0, b.rates!!.maxLegacyMbps!!, 0.001)
    }

    @Test
    fun `a rate of zero is ignored rather than becoming the minimum`() {
        // A padding byte read as a 0 Mbps basic rate would make every AP look misconfigured.
        val b = BeaconElements.parse(listOf(ie(1, 0x00, 0x8C)))

        assertEquals(6.0, b.rates!!.minBasicMbps!!, 0.001)
    }

    @Test
    fun `the roaming standards are detected from their elements`() {
        val b = BeaconElements.parse(
            listOf(
                ie(54, 0x01, 0x02, 0x03),
                ie(70, 0x70, 0x00, 0x00, 0x00, 0x00),
                ie(127, 0x00, 0x00, 0x08),
            ),
        )

        assertTrue(b.fastTransition)
        assertTrue(b.radioMeasurement)
        assertTrue(b.bssTransition)
    }

    @Test
    fun `absent elements read as absent, not as capable`() {
        // Claiming 802.11r on an AP that does not advertise it would send an engineer looking for
        // a roaming fault that does not exist.
        val b = BeaconElements.parse(emptyList())

        assertFalse(b.fastTransition)
        assertFalse(b.radioMeasurement)
        assertFalse(b.bssTransition)
        assertNull(b.bssLoad)
        assertNull(b.rates)
        assertNull(b.countryCode)
    }

    @Test
    fun `extended capabilities shorter than the bit it is asked about are not capable`() {
        val b = BeaconElements.parse(listOf(ie(127, 0xFF)))

        assertFalse("bit 19 does not exist in a one-byte body", b.bssTransition)
    }

    @Test
    fun `country and power constraint are read`() {
        val b = BeaconElements.parse(listOf(ie(7, 0x55, 0x53, 0x20), ie(32, 3)))

        assertEquals("US", b.countryCode)
        assertEquals(3, b.powerConstraintDb)
    }

    @Test
    fun `DTIM period is the second byte, not the first`() {
        // The first byte is the DTIM count, which changes every beacon. Reporting it as the period
        // would give a different answer on every scan.
        val b = BeaconElements.parse(listOf(ie(5, 0x02, 0x03, 0x00)))

        assertEquals(3, b.dtimPeriod)
    }

    @Test
    fun `a repeated element uses the first, as a receiver does`() {
        val b = BeaconElements.parse(
            listOf(ie(11, 0x01, 0x00, 51, 0x00, 0x00), ie(11, 0x99, 0x00, 255, 0x00, 0x00)),
        )

        assertEquals(1, b.bssLoad!!.stationCount)
        assertEquals(20, b.bssLoad!!.channelUtilisationPct)
    }

    // ---- HT / VHT capability -------------------------------------------------

    /** Builds an HT Capabilities body with just enough bytes for [BeaconElements] to read. */
    private fun htBody(info0: Int, vararg supportedMcs: Int): ByteArray {
        val body = ByteArray(7)
        body[0] = info0.toByte()
        for (mcs in supportedMcs) {
            val byteIndex = 3 + mcs / 8
            body[byteIndex] = (body[byteIndex].toInt() or (1 shl (mcs % 8))).toByte()
        }
        return body
    }

    /** Builds a VHT Capabilities body from a per-spatial-stream MCS map (nss -> raw 2-bit value,
     *  0=MCS0-7, 1=MCS0-8, 2=MCS0-9, 3=not supported); unlisted streams default to 3. */
    private fun vhtBody(info0: Int, vararg nssToGroupValue: Pair<Int, Int>): ByteArray {
        val byNss = (1..8).associateWith { 3 }.toMutableMap()
        for ((nss, v) in nssToGroupValue) byNss[nss] = v
        var mcsMap = 0
        for (nss in 1..8) mcsMap = mcsMap or (byNss.getValue(nss) shl ((nss - 1) * 2))
        val body = ByteArray(6)
        body[0] = info0.toByte()
        body[4] = (mcsMap and 0xFF).toByte()
        body[5] = ((mcsMap shr 8) and 0xFF).toByte()
        return body
    }

    @Test
    fun `HT MCS7 at 20MHz long GI is 65 Mbps`() {
        val b = BeaconElements.parse(listOf(45 to htBody(info0 = 0x00, 7)))

        assertEquals(7, b.rates!!.ht!!.maxMcsIndex)
        assertFalse(b.rates!!.ht!!.channelWidth40)
        assertEquals(65.0, b.rates!!.ht!!.maxRateMbps!!, 0.001)
        assertEquals(65.0, b.rates!!.maxPhyRateMbps!!, 0.001)
    }

    @Test
    fun `HT picks the highest supported MCS, not the last one seen`() {
        // MCS15 (2 streams) supported alongside MCS3 and MCS9 -- the highest must win regardless
        // of bit order, and 40MHz plus short GI must both be picked up from the info field.
        val body = htBody(info0 = 0x02 or 0x40, 3, 9, 15) // width40 | shortGi40
        val b = BeaconElements.parse(listOf(45 to body))

        assertEquals(15, b.rates!!.ht!!.maxMcsIndex)
        assertTrue(b.rates!!.ht!!.channelWidth40)
        assertTrue(b.rates!!.ht!!.shortGi40)
        // 2 streams, MCS mod 8 = 7, 40MHz short GI base is 150 -> 300.
        assertEquals(300.0, b.rates!!.ht!!.maxRateMbps!!, 0.001)
    }

    @Test
    fun `an HT element with no MCS bit set is absent rather than MCS0`() {
        val b = BeaconElements.parse(listOf(ie(1, 0x8C), 45 to htBody(info0 = 0x00)))

        assertNull(b.rates!!.ht)
    }

    @Test
    fun `VHT 1 stream MCS9 80MHz short GI is the famous 433 Mbps`() {
        // The number printed on every "AC1200"-class router box, and the reason this is worth
        // getting exactly right rather than approximately right.
        val body = vhtBody(info0 = 0x20, 1 to 2) // shortGi80, nss1 = MCS0-9
        val b = BeaconElements.parse(listOf(191 to body))

        assertEquals(mapOf(1 to 9), b.rates!!.vht!!.maxMcsByNss)
        assertFalse(b.rates!!.vht!!.channelWidth160)
        assertEquals(433.3, b.rates!!.vht!!.maxRateMbps!!, 0.01)
    }

    @Test
    fun `VHT skips a standard-defined gap rather than returning nothing for it`() {
        // 3 streams, MCS0-9 advertised, 160MHz with short GI. MCS9 at 3 streams/160MHz is one of
        // the combinations the standard itself does not define -- WifiPhyRatesTest pins that gap
        // directly, but the point of this test is that BeaconElements' search still finds the real
        // maximum (MCS8) instead of colliding with the gap and reporting nothing.
        val body = vhtBody(info0 = 0x0C or 0x40, 3 to 2) // width160 (bits2-3=11->160), shortGi160
        val b = BeaconElements.parse(listOf(191 to body))

        assertEquals(2340.0, b.rates!!.vht!!.maxRateMbps!!, 0.01)
    }

    @Test
    fun `VHT supersedes a weaker HT element on the same beacon`() {
        val ht = 45 to htBody(info0 = 0x02 or 0x40, 7) // 40MHz short GI, MCS7 -> 150
        val vht = 191 to vhtBody(info0 = 0x20, 1 to 2) // 80MHz short GI, MCS9 -> 433.3
        val b = BeaconElements.parse(listOf(ht, vht))

        assertEquals(433.3, b.rates!!.maxPhyRateMbps!!, 0.01)
    }

    @Test
    fun `no HT or VHT element leaves maxPhyRateMbps null, not zero`() {
        val b = BeaconElements.parse(listOf(ie(1, 0x8C)))

        assertNull(b.rates!!.ht)
        assertNull(b.rates!!.vht)
        assertNull(b.rates!!.maxPhyRateMbps)
    }
}
