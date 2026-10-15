package com.nhnengineering.rftest.report

import com.nhnengineering.rftest.session.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMosEstimateTest {

    private val t0 = 1_760_000_000_000L

    private fun pt(seq: Int, rat: String?, sinr: Int?, rsrp: Int? = -95) = TrackPoint(
        sequence = seq.toLong(),
        timestampUtcMillis = t0 + seq * 1000L,
        latitudeDeg = 26.0500, longitudeDeg = -80.1397,
        accuracyM = 4f, speedMps = 1.3f,
        rssiDbm = null, ssid = null, bssid = null, channel = null, band = null,
        coChannel = null, adjacentChannel = null,
        rsrpDbm = rsrp, sinrDb = sinr, rsrqDb = null,
        cellBand = if (rat != null) "n25" else null, rat = rat,
        floorplanId = null, floorplanX = null, floorplanY = null, waypoint = null,
    )

    @Test
    fun `voice tech classification follows the radio stack`() {
        assertEquals(VoiceMosEstimate.VoiceTech.VONR, VoiceMosEstimate.voiceTechFor("5G SA"))
        assertEquals(VoiceMosEstimate.VoiceTech.VOLTE, VoiceMosEstimate.voiceTechFor("5G NSA"))
        assertEquals(VoiceMosEstimate.VoiceTech.VOLTE, VoiceMosEstimate.voiceTechFor("LTE"))
        // Circuit-switched legacy and no-service are out of scope.
        assertNull(VoiceMosEstimate.voiceTechFor("WCDMA"))
        assertNull(VoiceMosEstimate.voiceTechFor("GSM"))
        assertNull(VoiceMosEstimate.voiceTechFor(null))
    }

    @Test
    fun `MOS is monotonic in SINR and bounded`() {
        var prev = 0.0
        for (sinr in -12..20) {
            val mos = VoiceMosEstimate.estimateMos(sinr, VoiceMosEstimate.VoiceTech.VOLTE)
            assertTrue("MOS in range at $sinr dB: $mos", mos in 1.0..4.5)
            assertTrue("MOS must not decrease as SINR rises ($sinr dB)", mos >= prev - 1e-9)
            prev = mos
        }
    }

    @Test
    fun `good SINR is toll quality, bad SINR is unusable`() {
        val good = VoiceMosEstimate.estimateMos(18, VoiceMosEstimate.VoiceTech.VOLTE)
        val bad = VoiceMosEstimate.estimateMos(-9, VoiceMosEstimate.VoiceTech.VOLTE)
        assertTrue("clean VoLTE should be >= 4.0, was $good", good >= 4.0)
        assertTrue("very weak SINR should be < 2.5, was $bad", bad < 2.5)
    }

    @Test
    fun `VoNR estimates at least as well as VoLTE at the same SINR`() {
        for (sinr in listOf(-6, 0, 6, 15)) {
            val volte = VoiceMosEstimate.estimateMos(sinr, VoiceMosEstimate.VoiceTech.VOLTE)
            val vonr = VoiceMosEstimate.estimateMos(sinr, VoiceMosEstimate.VoiceTech.VONR)
            assertTrue("VoNR >= VoLTE at $sinr dB ($vonr vs $volte)", vonr >= volte - 1e-9)
        }
    }

    @Test
    fun `analyse splits by stack and needs a SINR reading`() {
        val pts = listOf(
            pt(1, "5G SA", 18),
            pt(2, "5G SA", 6),
            pt(3, "LTE", 2),
            pt(4, "LTE", -6),
            pt(5, "LTE", null),      // no SINR → excluded
            pt(6, "WCDMA", 12),      // CS → excluded
            pt(7, null, 10),         // no service → excluded
        )
        val r = VoiceMosEstimate.analyse(pts)
        assertTrue(r.hasData)
        assertEquals(4, r.samples)
        assertEquals(2, r.byTech.size)
        assertEquals(setOf(VoiceMosEstimate.VoiceTech.VONR, VoiceMosEstimate.VoiceTech.VOLTE),
            r.byTech.map { it.tech }.toSet())
        assertTrue(r.medianMos!! in 1.0..4.5)
        assertTrue(r.pctAcceptable!! in 0.0..100.0)
    }

    @Test
    fun `no voice-capable samples does not fabricate a score`() {
        val r = VoiceMosEstimate.analyse(listOf(pt(1, "WCDMA", 10), pt(2, null, 5)))
        assertFalse(r.hasData)
        assertNull(r.medianMos)
    }
}
