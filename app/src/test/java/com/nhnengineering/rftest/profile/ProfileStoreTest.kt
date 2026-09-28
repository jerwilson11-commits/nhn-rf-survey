package com.nhnengineering.rftest.profile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Provenance must survive the round trip, and must not be invented when absent.
 */
class ProfileStoreProvenanceTest {

    // The serialiser and parser are instance methods; the file is never touched by either.
    private val store = ProfileStore(File.createTempFile("profiles", ".jsonl"))

    private fun profile(provenance: Provenance) = TddProfile(
        id = "p1", vendor = "Ericsson", operator = "T-Mobile", mcc = "310", mnc = "260",
        band = "n41", market = null, siteName = null,
        tddPattern = "DDDSU", tddPeriodicityMs = "2.5", dlSlots = 3, dlSymbols = 10,
        ulSlots = 1, ulSymbols = 2, ssbPeriodicityMs = 20, ssbPositionsInBurst = "10101010",
        scsKhz = 30, provenance = provenance, source = "NSG, SIB1, 19 Sep",
        recordedAtUtcMillis = 1_756_000_000_000, note = null,
    )

    @Test
    fun `measured survives the round trip`() {
        val back = store.parse(store.serialise(profile(Provenance.MEASURED)))

        assertEquals(Provenance.MEASURED, back.provenance)
    }

    @Test
    fun `reported survives the round trip`() {
        val back = store.parse(store.serialise(profile(Provenance.REPORTED)))

        assertEquals(Provenance.REPORTED, back.provenance)
    }

    @Test
    fun `a line written before provenance existed reads as reported`() {
        // Every profile stored before this field was added was hand-entered. Defaulting to
        // REPORTED keeps the weaker claim rather than silently promoting old hearsay to evidence.
        val line = store.serialise(profile(Provenance.MEASURED))
            .replace("\"provenance\":\"MEASURED\",", "")

        assertEquals(Provenance.REPORTED, store.parse(line).provenance)
    }

    @Test
    fun `an unrecognised provenance reads as reported rather than throwing`() {
        val line = store.serialise(profile(Provenance.MEASURED))
            .replace("MEASURED", "GUESSED")

        assertEquals(Provenance.REPORTED, store.parse(line).provenance)
    }

    /**
     * The exact line installed directly into `tdd-profiles.jsonl` on the test handset on
     * 2026-09-27 (and updated 2026-09-28 to add the per-pattern fields below), in place of driving
     * the paste dialog through `adb` -- unreliable on this handset's launcher, which intermittently
     * intercepted synthetic taps meant for the dialog. The parser's own behaviour is covered by
     * [Sib1ParserTest]; this pins that the literal bytes installed on the device parse back to the
     * values they are supposed to. Source: the real n41 T-Mobile capture (PCI 206), see
     * `Sib1ParserTest.realN41TddTmobile`.
     */
    @Test
    fun `the n41 T-Mobile profile installed on the device parses correctly`() {
        val line = "{\"id\":\"cd5a51aa-a3bc-4614-a050-de364a72475e\",\"vendor\":\"\"," +
            "\"operator\":\"T-Mobile\",\"mcc\":\"310\",\"mnc\":\"260\",\"band\":\"n41\"," +
            "\"market\":null,\"siteName\":null,\"tddPattern\":\"DDDSUUDDDD\"," +
            "\"tddPeriodicityMs\":\"5\",\"pattern1PeriodicityMs\":\"3\"," +
            "\"pattern2PeriodicityMs\":\"2\",\"dlSlots\":3,\"dlSymbols\":6,\"ulSlots\":2," +
            "\"ulSymbols\":4,\"p2DlSlots\":4,\"p2DlSymbols\":0,\"p2UlSlots\":0," +
            "\"p2UlSymbols\":0,\"ssbPeriodicityMs\":null,\"ssbPositionsInBurst\":\"00100000\"," +
            "\"scsKhz\":30,\"provenance\":\"MEASURED\",\"source\":\"SIB1 paste, 27 Sep 2026\"," +
            "\"recordedAtUtcMillis\":1790539624518,\"note\":\"carrierBandwidth 273, PCI 206. " +
            "Captured at the T-Mobile test desk, 2026-09-27; see docs/modem-diag-access.md. NSA " +
            "anchor was LTE B2; standalone n41 does not hold on this handset without an LTE " +
            "fallback (separate finding, not a property of this TDD config).\"}"

        val p = store.parse(line)

        assertEquals("n41", p.band)
        assertEquals("T-Mobile", p.operator)
        assertEquals("310", p.mcc)
        assertEquals("260", p.mnc)
        assertEquals("DDDSUUDDDD", p.tddPattern)
        assertEquals("5", p.tddPeriodicityMs)
        assertEquals("3", p.pattern1PeriodicityMs)
        assertEquals("2", p.pattern2PeriodicityMs)
        assertEquals(3, p.dlSlots)
        assertEquals(6, p.dlSymbols)
        assertEquals(2, p.ulSlots)
        assertEquals(4, p.ulSymbols)
        assertEquals(4, p.p2DlSlots)
        assertEquals(0, p.p2DlSymbols)
        assertEquals(0, p.p2UlSlots)
        assertEquals(0, p.p2UlSymbols)
        assertEquals("00100000", p.ssbPositionsInBurst)
        assertEquals(2, p.ssbPosition)
        assertEquals(30, p.scsKhz)
        assertEquals(Provenance.MEASURED, p.provenance)
        assertEquals(true, p.hasPattern2)
        assertEquals(false, p.isSiteOverride)
        assertEquals(false, p.isEmpty)
    }

    @Test
    fun `a line saved before per-pattern fields existed reads them as null, not as a crash`() {
        val old = "{\"id\":\"p\",\"vendor\":\"\",\"operator\":\"T-Mobile\",\"mcc\":\"310\"," +
            "\"mnc\":\"260\",\"band\":\"n41\",\"market\":null,\"siteName\":null," +
            "\"tddPattern\":\"DDDSU\",\"tddPeriodicityMs\":\"2.5\",\"dlSlots\":3," +
            "\"dlSymbols\":10,\"ulSlots\":1,\"ulSymbols\":2,\"ssbPeriodicityMs\":20," +
            "\"ssbPositionsInBurst\":\"10101010\",\"scsKhz\":30,\"provenance\":\"MEASURED\"," +
            "\"source\":\"NSG, SIB1, 19 Sep\",\"recordedAtUtcMillis\":1756000000000,\"note\":null}"

        val p = store.parse(old)

        assertEquals(null, p.pattern1PeriodicityMs)
        assertEquals(null, p.pattern2PeriodicityMs)
        assertEquals(null, p.p2DlSlots)
        assertEquals(false, p.hasPattern2)
        assertEquals("2.5", p.tddPeriodicityMs)
    }
}
