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
}
