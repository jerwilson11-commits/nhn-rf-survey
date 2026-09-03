package com.nhnengineering.rftest.spot

import com.nhnengineering.rftest.model.CellularSample
import com.nhnengineering.rftest.model.NeighborCell
import com.nhnengineering.rftest.model.NrCell
import com.nhnengineering.rftest.model.NrState
import com.nhnengineering.rftest.model.Rat
import com.nhnengineering.rftest.model.SimState
import com.nhnengineering.rftest.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the spot check.
 *
 * A spot check is the reading most likely to be shown to somebody who is not an engineer — held up
 * in a lift lobby, screenshotted into an email, quoted in a meeting. It therefore has to be at
 * least as careful as the walk analysis, not less, despite looking simpler.
 */
class SpotCheckTest {

    private fun cell(
        rsrp: Int?,
        sinr: Int? = null,
        pci: Int? = 206,
        arfcn: Int? = 501390,
        band: String? = "n41",
        neighbours: List<Pair<Int, Int>> = emptyList(),
    ) = CellularSample(
        simState = SimState.READY, rat = Rat.NR_SA, nrState = NrState.CONNECTED,
        overrideNetworkType = null, isRoaming = false,
        mcc = "310", mnc = "260", operator = "T-Mobile",
        lte = null,
        nr = NrCell(
            registered = true, nci = 1, pci = pci, tac = 1, nrarfcn = arfcn,
            bands = emptyList(), bandLabel = band, bandConflict = null, dlFreqMhz = 2507.0,
            ssRsrpDbm = rsrp, ssRsrqDb = -10, ssSinrDb = sinr,
            csiRsrpDbm = null, csiRsrqDb = null, csiSinrDb = null,
            mcc = "310", mnc = "260", operator = "T-Mobile",
        ),
        neighbors = neighbours.map { (p, ch) ->
            NeighborCell(rat = "NR", pci = p, channel = ch, band = "n41", rsrpDbm = -110, rsrqDb = -14)
        },
    )

    @Test
    fun `the mean is over the window, not the last sample`() {
        // A momentary dip at the end of a check must not become the reported reading.
        val a = SpotCheckAccumulator()
        a.start(0)
        listOf(-90, -90, -90, -90, -108).forEach { a.add(cell(it, sinr = 20), null) }

        val r = a.result(5_000)

        assertEquals(5, r.samples)
        assertEquals(-94, r.meanDbm)
        assertEquals(-108, r.minDbm)
        assertEquals(-90, r.maxDbm)
    }

    @Test
    fun `a reading that moved a lot during the check is marked unstable`() {
        // 6 dB is a factor of four in power. A check that varied by more than that was not
        // measuring one condition, and quoting its mean alone would be a fiction.
        val a = SpotCheckAccumulator()
        a.start(0)
        listOf(-86, -92, -99).forEach { a.add(cell(it, sinr = 18), null) }

        val r = a.result(3_000)

        assertEquals(13, r.spread)
        assertTrue("13 dB of movement must be flagged", r.unstable)
    }

    @Test
    fun `a steady reading is not flagged`() {
        val a = SpotCheckAccumulator()
        a.start(0)
        listOf(-92, -93, -92, -94).forEach { a.add(cell(it, sinr = 18), null) }

        val r = a.result(4_000)

        assertEquals(2, r.spread)
        assertFalse(r.unstable)
    }

    @Test
    fun `the verdict comes from the averaged reading, not a single sample`() {
        // Mean -80 with mean SINR -4 is the interference case. If the verdict were taken from the
        // last sample it would read as a coverage problem and point at the wrong remediation.
        val a = SpotCheckAccumulator()
        a.start(0)
        a.add(cell(-79, sinr = -5), null)
        a.add(cell(-81, sinr = -3), null)

        val r = a.result(2_000)

        assertEquals(-80, r.meanDbm)
        assertTrue("should diagnose interference", r.verdict.interferenceLimited)
    }

    @Test
    fun `an absent SINR throughout leaves quality null rather than zero`() {
        // Zero dB SINR is a real and meaningful value -- the point where wanted signal equals
        // interference. Substituting it for "not reported" would manufacture an interference
        // finding out of a missing field.
        val a = SpotCheckAccumulator()
        a.start(0)
        repeat(4) { a.add(cell(-88, sinr = null), null) }

        val r = a.result(4_000)

        assertNull(r.meanSinrDb)
        assertFalse(r.verdict.interferenceLimited)
        assertTrue(r.verdict.detail.contains("coverage only"))
    }

    @Test
    fun `distinct neighbours are counted once each across the whole check`() {
        // Neighbours come and go between reports at the detection floor. Counting occurrences
        // rather than distinct cells would make a stable spot look busy -- the same defect already
        // fixed once in the dominance analysis.
        val a = SpotCheckAccumulator()
        a.start(0)
        a.add(cell(-90, neighbours = listOf(216 to 501390, 865 to 501390)), null)
        a.add(cell(-90, neighbours = listOf(216 to 501390)), null)
        a.add(cell(-90, neighbours = listOf(216 to 501390, 865 to 501390, 340 to 521310)), null)

        val r = a.result(3_000)

        assertEquals(3, r.neighboursSeen)
    }

    @Test
    fun `the same PCI on two channels counts as two cells`() {
        val a = SpotCheckAccumulator()
        a.start(0)
        a.add(cell(-90, neighbours = listOf(216 to 501390, 216 to 521310)), null)

        assertEquals(2, a.result(1_000).neighboursSeen)
    }

    @Test
    fun `a check with nothing measurable reports unknown rather than a bad reading`() {
        val a = SpotCheckAccumulator()
        a.start(0)
        a.add(null, null)

        val r = a.result(1_000)

        assertEquals(0, r.samples)
        assertNull(r.meanDbm)
        assertNull(r.spread)
        assertFalse("no data is not instability", r.unstable)
        assertEquals(Verdict.Severity.UNKNOWN, r.verdict.severity)
    }

    @Test
    fun `identity follows a handover during the check`() {
        // If the handset moves cell mid-check, the place is better described by where it ended up
        // than by where it started.
        val a = SpotCheckAccumulator()
        a.start(0)
        a.add(cell(-95, pci = 206, arfcn = 501390, band = "n41"), null)
        a.add(cell(-92, pci = 262, arfcn = 124590, band = "n71"), null)

        val r = a.result(2_000)

        assertEquals(262, r.pci)
        assertEquals(124590, r.channel)
        assertEquals("n71", r.band)
    }

    @Test
    fun `the summary line carries the verdict and the numbers together`() {
        // What gets screenshotted into an email. It must not be a bare adjective, and it must not
        // be a bare number.
        val a = SpotCheckAccumulator()
        a.start(0)
        repeat(3) { a.add(cell(-88, sinr = 18), null) }

        val line = a.result(3_000).summaryLine

        assertTrue("must name the conclusion: $line", line.isNotBlank())
        assertTrue("must quote the level: $line", line.contains("-88 dBm"))
        assertTrue("must quote the quality: $line", line.contains("SINR 18"))
        assertTrue("must name the band: $line", line.contains("n41"))
    }
}
