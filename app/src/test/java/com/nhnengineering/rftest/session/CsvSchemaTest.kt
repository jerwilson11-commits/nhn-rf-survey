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
        assertEquals(73, CSV_COLUMN_COUNT)
        assertEquals(73, CSV_HEADER.split(",").size)
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
}
