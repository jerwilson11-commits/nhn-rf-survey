package com.nhnengineering.rftest.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CellularSample.isRegistered] is the one flag the UI checks before drawing a reading as a live
 * serving cell. It must be false for an out-of-service SIM even though the platform still marks the
 * strongest observed cell `CellInfo.isRegistered() = true` for an emergency-only camp -- the bug
 * that drew an out-of-service T-Mobile SIM as a phantom "5G SA n25" serving cell.
 */
class CellularSampleRegistrationTest {

    private fun lte(registered: Boolean, band: Int? = 2) = LteCell(
        registered = registered, ci = null, enbId = null, sectorId = null, pci = 1, tac = null,
        earfcn = 650, band = band, bandLabel = band?.let { "B$it" }, dlFreqMhz = null,
        bandwidthKhz = null, rsrpDbm = -94, rsrqDb = null, rssnrDb = null, rssiDbm = null,
        cqi = null, timingAdvance = null, mcc = "310", mnc = "260", operator = "T-Mobile",
    )

    private fun sample(lte: LteCell?, inService: Boolean) = CellularSample(
        simState = SimState.READY,
        rat = if (inService) Rat.LTE else Rat.NO_SERVICE,
        nrState = NrState.UNKNOWN,
        overrideNetworkType = null,
        isRoaming = false,
        mcc = "310", mnc = "260", operator = "T-Mobile",
        lte = lte, nr = null, neighbors = emptyList(),
        inService = inService,
    )

    @Test
    fun `a registered cell in service is serving`() {
        assertTrue(sample(lte(registered = true), inService = true).isRegistered)
    }

    @Test
    fun `an emergency-only camp is observed, not serving`() {
        // CellInfo.isRegistered() is true for the camp, but the modem is out of service.
        assertFalse(sample(lte(registered = true), inService = false).isRegistered)
    }

    @Test
    fun `the observed RF is retained even when not serving`() {
        // The point of the gate is to stop calling it "serving", not to discard the measurement:
        // the cell (and its RSRP/band) stay on the sample for the "observed Bxx" readout.
        val s = sample(lte(registered = true), inService = false)
        assertFalse(s.isRegistered)
        assertTrue(s.lte != null && s.servingBandLabel == "B2" && s.servingRsrpDbm == -94)
    }

    @Test
    fun `an unregistered observed cell is never serving regardless of service flag`() {
        assertFalse(sample(lte(registered = false), inService = true).isRegistered)
    }
}
