package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.report.SessionStats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the throughput round trip: written to CSV, read back, grouped by band and technology.
 *
 * The unit tests for the aggregation feed it TrackPoints built by hand, so they cannot catch the
 * step that actually broke -- a column present in the file and never parsed. dl_mbps and ul_mbps
 * sat in every session for months and stopped at the reader, which meant the report could not have
 * reported throughput however good the analysis behind it was.
 *
 * It also pins the reader's one hard requirement, which is not obvious from the outside: a CSV
 * without lat and lon columns is rejected outright, whatever else it contains. Most columns are
 * optional and absence is tolerated; those two are not, and a hand-made fixture missing them reads
 * back as a session that simply will not open.
 */
class SessionReaderThroughputTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val header =
        "seq,timestamp_utc,lat,lon,lte_rsrp,nr_ss_rsrp,lte_band,nr_band,rat,dl_mbps,ul_mbps,tp_error"

    private fun csv(vararg rows: String) = temp.newFile("session_test.csv").apply {
        writeText(header + "\n" + rows.joinToString("\n") + "\n")
    }

    @Test
    fun `throughput written to a row is read back onto that sample`() {
        val f = csv(
            "1,1758360000000,32.7,-117.1,,-92,,n25,5G SA,120.5,22.1,",
            "2,1758360001000,32.7,-117.1,,-93,,n25,5G SA,,,",
        )

        val (_, points) = runBlocking { SessionReader.read(f) }!!

        assertEquals(120.5, points[0].downloadMbps!!, 1e-9)
        assertEquals(22.1, points[0].uploadMbps!!, 1e-9)
        assertNull("A row with no test must not invent one.", points[1].downloadMbps)
    }

    @Test
    fun `a recorded failure reason survives the round trip`() {
        val f = csv("1,1758360000000,32.7,-117.1,,-92,,n41,5G SA,,18.0,download timed out")

        val (_, points) = runBlocking { SessionReader.read(f) }!!

        assertNull(points[0].downloadMbps)
        assertEquals(18.0, points[0].uploadMbps!!, 1e-9)
        assertEquals("download timed out", points[0].throughputError)
    }

    @Test
    fun `a band carrying two technologies reads back as two groups`() {
        val f = csv(
            "1,1758360000000,32.7,-117.1,-104,,2,,LTE,18.2,6.1,",
            "2,1758360001000,32.7,-117.1,-103,,2,,LTE,22.4,7.0,",
            "3,1758360002000,32.7,-117.1,,-92,,n25,5G SA,140.0,22.1,",
        )

        val (_, points) = runBlocking { SessionReader.read(f) }!!
        val b = SessionStats.throughputBreakdown(points) {
            SessionStats.bandOf(it, SessionStats.Kpi.CELL_RSRP)
        }

        // The reader spells an LTE band "B2" and an NR band "n25"; both carry their RAT.
        assertEquals(listOf("B2  (LTE)", "n25  (5G SA)"), b.groups.map { it.label })
        assertEquals(20.3, b.groups[0].dlMedianMbps!!, 1e-9)
    }

    @Test
    fun `a CSV without lat and lon columns will not open at all`() {
        // Not a quirk worth working around -- it is the guard that made a hand-made fixture
        // silently unopenable, and a test is cheaper than rediscovering it on a handset.
        val f = temp.newFile("no_position.csv").apply {
            writeText("seq,timestamp_utc,nr_band,rat,dl_mbps\n1,1758360000000,n25,5G SA,120.5\n")
        }

        assertNull(runBlocking { SessionReader.read(f) })
    }

    @Test
    fun `a session recorded before these columns existed still reads`() {
        val f = temp.newFile("old.csv").apply {
            writeText("seq,timestamp_utc,lat,lon,nr_ss_rsrp,nr_band,rat\n1,1758360000000,32.7,-117.1,-92,n25,5G SA\n")
        }

        val (_, points) = runBlocking { SessionReader.read(f) }!!

        assertNotNull(points.firstOrNull())
        assertNull(points[0].downloadMbps)
        assertEquals(0, SessionStats.throughputBreakdown(points) { it.cellBand }.groups.size)
    }
}
