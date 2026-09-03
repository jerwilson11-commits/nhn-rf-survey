package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the distribution tables.
 *
 * The competitor report this feature came from carries a sign error on fifteen pages — buckets
 * labelled "110 dBm to Infinity dBm" for values that are plainly negative. Several of these tests
 * exist purely so the same class of mistake cannot be made here by formatting.
 */
class DistributionTest {

    private fun pts(vararg rsrp: Int?) = rsrp.mapIndexed { i, v ->
        TrackPoint(
            sequence = i.toLong(), timestampUtcMillis = 1_756_000_000_000 + i * 1000L,
            latitudeDeg = null, longitudeDeg = null, accuracyM = null, speedMps = null,
            rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
            coChannel = null, adjacentChannel = null,
            rsrpDbm = v, sinrDb = null, rsrqDb = null, cellBand = "n41", rat = "5G SA",
            floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
            cells = emptyList(),
        )
    }

    private val bounds = listOf(-85, -95, -105, -115)

    @Test
    fun `values land in the bin whose lower bound they meet`() {
        val d = SessionStats.distribution("RSRP", pts(-80, -90, -100, -110, -120), bounds) { it.rsrpDbm }

        assertEquals(listOf(1, 1, 1, 1, 1), d.bins.map { it.samples })
        assertEquals(5, d.measured)
        assertEquals(0, d.noReading)
    }

    @Test
    fun `a value exactly on a bound goes to the better bin`() {
        // Half-open downward: -95 belongs to "-95 to -85", not to the bin below it. Getting this
        // backwards would move every boundary sample into the worse bucket and inflate the poor
        // percentage in an acceptance report.
        val d = SessionStats.distribution("RSRP", pts(-85, -95, -105, -115), bounds) { it.rsrpDbm }

        assertEquals(listOf(1, 1, 1, 1, 0), d.bins.map { it.samples })
    }

    @Test
    fun `labels keep the minus sign`() {
        val d = SessionStats.distribution("RSRP", pts(-90), bounds) { it.rsrpDbm }

        assertEquals("-85 and above", d.bins.first().label)
        assertEquals("-95 to -85", d.bins[1].label)
        assertEquals("below -115", d.bins.last().label)
        for (b in d.bins) {
            assertTrue(
                "a negative bound must not be printed without its sign: ${b.label}",
                !Regex("""(^|\s)\d""").containsMatchIn(b.label),
            )
        }
    }

    @Test
    fun `a missing reading is counted apart from a bad one`() {
        // The distinction the competitor report makes well: unmeasured and measured-badly are
        // different events and only one is the network's fault.
        val d = SessionStats.distribution("RSRP", pts(-90, null, null, -120), bounds) { it.rsrpDbm }

        assertEquals(2, d.measured)
        assertEquals(2, d.noReading)
        assertEquals(4, d.total)
        assertEquals(1, d.bins.last().samples)
    }

    @Test
    fun `percentages are of measured samples and sum to a hundred`() {
        // Of measured, not of total -- otherwise the column silently shrinks when the handset
        // missed readings, and a reader cannot tell a coverage problem from a sampling one.
        val d = SessionStats.distribution("RSRP", pts(-80, -90, null, null), bounds) { it.rsrpDbm }

        assertEquals(50.0, d.bins[0].pct, 0.001)
        assertEquals(50.0, d.bins[1].pct, 0.001)
        assertEquals(100.0, d.bins.sumOf { it.pct }, 0.001)
    }

    @Test
    fun `a session with no readings at all does not divide by zero`() {
        val d = SessionStats.distribution("RSRP", pts(null, null), bounds) { it.rsrpDbm }

        assertEquals(0, d.measured)
        assertEquals(2, d.noReading)
        assertTrue(d.bins.all { it.pct == 0.0 })
    }

    @Test
    fun `bounds given out of order are still ordered correctly`() {
        val d = SessionStats.distribution("RSRP", pts(-90), listOf(-105, -85, -115, -95)) { it.rsrpDbm }

        assertEquals("-85 and above", d.bins.first().label)
        assertEquals(1, d.bins[1].samples)
    }

    @Test
    fun `positive metrics label without a spurious sign`() {
        // SINR is positive when good, so the same formatter has to read correctly both ways.
        val d = SessionStats.distribution("SINR", pts(25), listOf(20, 13, 0)) { it.rsrpDbm }

        assertEquals("20 and above", d.bins.first().label)
        assertEquals("below 0", d.bins.last().label)
    }
}
