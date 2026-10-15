package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.billing.SubscriptionTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Generates FREE / FIELD / PRO sample KMLs from one representative session into
 * `app/build/kml-samples/`, and pins the tier escalation (each tier is a superset of the one below).
 *
 * Coordinates are the repo's placeholder values (26.0500xx / -80.1397xx), never a real location.
 */
class KmlTierSampleTest {

    private val t0 = 1_760_000_000_000L // fixed, so output is stable

    private fun tp(
        seq: Int,
        latOff: Int,
        lonOff: Int,
        rsrp: Int?,
        rat: String?,
        cellBand: String?,
        pci: Int?,
        operator: String? = "T-Mobile",
        sinr: Int? = null,
        rsrq: Int? = null,
        nci: Long? = null,
        rssi: Int? = null,
        ssid: String? = null,
        bssid: String? = null,
        wifiChannel: Int? = null,
        wifiBand: String? = null,
        dl: Double? = null,
        ul: Double? = null,
        cells: List<ObservedCell> = emptyList(),
        modemNeighbours: Boolean? = null,
        nrPci: Int? = null,
        ltePci: Int? = null,
        earfcn: Int? = null,
        nrarfcn: Int? = null,
        lockPci: Int? = null,
        lockArfcn: Int? = null,
    ) = TrackPoint(
        sequence = seq.toLong(),
        timestampUtcMillis = t0 + seq * 1000L,
        latitudeDeg = 26.050000 + latOff * 0.00008,
        longitudeDeg = -80.139700 + lonOff * 0.00008,
        accuracyM = 4f,
        speedMps = 1.3f,
        rssiDbm = rssi,
        ssid = ssid,
        bssid = bssid,
        channel = wifiChannel,
        band = wifiBand,
        coChannel = if (rssi != null) 1 else null,
        adjacentChannel = if (rssi != null) 0 else null,
        rsrpDbm = rsrp,
        sinrDb = sinr,
        rsrqDb = rsrq,
        cellBand = cellBand,
        rat = rat,
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
        networkOperator = operator,
        mcc = if (operator != null) "310" else null,
        mnc = if (operator != null) "260" else null,
        servingPci = pci,
        servingNci = nci,
        cells = cells,
        modemNeighbours = modemNeighbours,
        downloadMbps = dl,
        uploadMbps = ul,
        nrServingPci = nrPci,
        nrServingArfcn = nrarfcn,
        lteServingPci = ltePci,
        lteServingEarfcn = earfcn,
        lockWatchPci = lockPci,
        lockWatchArfcn = lockArfcn,
    )

    private fun nbr(pci: Int, ch: Int, rsrp: Int, band: String) =
        ObservedCell(pci = pci, channel = ch, rsrpDbm = rsrp, band = band, serving = false, ageMs = 0)

    private fun session(): List<TrackPoint> = listOf(
        // NR SA n25, strong, with Wi-Fi + a couple of public-API neighbours
        tp(1, 0, 0, -82, "NR SA", "n25", 929, sinr = 18, rsrq = -10, nci = 6592184333L,
            rssi = -45, ssid = "TestAP-5G", bssid = "aa:bb:cc:dd:ee:01", wifiChannel = 36, wifiBand = "5 GHz",
            cells = listOf(nbr(288, 2300, -96, "n25"), nbr(368, 2300, -104, "n25"))),
        tp(2, 1, 1, -88, "NR SA", "n25", 929, sinr = 14, rsrq = -11, nci = 6592184333L,
            dl = 142.0, ul = 38.0, cells = listOf(nbr(288, 2300, -98, "n25"))),
        tp(3, 2, 2, -94, "NR SA", "n25", 929, sinr = 9, rsrq = -13, nci = 6592184333L,
            rssi = -58, ssid = "TestAP-5G", bssid = "aa:bb:cc:dd:ee:01", wifiChannel = 36, wifiBand = "5 GHz"),
        // serving-cell change (PCI 929 -> 288)
        tp(4, 3, 3, -97, "NR SA", "n25", 288, sinr = 6, rsrq = -14, nci = 6592184999L,
            cells = listOf(nbr(929, 2300, -99, "n25"))),
        // NR -> LTE fallback
        tp(5, 4, 4, -101, "LTE", "B2", 114, sinr = 3, rsrq = -15, earfcn = 650,
            cells = listOf(nbr(288, 1000, -103, "B2"))),
        tp(6, 5, 5, -108, "LTE", "B66", 126, sinr = 0, rsrq = -16, earfcn = 66536),
        // FirstNet Band 14
        tp(7, 6, 6, -95, "LTE", "B14", 55, sinr = 5, rsrq = -12, earfcn = 5330),
        // back to NR with modem neighbours + EN-DC legs + lock watch
        tp(8, 7, 7, -90, "NR SA", "n41", 206, sinr = 12, rsrq = -10, nci = 6592184500L,
            modemNeighbours = true, nrPci = 206, ltePci = 114, nrarfcn = 501390,
            lockPci = 206, lockArfcn = 501390,
            cells = listOf(nbr(216, 501390, -100, "n41"), nbr(673, 501390, -114, "n41"), nbr(929, 2300, -97, "n25"))),
        // service lost (no cellular), Wi-Fi only
        tp(9, 8, 8, null, null, null, null, operator = null,
            rssi = -62, ssid = "TestAP-5G", bssid = "aa:bb:cc:dd:ee:01", wifiChannel = 36, wifiBand = "5 GHz"),
        // recover, spread of levels for colour
        tp(10, 9, 9, -110, "LTE", "B2", 114, sinr = 1, rsrq = -16, earfcn = 650),
        tp(11, 10, 10, -118, "LTE", "B2", 114, sinr = -2, rsrq = -17, earfcn = 650),
        tp(12, 11, 11, -98, "NR SA", "n25", 929, sinr = 8, rsrq = -12, nci = 6592184333L,
            dl = 88.0, ul = 22.0),
        tp(13, 12, 12, -86, "NR SA", "n25", 929, sinr = 15, rsrq = -10, nci = 6592184333L,
            rssi = -50, ssid = "TestAP-5G", bssid = "aa:bb:cc:dd:ee:01", wifiChannel = 36, wifiBand = "5 GHz"),
        tp(14, 13, 13, -83, "NR SA", "n25", 929, sinr = 19, rsrq = -9, nci = 6592184333L),
    )

    private fun summary(out: File, pts: List<TrackPoint>) = SessionSummary(
        file = out,
        displayName = "Sample walk 2026-10-15",
        startedAtUtcMillis = t0,
        durationMs = (pts.size - 1) * 1000L,
        rowCount = pts.size,
        pointCount = pts.count { it.hasGpsPosition },
        rssiMin = pts.mapNotNull { it.rssiDbm }.minOrNull(),
        rssiMax = pts.mapNotNull { it.rssiDbm }.maxOrNull(),
        minLat = pts.minOf { it.latitudeDeg!! }, maxLat = pts.maxOf { it.latitudeDeg!! },
        minLon = pts.minOf { it.longitudeDeg!! }, maxLon = pts.maxOf { it.longitudeDeg!! },
    )

    @Test
    fun `generate free field pro sample KMLs and pin the escalation`() = runBlocking {
        val dir = File("build/kml-samples").apply { mkdirs() }
        val pts = session()

        val tiers = mapOf(
            "free" to SubscriptionTier.FREE,
            "field" to SubscriptionTier.FIELD,
            "pro" to SubscriptionTier.PRO,
        )
        val text = tiers.mapValues { (name, tier) ->
            val out = File(dir, "SiteSurveyPro-sample-$name.kml")
            TrackExporters.writeKml(summary(out, pts), pts, out, tier).readText()
        }

        // All tiers: a route line and a coverage/cellular layer that carries cellular data.
        text.values.forEach {
            assertTrue(it.contains("<name>Route</name>"))
            assertTrue("every tier must carry cellular RSRP", it.contains("RSRP "))
        }
        // FREE: single Coverage folder, no Field layers.
        assertTrue(text["free"]!!.contains("<name>Coverage</name>"))
        assertTrue(!text["free"]!!.contains("<name>Events"))
        // FIELD adds split radio layers + events + throughput + FirstNet (superset of free).
        val field = text["field"]!!
        assertTrue(field.contains("<name>Cellular coverage</name>"))
        assertTrue(field.contains("<name>Wi-Fi coverage</name>"))
        assertTrue(field.contains("<name>Throughput</name>"))
        assertTrue(field.contains("<name>Events (handover / drop)</name>"))
        assertTrue(field.contains("<name>FirstNet (Band 14 / n14)</name>"))
        assertTrue("field has no lock-watch / modem layer", !field.contains("Cell lock watch"))
        // PRO adds deep identity (gNB split) + modem neighbours + lock-watch (superset of field).
        val pro = text["pro"]!!
        assertTrue(pro.contains("<name>Cellular coverage</name>"))
        assertTrue(pro.contains("<name>Cell lock watch</name>"))
        assertTrue(pro.contains("gNB "))
        assertTrue(pro.contains("Neighbours (modem)"))
        // Size escalation sanity: pro >= field >= free.
        assertTrue(pro.length >= field.length && field.length >= text["free"]!!.length)

        println("KML samples written to ${dir.absolutePath}")
    }

    @Test
    fun `generate free field pro sample GeoJSONs and pin the escalation`() = runBlocking {
        val dir = File("build/kml-samples").apply { mkdirs() }
        val pts = session()

        val tiers = mapOf(
            "free" to SubscriptionTier.FREE,
            "field" to SubscriptionTier.FIELD,
            "pro" to SubscriptionTier.PRO,
        )
        val text = tiers.mapValues { (name, tier) ->
            val out = File(dir, "SiteSurveyPro-sample-$name.geojson")
            TrackExporters.writeGeoJson(summary(out, pts), pts, out, tier).readText()
        }

        // All tiers: dual-radio — cellular samples now carry RSRP (the old writer dropped them).
        text.forEach { (name, g) ->
            assertTrue(g.contains("\"type\":\"FeatureCollection\""))
            assertTrue(g.contains("\"radio\":\"cellular\""))
            assertTrue(g.contains("\"radio\":\"wifi\""))
            assertTrue("$name must carry cellular RSRP", g.contains("\"rsrp_dbm\":"))
            assertTrue(g.contains("\"tier\":\"$name\""))
        }
        // FREE: no Field-only keys.
        val free = text["free"]!!
        assertTrue(!free.contains("\"kind\":\"event\""))
        assertTrue(!free.contains("\"earfcn\":"))
        // FIELD adds events + EARFCN/NR-ARFCN + neighbour count + throughput (superset of free).
        val field = text["field"]!!
        assertTrue(field.contains("\"kind\":\"event\""))
        assertTrue(field.contains("\"nrarfcn\":") || field.contains("\"earfcn\":"))
        assertTrue(field.contains("\"neighbour_count\":"))
        assertTrue(field.contains("\"download_mbps\":"))
        assertTrue("field has no gNB split", !field.contains("\"gnb_id\":"))
        // PRO adds gNB split + EN-DC + modem flag (superset of field).
        val pro = text["pro"]!!
        assertTrue(pro.contains("\"gnb_id\":"))
        assertTrue(pro.contains("\"endc_nr_pci\":"))
        assertTrue(pro.contains("\"modem_neighbours\":true"))
        assertTrue(pro.length >= field.length && field.length >= free.length)

        println("GeoJSON samples written to ${dir.absolutePath}")
    }
}
