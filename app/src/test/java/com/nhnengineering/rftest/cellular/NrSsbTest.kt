package com.nhnengineering.rftest.cellular

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the GSCN arithmetic and, more importantly, the boundary between what is measured and what
 * is guessed.
 *
 * These numbers get copied onto a vendor questionnaire and configured into a repeater. A wrong
 * GSCN is caught quickly because the unit fails to sync; a wrong TDD pattern is not, because the
 * unit transmits into the uplink and the symptom is degraded service somewhere else entirely. So
 * the tests that matter most here are the ones asserting that certain fields stay blank.
 */
class NrSsbTest {

    @Test
    fun `GSCN for the n41 carrier measured on this network`() {
        // NR-ARFCN 501390 -> 2506.95 MHz, seen live on T-Mobile 2026-09-02.
        // 2506.95 = N*1.2 + M*0.05 with N = 2089, M = 3, so GSCN = 3N + (M-3)/2 = 6267.
        val freq = BandMapping.nrArfcnToMhz(501_390)!!

        assertEquals(2506.95, freq, 0.001)
        assertEquals(6267, NrSsb.gscnFor(freq))
    }

    @Test
    fun `GSCN round-trips through the inverse`() {
        for (gscn in listOf(2, 100, 3000, 6267, 7000, 7499, 8000, 12000, 22256, 23000)) {
            val f = NrSsb.ssbFreqMhzForGscn(gscn)
            assertNotNull("no frequency for GSCN $gscn", f)
            assertEquals("round trip for GSCN $gscn", gscn, NrSsb.gscnFor(f!!))
        }
    }

    @Test
    fun `the three raster ranges use their own formulas`() {
        // Boundaries of TS 38.104 5.4.3.1. Getting the wrong branch yields a plausible number in
        // the wrong range, which is the failure mode a table lookup also has.
        assertEquals(7499, NrSsb.gscnFor(3000.0))
        assertEquals(7500, NrSsb.gscnFor(3001.44))
        assertEquals(22256, NrSsb.gscnFor(24250.08))
    }

    @Test
    fun `a frequency off the synchronisation raster returns null rather than the nearest`() {
        // The raster is sparse by design. Snapping to the nearest point would hand a vendor a
        // confident wrong GSCN, which is worse than reporting that the frequency is not an SSB.
        assertNull(NrSsb.gscnFor(2506.97))
        assertNull(NrSsb.gscnFor(3000.5))
        assertNull(NrSsb.gscnFor(0.0))
        assertNull(NrSsb.gscnFor(-100.0))
    }

    @Test
    fun `duplex mode comes from the band allocation`() {
        assertEquals("TDD", NrSsb.duplexFor("n41"))
        assertEquals("TDD", NrSsb.duplexFor("n78"))
        assertEquals("TDD", NrSsb.duplexFor("n258"))
        assertEquals("FDD", NrSsb.duplexFor("n71"))
        assertEquals("FDD", NrSsb.duplexFor("n25"))
        assertEquals("FDD", NrSsb.duplexFor("n66"))
        assertNull(NrSsb.duplexFor(null))
    }

    @Test
    fun `subcarrier spacing is labelled inferred, never measured`() {
        // The single most dangerous field to overstate: it is a convention, not a reading.
        val params = NrSsb.parameters(nrarfcn = 501_390, band = "n41", bandwidthKhz = 100_000)
        val scs = params.single { it.name == "Subcarrier spacing" }

        assertEquals(NrSsb.Confidence.INFERRED, scs.confidence)
        assertEquals("30 kHz", scs.value)
        assertTrue(
            "the note must warn against configuring from it: ${scs.note}",
            scs.note!!.contains("not a measurement"),
        )
    }

    @Test
    fun `the TDD slot pattern is never guessed`() {
        // The row that would cause real harm if invented. A plausible wrong pattern makes a
        // repeater transmit into the uplink, and the symptom appears somewhere else.
        val params = NrSsb.parameters(501_390, "n41", 100_000)

        for (name in listOf("TDD periodicity", "Downlink slots", "Downlink symbols")) {
            val p = params.single { it.name == name }
            assertNull("$name must stay blank", p.value)
            assertEquals(NrSsb.Confidence.UNAVAILABLE, p.confidence)
            assertTrue("$name must explain itself", !p.note.isNullOrBlank())
        }
    }

    @Test
    fun `unavailable rows are returned rather than omitted`() {
        // An engineer holding a vendor form needs to see which lines a walk cannot fill, not a
        // short list that leaves them wondering whether the app failed.
        val params = NrSsb.parameters(501_390, "n41", 100_000)
        val unavailable = params.filter { it.confidence == NrSsb.Confidence.UNAVAILABLE }

        assertTrue("expected the PHY rows to be present and blank", unavailable.size >= 6)
        assertTrue("every blank row must say why", unavailable.all { !it.note.isNullOrBlank() })
    }

    @Test
    fun `measured and derived rows carry the values a walk can actually supply`() {
        val params = NrSsb.parameters(501_390, "n41", 100_000)
        fun value(n: String) = params.single { it.name == n }.value

        assertEquals("501390", value("NR-ARFCN (SSB)"))
        assertEquals("2506.95 MHz", value("Carrier frequency"))
        assertEquals("6267", value("GSCN"))
        assertEquals("n41", value("Band"))
        assertEquals("TDD", value("Duplex"))
        assertEquals("100 MHz", value("Bandwidth"))
    }

    @Test
    fun `an off-raster frequency reports GSCN unavailable with a reason`() {
        // BandMapping maps every ARFCN to a frequency, but only some are SSB positions.
        val params = NrSsb.parameters(nrarfcn = 501_391, band = "n41", bandwidthKhz = null)
        val gscn = params.single { it.name == "GSCN" }

        assertNull(gscn.value)
        assertEquals(NrSsb.Confidence.UNAVAILABLE, gscn.confidence)
        assertTrue(gscn.note!!.contains("raster"))
    }

    @Test
    fun `a missing bandwidth is reported unavailable rather than blank`() {
        val params = NrSsb.parameters(501_390, "n41", bandwidthKhz = null)
        val bw = params.single { it.name == "Bandwidth" }

        assertNull(bw.value)
        assertEquals(NrSsb.Confidence.UNAVAILABLE, bw.confidence)
        assertTrue(!bw.note.isNullOrBlank())
    }
}
