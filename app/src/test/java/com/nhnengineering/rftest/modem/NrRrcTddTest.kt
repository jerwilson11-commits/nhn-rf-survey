package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the native-JSON -> Sib1Parser bridge against the real capture from the OnePlus 9. The JSON
 * here is exactly what libnrrrc.so emitted on-device for the n41 NSA RRCReconfiguration
 * (vec_rrcreconf.hex); the expected profile is the known T-Mobile n41 TDD config.
 */
class NrRrcTddTest {

    // Verbatim from the on-device decode of the real n41 NSA RRCReconfiguration.
    private val n41Nsa = """
        {"root":"RRCReconfiguration","tddPresent":true,"ssbSCS":1,"ssbPeriodicity":2,
         "ssbKind":"medium","ssbHex":"20","refSCS":1,
         "pattern1":{"periodicity":0,"dlSlots":3,"dlSymbols":6,"ulSlots":2,"ulSymbols":4,"periodicityV1530":0},
         "pattern2":{"periodicity":4,"dlSlots":4,"dlSymbols":0,"ulSlots":0,"ulSymbols":0}}
    """.trimIndent().replace("\n", "")

    @Test
    fun decodesN41NsaToKnownProfile() {
        val r = NrRrcTdd.fromJson(n41Nsa)
        requireNotNull(r) { "expected a TDD result" }
        assertTrue(r.isTdd)
        assertEquals(30, r.scsKhz)
        // pattern1: 3 ms (v1530 overrides the ms0p5 base), 3 DL / 6 / 2 UL / 4
        assertEquals("3", r.tddPeriodicityMs)
        assertEquals(3, r.dlSlots)
        assertEquals(6, r.dlSymbols)
        assertEquals(2, r.ulSlots)
        assertEquals(4, r.ulSymbols)
        // pattern2: 2 ms, 4 DL
        assertTrue(r.hasPattern2)
        assertEquals("2", r.pattern2PeriodicityMs)
        assertEquals(4, r.p2DlSlots)
        // combined cycle + slot string
        assertEquals("5", r.effectivePeriodicityMs)
        assertEquals("DDDSUUDDDD", r.derivedPattern)
        // SSB: medium bitmap 0x20 = 00100000 (position 2), 20 ms period
        assertEquals(20, r.ssbPeriodicityMs)
        assertEquals("00100000", r.ssbPositionsInBurst)
    }

    @Test
    fun fdbCellWithNoTddReturnsNull() {
        // What the SIB1 path emits for an FDD cell (real n25 SA SIB1 off the OnePlus).
        assertNull(NrRrcTdd.fromJson("""{"root":"SIB1","tddPresent":false}"""))
    }

    @Test
    fun decodeErrorReturnsNull() {
        assertNull(NrRrcTdd.fromJson("""{"error":"rrcreconf_decode"}"""))
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(NrRrcTdd.fromJson("not json"))
    }
}
