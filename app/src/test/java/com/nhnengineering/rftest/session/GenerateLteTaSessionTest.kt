package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.report.TimingAdvanceRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Generator (not a behavioural test): writes a schema-valid **LTE** session CSV carrying `lte_ta`,
 * so the A5 timing-advance → distance report section can be exercised on a device whose live radio is
 * NR (TA is LTE-only). Uses the app's own [CSV_HEADER] and round-trips through [SessionReader] +
 * [TimingAdvanceRange]. Placeholder coordinates per publishing hygiene.
 */
class GenerateLteTaSessionTest {

    private val t0 = Instant.parse("2026-10-15T18:00:00Z").toEpochMilli()
    private val cols = CSV_HEADER.split(",")

    private fun row(fields: Map<String, String>): String {
        val cells = Array(cols.size) { "" }
        for ((k, v) in fields) {
            val i = cols.indexOf(k); require(i >= 0) { "unknown column $k" }
            cells[i] = v
        }
        return cells.joinToString(",")
    }

    private fun lteRow(seq: Int, rsrp: Int, ta: Int, pci: Int, earfcn: Int) = row(
        mapOf(
            "timestamp_utc" to Instant.ofEpochMilli(t0 + seq * 10_000L).toString(),
            "session_id" to "LTE", "seq" to seq.toString(),
            "device_model" to "OnePlus LE2115", "device_build" to "Android 14",
            "lat" to "26.0500%02d".format(seq), "lon" to "-80.1397%02d".format(seq),
            "gps_accuracy_m" to "4.0", "speed_mps" to "1.30", "gps_provider" to "fused",
            "rat" to "LTE", "mcc" to "310", "mnc" to "260", "operator" to "T-Mobile",
            "lte_pci" to pci.toString(), "lte_earfcn" to earfcn.toString(), "lte_band" to "2",
            "lte_rsrp" to rsrp.toString(), "lte_rssnr" to "8", "lte_rsrq" to "-10",
            "lte_ta" to ta.toString(),
        ),
    )

    @Test
    fun `generate an LTE session with timing advance and self-verify`() = runBlocking {
        val dir = File("build/sample-sessions").apply { mkdirs() }
        val file = File(dir, "LTE-timing-advance.csv")

        val rows = buildList {
            // Near cell (PCI 100), small TA — DAS/near macro.
            for (i in 1..8) add(lteRow(i, rsrp = -92, ta = 2, pci = 100, earfcn = 650))
            // Overshooting macro (PCI 288), large TA ~2.3 km.
            for (i in 9..14) add(lteRow(i, rsrp = -108, ta = 30, pci = 288, earfcn = 66536))
        }
        file.writeText((listOf(CSV_HEADER) + rows).joinToString("\n") + "\n")

        val s = SessionReader.read(file)!!
        val ta = TimingAdvanceRange.analyse(s.second)
        assertTrue(ta.hasData)
        assertTrue("both cells present", ta.byCell.size == 2)
        // Overshoot cell is furthest, listed first.
        assertTrue(ta.byCell.first().pci == 288 && ta.byCell.first().medianDistanceM > 2000)
        println("LTE TA session written to ${file.absolutePath}; max distance ${ta.maxDistanceM} m")
    }
}
