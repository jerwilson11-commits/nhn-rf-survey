package com.nhnengineering.rftest.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end pivot: a wide session CSV in, iBwave long-format out. Exercises the wide→long
 * explosion, the EARFCN/NR-ARFCN→MHz conversion in situ, the unit labelling, and the honest
 * skip-and-count of un-positioned rows.
 */
class IBwaveCsvExporterTest {

    private val inHeader = listOf(
        "timestamp_utc", "seq", "lat", "lon", "operator",
        "lte_band", "lte_earfcn", "lte_pci", "lte_rsrp", "lte_rsrq", "lte_rssnr",
        "nr_band", "nr_arfcn", "nr_pci", "nr_ss_rsrp", "nr_ss_rsrq", "nr_ss_sinr",
        "wifi_ssid", "wifi_band", "wifi_freq_mhz", "wifi_rssi",
    )

    private val outHeader = listOf(
        "longitude", "latitude", "timestamp_utc",
        "technology", "operator", "band", "freq_mhz", "pci", "metric", "value", "unit",
    )

    /** Builds a row aligned to [inHeader]; unspecified columns are left empty. */
    private fun rowOf(vararg pairs: Pair<String, String>): String {
        val m = pairs.toMap()
        return inHeader.joinToString(",") { m[it] ?: "" }
    }

    private val time = "2026-09-03T18:22:04Z"
    private val lat = "32.715000"
    private val lon = "-117.161000"

    private fun writeSession(vararg dataRows: String): File {
        val f = File.createTempFile("session", ".csv").apply { deleteOnExit() }
        f.writeText((listOf(inHeader.joinToString(",")) + dataRows).joinToString("\n") + "\n")
        return f
    }

    private fun run(session: File, includeQuality: Boolean = true): Pair<IBwaveCsvExporter.Result, List<Map<String, String>>> {
        val out = File.createTempFile("ibwave", ".csv").apply { deleteOnExit() }
        val result = runBlocking { IBwaveCsvExporter.write(session, out, includeQuality) }
        val lines = out.readLines().filter { it.isNotBlank() }
        assertEquals("longitude must come first for a no-touch iBwave import", outHeader.joinToString(","), lines.first())
        val rows = lines.drop(1).map { line ->
            val c = SessionReader.splitCsv(line)
            outHeader.indices.associate { outHeader[it] to (c.getOrNull(it) ?: "") }
        }
        return result to rows
    }

    private fun List<Map<String, String>>.pick(tech: String, metric: String) =
        single { it["technology"] == tech && it["metric"] == metric }

    @Test
    fun pivots_lte_nr_and_wifi_with_frequency_conversion() {
        val session = writeSession(
            rowOf(
                "timestamp_utc" to time, "seq" to "1", "lat" to lat, "lon" to lon, "operator" to "AT&T",
                "lte_band" to "B2", "lte_earfcn" to "900", "lte_pci" to "177",
                "lte_rsrp" to "-98", "lte_rsrq" to "-11", "lte_rssnr" to "12",
            ),
            rowOf(
                "timestamp_utc" to time, "seq" to "2", "lat" to lat, "lon" to lon, "operator" to "T-Mobile",
                "nr_band" to "n78", "nr_arfcn" to "632628", "nr_pci" to "441",
                "nr_ss_rsrp" to "-104", "nr_ss_rsrq" to "-12", "nr_ss_sinr" to "8",
            ),
            rowOf(
                "timestamp_utc" to time, "seq" to "3", "lat" to lat, "lon" to lon,
                "wifi_ssid" to "VenueWiFi", "wifi_band" to "5 GHz", "wifi_freq_mhz" to "5180", "wifi_rssi" to "-67",
            ),
            // No fix: has RF but no lat/lon — must be skipped and counted, not emitted.
            rowOf("timestamp_utc" to time, "seq" to "4", "operator" to "Verizon", "lte_earfcn" to "66886", "lte_rsrp" to "-90"),
        )

        val (result, rows) = run(session)

        // 3 LTE (RSRP/RSRQ/RS-SNR) + 3 NR (SS-RSRP/RSRQ/SINR) + 1 Wi-Fi (RSSI) = 7; one row skipped.
        assertEquals(7, result.rowsWritten)
        assertEquals(1, result.sourceRowsSkipped)
        assertEquals(7, rows.size)

        val lteRsrp = rows.pick("LTE", "RSRP")
        assertEquals(lon, lteRsrp["longitude"])
        assertEquals(lat, lteRsrp["latitude"])
        assertEquals("AT&T", lteRsrp["operator"])
        assertEquals("B2", lteRsrp["band"])
        assertEquals("1960", lteRsrp["freq_mhz"])   // EARFCN 900, Band 2
        assertEquals("177", lteRsrp["pci"])
        assertEquals("-98", lteRsrp["value"])
        assertEquals("dBm", lteRsrp["unit"])

        // Quality metrics are dB, not dBm — the label must reflect that.
        assertEquals("dB", rows.pick("LTE", "RSRQ")["unit"])
        assertEquals("12", rows.pick("LTE", "RS-SNR")["value"])

        val nrRsrp = rows.pick("NR", "SS-RSRP")
        assertEquals("3489.42", nrRsrp["freq_mhz"])  // NR-ARFCN 632628, n78
        assertEquals("-104", nrRsrp["value"])
        assertEquals("dBm", nrRsrp["unit"])
        assertEquals("441", nrRsrp["pci"])

        val wifi = rows.pick("Wi-Fi", "RSSI")
        assertEquals("VenueWiFi", wifi["operator"])  // SSID is the layer identity
        assertEquals("5180", wifi["freq_mhz"])
        assertEquals("", wifi["pci"])
        assertEquals("dBm", wifi["unit"])

        // The skipped no-fix row must not have leaked in under any layer.
        assertTrue(rows.none { it["operator"] == "Verizon" })
    }

    @Test
    fun includeQuality_false_emits_power_metric_only() {
        val session = writeSession(
            rowOf(
                "timestamp_utc" to time, "lat" to lat, "lon" to lon, "operator" to "AT&T",
                "lte_band" to "B48", "lte_earfcn" to "55990", "lte_rsrp" to "-101", "lte_rsrq" to "-13", "lte_rssnr" to "5",
            ),
        )

        val (result, rows) = run(session, includeQuality = false)

        assertEquals(1, result.rowsWritten)
        assertEquals("3625", rows.pick("LTE", "RSRP")["freq_mhz"])  // Band 48 CBRS
        assertNull(rows.firstOrNull { it["metric"] == "RSRQ" })
    }

    @Test
    fun empty_session_writes_header_only() {
        val empty = File.createTempFile("empty", ".csv").apply { deleteOnExit(); writeText("") }
        val (result, rows) = run(empty)
        assertEquals(0, result.rowsWritten)
        assertEquals(0, result.sourceRowsSkipped)
        assertEquals(0, rows.size)
    }
}
