package com.nhnengineering.rftest.session

import com.nhnengineering.rftest.model.CellularSample
import com.nhnengineering.rftest.model.GeoPoint
import com.nhnengineering.rftest.model.IndoorPosition
import com.nhnengineering.rftest.model.LteCell
import com.nhnengineering.rftest.model.MeasurementSample
import com.nhnengineering.rftest.model.NrState
import com.nhnengineering.rftest.model.Rat
import com.nhnengineering.rftest.model.SimState
import com.nhnengineering.rftest.model.ThroughputSample
import com.nhnengineering.rftest.model.WifiBand
import com.nhnengineering.rftest.model.WifiSample
import com.nhnengineering.rftest.model.WifiSecurity
import com.nhnengineering.rftest.model.WifiStandard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import com.nhnengineering.rftest.session.SessionReader
import org.junit.Test

/**
 * Guards the failure that actually happened in Phase 2.
 *
 * The row builder emitted 29 empty cellular cells against a 31-column header, shifting every field
 * after it by two positions. A runtime assertion caught it, but only after the app had already
 * written a session and crashed. These tests catch the same class of defect at build time, and they
 * matter more each time the schema grows — it has now grown twice, for throughput and for indoor
 * positioning.
 *
 * A count mismatch is what these detect. A transposition — RSRQ values written into the RSRP
 * column — would pass, which is why the emission order sits directly beneath the column list in the
 * source rather than somewhere else in the file.
 */
class CsvSchemaTest {

    private fun fullSample() = MeasurementSample(
        sessionId = "test",
        sequence = 1,
        timestampUtcMillis = 1_756_000_000_000,
        location = GeoPoint(26.05, -80.13, 3.0, 4f, 1.2f, 90f, 1_756_000_000_000, "gps"),
        wifi = WifiSample(
            ssid = "TestAP", bssid = "aa:bb:cc:dd:ee:11", rssiDbm = -55,
            frequencyMhz = 5805, channel = 161, band = WifiBand.BAND_5,
            channelWidthMhz = 80, standard = WifiStandard.AX, security = WifiSecurity.WPA3_SAE,
            txLinkMbps = 1134, rxLinkMbps = 1200, maxSupportedTxMbps = 1200,
            neighbors = emptyList(), neighborScanAgeMs = 1000,
            coChannelCount = 1, adjacentChannelCount = 0,
        ),
        cellular = CellularSample(
            simState = SimState.READY, rat = Rat.LTE, nrState = NrState.NONE,
            overrideNetworkType = "none", isRoaming = false,
            mcc = "310", mnc = "260", operator = "T-Mobile",
            lte = LteCell(
                registered = true, ci = 12345678, enbId = 48225, sectorId = 78, pci = 100,
                tac = 5000, earfcn = 66836, band = 66, bandLabel = "B66", dlFreqMhz = 2150.0,
                bandwidthKhz = 10000, rsrpDbm = -95, rsrqDb = -11, rssnrDb = 12, rssiDbm = -70,
                cqi = 10, timingAdvance = 3, mcc = "310", mnc = "260", operator = "T-Mobile",
            ),
            nr = null, neighbors = emptyList(),
        ),
        indoor = IndoorPosition("plan.png", 0.25f, 0.75f, "Lobby"),
        throughput = ThroughputSample(
            downloadMbps = 100.0, uploadMbps = 20.0, latencyMedianMs = 30.0,
            latencyMinMs = 25.0, latencyMaxMs = 90.0, jitterMs = 5.0, lossPct = 0.0,
            server = "example.com",
        ),
        note = "speedtest",
    )

    @Test
    fun `schema is the expected width`() {
        assertEquals(75, CSV_COLUMN_COUNT)
        assertEquals(75, CSV_HEADER.split(",").size)
    }

    @Test
    fun `fully populated row matches the header width`() {
        val cells = fullSample().toCsvRow().split(",")
        assertEquals(
            "row width must equal header width, or every column after the mismatch is shifted",
            CSV_COLUMN_COUNT,
            cells.size,
        )
    }

    @Test
    fun `empty row matches the header width`() {
        // The all-null case is the one that shifted in Phase 2: the blanks are emitted by count,
        // so an off-by-two only shows when the optional sections are absent.
        val bare = MeasurementSample(
            sessionId = "t", sequence = 0, timestampUtcMillis = 1_756_000_000_000,
            location = null, wifi = null,
        )
        assertEquals(CSV_COLUMN_COUNT, bare.toCsvRow().split(",").size)
    }

    @Test
    fun `header column names are unique`() {
        val names = CSV_HEADER.split(",")
        assertEquals("duplicate column names make a CSV ambiguous to any consumer",
            names.size, names.toSet().size)
    }

    @Test
    fun `locale-sensitive numbers use a decimal point, never a comma`() {
        // A comma-decimal locale would split the field and shift every later column. This was a
        // real defect, invisible on a US device.
        val row = fullSample().toCsvRow()
        assertTrue("latitude should contain a decimal point", row.contains("26.05"))
        assertTrue("longitude should contain a decimal point", row.contains("-80.13"))
    }

    // ---- Neighbour JSON round-trip ---------------------------------------

    @Test
    fun `an absent neighbour level parses back as null, not as zero dBm`() {
        // The writer emits JSON null for a missing level. If the parser turned that into 0 the
        // cell would be the strongest reading in the file and would win every best-server and
        // dominance ranking. This is the same null-as-zero class of bug that cost this project a
        // walk of GPS distance data.
        val json = """[{"rat":"NR","pci":42,"ch":501390,"band":"n41","rsrp":null,""" +
            """"rsrq":null,"age_ms":0}]"""

        val cells = SessionReader.parseCellNeighbors(json)

        assertEquals(1, cells.size)
        assertNull(cells[0].rsrpDbm)
        assertEquals(42, cells[0].pci)
        assertEquals("n41", cells[0].band)
    }

    @Test
    fun `neighbour json parses negative levels and ages`() {
        val json = """[{"rat":"NR","pci":1,"ch":2,"band":"n41","rsrp":-94,"rsrq":-11,"age_ms":0},""" +
            """{"rat":"LTE","pci":300,"ch":5110,"band":"B66","rsrp":-118,"rsrq":-15,"age_ms":4200}]"""

        val cells = SessionReader.parseCellNeighbors(json)

        assertEquals(2, cells.size)
        assertEquals(-94, cells[0].rsrpDbm)
        assertEquals(0L, cells[0].ageMs)
        assertEquals(-118, cells[1].rsrpDbm)
        assertEquals(4200L, cells[1].ageMs)
        // Parsed neighbours are never marked serving -- only the row's own cell columns are.
        assertFalse(cells.any { it.serving })
    }

    @Test
    fun `an empty or missing neighbour array yields no cells`() {
        assertTrue(SessionReader.parseCellNeighbors("[]").isEmpty())
        assertTrue(SessionReader.parseCellNeighbors("").isEmpty())
        assertTrue(SessionReader.parseCellNeighbors(null).isEmpty())
    }

    // ---- Area label ------------------------------------------------------
    //
    // The area label shares the `waypoint` column with the indoor label rather than adding a
    // column, which keeps the schema at 73 and keeps every existing consumer working. That makes
    // the precedence between them worth pinning: a silent swap would relabel a whole survey.

    private fun waypointOf(sample: MeasurementSample): String {
        val i = CSV_HEADER.split(",").indexOf("waypoint")
        assertTrue("the schema must still have a waypoint column", i >= 0)
        return SessionReader.splitCsv(sample.toCsvRow())[i]
    }

    @Test
    fun `an area label reaches the waypoint column on a walk with no floorplan`() {
        // The case this was built for: an outdoor walk, no floorplan, indoor position impossible.
        val sample = MeasurementSample(
            sessionId = "t", sequence = 0, timestampUtcMillis = 1_756_000_000_000,
            location = null, wifi = null, areaLabel = "Driveway",
        )

        assertEquals("Driveway", waypointOf(sample))
        assertEquals(CSV_COLUMN_COUNT, sample.toCsvRow().split(",").size)
    }

    @Test
    fun `the indoor label wins over the area label when both are set`() {
        // A floorplan tap is the more specific statement of where the operator was, so it must
        // not be overwritten by a sticky area label set on the way in.
        val sample = MeasurementSample(
            sessionId = "t", sequence = 0, timestampUtcMillis = 1_756_000_000_000,
            location = null, wifi = null,
            indoor = IndoorPosition("plan.png", 0.5f, 0.5f, "Ballroom"),
            areaLabel = "Indoor",
        )

        assertEquals("Ballroom", waypointOf(sample))
    }

    @Test
    fun `an unset area label leaves the waypoint column empty, not the string null`() {
        val sample = MeasurementSample(
            sessionId = "t", sequence = 0, timestampUtcMillis = 1_756_000_000_000,
            location = null, wifi = null,
        )

        assertEquals("", waypointOf(sample))
    }

    @Test
    fun `an area label containing a comma does not shift the row`() {
        // "Lobby, east" is a name an operator would plausibly type, and an unquoted comma would
        // shift every column after it -- the exact Phase 2 failure, reintroduced by a text field.
        val sample = MeasurementSample(
            sessionId = "t", sequence = 0, timestampUtcMillis = 1_756_000_000_000,
            location = null, wifi = null, areaLabel = "Lobby, east",
        )

        assertEquals(CSV_COLUMN_COUNT, SessionReader.splitCsv(sample.toCsvRow()).size)
        assertEquals("Lobby, east", waypointOf(sample))
    }

    // ---- Throughput failures ---------------------------------------------

    private fun tpFieldOf(sample: MeasurementSample, column: String): String {
        val i = CSV_HEADER.split(",").indexOf(column)
        assertTrue("schema must have a $column column", i >= 0)
        return SessionReader.splitCsv(sample.toCsvRow())[i]
    }

    private fun throughputRow(
        down: Double?,
        up: Double?,
        error: String?,
    ) = MeasurementSample(
        sessionId = "t", sequence = 0, timestampUtcMillis = 1_756_000_000_000,
        location = null, wifi = null,
        throughput = ThroughputSample(
            downloadMbps = down, uploadMbps = up,
            latencyMedianMs = null, latencyMinMs = null, latencyMaxMs = null,
            jitterMs = null, lossPct = null, server = "speed.example.com", error = error,
        ),
    )

    @Test
    fun `a failed direction records why, not just an empty cell`() {
        // The 2026-09-02 walk wrote eight rows with upload only and nothing to explain the gap,
        // because the endpoint was returning HTTP 429. In a client report an empty dl_mbps reads
        // as "not measured here" -- a much weaker and quite different statement from "the
        // endpoint refused us".
        val row = throughputRow(down = null, up = 24.8, error = "down: download rate-limited (HTTP 429)")

        assertEquals("", tpFieldOf(row, "dl_mbps"))
        assertEquals("24.800", tpFieldOf(row, "ul_mbps"))
        assertTrue(
            "the reason must survive into the CSV",
            tpFieldOf(row, "tp_error").contains("429"),
        )
    }

    @Test
    fun `a successful burst leaves the error column empty`() {
        val row = throughputRow(down = 90.0, up = 26.3, error = null)

        assertEquals("", tpFieldOf(row, "tp_error"))
        assertEquals(CSV_COLUMN_COUNT, SessionReader.splitCsv(row.toCsvRow()).size)
    }

    @Test
    fun `an error containing a comma does not shift the row`() {
        // Failure messages join both directions with "; " and carry endpoint text, so a comma is
        // entirely plausible -- and unquoted would shift every column after it.
        val row = throughputRow(null, null, "down: HTTP 429, up: connection reset")

        assertEquals(CSV_COLUMN_COUNT, SessionReader.splitCsv(row.toCsvRow()).size)
        assertEquals("down: HTTP 429, up: connection reset", tpFieldOf(row, "tp_error"))
    }
}
