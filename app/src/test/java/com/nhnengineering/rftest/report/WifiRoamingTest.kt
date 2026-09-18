package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.ObservedAp
import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiRoamingTest {

    private fun ap(bssid: String, rssi: Int, ssid: String? = "Corp", ageMs: Long = 0) =
        ObservedAp(bssid, ssid, rssi, channel = 36, freqMhz = 5180, ageMs = ageMs)

    private fun point(
        seq: Long,
        bssid: String?,
        rssi: Int?,
        ssid: String? = "Corp",
        aps: List<ObservedAp> = emptyList(),
    ) = TrackPoint(
        sequence = seq, timestampUtcMillis = 1_756_000_000_000 + seq * 1000,
        latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
        rssiDbm = rssi, ssid = ssid, bssid = bssid, channel = 36, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = null, sinrDb = null, rsrqDb = null, cellBand = null, rat = null,
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        aps = aps,
    )

    @Test
    fun `a change of BSSID is a roam, with the level on each side`() {
        val a = WifiRoaming.analyse(
            listOf(point(0, "aa", -70), point(1, "aa", -76), point(2, "bb", -58)),
        )

        val roam = a.roams.single()
        assertEquals("aa", roam.fromBssid)
        assertEquals("bb", roam.toBssid)
        assertEquals(-76, roam.rssiBeforeDbm)
        assertEquals(-58, roam.rssiAfterDbm)
        assertTrue(roam.sameSsid)
        assertEquals(2, a.distinctAps)
    }

    @Test
    fun `staying put is not a roam`() {
        val a = WifiRoaming.analyse((0L..5L).map { point(it, "aa", -70) })

        assertTrue(a.roams.isEmpty())
        assertEquals(6, a.samplesWithServing)
    }

    @Test
    fun `returning to the previous AP counts as a ping-pong`() {
        val a = WifiRoaming.analyse(
            listOf(point(0, "aa", -70), point(1, "bb", -68), point(2, "aa", -69)),
        )

        assertEquals(2, a.roams.size)
        assertEquals(1, a.pingPongs)
    }

    @Test
    fun `a sustained better alternative on the same SSID is sticky`() {
        // The classic fault, and invisible to a coverage map: the signal is fine, from the wrong AP.
        val points = (0L..7L).map {
            point(it, "aa", -78, aps = listOf(ap("bb", -60)))
        }

        val sticky = WifiRoaming.analyse(points).sticky.single()
        assertEquals("aa", sticky.bssid)
        assertEquals(8, sticky.samples)
        assertEquals(18, sticky.maxDeltaDb)
        assertEquals("bb", sticky.bestAlternativeBssid)
    }

    @Test
    fun `a stale neighbour cannot make a client look sticky`() {
        // The neighbour set ages out over a minute. An AP last heard thirty seconds ago was better
        // somewhere else, not here, and comparing against it invents findings.
        val points = (0L..7L).map {
            point(it, "aa", -78, aps = listOf(ap("bb", -60, ageMs = 45_000)))
        }

        assertTrue(WifiRoaming.analyse(points).sticky.isEmpty())
    }

    @Test
    fun `a stronger AP on a different network is not somewhere this client could go`() {
        val points = (0L..7L).map {
            point(it, "aa", -78, aps = listOf(ap("bb", -50, ssid = "Guest")))
        }

        assertTrue(WifiRoaming.analyse(points).sticky.isEmpty())
    }

    @Test
    fun `a brief better alternative is not a finding`() {
        // Two samples is a scan artefact, not a sticky client.
        val points = listOf(
            point(0, "aa", -78, aps = listOf(ap("bb", -60))),
            point(1, "aa", -78, aps = listOf(ap("bb", -60))),
            point(2, "aa", -78),
        )

        assertTrue(WifiRoaming.analyse(points).sticky.isEmpty())
    }

    @Test
    fun `a small difference is noise, not stickiness`() {
        val points = (0L..9L).map { point(it, "aa", -70, aps = listOf(ap("bb", -66))) }

        assertTrue("4 dB is within noise", WifiRoaming.analyse(points).sticky.isEmpty())
    }

    @Test
    fun `a session with no Wi-Fi association yields nothing rather than failing`() {
        val a = WifiRoaming.analyse(listOf(point(0, null, null), point(1, null, null)))

        assertEquals(0, a.samplesWithServing)
        assertEquals(0, a.distinctAps)
        assertTrue(a.roams.isEmpty())
    }

    @Test
    fun `changing network is recorded as a roam but marked as a different SSID`() {
        val a = WifiRoaming.analyse(
            listOf(point(0, "aa", -70, ssid = "Corp"), point(1, "bb", -60, ssid = "Guest")),
        )

        assertEquals(false, a.roams.single().sameSsid)
    }
}
