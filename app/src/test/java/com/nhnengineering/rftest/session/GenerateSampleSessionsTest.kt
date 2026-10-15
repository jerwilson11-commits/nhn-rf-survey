package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.report.BenchmarkComparison
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Not a behavioural test — a generator. It writes two schema-valid 5G-SA session CSVs (placeholder
 * coordinates) to `app/build/sample-sessions/`, using the app's own [CSV_HEADER] so the column layout
 * can never drift from production, and round-trips them through [SessionReader] + [BenchmarkComparison]
 * so a broken file fails here rather than on the handset. The two files are then pushed to a device to
 * exercise the A-B benchmark end to end.
 */
class GenerateSampleSessionsTest {

    private val t0 = Instant.parse("2026-10-15T14:00:00Z").toEpochMilli()
    private val cols = CSV_HEADER.split(",")

    private fun row(fields: Map<String, String>): String {
        val cells = Array(cols.size) { "" }
        for ((k, v) in fields) {
            val i = cols.indexOf(k)
            require(i >= 0) { "unknown column $k" }
            cells[i] = v
        }
        return cells.joinToString(",")
    }

    /** One 5G-SA sample. rsrp null = a service-loss gap (no serving cell). */
    private fun saRow(session: String, seq: Int, rsrp: Int?, sinr: Int?, pci: Int, dl: Double?, ul: Double?): String {
        val base = mutableMapOf(
            "timestamp_utc" to Instant.ofEpochMilli(t0 + seq * 10_000L).toString(),
            "session_id" to session,
            "seq" to seq.toString(),
            "device_model" to "OnePlus LE2115",
            "device_build" to "Android 14",
            "lat" to "26.0500%02d".format(seq),      // placeholder, per publishing hygiene
            "lon" to "-80.1397%02d".format(seq),
            "gps_accuracy_m" to "4.0",
            "speed_mps" to "1.30",
            "gps_provider" to "fused",
        )
        if (rsrp != null) {
            base += mapOf(
                "rat" to "5G SA",
                "mcc" to "310", "mnc" to "260", "operator" to "T-Mobile",
                "nr_pci" to pci.toString(),
                "nr_arfcn" to "501390",
                "nr_band" to "n25",
                "nr_ss_rsrp" to rsrp.toString(),
                "nr_ss_sinr" to (sinr ?: 0).toString(),
                "nr_ss_rsrq" to "-10",
            )
        }
        dl?.let { base["dl_mbps"] = it.toString() }
        ul?.let { base["ul_mbps"] = it.toString() }
        return row(base)
    }

    private fun write(file: File, session: String, body: List<String>) {
        file.writeText((listOf(CSV_HEADER) + body).joinToString("\n") + "\n")
    }

    @Test
    fun `generate two benchmark sample sessions and self-verify`() = runBlocking {
        val dir = File("build/sample-sessions").apply { mkdirs() }

        // A — strong: -85 dBm, SINR 18, ~150 Mbps.
        val aFile = File(dir, "CarrierA-TMobile-strong.csv")
        write(aFile, "A", (1..24).map { saRow("A", it, rsrp = -85, sinr = 18, pci = 929, dl = 150.0, ul = 42.0) })

        // B — weaker, with a two-sample service-loss gap mid-route.
        val bFile = File(dir, "CarrierB-weak.csv")
        val bRows = buildList {
            for (i in 1..11) add(saRow("B", i, rsrp = -108, sinr = 2, pci = 288, dl = 30.0, ul = 8.0))
            add(saRow("B", 12, rsrp = null, sinr = null, pci = 0, dl = null, ul = null))
            add(saRow("B", 13, rsrp = null, sinr = null, pci = 0, dl = null, ul = null))
            for (i in 14..24) add(saRow("B", i, rsrp = -110, sinr = 1, pci = 288, dl = 25.0, ul = 6.0))
        }
        write(bFile, "B", bRows)

        // Round-trip: both must parse, and the benchmark must favour A.
        val a = SessionReader.read(aFile)!!
        val b = SessionReader.read(bFile)!!
        assertTrue("A parsed cellular RSRP", a.second.any { it.rsrpDbm != null })
        assertTrue("B parsed cellular RSRP", b.second.any { it.rsrpDbm != null })

        val cmp = BenchmarkComparison.analyse(
            BenchmarkComparison.Side("Carrier A", a.first, a.second),
            BenchmarkComparison.Side("Carrier B", b.first, b.second),
        )
        assertTrue(cmp.hasData)
        assertTrue("A should win the benchmark (${cmp.aWins}-${cmp.bWins})", cmp.aWins > cmp.bWins)
        println("Sample sessions written to ${dir.absolutePath}; verdict: ${cmp.verdict}")
    }
}
