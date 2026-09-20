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
}
