package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B4: the SystemInformation JSON filter that distinguishes a real SI message from the DL-DCCH false
 * positives (short frames that spuriously decode as BCCH-DL-SCH and come back as `not_plain_si`).
 * Exercised against the exact JSON the native decoder emits; the decode itself is validated by the
 * host round-trip in tools/asn1.
 */
class NrRrcSiTest {

    @Test
    fun `clean SystemInformation yields the SIB inventory and SIB2 params`() {
        val json = """{"root":"SystemInformation","sibs":["sib2","sib4"],""" +
            """"sib2":{"qHyst":2,"qRxLevMin":-62,"sIntraSearchP":7,"tReselectionNR":1,""" +
            """"threshServingLowP":5,"cellReselectionPriority":6}}"""
        val si = NrRrcTdd.systemInformationFromJson(json)!!
        assertEquals(listOf("sib2", "sib4"), si.sibs)
        assertEquals(2, si.sib2["qHyst"])
        assertEquals(-62, si.sib2["qRxLevMin"])
        assertEquals(6, si.sib2["cellReselectionPriority"])
    }

    @Test
    fun `SystemInformation without a plain-SI body is rejected (the DL-DCCH false positive)`() {
        assertNull(NrRrcTdd.systemInformationFromJson(
            """{"root":"SystemInformation","error":"not_plain_si"}"""))
    }

    @Test
    fun `an empty SIB list is rejected`() {
        assertNull(NrRrcTdd.systemInformationFromJson("""{"root":"SystemInformation","sibs":[]}"""))
    }

    @Test
    fun `a SIB1 or RRCReconf decode is not SystemInformation`() {
        assertNull(NrRrcTdd.systemInformationFromJson("""{"root":"SIB1","mcc":"310"}"""))
        assertNull(NrRrcTdd.systemInformationFromJson("""{"root":"RRCReconfiguration","pci":206}"""))
    }

    @Test
    fun `garbage is rejected without throwing`() {
        assertNull(NrRrcTdd.systemInformationFromJson("not json"))
        assertTrue(true)
    }
}
